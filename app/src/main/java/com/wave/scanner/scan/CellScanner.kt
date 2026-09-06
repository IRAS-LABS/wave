package com.wave.scanner.scan

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import com.wave.scanner.data.db.Band

/**
 * Cell-tower observation via getAllCellInfo.
 *
 * What this genuinely provides: a log of which cells served you where, which is enough to
 * notice a cell that appears in an implausible place or that you have never seen at a
 * location you visit daily.
 *
 * What it cannot provide, and Wave will not claim: real IMSI-catcher detection. The
 * signals that actually identify a catcher - downgrade requests, missing neighbour lists,
 * identity requests, cipher-mode changes - live in the baseband, and Android's public API
 * exposes none of them. Reaching them needs root plus a Qualcomm diag interface. The
 * heuristics in ImsiCatcherDetector are suspicion scoring, not proof.
 */
class CellScanner(private val context: Context) : Scanner {

    override val band = Band.CELL

    private val telephony = context.applicationContext
        .getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var callback: ((RadioObservation) -> Unit)? = null

    /** getAllCellInfo is a synchronous poll; 8s balances freshness against battery. */
    private val intervalMs = 8_000L

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            harvest()
            handler.postDelayed(this, intervalMs)
        }
    }

    override fun isAvailable(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED &&
            telephony.phoneType != TelephonyManager.PHONE_TYPE_NONE

    override fun start(onObservation: (RadioObservation) -> Unit) {
        if (running) return
        callback = onObservation
        running = true
        handler.post(poll)
    }

    override fun stop() {
        running = false
        handler.removeCallbacks(poll)
        callback = null
    }

    // isAvailable() is the permission check; lint does not follow it.
    @SuppressLint("MissingPermission")
    private fun harvest() {
        val cb = callback ?: return
        if (!isAvailable()) return
        val cells = runCatching { telephony.allCellInfo }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        cells.forEach { info -> toObservation(info, now)?.let(cb) }
    }

    private fun toObservation(info: CellInfo, now: Long): RadioObservation? {
        val facts: CellFacts
        val rssi: Int

        when (info) {
            is CellInfoLte -> {
                val id = info.cellIdentity
                val ss = info.cellSignalStrength
                rssi = ss.dbm
                facts = CellFacts(
                    technology = "LTE",
                    mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mccString else null,
                    mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mncString else null,
                    tac = id.tac.takeIf { it != Int.MAX_VALUE },
                    cid = id.ci.toLong().takeIf { id.ci != Int.MAX_VALUE },
                    pci = id.pci.takeIf { it != Int.MAX_VALUE },
                    earfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                        id.earfcn.takeIf { it != Int.MAX_VALUE } else null,
                    isRegistered = info.isRegistered,
                    timingAdvance = ss.timingAdvance.takeIf { it != Int.MAX_VALUE }
                )
            }
            is CellInfoGsm -> {
                val id = info.cellIdentity
                rssi = info.cellSignalStrength.dbm
                facts = CellFacts(
                    technology = "GSM",
                    mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mccString else null,
                    mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mncString else null,
                    tac = id.lac.takeIf { it != Int.MAX_VALUE },
                    cid = id.cid.toLong().takeIf { id.cid != Int.MAX_VALUE },
                    pci = null,
                    earfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                        id.arfcn.takeIf { it != Int.MAX_VALUE } else null,
                    isRegistered = info.isRegistered
                )
            }
            is CellInfoWcdma -> {
                val id = info.cellIdentity
                rssi = info.cellSignalStrength.dbm
                facts = CellFacts(
                    technology = "WCDMA",
                    mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mccString else null,
                    mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) id.mncString else null,
                    tac = id.lac.takeIf { it != Int.MAX_VALUE },
                    cid = id.cid.toLong().takeIf { id.cid != Int.MAX_VALUE },
                    pci = id.psc.takeIf { it != Int.MAX_VALUE },
                    earfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                        id.uarfcn.takeIf { it != Int.MAX_VALUE } else null,
                    isRegistered = info.isRegistered
                )
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr) {
                    val id = info.cellIdentity as? CellIdentityNr ?: return null
                    rssi = (info.cellSignalStrength as? CellSignalStrengthNr)?.dbm ?: -140
                    facts = CellFacts(
                        technology = "NR",
                        mcc = id.mccString,
                        mnc = id.mncString,
                        tac = id.tac.takeIf { it != Int.MAX_VALUE },
                        cid = id.nci.takeIf { it != Long.MAX_VALUE },
                        pci = id.pci.takeIf { it != Int.MAX_VALUE },
                        earfcn = id.nrarfcn.takeIf { it != Int.MAX_VALUE },
                        isRegistered = info.isRegistered
                    )
                } else return null
            }
        }

        return RadioObservation(
            band = Band.CELL,
            address = null,
            name = "${facts.technology} ${facts.mcc.orEmpty()}-${facts.mnc.orEmpty()} " +
                "cid ${facts.cid ?: "?"}",
            rssi = rssi,
            timestamp = now,
            cell = facts
        )
    }
}
