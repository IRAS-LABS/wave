package com.wave.scanner.detect

import android.bluetooth.BluetoothClass
import android.content.Context
import com.wave.scanner.data.db.Band
import com.wave.scanner.data.db.DeviceClass
import com.wave.scanner.data.db.Threat
import com.wave.scanner.data.oui.BtSigRepository
import com.wave.scanner.data.oui.OuiRepository
import com.wave.scanner.scan.RadioObservation

/**
 * Turns a sighting into a named device class, a threat level, and - importantly - a
 * human-readable reason.
 *
 * The reason string is not decoration. "Flock Safety" resolved from a registered OUI is a
 * fact; the same label inferred from an SSID containing the word "flock" is a guess. The
 * UI shows both, but the user needs to be able to tell them apart before acting on one.
 */
class Classifier(
    private val oui: OuiRepository,
    private val btSig: BtSigRepository
) {

    data class Result(
        val vendor: String?,
        val vendorSource: String?,
        val deviceClass: DeviceClass,
        val threat: Threat,
        val classReason: String,
        val signatureId: String?,
        /** Non-null when the device must be tracked by payload because its MAC rotates. */
        val payloadKey: String?,
        val fingerprint: String,
        /** Vendor-level product hint from the OUI registry. Weak: see [DeviceNaming]. */
        val vendorDeviceType: String? = null,
        /** Best human label, worked out by [DeviceNaming]. Null only if nothing is knowable. */
        val displayName: String? = null,
        /** Protocol-level detail lines - services offered, advertising formats, decoded roles. */
        val facts: List<String> = emptyList()
    )

    fun classify(obs: RadioObservation): Result {
        val vendorHit = resolveVendor(obs)
        val vendorName = vendorHit?.first
        val vendorSource = vendorHit?.second

        val trackerHit = TrackerPayloads.identify(obs)
        val matched = trackerHit?.signature ?: matchSignature(obs, vendorName)

        val vendorDeviceType = obs.address
            ?.takeUnless { OuiRepository.isRandomized(it) }
            ?.let { oui.lookup(it)?.deviceType }

        // Naming runs for every device, matched or not. A signature says what a thing means
        // to the user's safety; naming says what the thing IS, and those are different
        // questions - a matched Flock camera still deserves its service list, and an
        // unmatched pair of earbuds deserves to be called earbuds rather than UNKNOWN.
        val naming = DeviceNaming.describe(obs, vendorName, vendorDeviceType)

        // A signature is a deliberate, reviewed assertion, so it outranks inference. Below
        // it, naming outranks the coarse vendor guess, because "advertises the Heart Rate
        // service" is evidence about this device and "Samsung makes phones" is not.
        // Class-of-device sits between the two. It is not a reviewed signature, but it is
        // also not inference: the device itself declares its major class in every inquiry
        // response, and "declares itself a video camera" beats anything read off a name.
        val deviceClass = matched?.deviceClass
            ?: obs.btCod?.let { codClass(it) }
            ?: naming.deviceClass
            ?: inferClassWithoutSignature(obs, vendorName)
        val threat = matched?.threat ?: Threat.NONE

        val reason = when {
            trackerHit != null -> trackerHit.reason
            matched != null -> describeMatch(matched, obs, vendorName)
            obs.btCod != null && codClass(obs.btCod) != null ->
                "Declares itself " + (obs.capabilities ?: "a known class") +
                    " in its Bluetooth class-of-device"
            else -> naming.reason
        }

        return Result(
            vendor = vendorName,
            vendorSource = vendorSource,
            deviceClass = deviceClass,
            threat = threat,
            classReason = reason,
            signatureId = matched?.id,
            payloadKey = trackerHit?.payloadKey,
            fingerprint = Fingerprint.of(obs),
            vendorDeviceType = vendorDeviceType,
            displayName = naming.name,
            facts = naming.facts
        )
    }

    /** OUI first because it is authoritative; SIG company ID is the fallback for BLE. */
    private fun resolveVendor(obs: RadioObservation): Pair<String, String>? {
        obs.address?.let { addr ->
            if (!OuiRepository.isRandomized(addr)) {
                oui.lookup(addr)?.let { return it.manufacturer to "oui" }
            }
        }
        obs.manufacturerData.keys.firstOrNull()?.let { companyId ->
            btSig.name(companyId)?.let { return it to "btsig" }
        }
        return null
    }

    private fun matchSignature(obs: RadioObservation, vendor: String?): Signature? =
        Signatures.ALL.firstOrNull { it.matches(obs, vendor) }

    private fun describeMatch(sig: Signature, obs: RadioObservation, vendor: String?): String = when {
        vendor != null && sig.vendorRegex?.containsMatchIn(vendor) == true ->
            "OUI vendor is $vendor - registered assignment, high confidence"
        sig.serviceUuids.isNotEmpty() && obs.serviceUuids.any { it in sig.serviceUuids } ->
            "Advertises service UUID ${obs.serviceUuids.first { it in sig.serviceUuids }}"
        sig.companyIds.isNotEmpty() && obs.manufacturerData.keys.any { it in sig.companyIds } ->
            "BLE company ID matches ${sig.label}"
        obs.name != null && sig.nameRegex?.containsMatchIn(obs.name) == true ->
            "Broadcast name \"${obs.name}\" matches ${sig.label}"
        else -> sig.label
    }

    /**
     * Classic Bluetooth class-of-device to a Wave device class.
     *
     * The minor class is checked first where it carries real meaning - a camcorder and a
     * pair of headphones are both AUDIO_VIDEO majors and only one of them is a recording
     * device somebody might care about being near. Where the minor says nothing useful the
     * major decides, and where neither does this returns null so the ordinary naming and
     * inference path still gets its turn.
     */
    private fun codClass(cod: Int): DeviceClass? {
        minorClass(cod)?.let { return it }
        // The major class is bits 8..12. Masking rather than shifting because Android's
        // own Major constants are already the masked values.
        return when (cod and MAJOR_MASK) {
            BluetoothClass.Device.Major.COMPUTER -> DeviceClass.COMPUTER
            BluetoothClass.Device.Major.PHONE -> DeviceClass.PHONE
            BluetoothClass.Device.Major.NETWORKING -> DeviceClass.NETWORK
            BluetoothClass.Device.Major.AUDIO_VIDEO -> DeviceClass.AUDIO
            BluetoothClass.Device.Major.PERIPHERAL -> DeviceClass.IOT
            BluetoothClass.Device.Major.IMAGING -> DeviceClass.PRINTER
            BluetoothClass.Device.Major.WEARABLE -> DeviceClass.WEARABLE
            BluetoothClass.Device.Major.TOY -> DeviceClass.IOT
            BluetoothClass.Device.Major.HEALTH -> DeviceClass.MEDICAL
            else -> null
        }
    }

    /**
     * Minor classes specific enough to override their own major.
     *
     * A camcorder and a pair of headphones are both AUDIO_VIDEO, and only one of them is a
     * recording device somebody might care about standing next to.
     */
    private fun minorClass(cod: Int): DeviceClass? = when (cod) {
        BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CAMERA,
        BluetoothClass.Device.AUDIO_VIDEO_CAMCORDER -> DeviceClass.SURVEILLANCE
        BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR,
        BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX,
        BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER -> DeviceClass.MEDIA
        BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> DeviceClass.VEHICLE
        BluetoothClass.Device.COMPUTER_LAPTOP,
        BluetoothClass.Device.COMPUTER_DESKTOP,
        BluetoothClass.Device.COMPUTER_SERVER -> DeviceClass.COMPUTER
        BluetoothClass.Device.PHONE_SMART,
        BluetoothClass.Device.PHONE_CELLULAR -> DeviceClass.PHONE
        BluetoothClass.Device.WEARABLE_WRIST_WATCH,
        BluetoothClass.Device.WEARABLE_GLASSES -> DeviceClass.WEARABLE
        BluetoothClass.Device.TOY_CONTROLLER,
        BluetoothClass.Device.TOY_GAME -> DeviceClass.GAMING
        else -> null
    }

    /**
     * With no signature we still want something better than UNKNOWN, because a device
     * list where every row says UNKNOWN is a device list nobody reads.
     */
    private fun inferClassWithoutSignature(obs: RadioObservation, vendor: String?): DeviceClass = when {
        obs.band == Band.CELL -> DeviceClass.CELL_TOWER
        obs.band == Band.SUBGHZ -> DeviceClass.VEHICLE_TPMS
        obs.band == Band.WIFI && obs.capabilities?.contains("ESS") == true -> DeviceClass.ACCESS_POINT
        vendor != null && Regex("apple|samsung|google|xiaomi|oneplus|motorola mobility", RegexOption.IGNORE_CASE)
            .containsMatchIn(vendor) -> DeviceClass.PHONE
        vendor != null && Regex("garmin|fitbit|polar electro|suunto|whoop", RegexOption.IGNORE_CASE)
            .containsMatchIn(vendor) -> DeviceClass.WEARABLE
        obs.band == Band.BLE -> DeviceClass.IOT
        else -> DeviceClass.UNKNOWN
    }

    companion object {
        /** Bits 8..12 of a class-of-device word hold the major class. */
        private const val MAJOR_MASK = 0x1f00

        fun create(context: Context) = Classifier(
            OuiRepository.get(context),
            BtSigRepository.get(context)
        )
    }
}
