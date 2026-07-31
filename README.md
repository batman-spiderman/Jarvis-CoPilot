# Jarvis System Co-Pilot

A personal Android voice assistant with local device-action commands, a
multi-provider LLM fallback (via a server-side relay), notification read-aloud,
gated screen summarization, media vault (duplicate cleanup + date reorg), and
secondary cloud photo backup. Built for one user across up to two of their own
devices. All cloud-stored content — backed-up photos, voice-training audio,
and voice-training transcripts — lives in a single Backblaze B2 bucket, written
only by the relay server; the phone never holds a B2 key.

This project is the fully corrected version of an original spec that had
several silent-surveillance / broad-permission patterns — see
`jarvis-copilot-architecture-final.md` (provided separately) for the full
rationale behind every design decision, including what was deliberately left
out and why.

---

## 1. What's in this zip

```
JarvisCoPilot/
 ├─ app/                     Android app module (Kotlin + Jetpack Compose)
 ├─ relay-server/            Python FastAPI server — holds all API keys
 ├─ build.gradle.kts         Project-level Gradle config
 ├─ settings.gradle.kts
 └─ .gitignore
```

## 2. What was verified in this sandbox (and what wasn't)

This environment does **not** have the Android SDK, an emulator, or Gradle's
Android build tools — so the Kotlin/Compose/Android side could not be
compiled or run here. What I did verify directly:

- **Relay server**: syntax-checked, imported, started for real with `uvicorn`,
  and exercised end-to-end — health check, unauthenticated request correctly
  rejected (401), authenticated chat request correctly attempted all three
  providers and failed over between them, and a full voice-sample
  upload → count → delete lifecycle all worked against the running server.
- **Project structure**: every Kotlin file's package declaration was checked
  for consistency with its folder path and cross-file references.

What still needs verification on your machine, in Android Studio:
- Actual Gradle sync and Kotlin compilation of the app module
- Compose UI rendering (previews, layout on a real/emulated device)
- Runtime permission flows or Room/WorkManager behavior, all of which need
  a real Android runtime

I'd rather tell you this plainly than claim "fully tested" for a part I
couldn't actually run.

## 3. Filling in your credentials

Three things need real values before this builds and runs:

### a) Relay server secrets (`relay-server/.env`)
```bash
cd relay-server
cp .env.example .env
# edit .env and fill in:
#   APP_SHARED_SECRET    — any long random string you generate
#   NVIDIA_KEY            — from https://build.nvidia.com
#   GEMINI_KEY             — from https://aistudio.google.com/apikey
#   GROQ_KEY               — from https://console.groq.com/keys
#   B2_KEY_ID               — Backblaze B2 console → Application Keys →
#   B2_APPLICATION_KEY       Add a New Application Key, scoped to one bucket
#   B2_BUCKET_NAME           — the bucket name you created
#   B2_ENDPOINT_URL          — shown next to the bucket in the B2 console,
#                              e.g. https://s3.us-west-004.backblazeb2.com
```
Backblaze B2's free tier includes 10GB storage with no billing details
required. All photos, voice-training audio, and voice-training transcripts
are written to this one bucket by the relay server — the phone never holds
a B2 key.

### b) Android client relay config (`app/build.gradle.kts`)
Find these two lines in `defaultConfig { ... }` and fill them in:
```kotlin
buildConfigField("String", "RELAY_BASE_URL", "\"FILL_IN_YOUR_RELAY_URL_HERE\"")
buildConfigField("String", "APP_SHARED_SECRET", "\"FILL_IN_YOUR_SHARED_SECRET_HERE\"")
```
`APP_SHARED_SECRET` here must exactly match the one in `relay-server/.env`.
`RELAY_BASE_URL` is wherever you deploy the relay (see §5) — must end in `/`,
e.g. `"https://your-app.onrender.com/"`.

### c) App icon
`android:icon="@mipmap/ic_launcher"` in the manifest currently has no actual
mipmap resources generated — add a launcher icon via Android Studio's
Image Asset tool (right-click `res` → New → Image Asset) before building a
release APK. Debug builds will still run with Android's default icon.

## 4. Running the relay server

```bash
cd relay-server
python3 -m venv venv && source venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --reload
```
Visit `http://127.0.0.1:8000/docs` for interactive API docs (FastAPI's
built-in Swagger UI). For the Android app on a real phone to reach a locally
running server, deploy it (see below) rather than using `127.0.0.1` — a real
phone can't reach your laptop's localhost directly without extra networking
(e.g. ngrok, or being on the same LAN with your machine's local IP).

## 5. Deploying the relay server

Any small always-on host works — this app's traffic (one or two personal
devices) fits comfortably in a free tier:
- **Render** (render.com) — free web service, connect the `relay-server/`
  folder as a Python service, set the env vars from `.env` in its dashboard
- **Fly.io** — `fly launch` from `relay-server/`, set secrets via `fly secrets set`
- **Railway** — similar flow, env vars in project settings

After deploying, copy the live URL into `RELAY_BASE_URL` in step 3b.

## 6. Opening and building the Android app

1. Open Android Studio (Koala/2024.1 or newer recommended for AGP 8.5)
2. **File → Open** → select the `JarvisCoPilot/` folder
3. Let Gradle sync (it will download dependencies — needs internet)
4. Complete step 3b above before syncing, or sync will fail on the
   BuildConfig placeholders
5. Run on a device or emulator (API 26+)

## 7. First-run permission flow

On first launch you'll see two skippable permission explainer screens
(Notification Access, Accessibility) — see architecture doc §4. Location,
storage, and battery-optimization exemption are requested only when you
first use the feature that needs them (Power Profiles, Media Vault,
Settings → Battery, respectively).

## 8. Known gaps / integration points left as TODOs in the code

These are explicitly marked with comments in the source and are places where
a production build needs additional work beyond this scaffold:

- `LocalIntentEngine.setAlarm/setTimer` — needs real NLP/regex time parsing
  to extract hour/minute from spoken text (currently opens the alarm/timer UI
  without pre-filling the time)
- `ScreenSummarizerAccessibilityService.toggleBatterySaverViaSettings()` —
  needs OEM-specific node lookup (stock Android vs. Samsung vs. MIUI settings
  layouts differ)
- `JarvisViewModel.refreshSystemStatus()` — CPU load needs `/proc/stat`
  parsing or a small library; left as a stub returning 0
- Relay server's `VOICE_SAMPLES` dict is in-memory — the audio and transcript
  bytes themselves are durably stored in B2, but *which* samples have been
  marked used-in-training is tracked only in this dict, so that bookkeeping
  resets on server restart. Replace with a real database (Postgres/SQLite)
  before that matters to you.
- `VoiceTrainingScreen`'s "Train Model" button is a TODO hook — there's no
  actual fine-tuning pipeline in this project for it to call yet.
- `PermissionExplainerScreen` "Grant" buttons open system settings screens
  (Android requires this for Notification Listener / Accessibility — they
  can't be requested via the normal runtime-permission dialog)

## 9. Full architecture rationale

See the accompanying `jarvis-copilot-architecture-final.md` document for the
complete reasoning behind every permission choice, what was removed from the
original spec and why, the two-device data-scoping model, and the legal/data
notes section covering consent and third-party data handling.
