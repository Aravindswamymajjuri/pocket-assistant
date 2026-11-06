# Pocket Assistant

A private, offline-first voice assistant for a Realme 7 Pro on Android 13. Gemma proposes actions, and a separate Kotlin policy engine decides whether they run.

## Current status: v0.2

Built on GitHub with Java 21. Only the features you test on your phone should be treated as working.

Included:
- Policy engine, risk levels, secret redaction, rate limits, and one-time approvals
- Master switch, pause timers, Quick Settings stop tile, notification Stop button, and Lockdown
- Control Center for tools, permissions, battery, model, voice, calls, and security warnings
- Encrypted, tamper-evident audit log and full data wipe
- Wake word "Hey Assistant", Silero VAD, Whisper tiny.en, Piper voice, and Voice ID through sherpa-onnx
- Gemma through LiteRT-LM from a typed path, a picked file, or a private copy
- Apps, alarms, timers, flashlight, volume, lock, search, back, home, scroll, tap
- Notifications, SMS, WhatsApp, calls, and Gemma multi-step plans
- Experimental speakerphone AI call answering for allowed numbers only

Known limits:
- Voice ID can be fooled by recordings and cloned voices, so spoken codes and fingerprint fallback stay on
- AI calling depends on this phone letting the app use the mic during a call
- Whisper tiny.en understands English only
- There is no allow-list of apps yet, only a block list

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
4. In Control Center, enter the Gemma `.litertlm` path or tap Browse, then tap Test model. A file in Download needs All files access.
5. Turn on the assistant with your fingerprint. You must do this again after every restart.
6. Set up Voice ID, then turn on the wake word if you want hands-free use.
