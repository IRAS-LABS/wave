package com.wave.scanner.sdr

import android.content.Context
import android.util.Log
import com.wave.scanner.data.db.Band
import com.wave.scanner.data.oui.RfProtocolCatalog
import com.wave.scanner.scan.RadioObservation
import com.wave.scanner.scan.Scanner
import com.wave.scanner.scan.SubGhzFacts
import kotlin.concurrent.thread

/**
 * Sub-GHz lane: everything a phone radio physically cannot hear.
 *
 * Phone Wi-Fi and Bluetooth front-ends are hard-wired for 2.4 and 5 GHz. TPMS at 315 and
 * 433.92 MHz is not a software limitation, it is outside the antenna and the tuner, which
 * is why this lane requires an external receiver and why the app says so plainly rather
 * than pretending to scan.
 *
 * Both TPMS bands are covered by dwelling on each in turn. A sensor transmits every
 * 30-90 seconds while rolling, so a 20-second dwell catches most vehicles that stay near
 * you for a minute - which is exactly the set worth caring about.
 */
class SdrScanner(private val context: Context) : Scanner {

    override val band = Band.SUBGHZ

    private val client = RtlTcpClient()
    private val catalog = RfProtocolCatalog.get(context)
    private var worker: Thread? = null
    @Volatile private var running = false

    var status: String = "Not connected"
        private set

    val dongle: RtlTcpClient.DongleInfo? get() = client.dongleInfo

    /**
     * Availability is probed by actually connecting, because the driver app may be
     * installed but not running, and there is no reliable way to ask it.
     */
    override fun isAvailable(): Boolean {
        if (client.isConnected) return true
        val ok = client.connect(timeoutMs = 800)
        status = if (ok) {
            "Connected to " + (client.dongleInfo?.tunerType ?: "tuner")
        } else {
            "No rtl_tcp server on 127.0.0.1:1234"
        }
        return ok
    }

    override fun start(onObservation: (RadioObservation) -> Unit) {
        if (running) return
        if (!client.isConnected && !client.connect()) {
            status = "Could not connect to rtl_tcp"
            return
        }
        running = true

        client.setSampleRate(SAMPLE_RATE)
        client.setGainMode(auto = true)
        client.setAgcMode(on = true)

        worker = thread(name = "wave-sdr", isDaemon = true) {
            val buffer = ByteArray(BLOCK_BYTES)
            var bandIndex = 0
            var dwellStart = System.currentTimeMillis()
            var slicer = PulseSlicer(SAMPLE_RATE)

            client.setFrequency(BANDS[bandIndex].first)
            status = "Listening on " + BANDS[bandIndex].second + " MHz"

            while (running) {
                if (!client.readSamples(buffer)) {
                    status = "Stream ended"
                    break
                }

                val bursts = runCatching { slicer.process(buffer) }.getOrDefault(emptyList())
                bursts.forEach { burst ->
                    val freq = BANDS[bandIndex].second
                    val packet = runCatching { TpmsDecoder.decode(burst, freq) }.getOrNull()
                    if (packet != null) {
                        onObservation(packet.toObservation(burst.peakMagnitude))
                    } else {
                        unidentified(burst, freq)?.let(onObservation)
                    }
                }

                val now = System.currentTimeMillis()
                if (now - dwellStart > DWELL_MS) {
                    bandIndex = (bandIndex + 1) % BANDS.size
                    client.setFrequency(BANDS[bandIndex].first)
                    // A fresh slicer per hop: the old noise estimate belongs to the old band.
                    slicer = PulseSlicer(SAMPLE_RATE)
                    dwellStart = now
                    status = "Listening on " + BANDS[bandIndex].second + " MHz"
                }
            }
            status = "Stopped"
        }
    }

    override fun stop() {
        running = false
        worker?.let { runCatching { it.join(1500) } }
        worker = null
        client.close()
        status = "Not connected"
    }

    /**
     * A burst none of the native decoders claimed.
     *
     * Rather than throwing it away, the transition count is turned into a rough bit length
     * and matched against the protocol catalogue. That is a weak signal - dozens of formats
     * share a bit count - so the result is reported as a candidate family and never as an
     * identification, and the sensor id is the burst's own shape rather than a decoded one.
     *
     * Very short bursts are dropped entirely. Below about forty transitions almost anything
     * matches something, and a list of maybes is worse than silence.
     */
    private fun unidentified(burst: PulseSlicer.Burst, freqMhz: Double): RadioObservation? {
        if (burst.pulseCount < MIN_UNKNOWN_TRANSITIONS) return null

        // Two transitions per Manchester bit is the common case in this band, so the
        // transition count is halved before the lookup.
        val bits = burst.pulseCount / 2
        val candidates = catalog.candidatesFor(bits)
        if (candidates.isEmpty()) {
            Log.v(TAG, "undecoded burst, " + burst.pulseCount + " transitions, no catalogue match")
            return null
        }

        val families = candidates.mapNotNull { it.manufacturer ?: it.name }.distinct().take(4)
        val shape = burstShape(burst)

        return RadioObservation(
            band = Band.SUBGHZ,
            address = null,
            name = "Unidentified " + freqMhz + " MHz burst",
            rssi = -120 + burst.peakMagnitude.coerceAtMost(120),
            timestamp = System.currentTimeMillis(),
            frequencyMhz = freqMhz.toInt(),
            capabilities = bits.toString() + " bits, possibly " + families.joinToString(" / "),
            subGhz = SubGhzFacts(
                protocol = "unidentified",
                sensorId = shape,
                frequencyMhz = freqMhz,
                rawHex = null
            )
        )
    }

    /**
     * A stable-ish key for an undecoded burst, from its length and timing profile. Two
     * transmissions from the same device usually land on the same key; two different
     * devices with the same protocol will collide, which is why this is never presented
     * as a sensor identity.
     */
    private fun burstShape(burst: PulseSlicer.Burst): String {
        val sorted = burst.timings.sortedArray()
        val median = sorted[sorted.size / 2]
        val bucket = (median / 20) * 20
        return "shape-" + burst.pulseCount + "-" + bucket
    }

    private fun TpmsDecoder.Packet.toObservation(peak: Int): RadioObservation =
        RadioObservation(
            band = Band.SUBGHZ,
            address = null,
            name = protocol + " " + sensorId,
            // The dongle reports no calibrated RSSI over rtl_tcp, so burst amplitude is
            // mapped onto the dBm scale purely so the UI can sort by signal.
            rssi = (-120 + peak.coerceAtMost(120)),
            timestamp = System.currentTimeMillis(),
            frequencyMhz = frequencyMhz.toInt(),
            subGhz = SubGhzFacts(
                protocol = protocol,
                sensorId = sensorId,
                frequencyMhz = frequencyMhz,
                pressureKpa = pressureKpa,
                temperatureC = temperatureC,
                batteryLow = batteryLow,
                rawHex = rawHex
            )
        )

    private companion object {
        const val TAG = "SdrScanner"
        /** Above the RTL2832U minimum and a clean divisor for the bit rates involved. */
        const val SAMPLE_RATE = 250_000
        const val BLOCK_BYTES = 32 * 1024
        const val DWELL_MS = 20_000L
        const val MIN_UNKNOWN_TRANSITIONS = 40
        val BANDS = listOf(
            315_000_000L to 315.0,      // North American TPMS
            433_920_000L to 433.92      // European TPMS and most ISM devices
        )
    }
}
