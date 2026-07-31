package com.jarvis.copilot.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import com.jarvis.copilot.data.NotificationEntry
import com.jarvis.copilot.data.NotificationHistoryDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Reads notifications aloud by default — this is the accessibility use case
 * (screen-reading for a blind/low-vision user), which is why it does NOT default
 * to a whitelist. Individual apps can be excluded (e.g. banking/OTP apps) via
 * userExcludedApps, editable from the Notification History screen.
 *
 * Nothing is silently buffered: every entry is written to a visible, clearable
 * Room table the user can open in-app. Per-device only — not synced across
 * the user's two phones, since each phone receives different notifications.
 */
class NotificationDigestService : NotificationListenerService() {

    private lateinit var tts: TextToSpeech
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts.language = Locale.getDefault()
        }
    }

    /**
     * Android calls this once notification access is actually granted and
     * bound — the right moment to start the keep-alive foreground service,
     * rather than at app launch (when access may not be granted yet) or
     * never (the previous gap: the service existed but nothing started it).
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        ContextCompat.startForegroundService(this, Intent(this, JarvisForegroundService::class.java))
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        stopService(Intent(this, JarvisForegroundService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val excludedApps = getExcludedApps(this)
        if (sbn.packageName in excludedApps) return

        val title = sbn.notification.extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = sbn.notification.extras.getString(Notification.EXTRA_TEXT) ?: ""
        if (title.isBlank() && text.isBlank()) return

        tts.speak("$title: $text", TextToSpeech.QUEUE_ADD, null, sbn.key)

        scope.launch {
            NotificationHistoryDb.getInstance(applicationContext).notificationDao().insert(
                NotificationEntry(
                    packageName = sbn.packageName,
                    title = title,
                    text = text,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val PREFS_NAME = "notification_prefs"
        private const val KEY_EXCLUDED_APPS = "excluded_apps"

        fun getExcludedApps(context: Context): Set<String> =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(KEY_EXCLUDED_APPS, emptySet()) ?: emptySet()

        fun setExcludedApps(context: Context, packages: Set<String>) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_EXCLUDED_APPS, packages).apply()
        }
    }
}
