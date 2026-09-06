package com.wave.scanner.data.oui

import android.content.Context

/**
 * Bluetooth SIG company identifiers, merged from the official SIG assigned-numbers list
 * and the angorb gist (4,095 entries).
 *
 * BLE manufacturer-specific data begins with a little-endian 16-bit company ID. That ID
 * is the only vendor evidence available for a device using a resolvable-private address,
 * which is to say: for almost every phone and every item tracker in separated mode.
 */
class BtSigRepository private constructor(private val map: Map<Int, String>) {

    fun name(companyId: Int): String? = map[companyId and 0xFFFF]

    /** Reads the company ID from the front of a BLE manufacturer-data blob. */
    fun nameFromManufacturerData(data: ByteArray): String? {
        if (data.size < 2) return null
        val id = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        return name(id)
    }

    fun search(term: String): List<Pair<Int, String>> =
        map.entries
            .filter { it.value.contains(term, ignoreCase = true) }
            .map { it.key to it.value }
            .sortedBy { it.first }

    val size: Int get() = map.size

    companion object {
        private const val ASSET = "bt_company_ids.csv"
        @Volatile private var INSTANCE: BtSigRepository? = null

        fun get(context: Context): BtSigRepository =
            INSTANCE ?: synchronized(this) { INSTANCE ?: BtSigRepository(load(context)).also { INSTANCE = it } }

        private fun load(context: Context): Map<Int, String> {
            val out = HashMap<Int, String>(4200)
            context.assets.open(ASSET).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val (idRaw, nameRaw) = splitCsvPair(line) ?: return@forEach
                    val id = idRaw.removePrefix("0x").removePrefix("0X")
                        .toIntOrNull(16) ?: idRaw.toIntOrNull() ?: return@forEach
                    out[id] = nameRaw
                }
            }
            return out
        }

        /** Minimal two-column CSV split; vendor names routinely contain quoted commas. */
        private fun splitCsvPair(line: String): Pair<String, String>? {
            if (line.isBlank()) return null
            val comma = line.indexOf(',')
            if (comma <= 0) return null
            val first = line.substring(0, comma).trim().trim('"')
            if (first.equals("company_id", true) || first.equals("value", true)) return null
            val second = line.substring(comma + 1).trim().trim('"').trim()
            if (second.isEmpty()) return null
            return first to second
        }
    }
}
