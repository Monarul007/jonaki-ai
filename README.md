# Jonaki AI — Android

Jonaki (জোনাকি, "firefly") is an offline AI chat app. It downloads Google's Gemma 4 E2B
model once, then chats, looks at photos, reads documents, talks and listens **on the phone**.
Nothing is uploaded.

> **Repository scope:** this repository is the Android app (Kotlin + Jetpack Compose). The
> desktop Python app (`serve.py`, `agent.py`, `chat.html`, the Claude Code bridge) is a related
> project but is **not** included here. `KNOWLEDGE.md` still documents both, for context.

## What it does

- On-device chat with streaming, Markdown, LaTeX, code copy and tables
- Tutor mode: textbook figures, interactive HTML demos, follow-up questions
- Camera, photo picker, PDF/Word/PowerPoint/Excel/CSV reading, paste, share-into-app
- Talk mode (16 kHz microphone) and read-aloud with offline phone voices
- Web search / page reading (optional, the only feature that goes online)
- Saved chats, English/Bengali answers, light/dark theme

## Build

Requirements: JDK 17, Android SDK with platform 36 and build-tools 36.

```powershell
# local.properties is gitignored; point it at your SDK, or export ANDROID_HOME
sdk.dir=C\:\\Android\\Sdk

.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleRelease   # needs the release keystore, see below
```

The debug build installs as **Jonaki AI Dev** (`com.offlinegemma.app.dev`) next to the real app.
The release build uses `com.offlinegemma.app`.

Install (MIUI blocks `adb install` while the screen is locked):

```powershell
adb push app\build\outputs\apk\debug\app-debug.apk /data/local/tmp/jonaki-dev.apk
adb shell pm install -r /data/local/tmp/jonaki-dev.apk
```

## Model

The 2.59 GB `gemma-4-E2B-it.litertlm` file is **not in this repository**; the app downloads it
on first launch from a pinned Hugging Face commit, with resume + SHA-256 verification. See
`app/src/main/java/com/offlinegemma/app/ModelInfo.kt`.

## Signing

The release key is deliberately outside the repo (`~/.android-keys/offlinegemma.properties` +
`.jks`, or `OFFLINEGEMMA_KEY_PROPS`). Without it Gradle produces
`app-release-unsigned.apk`, which cannot install over an existing release build.

## Docs for contributors and coding agents

- `AGENTS.md` — start here: build/run commands, architecture, hard-won gotchas
- `KNOWLEDGE.md` — full workspace knowledge (Android + desktop, model facts, data flows)
- `PLAN.md` — product plan, milestones, test results
- `INSTALL.md` — instructions for internal testers
- `RELEASE.md` — shipping the signed 1.0.1 update (the key is on the office PC)

## Status

Version **1.0.3** (`versionCode 5`). The photo path uses an in-app CameraX preview: launching
the phone's camera app made MIUI's `camopt_killer` SIGKILL the process (model included) as soon
as the shutter was pressed. See `AGENTS.md` and `KNOWLEDGE.md` for the evidence.
