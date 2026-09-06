package com.wave.scanner.detect

import com.wave.scanner.data.db.Threat
import com.wave.scanner.scan.CellFacts

/**
 * Suspicion scoring for cell-site anomalies.
 *
 * Real IMSI-catcher detection depends on
 * baseband events - authentication and identity requests, cipher-mode downgrades, missing
 * neighbour lists, unusual paging - and Android exposes none of them to an unprivileged
 * app. Getting at them needs root plus a vendor diagnostic interface. Wave is not doing
 * that, so what follows is inference from the small set of facts getAllCellInfo does give.
 *
 * The output is therefore labelled "anomaly", never "IMSI catcher". Some are worth
 * knowing: a 2G cell appearing where the carrier decommissioned 2G years ago is genuinely
 * odd. But a legitimate temporary cell at an event produces the same signature, and the
 * user is told so.
 */
object ImsiCatcherDetector {

    data class Anomaly(
        val type: String,
        val severity: Threat,
        val title: String,
        val detail: String
    )

    /**
     * @param current the serving cell right now
     * @param knownCells cell identities previously observed at this rough location
     * @param recentTechnologies technologies seen on this device over the last few minutes
     */
    fun evaluate(
        current: CellFacts,
        knownCells: Set<Long>,
        recentTechnologies: List<String>
    ): List<Anomaly> {
        val found = mutableListOf<Anomaly>()

        // A downgrade to 2G is the classic prerequisite for a cheap interception device,
        // because GSM permits null encryption and has no network authentication.
        if (current.technology == "GSM" && current.isRegistered) {
            val hadModern = recentTechnologies.any { it == "LTE" || it == "NR" }
            found += Anomaly(
                type = "downgrade_2g",
                severity = if (hadModern) Threat.HIGH else Threat.MEDIUM,
                title = "Connected on 2G",
                detail = if (hadModern)
                    "Your phone dropped from LTE or 5G to GSM while staying in one place."
                else
                    "Your phone is registered on a GSM cell."
            )
        }

        // An unregistered cell broadcasting with an unfamiliar identity in a place you
        // have logged before is the shape a test transmitter makes.
        current.cid?.let { cid ->
            if (knownCells.isNotEmpty() && cid !in knownCells && current.isRegistered) {
                found += Anomaly(
                    type = "unknown_cell",
                    severity = Threat.LOW,
                    title = "Unfamiliar serving cell",
                    detail = "Cell $cid has not been seen at this location before."
                )
            }
        }

        // Timing advance near zero means the handset believes it is essentially on top of
        // the tower. A real macro cell is rarely that close.
        current.timingAdvance?.let { ta ->
            if (ta == 0 && current.technology == "LTE") {
                found += Anomaly(
                    type = "zero_timing_advance",
                    severity = Threat.LOW,
                    title = "Serving cell reports zero timing advance",
                    detail = "The network places you within a few hundred metres of the antenna."
                )
            }
        }

        // An MCC/MNC that does not belong to any operator you have used is worth a look.
        if (current.mcc != null && current.mcc.length == 3 && current.mnc.isNullOrBlank()) {
            found += Anomaly(
                type = "incomplete_identity",
                severity = Threat.LOW,
                title = "Cell broadcasting an incomplete network identity",
                detail = "MCC ${current.mcc} present but no MNC reported."
            )
        }

        return found
    }

    /**
     * What the user would actually need to detect a catcher properly. Surfaced verbatim in
     * the Hardware screen so the limitation is visible rather than buried in a comment.
     */
    const val LIMITATION_NOTE: String =
        "Wave scores cell anomalies from public Android APIs only. Genuine IMSI-catcher " +
            "detection needs baseband access - authentication requests, cipher-mode " +
            "downgrades and neighbour-list gaps - which requires root and a Qualcomm diag " +
            "interface. Treat everything here as a prompt to look closer, not as evidence."
}
