package com.wave.scanner.sdr

import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Client for the rtl_tcp protocol.
 *
 * Wave deliberately does not bundle a native librtlsdr build. The USB permission model
 * on Android makes an in-process driver fragile, and the ecosystem already solved this:
 * the standard RTL-SDR driver apps expose the dongle as an rtl_tcp server on loopback.
 * Talking to that over a socket means Wave needs no NDK toolchain, no vendor-specific
 * USB quirks handling, and works with whichever driver build the user already trusts.
 *
 * The cost is an extra app install, which the hardware screen explains.
 */
class RtlTcpClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 1234
) {

    private var socket: Socket? = null
    private var input: DataInputStream? = null

    @Volatile var isConnected: Boolean = false
        private set

    var dongleInfo: DongleInfo? = null
        private set

    data class DongleInfo(val tunerType: String, val tunerGainCount: Int)

    fun connect(timeoutMs: Int = 3000): Boolean {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.tcpNoDelay = true
            socket = s
            input = DataInputStream(s.getInputStream().buffered(1 shl 16))
            readDongleHeader()
            isConnected = true
            true
        } catch (e: IOException) {
            Log.w(TAG, "rtl_tcp connect failed: " + e.message)
            close()
            false
        }
    }

    /** rtl_tcp opens with a 12-byte magic header describing the tuner. */
    private fun readDongleHeader() {
        val header = ByteArray(12)
        input?.readFully(header)
        val magic = String(header, 0, 4)
        if (magic != "RTL0") {
            Log.w(TAG, "unexpected rtl_tcp magic: " + magic)
        }
        val tunerId = be32(header, 4)
        val gainCount = be32(header, 8)
        dongleInfo = DongleInfo(tunerName(tunerId), gainCount)
    }

    private fun tunerName(id: Int): String = when (id) {
        1 -> "E4000"
        2 -> "FC0012"
        3 -> "FC0013"
        4 -> "FC2580"
        5 -> "R820T"
        6 -> "R828D"
        else -> "unknown"
    }

    // ------------------------------------------------------------- commands

    fun setFrequency(hz: Long) = command(CMD_SET_FREQ, hz.toInt())
    fun setSampleRate(hz: Int) = command(CMD_SET_SAMPLE_RATE, hz)
    fun setGainMode(auto: Boolean) = command(CMD_SET_GAIN_MODE, if (auto) 0 else 1)
    /** Tenths of a dB, as rtl_tcp expects. */
    fun setGain(tenthsDb: Int) = command(CMD_SET_GAIN, tenthsDb)
    fun setAgcMode(on: Boolean) = command(CMD_SET_AGC_MODE, if (on) 1 else 0)
    fun setPpm(ppm: Int) = command(CMD_SET_FREQ_CORRECTION, ppm)

    private fun command(cmd: Byte, param: Int) {
        val out = socket?.getOutputStream() ?: return
        val buf = ByteArray(5)
        buf[0] = cmd
        buf[1] = (param ushr 24).toByte()
        buf[2] = (param ushr 16).toByte()
        buf[3] = (param ushr 8).toByte()
        buf[4] = param.toByte()
        runCatching {
            out.write(buf)
            out.flush()
        }.onFailure { Log.w(TAG, "command failed: " + it.message) }
    }

    /**
     * Fills [buffer] with interleaved unsigned 8-bit I/Q, which is what the RTL2832U
     * produces natively. Returns false when the stream dies.
     */
    fun readSamples(buffer: ByteArray): Boolean = try {
        input?.readFully(buffer)
        true
    } catch (e: IOException) {
        isConnected = false
        false
    }

    fun close() {
        runCatching { input?.close() }
        runCatching { socket?.close() }
        input = null
        socket = null
        isConnected = false
    }

    private fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private companion object {
        const val TAG = "RtlTcpClient"
        const val CMD_SET_FREQ: Byte = 0x01
        const val CMD_SET_SAMPLE_RATE: Byte = 0x02
        const val CMD_SET_GAIN_MODE: Byte = 0x03
        const val CMD_SET_GAIN: Byte = 0x04
        const val CMD_SET_FREQ_CORRECTION: Byte = 0x05
        const val CMD_SET_AGC_MODE: Byte = 0x08
    }
}
