package com.wave.scanner.detect

import com.wave.scanner.data.db.Band
import com.wave.scanner.data.db.DeviceEntity
import com.wave.scanner.data.db.ObservationEntity
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Where a device probably is, and how sure we can honestly claim to be.
 *
 * Read this before using any number out of here, because the headline question - "put the
 * device on the map down to the millimetre" - has a real answer and it is not the one the
 * numbers here give.
 *
 * A phone has ONE omnidirectional antenna per band. That fixes what is physically knowable
 * from a single point in space:
 *
 *  - **Distance: roughly.** Signal strength falls off with distance, so it carries range
 *    information. It also falls off with walls, bodies, car doors, rain and the orientation
 *    of the other device's antenna, and none of those are separable from distance by a
 *    receiver that only sees the total. A ±50% range error is normal and a 3x error is
 *    common indoors. Every distance here is an order of magnitude, not a measurement.
 *
 *  - **Direction: not at all.** Bearing needs either a directional antenna or an array of
 *    them far enough apart to compare phase. A phone exposes neither, so nothing in this
 *    file can tell you whether a device is in front of you or behind you. That is a
 *    hardware limit, not a software gap - no amount of processing recovers a bearing that
 *    was never sampled.
 *
 * What DOES get to metre accuracy is [Rtt] - 802.11mc time-of-flight, which times the round
 * trip instead of guessing from loudness. It reports in millimetres and lands within a
 * metre or two. It only works against access points that answer ranging requests, which is
 * a minority of them, so it is a bonus on the devices that support it rather than the
 * general mechanism.
 *
 * The other way to beat a single point is to stop being a single point: move, and the same
 * device gets measured from several places. [estimatePosition] does that.
 */
object Ranging {

    /**
     * Distance in metres from signal strength, by the log-distance path loss model.
     *
     * `rssi = txPower - 10 * n * log10(d)`, solved for d. Everything hard is hidden in the
     * two constants: the transmit power, which is not advertised and has to be assumed, and
     * the path loss exponent n, which is 2.0 in free space and 3-4 through buildings.
     *
     * Returns null below -100 dBm, where the estimate stops meaning anything at all.
     */
    fun distanceMeters(rssi: Int, band: RangeBand): Double? {
        if (rssi >= 0 || rssi < -100) return null
        val exp = (band.txPowerDbm - rssi) / (10.0 * band.pathLossExponent)
        return 10.0.pow(exp).coerceIn(0.2, 5_000.0)
    }

    /**
     * Reference transmit powers and path loss exponents per radio.
     *
     * The txPower figures are the received strength at one metre, not the radiated power:
     * that is the form the model actually needs, and it is measurable, which the other is
     * not. Values are the widely used defaults - a BLE beacon at 1 m reads about -59 dBm,
     * a Wi-Fi AP about -40 dBm because it transmits far harder.
     *
     * The exponents lean pessimistic (above free space) because this app is used in
     * buildings and streets, not anechoic chambers.
     */
    enum class RangeBand(val txPowerDbm: Double, val pathLossExponent: Double) {
        /** BLE advertising, typically 0 dBm radiated. */
        BLE(-59.0, 2.5),
        /** 2.4 GHz Wi-Fi - travels furthest, so the shallowest exponent. */
        WIFI_24(-40.0, 2.7),
        /** 5/6 GHz Wi-Fi - more absorbed by walls, so it falls off faster. */
        WIFI_5(-45.0, 3.0),
        /** Sub-GHz bursts. Long range, little obstruction, close to free space. */
        SUB_GHZ(-50.0, 2.2);

        companion object {
            fun forWifi(frequencyKhz: Int?): RangeBand =
                if (frequencyKhz != null && frequencyKhz > 4_000_000) WIFI_5 else WIFI_24
        }
    }

    /**
     * How to describe a distance without implying a precision that is not there.
     *
     * Deliberately coarse and deliberately in words. "8 m" invites the reader to trust a
     * digit that the underlying physics does not support; "under 10 m" says the same thing
     * without the false decimal.
     */
    fun describe(meters: Double?): String = when {
        meters == null -> "out of useful range"
        meters < 2 -> "within arm's reach"
        meters < 10 -> "under 10 m"
        meters < 30 -> "10-30 m"
        meters < 75 -> "30-75 m"
        meters < 150 -> "75-150 m"
        else -> "over 150 m"
    }

    /** A position estimate with the honest uncertainty attached. */
    data class Fix(
        val lat: Double,
        val lon: Double,
        /**
         * Radius in metres inside which the device probably sits. Never smaller than
         * [MIN_RADIUS_M], because claiming sub-10 m confidence from signal strength alone
         * would be inventing precision.
         */
        val radiusMeters: Double,
        /** How many separate listening positions went into it. More is better. */
        val samples: Int,
        /** Plain-language account of how good this estimate actually is. */
        val quality: String
    )

    /**
     * Estimates where a device is from sightings taken at different places.
     *
     * This is signal-strength-weighted centroid, which is what wardriving databases use and
     * is the right tool given the inputs. The reasoning: the closer you were to the emitter,
     * the stronger it read, so strong sightings should pull the estimate toward where you
     * were standing when you took them. With enough sightings from enough directions the
     * weighted average converges on the emitter.
     *
     * The catch, and it is a big one: this only works if the DEVICE is stationary and YOU
     * moved. For a car following you through an intersection, both are moving and the
     * centroid lands somewhere along your own path, meaning nothing. That case is
     * [FollowDetector]'s, not this function's - the useful signal about a follower is that
     * it keeps reappearing across turns you chose, not where a dot sits on a map.
     *
     * Weights are linear in the estimated distance rather than in raw dBm, because dBm is
     * logarithmic and averaging it directly gives the far-away readings far more pull than
     * they deserve.
     */
    fun estimatePosition(
        observations: List<ObservationEntity>,
        band: RangeBand
    ): Fix? {
        val located = observations.filter { it.lat != null && it.lon != null }
        if (located.isEmpty()) return null

        var wSum = 0.0
        var latSum = 0.0
        var lonSum = 0.0
        for (o in located) {
            // Inverse distance: a sighting believed to be 5 m away counts twenty times a
            // sighting believed to be 100 m away.
            val d = distanceMeters(o.rssi, band) ?: continue
            val w = 1.0 / d.coerceAtLeast(1.0)
            wSum += w
            latSum += o.lat!! * w
            lonSum += o.lon!! * w
        }
        if (wSum <= 0.0) return null

        val lat = latSum / wSum
        val lon = lonSum / wSum

        // Spread of the listening positions themselves, which bounds how much the geometry
        // could possibly have constrained the answer. All sightings from one spot means the
        // device could be anywhere on a ring around it, and the centroid is that spot -
        // confidently, uselessly.
        val spread = spreadMeters(located, lat, lon)
        val strongest = located.maxOf { it.rssi }
        val nearest = distanceMeters(strongest, band) ?: 200.0

        // The estimate cannot be tighter than how far away the closest approach was, nor
        // tighter than the geometry allows. Take the worse of the two, always.
        val radius = maxOf(nearest * 0.6, spread * 0.5, MIN_RADIUS_M)

        val distinct = distinctPositions(located)
        val quality = when {
            distinct < 3 -> "Rough. Heard from $distinct place${if (distinct == 1) "" else "s"} " +
                "- one vantage point cannot separate direction from distance. Walk or drive " +
                "past it again from another angle to tighten this."
            spread < 20 -> "Rough. All sightings came from within about ${spread.toInt()} m " +
                "of each other, which is not enough separation to triangulate from."
            radius < 40 -> "Good for signal strength. Heard from $distinct places over " +
                "${spread.toInt()} m of travel."
            else -> "Approximate. Heard from $distinct places; signal strength alone will " +
                "not do better than this."
        }

        return Fix(lat, lon, radius, distinct, quality)
    }

    /** Rough diameter of the area the sightings were taken from. */
    private fun spreadMeters(obs: List<ObservationEntity>, lat: Double, lon: Double): Double {
        var maxD = 0.0
        for (o in obs) {
            val d = metersBetween(lat, lon, o.lat!!, o.lon!!)
            if (d > maxD) maxD = d
        }
        return maxD * 2
    }

    /**
     * Counts listening positions that are actually distinct.
     *
     * Sitting still generates hundreds of sightings from one place, and counting those as
     * hundreds of samples would report high confidence for the one geometry that gives
     * none. Positions inside [SAME_PLACE_M] of each other collapse to one.
     */
    private fun distinctPositions(obs: List<ObservationEntity>): Int {
        val kept = ArrayList<Pair<Double, Double>>()
        for (o in obs) {
            val p = o.lat!! to o.lon!!
            if (kept.none { metersBetween(it.first, it.second, p.first, p.second) < SAME_PLACE_M }) {
                kept.add(p)
            }
        }
        return kept.size
    }

    /**
     * Distance between two coordinates, by equirectangular approximation.
     *
     * Good to a fraction of a percent at the scales this app deals in - hundreds of metres -
     * and far cheaper than haversine, which matters when it runs across every pair of
     * sightings in a session.
     */
    fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val latRad = Math.toRadians((lat1 + lat2) / 2)
        val dx = Math.toRadians(lon2 - lon1) * cos(latRad)
        val dy = Math.toRadians(lat2 - lat1)
        return sqrt(dx * dx + dy * dy) * EARTH_RADIUS_M
    }

    /**
     * Whether a device is getting closer, staying put, or falling behind.
     *
     * This is the number that actually answers "is that car still behind me". Position on a
     * map is the wrong question for a moving follower; whether the gap is closing is the
     * right one, and unlike position it needs no GPS fix at all - which matters, because
     * the moments you most want this are the moments you are between buildings.
     *
     * Compares the mean of the oldest third to the mean of the newest third, so a single
     * fading packet cannot flip the verdict.
     */
    fun trend(observations: List<ObservationEntity>): Trend {
        if (observations.size < 6) return Trend.UNKNOWN
        val sorted = observations.sortedBy { it.ts }
        val third = sorted.size / 3
        val early = sorted.take(third).map { it.rssi }.average()
        val late = sorted.takeLast(third).map { it.rssi }.average()
        val delta = late - early
        return when {
            // 6 dB is roughly a halving or doubling of distance - large enough that it is
            // not fading, small enough to catch a car closing across a couple of blocks.
            delta > 6 -> Trend.CLOSING
            delta < -6 -> Trend.RECEDING
            abs(delta) < 3 -> Trend.HOLDING
            else -> Trend.UNKNOWN
        }
    }

    enum class Trend(val label: String) {
        CLOSING("getting closer"),
        RECEDING("falling behind"),
        HOLDING("holding distance"),
        UNKNOWN("")
    }

    /** Below this, a signal-strength estimate is claiming precision it does not have. */
    private const val MIN_RADIUS_M = 15.0

    /** Sightings closer together than this were taken from the same vantage point. */
    private const val SAME_PLACE_M = 15.0

    private const val EARTH_RADIUS_M = 6_371_000.0
}

/**
 * Which propagation model applies to this device.
 *
 * Wi-Fi splits on frequency because 5 GHz is absorbed by walls far more than 2.4 GHz is,
 * and treating them alike puts every 5 GHz access point further away than it is.
 */
fun DeviceEntity.rangeBand(): Ranging.RangeBand = when (band) {
    Band.WIFI -> Ranging.RangeBand.forWifi(frequencyKhz)
    Band.BLE, Band.BT_CLASSIC -> Ranging.RangeBand.BLE
    Band.SUBGHZ -> Ranging.RangeBand.SUB_GHZ
    // Cell towers are kilometres away and transmit at power levels nothing here models.
    // Handing back a Wi-Fi model would produce a confident, meaningless number.
    Band.CELL -> Ranging.RangeBand.WIFI_24
}
