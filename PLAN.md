# Offline Gemma Phone App — Plan (Android first)

> **In one line:** a phone app that downloads Google's Gemma 4 E2B "brain" once,
> then chats with you with **no internet needed** — like Google's AI Edge Gallery.

Checked on **2026-11-21** against the official sources (links at the bottom).
Nothing here is from memory: every version, file name and size was looked up.

---

## 1. Your PC — what is ready, what is missing

| Tool | What it is (simple words) | Status |
|---|---|---|
| Java (JDK 17, Microsoft build) | The language engine that builds Android apps | ✅ Ready |
| Android SDK at `D:\Android-SDK` | Google's box of Android building parts | ✅ Ready (Android 35, 36 parts, build tools 36.1) |
| Android NDK 26 / 27 / 29 | Parts for the "native" (C++) code inside LiteRT-LM | ✅ Ready (not needed directly — the library is pre-built) |
| `adb` | The cable-talker: sends apps to a phone | ✅ Ready |
| Emulator + phone image (Android 37, x86_64) | A pretend phone on the PC | ✅ Ready (`Medium_Phone`) |
| Gradle 9.3.1 (cached) | The "build robot" that turns code into an app | ✅ Ready |
| Gemma 4 E2B model file | The AI brain, 2.59 GB | ✅ Already on PC (`~/.litert-lm/models/gemma4-e2b`), checksum verified |
| **Android Studio** | The big editor app for Android | ❌ **Not installed.** Not required to build, but very handy for design previews and debugging. |
| **`cmdline-tools` (sdkmanager, avdmanager)** | Small helpers that install SDK parts and make pretend phones | ❌ **Missing.** Install via Android Studio → SDK Manager → "SDK Tools", or download "command line tools only". |
| **A real Android phone** | The true test | ✅ OPPO CPH2895 (4 GB RAM) connected. Still want an **8 GB+ phone** to test the "normal" experience. |
| Pretend-phone memory | The emulator only has **2 GB** of memory | ⚠️ **Too small.** Gemma needs ~1.7 GB just for itself. We made a copy called `Gemma_Phone` with 6 GB. |

---

## 2. Big decision: copy Google's Gallery app, or build our own small app?

**Decision: build our own slim app.** We borrow ideas and small bits of code from
Gallery where it helps (its licence allows this, see §9).

| | Fork Gallery | Slim app (chosen) |
|---|---|---|
| Size of code | ~1,000 files, many demo features (Smart Album, Prompt Lab, skills, MCP, XR glasses…) | ~10 files to start |
| Extra baggage | Firebase, Hilt, Hugging Face sign-in, protobuf, many model choices | Only LiteRT-LM + Compose |
| Our branding & rules | Must strip Google branding everywhere | Ours from day one |
| Keeping up with Google | Merge conflicts every release (they ship weekly) | Just bump one library version |
| Speed to "hello, model loaded" | Slower (learn their whole app first) | Fast |

**Library we use:** `com.google.ai.edge.litertlm:litertlm-android` — latest
release **0.19.1** (5 Nov 2026). Gallery itself uses 0.18.1.

Things the official docs say we must do (we do them):
- Load the model **on a background thread** (it can take ~10 seconds).
- Add `<uses-native-library android:name="libOpenCL.so" android:required="false"/>`
  (and `libvndksupport.so`) so the **GPU** (the phone's graphics chip, much faster) can be used.
- Try in this order, falling back if one fails:
  1. GPU + pictures + sound → 2. CPU + pictures + sound → 3. CPU text-only.
- Close the `Engine` and `Conversation` when done (they hold lots of memory).

Main API (confirmed from `Engine.kt`, `Conversation.kt`, `Config.kt` on GitHub `main`):

```kotlin
val engine = Engine(EngineConfig(
    modelPath = file.path,
    backend = Backend.GPU(),        // or Backend.CPU()
    visionBackend = Backend.GPU(),  // pictures
    audioBackend = Backend.CPU(),   // sound must be CPU
    cacheDir = context.cacheDir.path))
engine.initialize()                 // slow → background thread
val chat = engine.createConversation(ConversationConfig(
    systemInstruction = Contents.of("You are a helpful assistant."),
    samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0)))
chat.sendMessageAsync("Hi").collect { piece -> show(piece) }  // streaming
chat.cancelProcess()                // "stop" button
```

---

## 3. Getting the model onto the phone (first launch)

**Which file:** `gemma-4-E2B-it.litertlm` from
`huggingface.co/litert-community/gemma-4-E2B-it-litert-lm`

| Fact | Value |
|---|---|
| Size | **2,588,147,712 bytes** (≈ 2.59 GB) |
| SHA-256 (fingerprint) | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |
| Pinned version (repo commit) | `2e12bfd869b2962984f97a1f2a26807316d534cc` |
| Needs a Hugging Face login? | **No** — the page is not "gated" (no sign-in wall) |
| Other files on that page | GPU-web, Tensor G5/G6 and Qualcomm NPU versions (later: pick the best file per phone) |

Download URL (pinned so it never changes under us):
`https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/2e12bfd869b2962984f97a1f2a26807316d534cc/gemma-4-E2B-it.litertlm`

How the download works (like Gallery's `DownloadWorker`):

1. **Storage check first.** Need file size + ~1 GB spare (for cache and safety).
   If not enough: "Free up X GB, then try again."
2. **Wi-Fi only (default ON).** A switch lets the user allow mobile data,
   with a warning: "This uses 2.6 GB of data."
3. **Runs in the background** with a notification showing %, speed and
   time left, even if the app is closed. Built on Android's WorkManager
   (a job helper that survives restarts) as a foreground task of type
   `dataSync`.
4. **Resume.** Bytes go into `gemma.litertlm.part`. If Wi-Fi drops, we ask
   the server for "the rest, starting at byte N" (an HTTP `Range` request).
   No starting over.
5. **Checksum.** When done, compute SHA-256 and compare with the number above.
   Wrong → delete and retry. Right → rename `.part` → `.litertlm`.
6. Saved in the app's private folder (`filesDir/models/`). Deleted if the app
   is uninstalled. A "Delete model" button in Settings frees 2.6 GB.
7. **Mirror (recommended before launch):** also host the file on our own
   storage/CDN (Apache 2.0 allows this) and fall back to it if Hugging Face is
   slow or down. Keep the same checksum.

Why not ship the model inside the Play Store app? Play app bundles have size
limits per download pack well below 2.6 GB, so a single 2.6 GB file must be
downloaded after install (this is also what Gallery does). Google's newer
"Play for On-device AI" packs are worth re-checking later as a second option.

---

## 4. Chat with streaming

- Words appear one by one as Gemma makes them (`sendMessageAsync` → Kotlin `Flow`).
- **Stop** button → `conversation.cancelProcess()`.
- **New chat** → close conversation, create a fresh one (the engine stays loaded).
- Show speed (tokens per second) in small grey text, like the desktop app.
- Chat history saved on the phone only (Room database). Never uploaded.
- Long chats: Gemma 4 E2B supports up to 32k tokens; start at 4k on phones
  (`maxNumTokens`) to save memory, raise on 12 GB+ phones.
- "Thinking" mode can be toggled through `extraContext = mapOf("enable_thinking" to true)`.

## 5. Pictures, voice, and talking back

| Feature | How |
|---|---|
| **Picture in** | Camera or gallery → shrink to ~1024 px → `Content.ImageBytes(...)` |
| **Voice in** | Gemma 4 E2B **hears audio directly** (no separate speech-to-text). Record with the mic (16 kHz mono) → `Content.AudioBytes(...)`. Needs the `RECORD_AUDIO` permission, asked only when the mic button is tapped. |
| **Order matters** | Put the `Content.Text` **before** pictures/audio (Google's rule) |
| **Talking back** | Android's built-in `TextToSpeech` reads answers aloud. Works offline if the phone has the voice pack. A speaker button on each answer + an "auto-read" switch. |
| Text-only fallback | If the phone can't load the picture/sound parts, hide those buttons and say why. |

## 6. Tools (the "agent" part)

LiteRT-LM supports tools natively: write a Kotlin function, mark it `@Tool`,
and Gemma can call it (`ConversationConfig(tools = listOf(tool(MyTools())))`).

| Tool | On the phone? | Notes |
|---|---|---|
| **Web search** | ✅ when online | Needs a search service. Options: our own small server that calls a search API (keeps the API key off the phone), or a key-less source. **Off by default; tell the user it goes online.** The rest of the app stays offline. |
| **Calculator / date / unit convert** | ✅ always | Pure Kotlin, safe, offline. Replaces most "run code" needs. |
| **Read a file/photo the user picked** | ✅ | Only files the user picks with Android's picker. No full storage access. |
| **Run code** | ⚠️ Limited | Play Store forbids apps that download and run new code from outside. Safe option: run small **JavaScript** inside a locked-down, offline WebView/JS sandbox with no network and no file access, with the same "Approve?" step as the desktop app. No Python, no shell. |
| Auto-testing of code answers | Later | Only via the JS sandbox above. |

Start with: calculator + date/time + (optional) web search. Turn on
automatic tool calling; show each tool call as a small card in the chat.

## 6b. Interactive tutor (same as the desktop app)

Copied from the desktop `chat.html` (`hydrateDemos`, `DEMO_PROBE`, `layoutDemo`,
`addFollowups`) and `agent.py` (`TEACH_RX`, `TEACH_RULES`), changed for a phone.

| Desktop | Phone |
|---|---|
| ```` ```html ```` block with script/canvas/input/svg/button → live mini-page after the reply ends | Same rule. One **WebView** per demo. |
| While streaming: "⚙️ Building an interactive demo…" | Same, plus "(N lines so far)" because phones are slow |
| `iframe sandbox="allow-scripts"` (no same-origin) | WebView: JavaScript on; **no** file/content access, no storage, `blockNetworkLoads`, every request except the inline page is refused, links can't navigate away |
| Probe script `postMessage`s `{error, height, width}` | Same probe, but reports through a tiny `AndroidDemo.post()` bridge that only accepts size and error |
| 640 px virtual page scaled to the column | Same 640 px page (`width=640` viewport), scaled to the screen width; widens if the demo is wider |
| Auto-fix once per user message, only if the reply is still the newest | Same. Otherwise a "🔧 Fix this demo" button. Never interrupts a quiz. |
| `NEXT: q1 \| q2 \| q3` → 3 buttons; drop `<question 1>` junk | Same |
| 🧒 Simpler · 🔬 Go deeper · ❓ Quiz me · ✅ Check facts | Same. Shown only on the newest reply, only if it has a NEXT line or is long (700+ chars). |
| Check facts: the model calls `web_search` | The **app** searches DuckDuckGo itself (same method as `agent.py`), then hands the results to Gemma. Small models use tools unreliably, and this skips a slow extra round. Needs internet; says so if offline. |
| Teaching rules added to the system prompt when `TEACH_RX` matches | LiteRT-LM's system prompt is fixed per chat, so the rules are attached, hidden, to that one message. The user sees only their own words. |

Phone-specific choices:
- **Demos switch** ("Demos: On/Off"). Default **off on phones under 8 GB RAM**
  (our 4 GB test phone makes ~2 words/s, so an 80-line demo takes minutes),
  on for bigger phones. With demos off, the tutor still explains and gives NEXT buttons.
- Demo size limit lowered from 80 to **60 lines** on the phone.
- Gemma's quiz questions can have more than one right answer — the quiz prompt
  asks for exactly one correct option.

## 7. Which phones can run it (minimum specs)

| | Minimum | Recommended |
|---|---|---|
| RAM (memory) | **8 GB** (same as Gallery's rule for this model) | 12 GB+ |
| Free storage | 4 GB (model 2.6 GB + ~0.9 GB load cache) | 6 GB+ |
| Android version | Android 12 (API 31) | Android 14+ |
| Chip | 64-bit ARM (arm64-v8a) | Recent Snapdragon 8 / Tensor / Dimensity 9000+ (GPU) |

Our own test: a **4 GB** budget phone (Unisoc UMS9230E) *did* load the model
on its GPU, but only made ~2 words/second. So 8 GB stays the "good experience"
line; below it we warn instead of blocking.

Google's measured numbers (Samsung S26 Ultra): CPU 47 words/sec using
~1.7 GB memory; GPU 52 words/sec using ~0.7 GB. Older/mid phones will be
much slower. On first launch we check `ActivityManager.MemoryInfo.totalMem`
and warn on phones under 8 GB ("This phone may be too small"). In the
Play Console we can also block phones with too little RAM (device catalog
rules), so people don't get a broken app.

## 8. Play Store checklist

- [ ] **Target Android 16 (API 36)** — required for new apps/updates since 31 Aug 2026. (Our scaffold targets 36.)
- [ ] **16 KB memory page support** — required for apps with native code targeting Android 15+. LiteRT-LM's pre-built libraries must be 16 KB-aligned; verify with Android Studio's APK Analyzer before release.
- [ ] Upload as an **Android App Bundle (.aab)**, signed with Play App Signing.
- [ ] **AI-generated content policy:** the app must let users **report/flag** offensive AI answers inside the app. Add a "Report" button on each answer.
- [ ] Add a short **safety system prompt** and block clearly harmful topics; Gemma's own **Prohibited Use Policy** still applies to our users — link it in our Terms.
- [ ] **Data safety form:** say chats stay on the phone; say what web search sends (if on).
- [ ] **Privacy policy** web page (required).
- [ ] **Foreground service** declaration in Play Console for the `dataSync` download (with a short video showing it).
- [ ] Permissions explained when asked: microphone, camera, notifications.
- [ ] Content rating questionnaire (an AI chat app is usually rated Teen or higher).
- [ ] Store listing must say "Downloads a 2.6 GB AI model on first use".
- [ ] Closed testing: new personal developer accounts must run a test with 12+ testers for 14 days before going public.

## 9. Licences (checked in the actual files)

| Thing | Licence | Checked where |
|---|---|---|
| LiteRT-LM library | **Apache 2.0** | `LICENSE` file in `google-ai-edge/LiteRT-LM` |
| Google AI Edge Gallery | **Apache 2.0** | `LICENSE` file in `google-ai-edge/gallery` |
| Gemma 4 E2B (`google/gemma-4-E2B-it` and the `litert-community` copy) | **Apache 2.0** | Hugging Face model card metadata (`license: apache-2.0`), not gated |

What Apache 2.0 means for us, simply: we may use, change, sell and
re-host it. We must **keep the licence text and copyright notes**, and say
if we changed files we copied. → Add an "Open-source licences" screen in
Settings listing LiteRT-LM, Gemma 4, and any Gallery code we borrow (keep
Google's copyright header on borrowed files).

## 10. Build order (milestones)

1. ✅ **Hello, model loaded** — app finds the model file, loads it, says hi. (Done in this folder.)
   Tested 2026-11-21 on the `Gemma_Phone` emulator (Android 37, x86_64, 6 GB, 16 KB pages):
   - GPU+media and CPU+media both failed on the emulator in the **audio** part
     (`audio_litert_compiled_model_executor.cc:364`); the app fell back to
     **CPU text-only**, which loaded in 1.8 s and answered "Hello!" in 1.3 s.
     Screenshot: `emulator-hello.png`.
   - Total time to ready was 113 s, almost all of it the two failed tries.
     Fix in milestone 2: remember which mode worked last time and try it first.
   - Must re-test pictures + voice on a **real arm64 phone**; the emulator is not proof either way.
   - The APK contains LiteRT-LM for `arm64-v8a` and `x86_64` only (no 32-bit phones — fine, they are too small anyway).
2. ✅ **Model downloader** — resume, Wi-Fi-only switch, storage check, RAM warning,
   checksum, background notification, pause / resume / cancel, delete model.
   Also: remembers which load mode worked and tries it first; the model is held
   app-wide so turning the phone doesn't load it twice.
   Tested 2026-11-21 on a real phone — **OPPO CPH2895, Unisoc UMS9230E, 4 GB RAM, Android 16**:
   - Full 2.59 GB download over Wi-Fi in ~4 min (~12 MB/s), notification shown.
   - Pause at 688 MB → file stopped growing → Resume carried on from 688 MB (no restart).
   - SHA-256 check passed; app moved straight on to loading.
   - Loaded in **GPU + image + audio** mode even with 4 GB RAM.
     First load 123 s; second load **13 s** (LiteRT-LM saves a ~0.9 GB cache in `cacheDir`).
   - Speed: ~2 words/s (a 4-line poem in 20 s). Works, but slow — budget phones
     need a "this phone is slow" notice, not a hard block.
   - Screenshot: `phone-chat.png`.
   - Not yet tested: a real drop of Wi-Fi mid-download (auto-retry path), and
     the mobile-data path.
3. Real chat screen: streaming, stop, new chat, history.
4. Pictures + voice in, text-to-speech out.
5. Tools: calculator, date/time, optional web search; JS sandbox later.
6. Phone check (RAM warning), settings, licences screen, report button.
7. Play Store prep (§8), closed testing on 3–4 real phones (8 GB and 12 GB).
8. iOS version with the LiteRT-LM Swift package, same plan.

## 11. How to run the starter app (today)

```powershell
cd D:\practice\litert-lm\mobile
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
# The app now downloads the model itself on first launch.
# Optional shortcut for the emulator (skips the 2.6 GB download):
adb push C:\Users\monar\.litert-lm\models\gemma4-e2b\model.litertlm /data/local/tmp/gemma.litertlm
adb shell run-as com.offlinegemma.app mkdir -p files/models
adb shell run-as com.offlinegemma.app cp /data/local/tmp/gemma.litertlm files/models/gemma-4-E2B-it.litertlm
```

---

### Sources
- LiteRT-LM Kotlin guide: <https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md>
- LiteRT-LM Android rules: <https://github.com/google-ai-edge/LiteRT-LM/tree/main/agents/skills/create-litert-lm-android-demo-app>
- Maven versions: <https://maven.google.com/web/index.html#com.google.ai.edge.litertlm:litertlm-android>
- Model files: <https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm>
- Gallery app + model list (`model_allowlists/1_0_23.json`): <https://github.com/google-ai-edge/gallery>
- Play target API rule: <https://developer.android.com/google/play/requirements/target-sdk>

## 12. Production build (2026-11-21) — results on a REDMI Note 15 Pro+ (8 GB, Snapdragon 7s Gen 3, Android 16)

| Milestone | What | Tested on phone |
|---|---|---|
| M3 Look | Brand theme (light/dark, wallpaper colours), icon, splash, welcome + download screens, saved chats (rename/delete), settings, about/licences | ✅ |
| M4 Answers | Markdown, code copy, maths/TeX, tables; copy / regenerate / read-aloud; compacting old messages; Check facts with Sources | ✅ (compacting only by unit logic) |
| M5 Pictures & files | Camera, photos (system picker), files (PDF/Office/CSV/text, ~5000 chars), paste, share-into-app; "solve it" on screenshots + JavaScript auto-test with one automatic fix | ✅ photo picker, screenshot solve, code test · ⏳ camera, documents, share |
| M6 Talk mode | 16 kHz mic, voice detection (PC numbers), sentence-by-sentence speech, aurora orb | ✅ loop 1.6–4 s from end of speech to first spoken words (PC ~15 s full loop) |
| M7 Tools | `<tool_call>` text protocol + forgiving parser, Go-ahead / answer nudges, web_search + web_fetch, green/red chips, sources, offline message | ✅ web search answer with 4 sources in 25 s |
| M8 Release | R8 shrink (139 MB → 30 MB, arm64 only), signed with key outside the repo, 20 JVM unit tests, INSTALL.md | ✅ release APK installed, downloaded the model (3.5 min) and ran |

Model load: 18–30 s on the GPU with image + audio.

**Talk mode lesson (loudspeaker):** with the call-style microphone (VOICE_COMMUNICATION + platform echo
canceller) Xiaomi mutes the mic completely while the phone speaks, so talking over Gemma could never work.
With the plain VOICE_RECOGNITION mic, Gemma's own echo (peaks 0.04–0.06) is about as loud as the user's
voice at arm's length (~0.06), so voice interrupting can't be told apart from echo. Result:
- headphones → talk over Gemma to interrupt (PC rule);
- loudspeaker → **tap the orb to interrupt** (instant); voice interrupt still allowed but needs to be clearly
  louder than the learned echo, so the app never interrupts itself (verified: no false interrupts).

**R8 lesson:** WorkManager's Room database must be kept (`-keep class * extends androidx.room.RoomDatabase { <init>(); }`),
or the release app crashes at start.

Not done / next: native LiteRT-LM tool calls (text protocol only for now), Python (Chaquopy) skipped —
JavaScript covers code tests; camera/document/share paths not yet tried on the phone; OPPO 4 GB smoke test
of the new build.
