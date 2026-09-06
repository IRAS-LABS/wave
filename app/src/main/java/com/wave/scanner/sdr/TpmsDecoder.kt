package com.wave.scanner.sdr

/**
 * TPMS packet decoders.
 *
 * Every tyre on the road since roughly 2008 carries a sensor that periodically announces a
 * unique 28-to-32-bit serial number in clear text on 315 or 433.92 MHz. Those serials do
 * not rotate, which makes them the single most durable vehicle identifier available
 * passively - far more durable than a MAC address, and the reason this lane is worth the
 * hardware it needs.
 *
 * Three families are decoded natively below. They were chosen because between them they
 * cover most of the North American and European fleet, and because their framing is
 * documented well enough to implement honestly. Everything else is reported as a raw
 * burst with candidate protocol names from the bundled catalog rather than being
 * guessed at - a wrong make and model is worse than an unlabelled sighting.
 */
object TpmsDecoder {

    data class Packet(
        val protocol: String,
        val sensorId: String,
        val pressureKpa: Double?,
        val temperatureC: Double?,
        val batteryLow: Boolean?,
        val rawHex: String,
        val frequencyMhz: Double
    )

    fun decode(burst: PulseSlicer.Burst, frequencyMhz: Double): Packet? {
        // Try Manchester first: two of the three families use it, and a Manchester decode
        // of a PWM signal fails cleanly rather than producing plausible garbage.
        manchester(burst.timings)?.let { bits ->
            toyota(bits, frequencyMhz)?.let { return it }
            ford(bits, frequencyMhz)?.let { return it }
            schrader(bits, frequencyMhz)?.let { return it }
        }
        differentialManchester(burst.timings)?.let { bits ->
            toyota(bits, frequencyMhz)?.let { return it }
        }
        return null
    }

    // -------------------------------------------------------------- protocols

    /**
     * Toyota / Pacific Industrial PMV-107J. 9 bytes, CRC-8 poly 0x07 init 0x80.
     * The CRC is what makes this safe to claim: a false positive would have to pass an
     * 8-bit check over 8 bytes, so roughly 1 in 256 of already-well-framed noise.
     */
    private fun toyota(bits: BooleanArray, freq: Double): Packet? {
        val b = findFramed(bits, 9) ?: return null
        if (crc8(b, 0, 8, poly = 0x07, init = 0x80) != (b[8].toInt() and 0xFF)) return null

        val id = ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
            ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)
        val p1 = ((b[4].toInt() and 0x7F) shl 1) or ((b[5].toInt() and 0xFF) ushr 7)
        val t = ((b[5].toInt() and 0x7F) shl 1) or ((b[6].toInt() and 0xFF) ushr 7)

        return Packet(
            protocol = "Toyota PMV-107J",
            sensorId = "%08X".format(id),
            pressureKpa = psiToKpa(p1 * 0.25 - 7.0),
            temperatureC = (t - 40).toDouble(),
            batteryLow = (b[4].toInt() and 0x80) != 0,
            rawHex = b.toHex(),
            frequencyMhz = freq
        )
    }

    /** Ford. 8 bytes, CRC-8 poly 0x07 init 0x00 over the first seven. */
    private fun ford(bits: BooleanArray, freq: Double): Packet? {
        val b = findFramed(bits, 8) ?: return null
        if (crc8(b, 0, 7, poly = 0x07, init = 0x00) != (b[7].toInt() and 0xFF)) return null

        val id = ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
            ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)
        val pressure = b[4].toInt() and 0xFF
        val temp = b[5].toInt() and 0xFF
        val flags = b[6].toInt() and 0xFF

        return Packet(
            protocol = "Ford",
            sensorId = "%08X".format(id),
            pressureKpa = psiToKpa(pressure * 0.25),
            temperatureC = (temp - 56).toDouble(),
            batteryLow = (flags and 0x10) != 0,
            rawHex = b.toHex(),
            frequencyMhz = freq
        )
    }

    /**
     * Schrader EG53MA4-family, nibble-summed checksum. Weaker validation than a CRC, so
     * an extra sanity gate on the decoded pressure keeps obvious nonsense out.
     */
    private fun schrader(bits: BooleanArray, freq: Double): Packet? {
        val b = findFramed(bits, 8) ?: return null

        var sum = 0
        for (i in 0 until 7) {
            sum += (b[i].toInt() and 0x0F) + ((b[i].toInt() and 0xF0) ushr 4)
        }
        if ((sum and 0xFF) != (b[7].toInt() and 0xFF)) return null

        val serial = ((b[1].toLong() and 0x0F) shl 20) or ((b[2].toLong() and 0xFF) shl 12) or
            ((b[3].toLong() and 0xFF) shl 4) or ((b[4].toLong() and 0xF0) ushr 4)
        val pressure = ((b[4].toInt() and 0x0F) shl 4) or ((b[5].toInt() and 0xF0) ushr 4)
        val temp = ((b[5].toInt() and 0x0F) shl 4) or ((b[6].toInt() and 0xF0) ushr 4)

        val kpa = pressure * 2.5
        // No road tyre sits below 50 kPa or above 800 kPa while transmitting.
        if (kpa < 50 || kpa > 800) return null

        return Packet(
            protocol = "Schrader",
            sensorId = "%06X".format(serial),
            pressureKpa = kpa,
            temperatureC = (temp - 50).toDouble(),
            batteryLow = null,
            rawHex = b.toHex(),
            frequencyMhz = freq
        )
    }

    // ------------------------------------------------------------- bit layer

    /**
     * Manchester: each bit is a pulse/gap pair whose ordering carries the value. Timings
     * arrive as alternating durations, so a pair of roughly equal durations is one bit and
     * a long duration is two half-bits of the same level.
     */
    private fun manchester(timings: IntArray): BooleanArray? {
        val half = estimateHalfBit(timings) ?: return null
        val levels = ArrayList<Boolean>(timings.size * 2)
        timings.forEachIndexed { idx, dur ->
            val level = idx % 2 == 0
            val units = Math.round(dur.toDouble() / half).toInt()
            if (units < 1 || units > 4) return@forEachIndexed
            repeat(units) { levels.add(level) }
        }
        if (levels.size < 32) return null

        val bits = ArrayList<Boolean>(levels.size / 2)
        var i = 0
        while (i + 1 < levels.size) {
            val a = levels[i]
            val b = levels[i + 1]
            if (a == b) { i++; continue }   // resync on a half-bit slip
            bits.add(!a && b)               // low-to-high is a one
            i += 2
        }
        return if (bits.size >= 32) bits.toBooleanArray() else null
    }

    /** Differential Manchester: a transition at the bit boundary means zero. */
    private fun differentialManchester(timings: IntArray): BooleanArray? {
        val half = estimateHalfBit(timings) ?: return null
        val bits = ArrayList<Boolean>(timings.size)
        var i = 0
        while (i < timings.size) {
            val units = Math.round(timings[i].toDouble() / half).toInt()
            when (units) {
                1 -> { bits.add(false); i++ }
                2 -> { bits.add(true); i++ }
                else -> i++
            }
        }
        return if (bits.size >= 32) bits.toBooleanArray() else null
    }

    /**
     * The shortest recurring duration is the half-bit period. Taking the median of the
     * shortest quartile resists both runt pulses and a merged pair.
     */
    private fun estimateHalfBit(timings: IntArray): Double? {
        if (timings.size < 16) return null
        val sorted = timings.sortedArray()
        val quartile = sorted.copyOfRange(0, maxOf(4, sorted.size / 4))
        val median = quartile[quartile.size / 2].toDouble()
        return if (median in 20.0..600.0) median else null
    }

    /**
     * Slides a window looking for a byte alignment whose first byte is non-zero, which
     * every one of these formats guarantees because the ID never starts a packet at zero.
     */
    private fun findFramed(bits: BooleanArray, byteCount: Int): ByteArray? {
        val needed = byteCount * 8
        if (bits.size < needed) return null
        for (offset in 0..(bits.size - needed)) {
            val bytes = ByteArray(byteCount)
            for (i in 0 until needed) {
                if (bits[offset + i]) {
                    bytes[i / 8] = (bytes[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
                }
            }
            if (bytes[0].toInt() != 0 && bytes.any { it.toInt() != -1 }) return bytes
        }
        return null
    }

    private fun crc8(data: ByteArray, from: Int, len: Int, poly: Int, init: Int): Int {
        var crc = init and 0xFF
        for (i in from until from + len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor poly) and 0xFF
                else (crc shl 1) and 0xFF
            }
        }
        return crc
    }

    private fun psiToKpa(psi: Double) = psi * 6.89476

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
