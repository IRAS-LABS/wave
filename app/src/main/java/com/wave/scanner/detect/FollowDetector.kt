package com.wave.scanner.detect

import com.wave.scanner.data.db.DeviceClass
import com.wave.scanner.data.db.ObservationEntity
import com.wave.scanner.data.db.Threat
import com.wave.scanner.scan.GnssTracker
import kotlin.math.max

/**
 * Decides whether a device is travelling with the user rather than being passed on the road.
 *
 * The discriminator is not signal strength and not sighting count - it is geographic
 * spread over time. A shop's access point is seen many times in one place. A tracker in
 * your bumper is seen in many places, and the places are far apart.
 *
 * Every threshold below is deliberately conservative. A follow alert that fires on the
 * commute you share with a neighbour is worse than useless: it trains the user to dismiss
 * the notification that actually matters.
 */
object FollowDetector {

    /** Two fixes count as separate places only beyond this radius. */
    private const val PLACE_SEPARATION_M = 400.0

    /** Below this span the sightings are one encounter, not a pattern. */
    private const val MIN_SPAN_MINUTES = 8L

    private const val MIN_DISTINCT_PLACES = 3

    /** Beyond this the coincidence explanation gets very thin. */
    private const val STRONG_SPREAD_M = 3_000.0

    data class Verdict(
        val isFollowing: Boolean,
        val score: Int,
        val distinctPlaces: Int,
        val spreadMeters: Double,
        val spanMinutes: Long,
        val threat: Threat,
        val explanation: String
    )

    fun evaluate(
        observations: List<ObservationEntity>,
        deviceClass: DeviceClass
    ): Verdict {
        val located = observations.filter { it.lat != null && it.lon != null }
        if (located.size < MIN_DISTINCT_PLACES) {
            return Verdict(
                false, 0, located.size, 0.0, 0, Threat.NONE,
                "Only ${located.size} located sighting(s) - not enough to judge"
            )
        }

        val places = clusterPlaces(located)
        val spread = maxPairwiseDistance(places)
        val spanMinutes = (located.maxOf { it.ts } - located.minOf { it.ts }) / 60_000

        var score = 0
        val reasons = mutableListOf<String>()

        if (places.size >= MIN_DISTINCT_PLACES) {
            score += 30 + (places.size - MIN_DISTINCT_PLACES) * 8
            reasons += "seen at ${places.size} separate locations"
        }
        if (spread >= PLACE_SEPARATION_M * 2) {
            score += 20
            reasons += "spread over ${formatDistance(spread)}"
        }
        if (spread >= STRONG_SPREAD_M) {
            score += 20
            reasons += "which is far beyond any fixed installation's range"
        }
        if (spanMinutes >= MIN_SPAN_MINUTES) {
            score += 15
            reasons += "across $spanMinutes minutes"
        }

        // Class weighting: a tag has no innocent reason to travel with a stranger, while
        // a phone or an AP very often does.
        score += when (deviceClass) {
            DeviceClass.TRACKER -> 30
            DeviceClass.ALPR_CAMERA, DeviceClass.BODY_CAMERA -> 25
            DeviceClass.POLICE_VEHICLE -> 20
            DeviceClass.ACCESS_POINT -> -10   // mobile hotspots are common and usually benign
            DeviceClass.PHONE -> -5
            else -> 0
        }
        score = score.coerceIn(0, 100)

        val following = places.size >= MIN_DISTINCT_PLACES &&
            spread >= PLACE_SEPARATION_M * 2 &&
            spanMinutes >= MIN_SPAN_MINUTES &&
            score >= 60

        val threat = when {
            !following -> Threat.LOW
            score >= 85 -> Threat.CRITICAL
            score >= 70 -> Threat.HIGH
            else -> Threat.MEDIUM
        }

        val explanation = if (following) {
            "Travelling with you: " + reasons.joinToString(", ") + "."
        } else {
            "Not following: " + when {
                places.size < MIN_DISTINCT_PLACES -> "only ${places.size} distinct location(s)"
                spread < PLACE_SEPARATION_M * 2 -> "all sightings within ${formatDistance(spread)}"
                spanMinutes < MIN_SPAN_MINUTES -> "only $spanMinutes minutes of history"
                else -> "pattern is consistent with a fixed installation"
            } + "."
        }

        return Verdict(following, score, places.size, spread, spanMinutes, threat, explanation)
    }

    /**
     * Greedy clustering: walk the fixes in time order and open a new place whenever the
     * fix is beyond the separation radius from every place already open. Cheap, stable,
     * and good enough given GPS noise dwarfs the difference from a proper clusterer.
     */
    private fun clusterPlaces(obs: List<ObservationEntity>): List<Pair<Double, Double>> {
        val places = mutableListOf<Pair<Double, Double>>()
        obs.sortedBy { it.ts }.forEach { o ->
            val lat = o.lat!!
            val lon = o.lon!!
            val near = places.any { (plat, plon) ->
                GnssTracker.distanceMeters(lat, lon, plat, plon) < PLACE_SEPARATION_M
            }
            if (!near) places.add(lat to lon)
        }
        return places
    }

    private fun maxPairwiseDistance(places: List<Pair<Double, Double>>): Double {
        var maxD = 0.0
        for (i in places.indices) {
            for (j in i + 1 until places.size) {
                maxD = max(
                    maxD,
                    GnssTracker.distanceMeters(
                        places[i].first, places[i].second,
                        places[j].first, places[j].second
                    )
                )
            }
        }
        return maxD
    }

    private fun formatDistance(m: Double): String =
        if (m >= 1000) "%.1f km".format(m / 1000) else "%.0f m".format(m)
}
