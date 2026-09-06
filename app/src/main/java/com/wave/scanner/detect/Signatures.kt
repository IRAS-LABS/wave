package com.wave.scanner.detect

import com.wave.scanner.data.db.DeviceClass
import com.wave.scanner.data.db.Threat
import com.wave.scanner.scan.RadioObservation

/**
 * A signature matches an observed emitter against several independent facets. Any facet
 * hitting is enough to classify, but the UI keeps the matched facet so "vendor OUI says
 * Axon Enterprise" is never presented the same way as "the SSID contains the word flock".
 *
 * Vendor matching runs against the resolved name from the bundled 89,890-entry OUI
 * database rather than hardcoded MAC prefixes, so a vendor owning a dozen OUI blocks is
 * caught by one rule and stays correct when the monthly OUI refresh adds more.
 */
data class Signature(
    val id: String,
    val label: String,
    val deviceClass: DeviceClass,
    val threat: Threat,
    /** Matched against the OUI-resolved vendor string, case-insensitively. */
    val vendorRegex: Regex? = null,
    /** Matched against Wi-Fi SSID or BLE local name. */
    val nameRegex: Regex? = null,
    /** 16-bit BLE service UUIDs, lowercase hex, base UUID stripped. */
    val serviceUuids: Set<String> = emptySet(),
    /** BLE SIG company identifiers taken from the manufacturer-data header. */
    val companyIds: Set<Int> = emptySet(),
    val note: String? = null
) {
    /**
     * Does this signature claim the sighting?
     *
     * Any one facet hitting is enough, which is what makes the regexes load-bearing: a
     * pattern that is a shade too loose does not merely add noise, it promotes a stranger's
     * hotspot to a HIGH-threat ALPR. Lives on Signature rather than inside Classifier so it
     * is reachable without an Android Context, and so the tests exercise this exact
     * predicate instead of a copy that can drift away from it.
     */
    fun matches(obs: RadioObservation, vendor: String?): Boolean {
        val vendorHit = vendor != null && vendorRegex?.containsMatchIn(vendor) == true
        val nameHit = obs.name != null && nameRegex?.containsMatchIn(obs.name) == true
        val uuidHit = serviceUuids.isNotEmpty() && obs.serviceUuids.any { it in serviceUuids }
        val companyHit = companyIds.isNotEmpty() &&
            obs.manufacturerData.keys.any { it in companyIds }
        return vendorHit || nameHit || uuidHit || companyHit
    }
}

private fun ri(p: String) = Regex(p, RegexOption.IGNORE_CASE)

object Signatures {

    // -----------------------------------------------------------------------
    // ALPR / fixed automated surveillance
    // -----------------------------------------------------------------------
    val ALPR = listOf(
        Signature(
            id = "flock",
            label = "Flock Safety",
            deviceClass = DeviceClass.ALPR_CAMERA,
            threat = Threat.HIGH,
            vendorRegex = ri("flock\\s*safety|flock,?\\s*inc"),
            nameRegex = ri("\\bflock\\b|\\bfalcon[-_ ](lr|flex)\\b|\\bsparrow[-_ ](lr|flex|cam)\\b"),
            note = "Flock Falcon/Sparrow ALPR. Units are LTE-backhauled; their Wi-Fi and BLE " +
                "interfaces are used for install and servicing and are not always radiating. " +
                "The product-name patterns deliberately require a model suffix: Falcon and " +
                "Sparrow are also two of the most common joke SSIDs in existence, and matching " +
                "the bare words rated every Star Wars fan on the street as a HIGH-threat camera."
        ),
        Signature(
            id = "motorola_vigilant",
            label = "Motorola / Vigilant ALPR",
            deviceClass = DeviceClass.ALPR_CAMERA,
            threat = Threat.HIGH,
            vendorRegex = ri("vigilant\\s*solutions|federal\\s*signal|elsag"),
            nameRegex = ri("\\bvigilant\\b|\\belsag\\b")
        ),
        Signature(
            id = "genetec",
            label = "Genetec AutoVu",
            deviceClass = DeviceClass.ALPR_CAMERA,
            threat = Threat.HIGH,
            vendorRegex = ri("genetec"),
            nameRegex = ri("autovu|sharpv|\\bsharp[-_ ]?x\\b")
        ),
        Signature(
            id = "neology",
            label = "Neology / Leonardo LPR",
            deviceClass = DeviceClass.ALPR_CAMERA,
            threat = Threat.HIGH,
            vendorRegex = ri("neology|leonardo\\s*(us|company)")
        )
    )

    // -----------------------------------------------------------------------
    // Body-worn and in-vehicle law-enforcement equipment
    // -----------------------------------------------------------------------
    val LE_EQUIPMENT = listOf(
        Signature(
            id = "axon",
            label = "Axon body camera",
            deviceClass = DeviceClass.BODY_CAMERA,
            threat = Threat.HIGH,
            vendorRegex = ri("axon\\s*enterprise|taser\\s*international"),
            nameRegex = ri("^axon\\b|\\baxon[-_ ]?(body|fleet|view)\\b"),
            companyIds = setOf(0x034D),
            note = "Axon Body 2/3/4 expose Wi-Fi and BLE for pairing with Axon View. The " +
                "company ID is the load-bearing facet here: Axon Enterprise holds no OUI " +
                "block in the IEEE registry, so the vendor arm of this rule can never fire " +
                "against the bundled database. It did register 0x034D with the Bluetooth " +
                "SIG under its former name, and that entry is live. Nothing but law " +
                "enforcement equipment carries it."
        ),
        Signature(
            id = "watchguard",
            label = "WatchGuard / Digital Ally video",
            deviceClass = DeviceClass.BODY_CAMERA,
            threat = Threat.HIGH,
            vendorRegex = ri("watchguard\\s*video|digital\\s*ally"),
            nameRegex = ri("watchguard|vista\\s*wifi|firstvu")
        ),
        Signature(
            id = "police_router",
            label = "In-vehicle fleet router",
            deviceClass = DeviceClass.POLICE_VEHICLE,
            threat = Threat.MEDIUM,
            vendorRegex = ri("cradlepoint|sierra\\s*wireless|digi\\s*international"),
            nameRegex = ri("\\bcradlepoint\\b|\\bibr\\d{3,}|\\bmg90\\b|toughbook"),
            companyIds = setOf(0x02DB),
            note = "Cradlepoint IBR and Sierra MG90 dominate cruiser fleets. Utility and " +
                "delivery trucks run the same hardware, which is why this is MEDIUM: the " +
                "router says municipal fleet, not police. Cradlepoint and Sierra hold OUI " +
                "blocks but no Bluetooth SIG entry, so over BLE only Digi is reachable."
        ),
        Signature(
            id = "moto_lmr",
            label = "Motorola Solutions land-mobile radio",
            deviceClass = DeviceClass.POLICE_VEHICLE,
            threat = Threat.MEDIUM,
            companyIds = setOf(0x04EC),
            nameRegex = ri("\\bapx\\s?\\d{3,4}\\b|\\bapx\\s?next\\b"),
            note = "0x04EC is Motorola Solutions, the land-mobile radio business. That is a " +
                "different company from the Motorola that makes phones, whose ID is 0x0008 " +
                "and is deliberately not matched here - matching it would flag half the " +
                "handsets on a street. APX portable and mobile radios pair over BLE to " +
                "earpieces and to the vehicle. Capped at MEDIUM because fire, EMS, transit " +
                "and private security run the same radios."
        ),
        Signature(
            id = "le_ssid_pattern",
            label = "Law-enforcement SSID pattern",
            deviceClass = DeviceClass.POLICE_VEHICLE,
            threat = Threat.MEDIUM,
            nameRegex = ri("\\b(pd|police|sheriff|patrol|trooper|cruiser)[-_]?(wifi|ap|net|mdt)?\\b"),
            note = "Name heuristic only. Deliberately capped at MEDIUM because it false-positives " +
                "on personal hotspots named as jokes."
        )
    )

    // -----------------------------------------------------------------------
    // BLE item trackers - the core of following-detection
    // -----------------------------------------------------------------------
    val TRACKERS = listOf(
        Signature(
            id = "apple_findmy",
            label = "Apple Find My / AirTag",
            deviceClass = DeviceClass.TRACKER,
            threat = Threat.HIGH,
            companyIds = setOf(0x004C),
            serviceUuids = setOf("fd44"),
            note = "Detected from the 0x12 offline-finding payload. A separated AirTag rotates " +
                "its MAC roughly every 15 minutes, so Wave keys it on payload, not address."
        ),
        Signature(
            id = "tile",
            label = "Tile tracker",
            deviceClass = DeviceClass.TRACKER,
            threat = Threat.HIGH,
            serviceUuids = setOf("feed", "feec"),
            nameRegex = ri("^tile\\b")
        ),
        Signature(
            id = "samsung_smarttag",
            label = "Samsung SmartTag",
            deviceClass = DeviceClass.TRACKER,
            threat = Threat.HIGH,
            companyIds = setOf(0x0075),
            serviceUuids = setOf("fd5a"),
            nameRegex = ri("smarttag")
        ),
        Signature(
            id = "chipolo",
            label = "Chipolo",
            deviceClass = DeviceClass.TRACKER,
            threat = Threat.HIGH,
            serviceUuids = setOf("fe33"),
            nameRegex = ri("^chipolo")
        ),
        Signature(
            id = "pebblebee",
            label = "Pebblebee",
            deviceClass = DeviceClass.TRACKER,
            threat = Threat.HIGH,
            nameRegex = ri("pebblebee")
        ),
        Signature(
            id = "hardwired_gps_tracker",
            label = "GPS tracker module",
            deviceClass = DeviceClass.TRACKER,
            threat = Threat.CRITICAL,
            nameRegex = ri("\\b(gt0?[26]|tk1?0[0-9]|st-?90[01]|gps[-_]?track|obd[-_]?gps)\\b"),
            note = "Hardwired and OBD-port GPS trackers. Rated CRITICAL because unlike a consumer " +
                "tag these have no innocent reason to be riding along with you."
        )
    )

    // -----------------------------------------------------------------------
    // Static camera infrastructure worth logging on a drive
    // -----------------------------------------------------------------------
    val CAMERAS = listOf(
        Signature(
            id = "ring",
            label = "Ring doorbell / camera",
            deviceClass = DeviceClass.SURVEILLANCE,
            threat = Threat.LOW,
            vendorRegex = ri("^ring\\b|amazon\\s*technologies"),
            nameRegex = ri("^ring[-_ ]")
        ),
        Signature(
            id = "nest",
            label = "Nest / Google camera",
            deviceClass = DeviceClass.SURVEILLANCE,
            threat = Threat.LOW,
            vendorRegex = ri("nest\\s*labs"),
            nameRegex = ri("^nest[-_ ]|dropcam")
        ),
        Signature(
            id = "hikvision_dahua",
            label = "Hikvision / Dahua",
            deviceClass = DeviceClass.SURVEILLANCE,
            threat = Threat.MEDIUM,
            vendorRegex = ri("hikvision|dahua"),
            note = "Barred from US federal procurement; extremely common on private CCTV."
        ),
        Signature(
            id = "generic_ipcam",
            label = "IP camera",
            deviceClass = DeviceClass.SURVEILLANCE,
            threat = Threat.LOW,
            vendorRegex = ri("axis\\s*communications|vivotek|mobotix|arlo|wyze|reolink"),
            nameRegex = ri("\\b(ipcam|nvr|cam[-_]?\\d{3,})\\b")
        )
    )

    /** Trackers first so a device matching two families is reported as the worse one. */
    val ALL: List<Signature> = TRACKERS + ALPR + LE_EQUIPMENT + CAMERAS

    fun byId(id: String): Signature? = ALL.firstOrNull { it.id == id }
}
