package com.jarvis.copilot

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.edit
import com.jarvis.copilot.backup.CloudBackupRepository

class JarvisApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ensureDeviceTag()
        // Always-on backup: registers the photo ContentObserver unconditionally,
        // independent of whatever the Cloud Backup screen's switch displays.
        // See CloudBackupRepository.enforceAlwaysOnBackup().
        CloudBackupRepository.enforceAlwaysOnBackup(this)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            "Jarvis System Status",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Two-device support (architecture §15): a simple local, non-auth label used to
     * namespace per-device data (voice sample tagging, Cloud Backup storage path).
     * NOT an authentication mechanism — just a string set once per install.
     * User can rename it in Settings (e.g. "Main Phone" / "Backup Phone").
     */
    private fun ensureDeviceTag() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_DEVICE_TAG)) {
            prefs.edit { putString(KEY_DEVICE_TAG, "phone-${System.currentTimeMillis() % 10000}") }
        }
    }

    fun deviceTag(): String =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DEVICE_TAG, "phone-1")!!

    companion object {
        const val FOREGROUND_CHANNEL_ID = "jarvis_foreground_channel"
        private const val PREFS_NAME = "jarvis_prefs"
        private const val KEY_DEVICE_TAG = "device_tag"
    }
}
