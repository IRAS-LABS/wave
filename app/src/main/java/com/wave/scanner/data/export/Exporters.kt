package com.wave.scanner.data.export

import android.content.Context
import android.os.Build
import com.wave.scanner.data.db.Band
import com.wave.scanner.data.db.DeviceEntity
import com.wave.scanner.data.db.ObservationEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Export formats.
 *
 * A wardrive that cannot leave the phone is a wardrive nobody can check. WiGLE CSV in
 * particular is the lingua franca - it opens in every other scanner and mapping tool -
 * so it is implemented to the published header rather than approximated.
 *
 * Timestamps are written in UTC in every format. Local time in an exported dataset that
 * crosses a timezone or a DST boundary produces an unfixable mess later.
 */
object Exporters {

    private fun utc(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val wigleTime = utc("yyyy-MM-dd HH:mm:ss")
    private val isoTime = utc("yyyy-MM-dd'T'HH:mm:ss'Z'")
    private val fileStamp = utc("yyyyMMdd-HHmmss")

    fun outputDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "exports")
            .apply { mkdirs() }

    private fun newFile(context: Context, prefix: String, ext: String): File =
        File(outputDir(context), prefix + "-" + fileStamp.format(Date()) + "." + ext)

    // ------------------------------------------------------------- WiGLE CSV

    /**
     * WiGLE CSV 1.4. Each row is one device at its strongest observed position, which is
     * what the format expects - it is a sighting list, not a full track.
     */
    fun wigleCsv(
        context: Context,
        devices: List<DeviceEntity>,
        bestFix: Map<String, ObservationEntity>
    ): File {
        val f = newFile(context, "wave-wigle", "csv")
        f.bufferedWriter().use { w ->
            w.write(
                "WigleWifi-1.4,appRelease=1.0.0,model=" + Build.MODEL +
                    ",release=" + Build.VERSION.RELEASE +
                    ",device=" + Build.DEVICE +
                    ",display=" + Build.DISPLAY +
                    ",board=" + Build.BOARD +
                    ",brand=" + Build.BRAND + "\n"
            )
            w.write(
                "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude," +
                    "AltitudeMeters,AccuracyMeters,Type\n"
            )
            devices.forEach { d ->
                val obs = bestFix[d.id] ?: return@forEach
                if (obs.lat == null || obs.lon == null) return@forEach
                w.write(
                    listOf(
                        d.address,
                        csvEscape(d.displayName ?: ""),
                        csvEscape(d.capabilities ?: authModeFor(d)),
                        wigleTime.format(Date(d.firstSeen)),
                        (d.channel ?: 0).toString(),
                        d.bestRssi.toString(),
                        obs.lat.toString(),
                        obs.lon.toString(),
                        (obs.altitude ?: 0.0).toString(),
                        (obs.accuracy ?: 0f).toString(),
                        wigleType(d.band)
                    ).joinToString(",") + "\n"
                )
            }
        }
        return f
    }

    private fun wigleType(band: Band) = when (band) {
        Band.WIFI -> "WIFI"
        Band.BLE -> "BLE"
        Band.BT_CLASSIC -> "BT"
        Band.CELL -> "GSM"
        Band.SUBGHZ -> "WIFI"   // no WiGLE type exists for sub-GHz; kept out of scope
    }

    private fun authModeFor(d: DeviceEntity) = when (d.band) {
        Band.BLE, Band.BT_CLASSIC -> "Misc [BLE]"
        Band.CELL -> ""
        else -> "[ESS]"
    }

    // -------------------------------------------------------------- Full CSV

    /** Everything Wave knows, including the classification and its reason. */
    fun fullCsv(context: Context, devices: List<DeviceEntity>): File {
        val f = newFile(context, "wave-devices", "csv")
        f.bufferedWriter().use { w ->
            w.write(
                "id,band,address,name,label,vendor,vendorSource,class,threat,reason," +
                    "firstSeen,lastSeen,timesSeen,bestRssi,lastRssi,channel,capabilities," +
                    "fingerprint,watched\n"
            )
            devices.forEach { d ->
                w.write(
                    listOf(
                        d.id, d.band.name, d.address,
                        csvEscape(d.displayName ?: ""),
                        csvEscape(d.userLabel ?: ""),
                        csvEscape(d.vendor ?: ""),
                        d.vendorSource ?: "",
                        d.deviceClass.name, d.threat.name,
                        csvEscape(d.classReason ?: ""),
                        isoTime.format(Date(d.firstSeen)),
                        isoTime.format(Date(d.lastSeen)),
                        d.timesSeen.toString(),
                        d.bestRssi.toString(), d.lastRssi.toString(),
                        (d.channel ?: "").toString(),
                        csvEscape(d.capabilities ?: ""),
                        csvEscape(d.fingerprint ?: ""),
                        d.isWatched.toString()
                    ).joinToString(",") + "\n"
                )
            }
        }
        return f
    }

    // ------------------------------------------------------------------- KML

    /**
     * KML for Google Earth. Devices become placemarks coloured by threat; a device with
     * several located sightings also gets a line showing where it was heard, which is the
     * view that makes a follower obvious at a glance.
     */
    fun kml(
        context: Context,
        devices: List<DeviceEntity>,
        tracks: Map<String, List<ObservationEntity>>
    ): File {
        val f = newFile(context, "wave", "kml")
        f.bufferedWriter().use { w ->
            w.write("""<?xml version="1.0" encoding="UTF-8"?>""" + "\n")
            w.write("""<kml xmlns="http://www.opengis.net/kml/2.2"><Document>""" + "\n")
            w.write("<name>Wave wardrive " + isoTime.format(Date()) + "</name>\n")

            // KML colours are aabbggrr, not rrggbb.
            listOf(
                "threatCRITICAL" to "ff583bff",
                "threatHIGH" to "ff2b6bff",
                "threatMEDIUM" to "ff30c4ff",
                "threatLOW" to "ffa0e500",
                "threatNONE" to "ff756a5a"
            ).forEach { (id, colour) ->
                w.write(
                    "<Style id=\"" + id + "\"><IconStyle><color>" + colour +
                        "</color><scale>1.1</scale></IconStyle><LineStyle><color>" + colour +
                        "</color><width>3</width></LineStyle></Style>\n"
                )
            }

            devices.forEach { d ->
                val obs = tracks[d.id].orEmpty().filter { it.lat != null && it.lon != null }
                if (obs.isEmpty()) return@forEach
                val strongest = obs.maxByOrNull { it.rssi }!!

                w.write("<Placemark>\n")
                w.write("<name>" + xml(d.userLabel ?: d.displayName ?: d.address) + "</name>\n")
                w.write("<styleUrl>#threat" + d.threat.name + "</styleUrl>\n")
                w.write("<description><![CDATA[")
                w.write("<b>" + xml(d.deviceClass.name) + "</b><br/>")
                w.write("Address: " + xml(d.address) + "<br/>")
                d.vendor?.let { w.write("Vendor: " + xml(it) + "<br/>") }
                d.classReason?.let { w.write("Why: " + xml(it) + "<br/>") }
                w.write("Seen " + d.timesSeen + " times, best " + d.bestRssi + " dBm")
                w.write("]]></description>\n")
                w.write(
                    "<Point><coordinates>" + strongest.lon + "," + strongest.lat +
                        ",0</coordinates></Point>\n"
                )
                w.write("</Placemark>\n")

                if (obs.size > 2) {
                    w.write("<Placemark><name>" + xml(d.address) + " track</name>\n")
                    w.write("<styleUrl>#threat" + d.threat.name + "</styleUrl>\n")
                    w.write("<LineString><tessellate>1</tessellate><coordinates>\n")
                    obs.sortedBy { it.ts }.forEach {
                        w.write(it.lon.toString() + "," + it.lat + ",0 ")
                    }
                    w.write("\n</coordinates></LineString></Placemark>\n")
                }
            }
            w.write("</Document></kml>\n")
        }
        return f
    }

    // ------------------------------------------------------------------ JSON

    /** Lossless export, for re-import or analysis elsewhere. */
    fun json(
        context: Context,
        devices: List<DeviceEntity>,
        tracks: Map<String, List<ObservationEntity>>
    ): File {
        val f = newFile(context, "wave", "json")
        val root = JSONObject()
        root.put("format", "wave-export-1")
        root.put("exportedAt", isoTime.format(Date()))
        root.put("deviceCount", devices.size)

        val arr = JSONArray()
        devices.forEach { d ->
            val o = JSONObject()
            o.put("id", d.id)
            o.put("band", d.band.name)
            o.put("address", d.address)
            o.put("name", d.displayName)
            o.put("label", d.userLabel)
            o.put("vendor", d.vendor)
            o.put("vendorSource", d.vendorSource)
            o.put("class", d.deviceClass.name)
            o.put("threat", d.threat.name)
            o.put("reason", d.classReason)
            o.put("firstSeen", d.firstSeen)
            o.put("lastSeen", d.lastSeen)
            o.put("timesSeen", d.timesSeen)
            o.put("bestRssi", d.bestRssi)
            o.put("channel", d.channel)
            o.put("capabilities", d.capabilities)
            o.put("fingerprint", d.fingerprint)

            val obsArr = JSONArray()
            tracks[d.id].orEmpty().forEach { ob ->
                obsArr.put(
                    JSONObject()
                        .put("ts", ob.ts)
                        .put("rssi", ob.rssi)
                        .put("lat", ob.lat)
                        .put("lon", ob.lon)
                        .put("accuracy", ob.accuracy)
                )
            }
            o.put("observations", obsArr)
            arr.put(o)
        }
        root.put("devices", arr)
        f.writeText(root.toString(2))
        return f
    }

    // ----------------------------------------------------------------- utils

    private fun csvEscape(s: String): String {
        val cleaned = s.replace("\n", " ").replace("\r", " ")
        return if (cleaned.contains(',') || cleaned.contains('"')) {
            "\"" + cleaned.replace("\"", "\"\"") + "\""
        } else cleaned
    }

    private fun xml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
