package com.wave.scanner

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.wave.scanner.data.db.WaveDatabase

class WaveApp : Application() {

    val db: WaveDatabase by lazy { WaveDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerChannels()
    }

    private fun registerChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SCAN, getString(R.string.scan_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, getString(R.string.alert_channel_name), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        const val CH_SCAN = "wave.scan"
        const val CH_ALERT = "wave.alert"
        lateinit var instance: WaveApp
            private set
    }
}
