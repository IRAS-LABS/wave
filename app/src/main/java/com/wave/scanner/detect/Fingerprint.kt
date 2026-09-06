package com.wave.scanner.detect

import com.wave.scanner.data.db.Band
import com.wave.scanner.scan.RadioObservation

/**
 * A compact description of everything structural about an emitter, independent of its
 * address.
 *
 * Two sightings with different MACs but identical fingerprints are very likely the same
 * physical radio after a randomisation event. This is the only leverage available against
 * MAC randomisation without transmitting anything.
 */
object Fingerprint {

    fun of(obs: RadioObservation): String = when (obs.band) {
        Band.WIFI -> wifi(obs)
        Band.BLE, Band.BT_CLASSIC -> ble(obs)
        Band.CELL -> cell(obs)
        Band.SUBGHZ -> subGhz(obs)
    }

    /**
     * Vendor IE ids plus the capability string. Chipset and firmware choose which vendor
     * elements to emit and in what order, so this is fairly discriminating between models
     * while staying identical across randomisations of one device.
     */
    private fun wifi(obs: RadioObservation): String {
        val ies = obs.vendorIes.keys.sorted().joinToString(",")
        val caps = obs.capabilities
            ?.replace("[WPS]", "")
            ?.trim()
            .orEmpty()
        return "wifi|ie=$ies|cap=${caps.hashCode()}|ch=${obs.channel ?: -1}"
    }

    private fun ble(obs: RadioObservation): String {
        val companies = obs.manufacturerData.keys.sorted().joinToString(",") { "%04x".format(it) }
        val uuids = obs.serviceUuids.sorted().joinToString(",")
        val lengths = obs.manufacturerData.entries
            .sortedBy { it.key }
            .joinToString(",") { it.value.size.toString() }
        return "ble|co=$companies|uuid=$uuids|len=$lengths"
    }

    private fun cell(obs: RadioObservation): String {
        val c = obs.cell ?: return "cell|?"
        return "cell|${c.technology}|${c.mcc}-${c.mnc}|pci=${c.pci}|earfcn=${c.earfcn}"
    }

    private fun subGhz(obs: RadioObservation): String {
        val s = obs.subGhz ?: return "subghz|?"
        return "subghz|${s.protocol}|${s.sensorId}"
    }
}
