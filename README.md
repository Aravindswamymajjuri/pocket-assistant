# Pocket Assistant

A private, offline-first voice assistant for a Realme 7 Pro on Android 12 or later. Gemma proposes actions, and a separate Kotlin policy engine decides whether they run.

## Current status: v0.3

Built on GitHub with Java 21. Only the features you test on your phone should be treated as working.

Included:
- Policy engine, risk levels, secret redaction, rate limits, and one-time approvals
- Master switch, pause timers, Quick Settings stop tile, notification Stop button, and Lockdown
- Control Center for tools, permissions, battery, model, voice, calls, and security warnings
- Room database encrypted with SQLCipher, tamper-evident audit log, and full data wipe
- A wake word using a name you set in the app, Silero VAD, and Voice ID through sherpa-onnx
- whisper.cpp speech-to-text in English, Hindi, Telugu, or auto-detect
- Piper voices for English and Hindi, and Android's voice for Telugu
- Gemma through LiteRT-LM from a typed path, a picked file, or a private copy
- Apps, alarms, timers, flashlight, volume, brightness, Wi-Fi, Bluetooth, Do Not Disturb, lock, search, back, home, scroll, tap
- Ring, vibrate, and silent modes, plus mobile data, hotspot, airplane mode, location, dark mode, power saving, auto-rotate, and screen recording through Quick Settings tiles
- Notifications, SMS, WhatsApp, calls, and Gemma multi-step plans
- Experimental speakerphone AI call answering for allowed numbers only

Known limits:
- Voice ID can be fooled by recordings and cloned voices, so spoken codes and fingerprint fallback stay on
- AI calling depends on this phone letting the app use the mic during a call
- Whisper base is less accurate for Telugu and Hindi than for English, and slower than tiny
- Wake names must use English letters, because the wake-word model is English
- Wi-Fi and Bluetooth use the settings screen, because Android blocks direct switching for this app
- There is no allow-list of apps yet, only a block list

## Windows laptop version

The `desktop` folder holds a Windows version that shares the same policy engine and security tests.

Laptop limits:
- No SIM, so there's no SMS or AI call answering. Calls are handed to Phone Link
- Windows doesn't let this app read notifications or control Do Not Disturb
- Screen tapping only works in apps that expose Windows UI Automation
- Alarms and timers only ring while the assistant is on

## Download

Direct files are on the Releases page: https://github.com/Aravindswamymajjuri/pocket-assistant/releases/tag/latest

- `PocketAssistant-Android.apk` for Android 12 or later
- `PocketAssistant-Windows-Setup.exe` for 64-bit Windows

## Build

Every push to `main` runs the security tests and builds `app-release.apk`. Download it from the workflow run's artifacts.

To update the app without uninstalling it, add these repository secrets so every build uses the same key:
- `SIGNING_KEYSTORE_B64`: your keystore, base64-encoded, with the key alias `assistant`
- `SIGNING_PASSWORD`: its password

Without them, each build uses a throwaway key and the previous version must be uninstalled first.

## Set up on the phone

1. Install the APK.
2. On Android 13 or later, try to enable the Accessibility Service, then go to App info → ⋮ → Allow restricted settings. Android 12 doesn't need this.
3. Open the app and turn on only the tools you want.
4. In Control Center, enter the Gemma `.litertlm` path or tap Browse, then tap Test model. A file in Download needs All files access.
5. Turn on the assistant with your fingerprint. You must do this again after every restart.
6. Set up Voice ID, then turn on the wake word if you want hands-free use.
