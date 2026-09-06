package com.wave.scanner.sdr

/**
 * Turns raw 8-bit I/Q into a train of pulse and gap durations.
 *
 * Nearly every sub-GHz device Wave cares about - TPMS sensors, remote keyless entry,
 * weather stations - is on-off keyed or narrow FSK. At the amplitude level both look like
 * bursts of energy separated by silence, so a magnitude-and-threshold slicer recovers the
 * timing for both. Distinguishing OOK from FSK is left to the decoders, which know what
 * their own bit timings should look like.
 *
 * The noise floor is tracked continuously rather than fixed, because gain and interference
 * change constantly while driving and a static threshold either goes deaf or triggers on
 * everything.
 */
class PulseSlicer(private val sampleRate: Int) {

    data class Burst(
        /** Alternating durations in microseconds, starting with a pulse. */
        val timings: IntArray,
        val peakMagnitude: Int,
        val noiseFloor: Int
    ) {
        val pulseCount: Int get() = timings.size

        override fun equals(other: Any?): Boolean =
            this === other || (other is Burst && timings.contentEquals(other.timings))

        override fun hashCode(): Int = timings.contentHashCode()
    }

    private val usPerSample = 1_000_000.0 / sampleRate

    /** Exponential moving average of the magnitude, our running noise estimate. */
    private var floor = 8.0

    /** Bursts shorter than this are interference, not packets. */
    private val minPulseUs = 20
    /** A gap this long ends the burst. */
    private val maxGapUs = 12_000

    private val timings = ArrayList<Int>(512)
    private var inPulse = false
    private var runSamples = 0
    private var peak = 0

    /**
     * Feeds one block of interleaved unsigned I/Q and returns any completed bursts.
     * [buffer] is not retained.
     */
    fun process(buffer: ByteArray, length: Int = buffer.size): List<Burst> {
        val out = ArrayList<Burst>(4)
        var i = 0
        while (i + 1 < length) {
            val iq = (buffer[i].toInt() and 0xFF) - 128
            val qq = (buffer[i + 1].toInt() and 0xFF) - 128
            // |I| + |Q| approximates the true envelope closely enough for OOK slicing and
            // avoids a sqrt per sample, which matters at 250 kS/s on a phone.
            val mag = (if (iq < 0) -iq else iq) + (if (qq < 0) -qq else qq)
            i += 2

            floor += (mag - floor) * FLOOR_ALPHA
            val threshold = floor * THRESHOLD_FACTOR + 3

            val high = mag > threshold
            if (high && mag > peak) peak = mag

            if (high == inPulse) {
                runSamples++
                // Guard against a stuck carrier eating all memory.
                if (runSamples > sampleRate) { reset(); continue }
            } else {
                val durationUs = (runSamples * usPerSample).toInt()
                if (inPulse) {
                    if (durationUs >= minPulseUs) timings.add(durationUs)
                    else if (timings.isNotEmpty()) {
                        // Runt pulse inside a burst: fold it into the previous gap so the
                        // alternating pulse/gap structure stays intact.
                        timings[timings.size - 1] = timings.last() + durationUs
                    }
                } else {
                    if (durationUs > maxGapUs) {
                        emit(out)
                    } else if (timings.isNotEmpty()) {
                        timings.add(durationUs)
                    }
                }
                inPulse = high
                runSamples = 1
            }
        }
        return out
    }

    private fun emit(out: MutableList<Burst>) {
        if (timings.size >= MIN_TIMINGS) {
            out.add(Burst(timings.toIntArray(), peak, floor.toInt()))
        }
        timings.clear()
        peak = 0
    }

    private fun reset() {
        timings.clear()
        runSamples = 0
        inPulse = false
        peak = 0
    }

    private companion object {
        const val FLOOR_ALPHA = 0.0005
        const val THRESHOLD_FACTOR = 2.2
        /** Below this a burst is too short to be any packet worth decoding. */
        const val MIN_TIMINGS = 24
    }
}
