package com.wave.scanner.data.oui

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.LruCache
import java.io.File

/**
 * Vendor lookup against the bundled 89,890-row OUI database.
 *
 * IEEE hands out blocks at three sizes - MA-L (24-bit), MA-M (28-bit) and MA-S (36-bit) -
 * and this database stores each one in TWO notations: a bare prefix, and a zero-padded
 * prefix with a `/28` or `/36` suffix. Getting this wrong is not a near miss. When a 36-bit
 * probe fails, the 24-bit fallback hits the PARENT block, and the parent of every delegated
 * range is registered to "IEEE Registration Authority" - so the bug did not show up as a
 * blank vendor, it showed up as thousands of devices confidently labelled with a
 * registrar's name. That is worse than admitting ignorance, and it is why [PLACEHOLDERS]
 * exists below.
 *
 * Probing is strict longest-prefix: 36-bit, then 28-bit, then 24-bit, trying both stored
 * notations at each size before dropping to the next.
 */
class OuiRepository private constructor(private val db: SQLiteDatabase) {

    data class Vendor(
        val oui: String,
        val manufacturer: String,
        val shortName: String?,
        val registry: String?,
        val deviceType: String?,
        val address: String?,
        val sources: String?
    ) {
        /** Country is only recoverable from the tail of the free-text address field. */
        val country: String? get() = address
            ?.trim()
            ?.split(" ")
            ?.let { parts -> parts.firstOrNull { it.length == 2 && it.all(Char::isUpperCase) } }
    }

    private val cache = LruCache<String, Vendor>(2048)
    private val misses = mutableSetOf<String>()

    fun lookup(mac: String?): Vendor? {
        if (mac.isNullOrBlank()) return null
        val norm = normalize(mac) ?: return null
        cache.get(norm)?.let { return it }
        if (norm in misses) return null

        for (prefix in probes(norm)) {
            val hit = queryPrefix(prefix) ?: continue
            if (hit.manufacturer.trim() in PLACEHOLDERS) continue
            cache.put(norm, hit)
            return hit
        }
        misses.add(norm)
        return null
    }

    /**
     * Every prefix worth trying, longest first.
     *
     * A 36-bit block is nine nibbles, which is not a whole number of octets, so the two
     * stored notations differ in shape: bare keeps the odd nibble (`00:1B:C5:00:0`) while
     * the suffixed form pads it to an even length (`00:1B:C5:00:00/36`). Both are generated
     * because the database genuinely contains both, in comparable quantities.
     */
    private fun probes(norm: String): List<String> {
        val hex = norm.replace(":", "")
        val out = ArrayList<String>(5)
        for (nibbles in intArrayOf(9, 7)) {                  // MA-S then MA-M
            if (hex.length < nibbles) continue
            val body = hex.substring(0, nibbles)
            out.add(colonize(body + "0"))                    // zero-padded, matches `.../36`
            out.add(colonize(body))                          // bare, odd-length tail
        }
        if (hex.length >= 6) out.add(colonize(hex.substring(0, 6)))  // MA-L
        return out
    }

    /** Groups hex into colon-separated octets, leaving a trailing odd nibble on its own. */
    private fun colonize(hex: String): String {
        val sb = StringBuilder(hex.length + hex.length / 2)
        var i = 0
        while (i < hex.length) {
            if (i > 0) sb.append(':')
            sb.append(hex, i, minOf(i + 2, hex.length))
            i += 2
        }
        return sb.toString()
    }

    private fun queryPrefix(prefix: String): Vendor? {
        // MA-M/MA-S rows appear both bare and with a /nn suffix, so match either form.
        val sql = """
            SELECT oui, manufacturer, short_name, registry, device_type, address, sources
            FROM oui_registry
            WHERE oui = ? OR oui LIKE ? || '/%'
            LIMIT 1
        """.trimIndent()
        return db.rawQuery(sql, arrayOf(prefix, prefix)).use { c ->
            if (!c.moveToFirst()) null
            else Vendor(
                oui = c.getString(0),
                manufacturer = c.getString(1),
                shortName = c.getStringOrNull(2),
                registry = c.getStringOrNull(3),
                deviceType = c.getStringOrNull(4),
                address = c.getStringOrNull(5),
                sources = c.getStringOrNull(6)
            )
        }
    }

    /** Free-text vendor search, used by the signature engine and the device search box. */
    fun searchManufacturer(term: String, limit: Int = 50): List<Vendor> {
        val sql = """
            SELECT oui, manufacturer, short_name, registry, device_type, address, sources
            FROM oui_registry WHERE manufacturer LIKE '%' || ? || '%' LIMIT ?
        """.trimIndent()
        return db.rawQuery(sql, arrayOf(term, limit.toString())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Vendor(
                            c.getString(0), c.getString(1), c.getStringOrNull(2),
                            c.getStringOrNull(3), c.getStringOrNull(4),
                            c.getStringOrNull(5), c.getStringOrNull(6)
                        )
                    )
                }
            }
        }
    }

    fun rowCount(): Int =
        db.rawQuery("SELECT COUNT(*) FROM oui_registry", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    private fun android.database.Cursor.getStringOrNull(i: Int): String? =
        if (isNull(i)) null else getString(i)

    companion object {
        private const val ASSET = "master_oui.db"

        /**
         * Names that mean "this block was delegated, ask a longer prefix" rather than
         * naming a manufacturer. Returning one of these is strictly worse than returning
         * nothing, because the UI would present it as an identification.
         */
        private val PLACEHOLDERS = setOf("IEEE Registration Authority", "Private")
        @Volatile private var INSTANCE: OuiRepository? = null

        /**
         * A randomised MAC carries no vendor at all: bit 1 of the first octet is the
         * locally-administered flag. Every modern phone sets it, which is exactly why
         * OUI lookup is useless for phone tracking and excellent for infrastructure.
         */
        fun isRandomized(mac: String): Boolean {
            val first = mac.take(2).toIntOrNull(16) ?: return false
            return (first and 0x02) != 0
        }

        fun normalize(mac: String): String? {
            val hex = mac.filter { it.isLetterOrDigit() }.uppercase()
            if (hex.length < 6) return null
            return hex.chunked(2).joinToString(":")
        }

        fun get(context: Context): OuiRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: OuiRepository(openDb(context)).also { INSTANCE = it }
            }

        /**
         * The asset is stored uncompressed (see `noCompress` in build.gradle.kts) but the
         * SQLite API needs a real path, so it is copied out once on first run.
         */
        private fun openDb(context: Context): SQLiteDatabase {
            val out = File(context.filesDir, ASSET)
            if (!out.exists() || out.length() == 0L) {
                context.assets.open(ASSET).use { input ->
                    out.outputStream().use { input.copyTo(it, 1 shl 16) }
                }
            }
            return SQLiteDatabase.openDatabase(out.path, null, SQLiteDatabase.OPEN_READONLY)
        }
    }
}
