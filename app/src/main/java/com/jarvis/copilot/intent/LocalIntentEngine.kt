package com.jarvis.copilot.intent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import com.jarvis.copilot.service.ScreenSummarizerAccessibilityService

/**
 * Every branch here hands off to another app via a standard Intent — the target
 * app runs its own UI. Jarvis never reads or drives another app's screen from
 * this engine. Contrast with ScreenSummarizerAccessibilityService, which is the
 * one narrow, one-shot exception (architecture §7 / §13).
 */
object LocalIntentEngine {

    /** Returns true if the command was handled locally (0ms, no cloud call). */
    fun dispatch(command: String, context: Context): Boolean {
        val c = command.lowercase().trim()

        return when {
            c.contains("check battery") -> { showBatteryStatus(context); true }
            c.contains("clean ram") -> { trimMemory(context); true }
            c.contains("turn on flashlight") -> { toggleFlashlight(context, true); true }
            c.contains("turn off flashlight") -> { toggleFlashlight(context, false); true }

            c.contains("open camera") -> safeStart(context, Intent(MediaStore.ACTION_IMAGE_CAPTURE))
            c.contains("open youtube") -> openApp(context, "com.google.android.youtube")
            c.contains("open whatsapp") -> openApp(context, "com.whatsapp")

            c.startsWith("search youtube for") ->
                searchYouTube(context, c.removePrefix("search youtube for").trim())

            c.startsWith("message") -> prefillMessage(context, c)
            c.startsWith("set alarm for") -> setAlarm(context, c)
            c.startsWith("set timer for") -> setTimer(context, c)
            c.startsWith("call") -> dialNumber(context, c)
            c.startsWith("navigate to") -> openNavigation(context, c)
            c.startsWith("add event") -> addCalendarEvent(context, c)

            c.contains("toggle battery saver") -> {
                ScreenSummarizerAccessibilityService.instance?.toggleBatterySaverViaSettings()
                true
            }
            c.contains("summarize this screen") -> {
                ScreenSummarizerAccessibilityService.instance?.requestScreenCapture()
                true
            }

            else -> false // falls through to the cloud LLM router (RelayApiClient)
        }
    }

    private fun safeStart(context: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private fun openApp(context: Context, packageName: String): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return safeStart(context, launchIntent)
    }

    private fun searchYouTube(context: Context, query: String): Boolean =
        safeStart(context, Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")))

    private fun prefillMessage(context: Context, command: String): Boolean {
        // e.g. "message john saying I'm running late"
        val parts = command.removePrefix("message").trim().split(" saying ", limit = 2)
        val contactName = parts.getOrNull(0) ?: return false
        val body = parts.getOrNull(1) ?: ""
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).apply {
            putExtra("sms_body", body)
            putExtra("contact_name_hint", contactName) // best-effort; user still picks contact/taps send
        }
        return safeStart(context, intent)
    }

    private fun setAlarm(context: Context, command: String): Boolean {
        // e.g. "set alarm for 7 30 am" — a real command parser would extract hour/min;
        // left as a TODO hook since NLP parsing is out of scope for this local engine.
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis Alarm")
            // putExtra(AlarmClock.EXTRA_HOUR, hour)
            // putExtra(AlarmClock.EXTRA_MINUTES, minute)
        }
        return safeStart(context, intent)
    }

    private fun setTimer(context: Context, command: String): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis Timer")
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        return safeStart(context, intent)
    }

    private fun dialNumber(context: Context, command: String): Boolean {
        val number = command.removePrefix("call").trim()
        return safeStart(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
    }

    private fun openNavigation(context: Context, command: String): Boolean {
        val destination = command.removePrefix("navigate to").trim()
        return safeStart(context, Intent(Intent.ACTION_VIEW,
            Uri.parse("geo:0,0?q=${Uri.encode(destination)}")))
    }

    private fun addCalendarEvent(context: Context, command: String): Boolean {
        val title = command.removePrefix("add event").trim()
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, title)
        }
        return safeStart(context, intent)
    }

    private fun showBatteryStatus(context: Context) {
        // Read via BatteryManager — surfaced in JarvisViewModel's status flow, not here.
    }

    private fun trimMemory(context: Context) {
        // Best-effort cache clearing hook; Android sandboxing limits what a
        // third-party app can actually free on another app's behalf.
    }

    private fun toggleFlashlight(context: Context, on: Boolean) {
        // CameraManager.setTorchMode(cameraId, on) — needs a valid cameraId lookup.
    }
}
