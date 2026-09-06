package com.wave.scanner.scan

import com.wave.scanner.data.db.Band

/**
 * One emitter heard once, normalised across four very different radios.
 *
 * Every scanner produces this shape so the classifier, the database writer and the
 * follow-detector never need to know which radio a sighting came from.
 */
data class RadioObservation(
    val band: Band,
    /** Hardware address as reported. Null for cell and sub-GHz, which have no MAC. */
    val address: String?,
    val name: String?,
    val rssi: Int,
    val timestamp: Long,
    val frequencyMhz: Int? = null,
    val channel: Int? = null,
    /** Wi-Fi capability string, or a rendered summary of BLE advertising flags. */
    val capabilities: String? = null,
    /** BLE manufacturer-specific data, keyed by SIG company identifier. */
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    /** 16-bit service UUIDs, lowercase, base-UUID stripped. */
    val serviceUuids: Set<String> = emptySet(),
    /** Raw vendor information elements from a Wi-Fi beacon, keyed by IE id. */
    val vendorIes: Map<Int, ByteArray> = emptyMap(),
    /**
     * Classic Bluetooth class-of-device, present only on Band.BT_CLASSIC.
     *
     * This is the device's own declaration of what it is - major class, minor class and
     * service bits packed into one integer - and it is the single most useful field the
     * classic radio gives us. It is a claim rather than a proof, but an unforced one, so
     * it outranks any guess drawn from the vendor's OUI.
     */
    val btCod: Int? = null,
    /** Cell identity fields, present only on Band.CELL. */
    val cell: CellFacts? = null,
    /** Decoded sub-GHz packet, present only on Band.SUBGHZ. */
    val subGhz: SubGhzFacts? = null
) {
    /**
     * A stable identity for a sighting. Trackers in separated mode rotate their address,
     * so anything the payload can key on must win over the address or the same physical
     * tag shows up as dozens of one-off devices and the follow-detector never fires.
     *
     * The same reasoning extends past trackers to ordinary consumer hardware. A Samsung TV,
     * a laptop, a pair of earbuds - anything on a modern BLE stack - advertises from a
     * random resolvable address that rolls every fifteen minutes or so. Keyed on the
     * address, one television in the living room becomes a fresh "device" four times an
     * hour, and an evening of scanning invents a dozen televisions that were never there.
     */
    fun identityKey(payloadKey: String?): String =
        payloadKey ?: contentKey() ?: address ?: syntheticKey()

    /**
     * Identity derived from what the device SAYS rather than the address it says it from,
     * used only when the address is provably disposable.
     *
     * Guarded hard, because over-merging is the opposite failure and just as misleading:
     * this needs a real discriminator - a broadcast name, a company ID, or service UUIDs -
     * before it will collapse anything. Two silent, featureless beacons stay separate,
     * which is the right call when there is genuinely nothing to tell them apart.
     *
     * Manufacturer data VALUES are deliberately excluded: Apple and Microsoft rewrite those
     * bytes constantly (battery, activity, nearby state), so including them would key on
     * noise and defeat the whole purpose. Only the company ID itself is stable.
     */
    private fun contentKey(): String? {
        val addr = address ?: return null
        if (!isRandomAddress(addr)) return null

        val label = name?.trim()?.takeIf { it.isNotEmpty() }
        val companies = manufacturerData.keys.sorted()
        val services = serviceUuids.sorted()
        if (label == null && companies.isEmpty() && services.isEmpty()) return null

        return buildString {
            append("ble-content:")
            append(label.orEmpty()).append('|')
            companies.joinTo(this, ",") { it.toString(16) }
            append('|')
            services.joinTo(this, ",")
        }
    }

    /**
     * Bit 1 of the first octet is the locally-administered flag. Every random BLE address
     * sets it; a real burned-in hardware address never does.
     */
    private fun isRandomAddress(addr: String): Boolean {
        val first = addr.take(2).toIntOrNull(16) ?: return false
        return (first and 0x02) != 0
    }

    private fun syntheticKey(): String = when {
        cell != null -> "cell:${cell.mcc}-${cell.mnc}-${cell.tac}-${cell.cid}"
        subGhz != null -> "subghz:${subGhz.protocol}:${subGhz.sensorId}"
        else -> "anon:$band:${name.orEmpty()}"
    }
}

data class CellFacts(
    val technology: String,
    val mcc: String?,
    val mnc: String?,
    val tac: Int?,
    val cid: Long?,
    val pci: Int?,
    val earfcn: Int?,
    val isRegistered: Boolean,
    val timingAdvance: Int? = null
)

data class SubGhzFacts(
    val protocol: String,
    val sensorId: String,
    val frequencyMhz: Double,
    val pressureKpa: Double? = null,
    val temperatureC: Double? = null,
    val batteryLow: Boolean? = null,
    val rawHex: String? = null
)

/** Per-band scanner lifecycle. Uniform so ScanService can start and stop them as a set. */
interface Scanner {
    val band: Band
    /** False when the radio is off, unsupported, or the permission was refused. */
    fun isAvailable(): Boolean
    fun start(onObservation: (RadioObservation) -> Unit)
    fun stop()
}
