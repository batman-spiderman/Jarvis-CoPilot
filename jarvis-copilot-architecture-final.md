# Jarvis System Co-Pilot — Final Architecture

Personal-use Android voice assistant, designed for one user across up to two of his
own devices. Every subsystem was deliberately redesigned from a riskier initial
draft to remove silent background surveillance, unbounded permissions, and
credential exposure — while keeping full assistant functionality.

---

## 1. Design Principles

1. **Trigger, not always-on** — mic, screen-read, and accessibility actions fire
   only on explicit user action, never as a standing background listener.
2. **Visible, not silent** — anything stored (notifications, voice samples, photo
   backups) has an in-app screen to view, review, and delete it.
3. **Narrowest API over broadest permission** — scoped system APIs
   (`MediaStore.createDeleteRequest`, Intents, one-shot Accessibility) instead of
   broad grants (`MANAGE_EXTERNAL_STORAGE`, continuous UI automation).
4. **Lazy permissions** — requested at first use of the specific feature, except
   Notification Listener and Accessibility, offered (skippably) at first launch
   since core features need them immediately.
5. **No credentials on-device** — all third-party API keys live behind a server
   relay; the client never holds a provider key.
6. **Device-scoped where device-scoped makes sense** — data tied to what a device
   captured (photos, notifications) stays per-device; data tied to the person
   (voice) can be pooled without needing a shared login.

---

## 2. Module Overview

| Module | Purpose | Sensitive capability | Gating |
|---|---|---|---|
| `MainActivity` / `JarvisHudScreen` | Compose UI, HUD, cards | — | — |
| `JarvisViewModel` | Intent dispatch, LLM routing, state | — | — |
| `JarvisForegroundService` | Keeps notification-reading alive | Foreground service | User-enabled battery exemption |
| `NotificationDigestService` | Reads notifications aloud + logs history | Notification content | Exclude-list, visible history |
| `ScreenSummarizerAccessibilityService` | Reads current screen on tap; toggles system settings | Accessibility | One-shot flag, off by default |
| Media Vault | Duplicate scan/delete, date reorg | `READ_MEDIA_IMAGES` | Lazy request on first open |
| Power Profiles | Location-aware battery mode | `ACCESS_FINE_LOCATION` | Icon-triggered, foreground-only |
| Voice Training | Collects labeled voice samples for fine-tuning | Raw audio + transcript | Uploads via relay to B2, deletable, pooled across devices |
| Cloud Backup | Compressed photo backup | Backblaze B2 (via relay) | Toggle + explicit backfill button, per-device |
| Local Intent Engine | Voice → device actions | — | Intents only, no automation |
| Cloud Relay Server | Holds all API keys, routes LLM + storage calls | — | Auth via app shared secret |

---

## 3. UI Layer

**Theme:** Dark HUD — background `#0D1117`, accent `#00E5FF`.

- **Top bar:** battery %, free memory, CPU load, status LED (idle/listening/processing)
- **Center:** glowing state ring reflecting Jarvis mode
- **Card grid:** System Diagnostics · Notification Reader · Power Profiles · Media Vault
- **Bottom:** text input + push-to-talk button (mic active only while held/toggled)
- **Settings screens (visible, user-editable):** Notification History, Voice
  Training, Cloud Backup, Permissions overview

---

## 4. Local Intent Engine (0ms, on-device)

Regex/keyword match on voice or typed text. Handles device actions without a
network call; falls through to the cloud LLM router for conversational queries.

```kotlin
fun dispatchLocalIntent(command: String, context: Context): Boolean {
    val c = command.lowercase().trim()
    return when {
        c.contains("check battery") -> { showBatteryStatus(context); true }
        c.contains("clean ram") -> { trimMemory(context); true }
        c.contains("turn on flashlight") -> { toggleFlashlight(context, true); true }
        c.contains("open camera") -> { openCameraForCapture(context); true }
        c.contains("open youtube") -> { openApp(context, "youtube"); true }
        c.startsWith("search youtube for") -> { searchYouTube(context, c.removePrefix("search youtube for").trim()); true }
        c.startsWith("message") -> { prefillMessage(context, c); true }
        c.startsWith("set alarm for") -> { setAlarm(context, c); true }
        c.startsWith("set timer for") -> { setTimer(context, c); true }
        c.startsWith("call") -> { dialNumber(context, c); true }
        c.startsWith("navigate to") -> { openNavigation(context, c); true }
        c.contains("toggle battery saver") -> { accessibilityService?.toggleBatterySaverViaSettings(); true }
        c.contains("summarize this screen") -> { accessibilityService?.requestScreenCapture(); true }
        else -> false // → cloud LLM router
    }
}
```

All action items route through standard `Intent`s (`ACTION_DIAL`, `ACTION_SENDTO`,
`AlarmClock.ACTION_SET_ALARM`, `ACTION_VIEW`, `geo:`, `ACTION_IMAGE_CAPTURE`) — the
target app runs its own UI. Jarvis hands off; it never reads or drives another
app's screen.

---

## 5. Cloud LLM Router

```
Android App --HTTPS(Bearer app-secret)--> Relay Server --> NVIDIA / Gemini / Groq
```

```python
PROVIDERS = [nvidia, gemini, groq]  # keys from server env vars, never in client
@app.post("/chat")
async def chat(payload: dict, authorization: str):
    verify(authorization)
    for provider in round_robin(PROVIDERS):
        resp = await call(provider, payload["query"])
        if resp.status_code == 429: continue
        return resp.json()
```

Deployed to any always-on host (Render/Fly.io hobby tier). Keys never leave the
server; client only ever calls `/chat`.

---

## 6. Notification Reader

```kotlin
class NotificationDigestService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName in userExcludedApps) return  // e.g. banking/OTP apps
        val title = sbn.notification.extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text  = sbn.notification.extras.getString(Notification.EXTRA_TEXT) ?: ""
        ttsEngine.speak("$title: $text")
        notificationHistoryRepository.save(NotificationEntry(sbn.packageName, title, text, now()))
    }
}
```

Reads everything by default (accessibility use case), user-editable exclude-list,
visible/clearable Room-backed history. **Local per device — not synced**, since each
phone receives different notifications.

---

## 7. Screen Summarizer + Settings Automation

```kotlin
class ScreenSummarizerAccessibilityService : AccessibilityService() {
    private var captureRequested = false
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!captureRequested) return
        captureRequested = false
        sendToSummarizer(extractTextFromNode(rootInActiveWindow ?: return))
    }
    fun requestScreenCapture() { captureRequested = true }
    fun toggleBatterySaverViaSettings() { /* one simulated tap, on demand only */ }
}
```

Inert unless just triggered by a button press; one-shot; no package/window-state
tracking; nothing buffered.

---

## 8. Foreground Persistence

```kotlin
class JarvisForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Reading notifications aloud. Tap to open."))
        return START_STICKY
    }
    fun requestBatteryOptimizationExemption(activity: Activity) {
        activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${activity.packageName}")))
    }
}
```

No boot receiver, no silent auto-start. Battery exemption requested via a Settings
button, real system dialog, user-approved. Notification text states plainly what's
running.

---

## 9. Media Vault

```kotlin
// Scan (READ_MEDIA_IMAGES, lazy on first open)
val duplicates = hashAndGroupPhotos(queryAllPhotos(context))

// Batch delete — one system dialog, no write permission
val delReq = MediaStore.createDeleteRequest(resolver, urisToDelete)
activity.startIntentSenderForResult(delReq.intentSender, DELETE_REQUEST_CODE, null, 0, 0, 0)

// Date reorganization — one write confirmation
val writeReq = MediaStore.createWriteRequest(resolver, urisToModify)
// after approval:
resolver.update(photoUri, ContentValues().apply {
    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Organized/$monthKey")
}, null, null)
```

Permission footprint: one runtime permission total. Delete/reorganize ride on
per-action system dialogs.

---

## 10. Power Profiles

`ACCESS_FINE_LOCATION` only, requested when the location icon is tapped.
Foreground-evaluated (on resume / periodic check while app is active) —
**no** `ACCESS_BACKGROUND_LOCATION`, no continuous tracking.

---

## 11. Voice Training — pooled across devices, no shared login required

Same person's voice regardless of which phone captured it, so both devices
contribute to one training pool. This does **not** require a shared account —
just a local, non-auth device label used for tagging:

```kotlin
val deviceTag = "phone-1" // set once per install, user-nameable ("Main Phone")
uploadVoiceSample(audioBytes, transcript, deviceTag, timestamp)
```

```
Phone 1 ──┐
          ├─→ Cloud bucket (all samples, tagged by device) ─→ Colab fine-tune
Phone 2 ──┘                                                         │
                                                          mark trained, delete originals
```

- Upload happens immediately on capture; no local audio retention.
- Deletable anytime from the Voice Training screen, on either device.
- Colab pulls the full untrained pool regardless of `deviceTag` — more data from
  the same voice only helps the fine-tune.

---

## 12. Cloud Backup — separate per device

Photos are physically different files on each phone, so backups stay fully
separated by device, namespaced by the same local `deviceTag` (not an auth
UID of any kind, avoiding any need for cross-device sign-in). The phone never
touches B2 directly — it uploads the compressed bytes to the relay server,
which is the only thing holding a B2 application key:

```kotlin
// Android side — CloudBackupRepository.uploadPhoto()
RelayApiClient.api.uploadPhoto(photoPart, deviceTag, fileName)
```
```python
# relay-server/main.py — POST /photos
b2_client.put_object(Bucket=B2_BUCKET_NAME, Key=f"photos/{deviceTag}/{filename}", Body=photo_bytes)
```

- `photos/phone-1/…` and `photos/phone-2/…` never overlap or merge.
- Each device's Cloud Backup screen only shows and manages its own folder
  (via `GET /photos?deviceTag=…` on the relay, which lists straight from B2).
- Backblaze B2 free tier: no billing details required to sign up, 10GB free
  total across the bucket.

**Two explicit controls, per device:**
1. **Auto-backup toggle** — new photos only, via `ContentObserver` (event-driven)
   + `WorkManager` (Wi-Fi-only, battery-not-low constraints). Off by default.
2. **Backup existing photos** — one-time backfill with visible progress, Pause,
   Resume (continues from saved index), Stop (cancels and resets).

```kotlin
fun pauseBackfill()  { WorkManager.getInstance(context).cancelUniqueWork("backfill_all_photos") }
fun resumeBackfill() { enqueueBackfillWork(allPhotos.drop(savedBackfillIndex)) }
fun stopBackfill()   { WorkManager.getInstance(context).cancelUniqueWork("backfill_all_photos"); savedBackfillIndex = 0 }
```

**Capacity note:** this is a secondary, space-conscious copy — not a full-resolution
permanent archive. Originals stay on-device. If a device's free-tier quota is
approached: reduce compression target, apply rolling retention (prune oldest cloud
copies since the on-device original is unaffected), or move that device's backups
to a paid tier once the feature has proven worth it.

---

## 13. What Was Deliberately Left Out, and Why

| Rejected capability | Reason |
|---|---|
| Obfuscated naming to evade security scanners | Only useful to hide behavior from a scanner — no legitimate purpose for a personal app; also the one item Gemini likely generated as boilerplate rather than intentionally |
| Silent notification buffering (`systemInputBuffer`) | Replaced with visible, deletable history — retained data should be inspectable by the person it belongs to |
| Boot-persistent, unkillable background service | Replaced with `START_STICKY` + user-triggered battery exemption — persistence should be requested transparently, not forced |
| Bundled upfront permission grab (5 at once) | Replaced with lazy, per-feature requests, consistent with Android's own permission-best-practices guidance |
| `ACCESS_BACKGROUND_LOCATION` | Foreground-only evaluation covers the actual feature; background location is Google Play's most heavily scrutinized permission for good reason — continuous location logging with no on-screen indication is the core mechanism behind covert location tracking |
| `MANAGE_EXTERNAL_STORAGE` | `MediaStore` scoped APIs (`createDeleteRequest`, `createWriteRequest`) cover the same functionality without filesystem-wide access |
| Continuous Accessibility screen/package logging | Replaced with one-shot, button-triggered capture — an Accessibility Service that logs everything on screen at all times is functionally identical to the core mechanism of commercial stalkerware, independent of stated intent |
| Arbitrary third-party app UI automation ("book a cab," "auto-reply for me") | No scoped version exists for apps that don't expose a public Intent — the only way to do it is always-on UI reading/synthetic input, which is the same technique used by remote-access trojans. Replaced with Intent hand-offs that only work for apps voluntarily supporting them |

**Why this matters practically, not just ethically:** several of the removed
patterns (bundled permissions, background location, unbounded accessibility,
`MANAGE_EXTERNAL_STORAGE`) are also the specific things Google Play's automated
review and Play Protect scanning flag most aggressively — so this redesign is more
robust even if the app is only ever sideloaded and never published, since Android's
own OS-level warnings (e.g. "this app has excessive permissions," Accessibility
misuse warnings) are triggered by the same signals regardless of distribution
channel.

---

## 14. Data & Legal Notes

- **All monitored/collected data (notifications, voice, photos, screen text)
  belongs to the same person operating the device** — this is what keeps every
  capability in this design in the "personal productivity tool" category rather
  than the "monitoring another person" category. If this app is ever installed on
  a device used by anyone other than its owner, every capability above would need
  re-justifying under a completely different consent model (the person being
  monitored would need to knowingly and separately consent — a permission grant by
  a device's *owner* is not consent from whoever else uses that device).
- **Notification content, screen text, and voice recordings can include
  third-party personal data** (a friend's message appearing in a notification, a
  screen showing someone else's info) even when only one person operates the
  device — this is inherent to any notification-reading or screen-reading feature
  and isn't fully avoidable, which is part of why exclude-lists, one-shot
  triggers, and visible/deletable history matter: they minimize how much of that
  incidental third-party data is retained or exposed beyond the moment it's read.
- **No feature in this design is built for, or repurposable as, covert monitoring
  of another person** — no silent install indicators are suppressed, no data is
  sent anywhere the device owner can't see, and every sensitive capability has a
  visible, on-screen trace (persistent notification, in-app history, settings
  toggle) rather than operating invisibly.
- **This document assumes non-commercial, non-distributed personal use.** Publishing
  this app (Play Store or otherwise) would require additional work not covered
  here: a privacy policy, Google's Accessibility/Notification-access declaration
  review (both require a video demo and written justification per feature),
  data-safety disclosures for the cloud storage/relay components, and likely
  removal or further restriction of the Accessibility settings-toggle feature,
  which Google reviews especially strictly.

---

## 15. Two-Device Summary

| Data type | Scope | Mechanism |
|---|---|---|
| Notifications, history | Per-device | Local Room DB, no sync |
| Media Vault dedup state | Per-device | Local, photos differ per phone anyway |
| App settings (toggles, exclude-list) | Per-device | Local, not synced |
| Voice samples | Pooled across devices, stored in B2 via relay | `deviceTag` label only, no auth merge needed |
| Photo backups | Per-device, separate folders, stored in B2 via relay | `photos/{deviceTag}/…` namespacing |
| Relay server access | Shared | Same `APP_SHARED_SECRET` works from both phones already |

No shared login, no cloud-provider account merge — a simple local `deviceTag`
constant (user-nameable, e.g. "Main Phone" / "Backup Phone") is enough to get the
right sharing behavior in each case.

---

## 16. File / Project Structure

```
app/
 ├─ build.gradle.kts
 ├─ src/main/
 │   ├─ AndroidManifest.xml
 │   ├─ java/.../
 │   │   ├─ MainActivity.kt
 │   │   ├─ JarvisViewModel.kt
 │   │   ├─ ui/JarvisHudScreen.kt
 │   │   ├─ ui/CloudBackupScreen.kt
 │   │   ├─ ui/NotificationHistoryScreen.kt
 │   │   ├─ ui/VoiceTrainingScreen.kt
 │   │   ├─ service/JarvisForegroundService.kt
 │   │   ├─ service/NotificationDigestService.kt
 │   │   ├─ service/ScreenSummarizerAccessibilityService.kt
 │   │   ├─ intent/LocalIntentEngine.kt
 │   │   ├─ media/MediaVaultRepository.kt
 │   │   ├─ backup/CloudBackupRepository.kt
 │   │   ├─ voice/VoiceTrainingRepository.kt
 │   │   └─ network/RelayApiClient.kt
relay-server/
 ├─ main.py            # FastAPI: /chat (LLM router), /voice-samples, /backup
 ├─ requirements.txt
 └─ .env.example       # NVIDIA_KEY, GEMINI_KEY, GROQ_KEY, APP_SHARED_SECRET
```

---

## 17. Final Permission Manifest

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES"/>
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>
<!-- Accessibility + Notification Listener declared as service entries,
     granted via their own system settings screens, not uses-permission -->
```

No `WRITE_EXTERNAL_STORAGE`, no `MANAGE_EXTERNAL_STORAGE`, no
`ACCESS_BACKGROUND_LOCATION`, no `RECEIVE_BOOT_COMPLETED`.

---

Ready to scaffold the actual Kotlin/Gradle/Manifest files from this document.
