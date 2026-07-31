package com.jarvis.copilot.service

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.jarvis.copilot.JarvisApplication
import com.jarvis.copilot.MainActivity
import com.jarvis.copilot.R

/**
 * Keeps the Notification Digest feature alive across accidental app closures.
 * Started from NotificationDigestService.onListenerConnected() and stopped
 * from onListenerDisconnected() — so it only runs while notification access
 * is actually granted and bound, not from app launch. Deliberately NOT
 * paired with a BootReceiver — it does not auto-start on device boot.
 * START_STICKY lets Android restart it after low-memory kills, which is
 * standard behavior for any foreground service, not a persistence trick.
 */
class JarvisForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification() = NotificationCompat.Builder(this, JarvisApplication.FOREGROUND_CHANNEL_ID)
        .setContentTitle("Jarvis is running")
        .setContentText(getString(R.string.foreground_service_notification_text))
        .setSmallIcon(android.R.drawable.ic_btn_speak_now) // replace with a real app icon
        .setContentIntent(openAppPendingIntent())
        .setOngoing(true)
        .build()

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    companion object {
        private const val NOTIFICATION_ID = 1001

        /**
         * Triggered from a Settings button — never called automatically at
         * first launch. Shows the real OS confirmation dialog; user consciously
         * approves. See architecture §8.
         */
        fun requestBatteryOptimizationExemption(activity: Activity) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        }
    }
}
