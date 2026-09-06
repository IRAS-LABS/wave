package com.wave.scanner.detect

import com.wave.scanner.scan.RadioObservation

/**
 * Item-tracker identification from advertising payloads.
 *
 * The hard part is not spotting a tracker, it is spotting the SAME tracker twice. A
 * separated AirTag rotates both its MAC address and its advertised key roughly every 15
 * minutes, so neither field is a durable identity. Wave keys on the payload for
 * within-window continuity and leaves cross-window correlation to FollowDetector, which
 * reasons about a tracker family persisting along a route rather than a fixed address.
 *
 * Honest limitation, stated once here so it is not oversold in the UI: no passive scanner
 * can prove two rotations belong to one physical tag. What it can prove is that a
 * separated tracker of a given family was present at N places over M minutes with you.
 */
object TrackerPayloads {

    private const val APPLE = 0x004C
    private const val SAMSUNG = 0x0075

    /** Apple offline-finding message type. */
    private const val TYPE_FIND_MY = 0x12
    /** Length byte for the full separated-tag payload; the paired form is 0x02. */
    private const val LEN_SEPARATED = 0x19

    data class Hit(
        val signature: Signature,
        val payloadKey: String?,
        val reason: String,
        val isSeparated: Boolean
    )

    fun identify(obs: RadioObservation): Hit? =
        appleFindMy(obs) ?: samsung(obs) ?: tile(obs) ?: chipolo(obs)

    /**
     * Apple's payload also carries every nearby iPhone and Mac, which is why a naive
     * "company 0x004C means AirTag" check drowns the user in false positives in any
     * public place. Only the 0x19-length separated form is a tracker away from its owner.
     */
    private fun appleFindMy(obs: RadioObservation): Hit? {
        val data = obs.manufacturerData[APPLE] ?: return null
        if (data.size < 2) return null
        val type = data[0].toInt() and 0xFF
        if (type != TYPE_FIND_MY) return null
        val len = data[1].toInt() and 0xFF
        val sig = Signatures.byId("apple_findmy") ?: return null

        if (len != LEN_SEPARATED || data.size < 4) {
            // Paired and nearby: this is somebody's phone or laptop, not a planted tag.
            return null
        }

        val status = data[2].toInt() and 0xFF
        // Bit 2 of the status byte is set once the tag has been registered and is
        // maintaining its own advertisement away from the owner device.
        val maintained = (status and 0x04) != 0

        // Bytes 3.. are the truncated rotating public key. Stable only until the next
        // rotation, so it gives us continuity inside a window, never a permanent id.
        val key = data.copyOfRange(3, minOf(data.size, 25)).toHex()

        return Hit(
            signature = sig,
            payloadKey = "findmy:$key",
            reason = if (maintained)
                "Apple Find My tag advertising in separated mode - away from its owner"
            else
                "Apple Find My accessory in offline-finding mode",
            isSeparated = maintained
        )
    }

    private fun samsung(obs: RadioObservation): Hit? {
        val sig = Signatures.byId("samsung_smarttag") ?: return null
        val byUuid = obs.serviceUuids.contains("fd5a")
        val data = obs.manufacturerData[SAMSUNG]
        if (!byUuid && data == null) return null
        val key = data?.toHex()?.take(24) ?: obs.address.orEmpty()
        return Hit(
            signature = sig,
            payloadKey = "smarttag:$key",
            reason = "Samsung SmartTag offline-finding advertisement",
            isSeparated = true
        )
    }

    /**
     * Tile advertises a stable identifier on feed/feec, so unlike the Find My family it
     * really can be followed across an entire drive on one key.
     */
    private fun tile(obs: RadioObservation): Hit? {
        if (obs.serviceUuids.none { it == "feed" || it == "feec" }) return null
        val sig = Signatures.byId("tile") ?: return null
        return Hit(
            signature = sig,
            payloadKey = obs.address?.let { "tile:$it" },
            reason = "Tile service UUID present - Tile identifiers are stable, so repeat " +
                "sightings are reliable",
            isSeparated = true
        )
    }

    private fun chipolo(obs: RadioObservation): Hit? {
        if (!obs.serviceUuids.contains("fe33")) return null
        val sig = Signatures.byId("chipolo") ?: return null
        return Hit(sig, obs.address?.let { "chipolo:$it" }, "Chipolo service UUID present", true)
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
