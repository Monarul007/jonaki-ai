# AGENTS.md — orientation for coding agents

**What this repo is:** the Android app for **Jonaki AI**, an offline chat app that runs
Google's Gemma 4 E2B model on the phone (Kotlin + Jetpack Compose + LiteRT-LM).

**Scope:** this repository is the Android app only. The desktop Python app (`serve.py`,
`agent.py`, `chat.html`, `anthropic_bridge.py`, `figures.py`, `tts.py`) lives in the same
workspace on the original machine but is **not in this git repository**. `KNOWLEDGE.md`
documents both sides so you are not starting from zero.

Read in this order: `README.md` → this file → `KNOWLEDGE.md` → `PLAN.md` / `INSTALL.md`.

---

## Build, test, install

Requirements: JDK 17, Android SDK platform 36, build-tools 36.

```powershell
# local.properties is gitignored. Point it at the local SDK, or export ANDROID_HOME.
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest     # ParsersTest, FiguresTest, SpeechTest
.\gradlew.bat :app:assembleRelease       # R8; signs only if the release key is present
```

- Debug id: `com.offlinegemma.app.dev` (label “Jonaki AI Dev”), installs next to the real app.
- Release id: `com.offlinegemma.app`.
- On MIUI, `adb install` fails with `INSTALL_FAILED_USER_RESTRICTED` while the screen is locked.
  Wake it first. Reliable path:
  ```powershell
  adb push app\build\outputs\apk\debug\app-debug.apk /data/local/tmp/jonaki-dev.apk
  adb shell pm install -r /data/local/tmp/jonaki-dev.apk
  ```
- The release key is **not** in the repo. Without
  `~/.android-keys/offlinegemma.properties` (+ `.jks`) or `OFFLINEGEMMA_KEY_PROPS`, release
  output is unsigned and cannot update an installed release app.

Never commit: `local.properties`, `*.jks`, `*.keystore`, `keystore.properties`,
`signing.properties`, `*.apk`, `*.aab`, `*.litertlm`, build output or the model cache.

## Commit messages: NO agent attribution

**Hard rule. Never add a co-author, "generated with", or any other attribution line to a commit
message.** The commit is attributed to the person who owns the repo, and to nobody else.

Do not write any of these:

```
Co-authored-by: <any agent, bot, or tool>
Generated with <tool>
<noreply@...>
```

A commit message is the subject line, a blank line, and the body. Nothing after the body. This applies
to every commit, amend, rebase, cherry-pick and squash — if a tool inserts attribution automatically,
remove it before the commit is created. A HUMAN co-author is fine; the ban is on attributing the work
to an agent.

Enforced: `sh tools/install-hooks.sh` installs `.git/hooks/commit-msg`, which rejects such a message
(exit 1) before the commit exists. Run it once per clone — `.git/hooks` is not versioned.

The history was rewritten once to remove 46 such lines; do not reintroduce them.

---

## Architecture at a glance

| Area | File(s) |
|---|---|
| Model load/inference | `GemmaEngine.kt` (GPU+vision+audio → CPU+media → CPU text fallback, remembers the working mode) |
| Chat/brain | `ChatController.kt` (streaming, stop, saved chats, tutor, tools, compaction) |
| Screens | `MainActivity.kt`, `ChatScreen.kt`, `Composer.kt`, `TalkScreen.kt`, `SettingsScreen.kt`, `AboutScreen.kt` |
| Camera | **`CameraCapture.kt`** (in-app CameraX preview/shutter/flip) |
| Keep-alive | `Capture.kt` (`KeepAliveService`, only for photo/file pickers) |
| Attachments | `Attachments.kt` (photo scaling, PDF/Office text), `Viewer.kt` |
| Model download | `ModelInfo.kt` (pinned URL/size/SHA-256), `ModelDownloadWorker.kt`, `DownloadScreen.kt` |
| Tutor | `Tutor.kt`, `AnswerView.kt`, `Figures.kt`, `FigureView.kt`, `DemoView.kt` |
| Tools | `Tools.kt` (`<tool_call>` text protocol, web search/fetch) |
| Voice | `Speaker.kt`, `VoiceSheet.kt`, `VoiceDetector.kt`, `TalkController.kt` |
| Sandboxed code | `JsRunner.kt` (WebView, no network/file access) |
| Persistence | `ChatStore.kt` (JSON files inside the app), `Settings.kt` (prefs) |

State lives in `App` (app-wide `GemmaEngine`, `ChatController`, `Speaker`, `TalkController`), so
rotation/screens never reload the 2.6 GB model.

---

## Hard-won constraints — do not regress these

1. **Never launch `ACTION_IMAGE_CAPTURE` for photos.** On Xiaomi/Redmi, MIUI's
   `camopt_killer` SIGKILLs this process when the system camera opens, even while it holds a
   foreground service. Measured exit: `reason=2 SIGNALED status=9`, importance 125. The model
   was lost and the photo import failed after relaunch. Photos stay in-process via
   `CameraCapture.kt` (CameraX). This was the 1.0.1 fix.
2. **Keep model loading in `MainActivity.onResume`.** It is a post-process-death safety net;
   `loadModel()` is idempotent. Loading only from the chat screen left the app stuck on
   “Waking up” if the process was killed while another app was in front.
3. **A model's memory figure is advice, never a permission.** `ChatModel.minTotalRamBytes` is only
   the *warning* threshold. It used to be a hard gate — E4B was withheld below 11 GB total RAM because
   an 8 GB Redmi was made unusable by it — and a second phone disproved that: a **moto edge 50 fusion
   reports the same 7.56 GB** (Snapdragon 7s Gen 3, Adreno 710, 3.1 GB free, **5.7 GB swap**) and runs
   E4B steadily, `GPU + image + audio`, in ~30 s. Total RAM cannot separate those two phones, so it
   cannot be the verdict. What actually holds:
   - **Storage** is the only hard requirement (`Fit.NO_STORAGE`).
   - Every installed model is loadable; the phone decides at load time. A failure surfaces as
     `chat.loadError` with a sentence, never as a crash or a silent kernel kill.
   - `roomyEnough()` drives warnings only (download screen, Settings). It must never filter a model
     out of `installable()` or refuse a load.
   - `tightPhone` in `GemmaEngine` (audio in/out) keys on **the phone**, not on the model: keying it
     on the model's recommendation silently dropped audio for E4B on a 7.5 GB phone.
4. **One conversation at a time.** `GemmaEngine.oneShot()` closes the open chat; callers must
   rebuild it afterwards (`startChat(history(lines))`).
5. **Compact before the window overflows.** `compactIfNeeded()` keeps recent turns verbatim and
   summarises older ones; `maxTokens` is `model.maxTokens`, halved below that model's
   `recommendedRamBytes` (8192 → 4096 for E2B, 4096 → 2048 for E4B).
6. **Keep the model file out of git.** It is downloaded and verified on device; `.gitignore`
   already excludes `*.litertlm` and caches.
7. **Release signing is external.** Do not generate or commit a key to “make it build”; an
   unsigned release APK is expected in a fresh checkout. The original key is on the office PC;
   the exact shipping steps (install over 1.0.0 without losing the model or chats) are in
   `RELEASE.md`.

---

## Current state (2026-11-21)

- Version **1.0.3** / `versionCode 5` (1.0.3: answers as background jobs with a queue and "Answer ready" notices, general page reading, test bench in evals/).
- In-app camera + model-wake fixes are committed on `main`.
- Debug build verified on a Redmi Note 15 Pro+: PID unchanged across capture, no process-exit
  record, photo landed as a draft chip, model stayed loaded.
- Known gaps from the plan: native LiteRT-LM tool calls (text protocol only), document/share
  paths not exercised on a phone, full agent loop not tested, Python/Chaquopy deliberately
  skipped for code execution (sandboxed JavaScript is used instead).

---

## Product context worth knowing

- The desktop app is the reference implementation: `agent.py` has the tool loop and context
  compaction; `chat.html` has the Tutor/figures/demos UI; `figures.py` finds Wikipedia/Commons
  pictures and asks Gemma to verify them. This app ports those ideas; keep behaviour aligned.
- The phone is the product: 8 GB RAM is the recommended line, 4 GB works but slowly, Android 12+.
- The model download is 2.59 GB with resume and SHA-256; do not add another large asset to git.
- Bengali is first-class: `Prefs.answerBengali`, Bengali TTS, Bengali transcript hints.
