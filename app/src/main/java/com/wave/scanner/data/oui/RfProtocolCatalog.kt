package com.wave.scanner.data.oui

import android.content.Context
import org.json.JSONObject

/**
 * The 692-entry RF protocol catalog (Ringmast4r/RF-Protocol-Database), of which 28 entries
 * are TPMS families.
 *
 * This is a catalog, not a decoder bank: it tells us modulation, bit length, encoding and
 * checksum for a family, which is enough to narrow a captured sub-GHz burst to a small set
 * of candidates and to label the ones Wave decodes natively.
 */
class RfProtocolCatalog private constructor(val protocols: List<Protocol>) {

    data class Protocol(
        val deviceId: String,
        val name: String,
        val manufacturer: String?,
        val category: String,
        val modulation: String?,
        val encoding: String?,
        val bits: Int?,
        val checksum: String?,
        val source: String?
    ) {
        val isTpms: Boolean get() = category.contains("tpms", ignoreCase = true) ||
            name.contains("tpms", ignoreCase = true)
    }

    val tpms: List<Protocol> by lazy { protocols.filter { it.isTpms } }

    val categories: List<String> by lazy {
        protocols.map { it.category }.distinct().sorted()
    }

    fun byId(id: String): Protocol? = protocols.firstOrNull { it.deviceId == id }

    /**
     * Candidate families for a burst characterised only by bit length and modulation,
     * which is all a cheap decoder recovers before it knows the manufacturer.
     */
    fun candidatesFor(bits: Int, modulation: String? = null): List<Protocol> =
        tpms.filter { p ->
            val bitsOk = p.bits != null && kotlin.math.abs(p.bits - bits) <= 4
            val modOk = modulation == null || p.modulation == null ||
                p.modulation.contains(modulation, ignoreCase = true)
            bitsOk && modOk
        }

    /** Vehicle makes seen in the TPMS families, for the hardware screen's coverage list. */
    fun tpmsManufacturers(): List<String> =
        tpms.mapNotNull { it.manufacturer }.distinct().sorted()

    companion object {
        private const val ASSET = "rf_protocols.json"
        @Volatile private var INSTANCE: RfProtocolCatalog? = null

        fun get(context: Context): RfProtocolCatalog =
            INSTANCE ?: synchronized(this) { INSTANCE ?: RfProtocolCatalog(load(context)).also { INSTANCE = it } }

        private fun load(context: Context): List<Protocol> {
            val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val devices = root.optJSONObject("devices") ?: return emptyList()
            val out = ArrayList<Protocol>(devices.length())
            val keys = devices.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val o = devices.optJSONObject(k) ?: continue
                out.add(
                    Protocol(
                        deviceId = o.optString("device_id", k),
                        name = o.optString("name", k),
                        manufacturer = o.optString("manufacturer").ifBlank { null },
                        category = o.optString("category", "unknown"),
                        modulation = o.optString("modulation").ifBlank { null },
                        encoding = o.optString("encoding").ifBlank { null },
                        bits = o.optInt("bits", -1).takeIf { it > 0 },
                        checksum = o.optString("checksum").ifBlank { null },
                        source = o.optString("source").ifBlank { null }
                    )
                )
            }
            return out
        }
    }
}
