# Pocket Assistant

A private, offline-first voice assistant for a Realme 7 Pro on Android 13. Gemma proposes actions, and a separate Kotlin policy engine decides whether they run.

## Current status: v0.1 draft

This code has not been compiled, tested, or run on a phone yet. The first GitHub build will show what needs fixing.

Included in v0.1:
- Policy engine, risk levels, secret redaction, rate limits, and one-time approvals
- Master switch, Quick Settings stop tile, notification Stop button, and Lockdown
- Control Center for tools, permissions, battery, the model, and security warnings
- Encrypted, tamper-evident audit log and full data wipe
- Push-to-talk using Android speech recognition and text-to-speech
- Gemma through LiteRT-LM, with a model you import
- Apps, alarms, timers, flashlight, volume, lock, search, back, home, scroll, tap
- Read notifications, SMS, WhatsApp, and calls
- Simple multi-step commands, such as "open maps and then go home"

Not built yet:
- Wake word
- Voice ID. Until it exists, personal and outbound actions need your fingerprint
- Whisper and Piper. v0.1 uses Android's speech services instead
- Multi-step planning by Gemma
- Pause timers, low-battery auto-pause, and per-item last-used times
- AI call answering, which must pass a test on the real phone first

## Build

Every push to `main` runs the security tests and builds `app-release.apk`. Download it from the workflow run's artifacts.

To update the app without uninstalling it, add these repository secrets so every build uses the same key:
- `SIGNING_KEYSTORE_B64`: your keystore, base64-encoded, with the key alias `assistant`
- `SIGNING_PASSWORD`: its password

Without them, each build uses a throwaway key and the previous version must be uninstalled first.

## Set up on the phone

1. Install the APK. Android 13 will block its accessibility and notification access at first.
2. Try to enable the service, then go to App info → ⋮ → Allow restricted settings.
3. Open the app and turn on only the tools you want.
4. Import the Gemma `.litertlm` file from Control Center.
5. Turn on the assistant with your fingerprint. You must do this again after every restart.
