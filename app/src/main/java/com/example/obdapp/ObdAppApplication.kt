package com.example.obdapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class ObdAppApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Required ongoing-notification channel for the connectedDevice foreground
        // service. This is a system requirement, not an app "notification feature".
        val channel = NotificationChannel(
            CHANNEL_OBD,
            getString(R.string.channel_connection),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_connection_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_OBD = "obd_connection"
    }
}
