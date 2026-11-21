# Jonaki — Project Knowledge

_Last gathered: 2026-11-21._

> **Repository scope.** This note was written for the full Jonaki workspace
> (`E:\AI\litert-lm\Jonaki`), which holds the desktop Python app and the Android app in
> `mobile/`. **This git repository is the Android app**: the `mobile/` row in the map below is
> the repository root, so `mobile/...` paths here mean `./...`. The desktop files are context
> only and are not in this repository.

**In one line:** Jonaki (জোনাকি, "firefly") is an offline AI chat product built on Google's
LiteRT-LM + Gemma 4 E2B — a Python desktop web UI, a Claude Code protocol bridge, and a
native Android app that runs the model on the phone.

---

## 1. Repo map

| Path | What it is |
|---|---|
| `serve.py` | Desktop web server: serves `chat.html`, proxies `/v1/*` to litert-lm, sessions API, uploads, figures, TTS, agent SSE |
| `chat.html` | Single-file web client (inline CSS/JS, no build step) — ~2 800 lines |
| `agent.py` | Multi-agent tool loop, 13 tools, ReAct fallback, context compaction |
| `anthropic_bridge.py` | Anthropic Messages ⇄ OpenAI chat/completions streaming bridge for Claude Code |
| `cclocal.cmd` | One-command launcher: model server + bridge + Claude Code against it |
| `chat.cmd` | `python serve.py --spawn %*` |
| `figures.py` | Textbook figures: Wikipedia/Commons search → download → Gemma verifies + captions → cache |
| `tts.py` | Optional Kokoro (English/multi) + sherpa-onnx VITS (Bengali) offline voices |
| `sessions/` | Append-only JSONL transcripts + `.meta.json` sidecars |
| `workspace/` | Agent sandbox: project files, `uploads/`, `figures/` + `index.json` |
| `voices/` | Kokoro ONNX model (326 MB) + voice bin (28 MB) |
| `mobile/` | Android app (Kotlin/Compose, separate git repo) |
| `bridge.log`, `litert.log` | Bridge log (self-written, unbuffered) and litert-lm serve log |
| `.claude/launch.json` | Run configs: `litert-chat` (`python -u serve.py --port 8010`) and an unrelated PHP app |

`mobile/` is ~3 400 files / 622 MB in the full workspace — mostly `app/build`, `.gradle`,
`dist`. Don't blind-search it. In this repository those directories are gitignored; only source
is tracked. The desktop rows above are context, not files in this repository.

---

## 2. Runtime architecture

```
Browser (chat.html)
   │  same origin
   ▼
serve.py :8000 ──/v1/* proxy (chunked, read1, no-store)──► litert-lm serve :9379
   ├── /agent/chat  (SSE)  ──► agent.py agent_chat() ──► :9379 /v1/chat/completions
   ├── /api/sessions, /api/upload, /api/filetext
   ├── /api/figure  ──► figures.py ──► Wikipedia/Commons + :9379 (vision)
   └── /api/tts, /api/voices ──► tts.py (Kokoro / sherpa-onnx)

Claude Code ──ANTHROPIC_BASE_URL :3457──► anthropic_bridge.py ──► :9379
```

Default ports: UI `8000`, model `9379`, bridge `3457`.

---

## 3. Desktop server (`serve.py`)

- `ThreadingHTTPServer`, `protocol_version = "HTTP/1.1"`, `SimpleHTTPRequestHandler` rooted at project dir.
- `/` and `""` → `chat.html`. Static GETs get `Cache-Control: no-store` and validators are stripped.
- `/v1/*` → transparent proxy: forwards `Content-Type`, `Authorization`, `Accept`; streams with
  `read1(4096)` and manual chunked framing; 502 JSON when the model server is down.
- Routes:
  - `GET/POST/PATCH/DELETE /api/sessions[/{id}[/entries]]`
  - `POST /api/upload?name=...` (24 MB cap, 12 000 chars extracted text)
  - `POST /api/figure` → `figures.find_figure`
  - `GET /api/voices`, `POST /api/tts` (WAV)
  - `GET /api/filetext?path=` — only files under `workspace/uploads`
  - `POST /agent/chat` (SSE), `GET /agent/tools`, `GET /agent/info`, `POST /agent/approve`
- SSE writes are chunked manually; every response ends with `0\r\n\r\n`.
- `--spawn` starts `litert-lm serve --port <upstream port>` unless one is already alive.
- `lan_ip()` prints a phone-accessible URL when bound to `0.0.0.0`.
- On boot: `tts.warm_up()` in a daemon thread.

### Session format
One JSONL per session, append-only, fsync per commit:
```
{"type":"header","id":...,"created":...,"model":"gemma4-e2b"}
{"type":"message","id":"...","parent":null|"<id>","role":"user|assistant","content":...,"ts":...}
```
Entries form a tree; the active branch is walked by `parent`. Sidecar meta holds
`createdAt`, `updatedAt`, `title`, `model`, `messageCount`. Corrupt lines are skipped.
`SESSION_ID_RE = ^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$`.

---

## 4. Agent (`agent.py`)

### Tools (`@tool` decorator → `TOOLS` dict; schemas built from type hints)
| Group | Tools |
|---|---|
| youtube | `youtube_transcript` |
| web | `web_search` (DuckDuckGo), `web_fetch` |
| files | `read_file`, `write_file`, `edit_file`, `list_files`, `search_files` |
| docs | `read_document` (PDF/DOCX/XLSX/PPTX/CSV), `view_image` |
| code | `run_python` |
| shell | `run_shell` (PowerShell, user approval via `APPROVALS`, 120 s timeout) |
| delegate | `delegate` (one level deep, quiet sub-stream to parent) |

### Sandbox
`WORKSPACE = ./workspace`, `UPLOADS = workspace/uploads`, `set_root()` overrides.
`_safe_path`/`_inside` reject traversal. `SKIP_DIRS` excludes `.git`, `node_modules`, venvs, etc.

### Tool selection (small windows can't hold all schemas)
`ROUTES` regex → groups; `DEFAULT_GROUPS = ["web","code","files"]`; `files` implies `code`.
`TEACH_RX` enables `TEACH_RULES` (analogy, `[FIGURE: ...]`, one interactive HTML demo, `NEXT: q1 | q2 | q3`).

### Loop (`agent_chat`, `MAX_TURNS = 10`)
1. Build system prompt + history + current turn (`_clean_parts` keeps media only on the newest user turn for multimodal models; `is_multimodal` = model starts with `gemma`).
2. Detect links in the user's own words (`find_links`, filters file TLDs) → **pre-fetch** them before the model answers.
3. Cap tool schemas at ~25 % of the context window, dropping trailing tools.
4. Stream; consume native `tool_calls` deltas or `<tool_call>{...}</tool_call>` ReAct text.
5. Recovery nudges: empty answer after tools → one nudge; "I'll do X" without a call → "Go ahead"; refusal pattern → auto `web_search` once.
6. If `run_python` is offered, auto-run the largest ```` ```python ```` block once and ask for a fix on crash (`_check_code`), refusing `input()`, `while True`, network, destructive ops.
7. Append a `**Sources**` list from `web_search`/`web_fetch` if the model forgot to cite.

### Model-specific quirks baked in
- `TOOL_ROLE_OK = ("gemma",)`: other models get tool results rewritten as user messages and
  assistant tool calls stripped, because litert-lm drops `tool` role content for qwen and
  tool-call tags in history can hang it.
- `_merge_call_delta`: litert-lm may resend whole tool names/arguments; naive appending produced
  `edit_fileedit_file` and duplicate JSON objects.
- Errors arrive **inside** a 200 SSE stream as `{"error": ...}` — treated as a real error, not content.
- `MODEL_CONTEXT = {"qwen3-4b-instruct": 2048, "fn-garden": 1024}`, `DEFAULT_CONTEXT = 4096`,
  `CHARS_PER_TOKEN = 4`; image = 280 tokens, audio = 25 tokens/s (16 kHz mono base64).

### Context compaction (`fit_context`)
System prompt shortened → recent turns kept verbatim (0.6 of budget) → older turns summarized
(`summarize_history`, cached on model + length + first/last message) → digest appended to the
system message → hard-drop only if still over. Emits `_Compacting N earlier messages…_`.
Tool output appended to the transcript is capped (`cap = max(800, min(2400, limit*4//6))` chars).

---

## 5. Web UI (`chat.html`)

- `API` = `?api=` override, else `location.origin` when served, else `http://127.0.0.1:9379` when opened as a file.
- Chat mode → `/v1/chat/completions` (direct streaming). Agent mode toggle (persisted in
  localStorage) → `/agent/chat` SSE with tool chips, approvals, status, `text_clear`.
- Markdown renderer: headings, lists, tables, blockquotes, fenced code with copy, `$$` display math
  (custom `tex()`), inline math, SVG blocks rendered live with a collapsible source view.
- Figures: `[FIGURE: text]` → `POST /api/figure`, cached client-side in `figCache`, credit line
  (licence · author) and a "wrong picture?" retry.
- Interactive demos: ```` ```html ```` → sandboxed `iframe` (`allow-scripts`, no same-origin), 640 px
  virtual width scaled to the column, probe `postMessage`s size/errors, auto-fix once on newest reply.
- Tutor follow-ups from `NEXT: a | b | c` → chips for 🧒 Simpler · 🔬 Go deeper · ❓ Quiz me · ✅ Check facts.
- Sessions served from `/api/sessions`; drawer with relative times, rename (PATCH), delete.
- Attachments: images/audio as data URLs, documents uploaded to `/api/upload` with extracted text
  inline and a viewer (`/api/filetext`) for PDF/DOCX/CSV.
- Voice: browser speech capture (`encodeWav` → 16 kHz), `Speaker` queue fetching `/api/tts`
  sentence by sentence, voice preference in localStorage.
- Scroll behavior: follow only when already at the bottom (`PIN_SLACK = 80`), plus a "New message" affordance.

---

## 6. Claude Code bridge (`anthropic_bridge.py`)

- Translates `/v1/messages` + `/count_tokens` + `/v1/models` + `HEAD`; always answers as
  `text/event-stream` with the full Anthropic event sequence
  (`message_start` → `content_block_start/delta/stop` → `message_delta` → `message_stop`).
- `to_openai()`: Anthropic content blocks → OpenAI parts; text, images (base64 data URLs),
  tool_use/tool_result conversion.
- `strip_system_reminders()` removes `<system-reminder>` blocks Claude Code interleaves with the
  real question (they otherwise dominate a 2B model's answer).
- `fit_to_context()`: system prompt → one short line, drop history oldest-first, never drop the
  final user turn, tail-truncate if it alone overflows.
- Tool schemas are trimmed to fit and counted against the same budget. Models flagged
  `tools: False` get schemas dropped and an explicit "you cannot call tools" note.
- `MODEL_SPECS` (measured, not trusted from `litert-lm describe`):
  `gemma4-e2b` 4096/tools · `qwen3-4b-instruct` 2048/tools · `lfm2.5-2.6b` 4096/no tools ·
  `fn-garden` 1024/no tools (calls a tool for *every* prompt, even "capital of France").
- Logs to `bridge.log` by opening/writing/closing per line (shell redirection buffers and hid output).
- Proper stream termination: `Connection: close` header + `close_connection = True` on every path.

### `cclocal.cmd`
```
cclocal.cmd                    # interactive, default gemma4-e2b
cclocal.cmd lfm2.5-2.6b        # pick model
cclocal.cmd -p "question"      # one-shot
```
Starts `litert-lm serve` on `:9379` and the bridge on `:3457` if not listening, then sets
`ANTHROPIC_BASE_URL`, `ANTHROPIC_API_KEY=litert-local`, `ANTHROPIC_MODEL=sonnet`,
`CLAUDE_CODE_MAX_CONTEXT_TOKENS=4096`, and calls the real `claude.exe`.
Uses `setlocal enabledelayedexpansion` because `shift` is a no-op inside `if (...)`; only
`%2..%9` are forwarded (never `%*`, which would re-pass the consumed model id).

---

## 7. Android app (`mobile/`, package `com.offlinegemma.app`)

Name **Jonaki**; app ID kept as `com.offlinegemma.app` so updates install over old builds.
Kotlin + Jetpack Compose, LiteRT-LM `0.19.1`, min/target handled in `app/build.gradle.kts`.

| Module | Role |
|---|---|
| `GemmaEngine.kt` | Loads `Engine`/`Conversation`; GPU+vision+audio → CPU+media → CPU text fallback; remembers the mode that worked; app-wide engine |
| `ChatController.kt` | Streaming chat, stop, new chat, tutor hooks, tool loop |
| `ModelInfo.kt` | Pinned Gemma 4 E2B URL/commit/size/SHA-256 |
| `ModelDownloadWorker.kt` | WorkManager foreground `dataSync` download, resume via `Range`, checksum |
| `DownloadScreen.kt` | Storage/RAM checks, Wi-Fi-only switch, progress, pause/resume/cancel |
| `ChatStore.kt` | On-device saved chats (JSON files), messages + attachments |
| `Markdown.kt` | Compose Markdown: headings, lists, code, tables, LaTeX-ish spans, links |
| `AnswerView.kt` | Answer actions, figures, demos, follow-ups |
| `Tutor.kt` | Desktop tutor rules ported (TEACH_RX/rules, NEXT parsing, Check facts via DDG) |
| `Tools.kt` | `<tool_call>` text protocol, forgiving parser, web_search/web_fetch, chips |
| `Figures.kt` / `FigureView.kt` | Wikipedia/Commons figures, verification, captions, viewer |
| `Attachments.kt` / `Composer.kt` | PDF/Office text extraction, attachment sheet, pickers |
| `CameraCapture.kt` | **In-app CameraX camera** (preview + shutter + flip). Photos are taken inside the app; the system camera app is never launched |
| `Capture.kt` | `KeepAliveService`, a short foreground service used while a photo/file picker is open |
| `Speaker.kt` / `VoiceSheet.kt` / `VoiceDetector.kt` / `TalkController.kt` / `TalkScreen.kt` | TTS per language, voice sheet, 16 kHz recorder, talk mode with echo-aware interrupt |
| `JsRunner.kt` | Sandboxed WebView JS runner (network/file access blocked) for "solve it" code tests |
| `DemoView.kt` | Interactive HTML demo WebView (`blockNetworkLoads`, size probe) |
| `Viewer.kt` | Image zoom, PDF pages, DOCX text, audio playback |
| `SettingsScreen.kt` / `AboutScreen.kt` | Voice, speed, theme, web search, licences, report |
| `ui/Theme.kt` | Light/dark brand theme, wallpaper colors |

Milestones 1–8 are done (see `mobile/PLAN.md` §10–14 and `mobile/INSTALL.md`):
download/resume/checksum, chat, pictures/files, talk mode, tools, release build.
Release: R8 shrinks 139 MB → 30 MB (arm64 only); 20 JVM unit tests
(`ParsersTest`, `FiguresTest`, `SpeechTest`); signed with a key outside the repo
(`C:\Users\monar\.android-keys\offlinegemma.jks` — user's original machine).

**Camera fix (2026-11-21, versionCode 3 / 1.0.1):** the phone's camera app is gone from the
photo path. MIUI's `camopt_killer` SIGKILLed the app when `ACTION_IMAGE_CAPTURE` opened — even
with a short foreground service (exit record: `SIGNALED`, status 9, importance 125) — and the
reopened app failed to re-import the photo. Photos now use `CameraCapture.kt` (CameraX) inside
our own foreground process. Model loading is also kicked from `MainActivity.onResume`, not only
from the chat screen, as a safety net after any process death. Verified on the Redmi: the PID
stayed identical across capture and the photo landed as a draft chip.

Known gaps from the plan: native LiteRT-LM tool calls (text protocol only), document/share
paths not tried on the phone, full agent loop untested, Python/Chaquopy deliberately skipped.

---

## 8. Data and artifacts on disk

- `sessions/` — 9 sessions; two are large multi-turn transcripts (794 KB, 283 KB).
- `workspace/uploads/` — `lesson.pdf`, `plan.docx`, `problem.png`, `sales.csv`, `question.wav`, `long.wav`.
- `workspace/figures/` — 5 verified figures + `index.json` cache keyed by lowercased query
  (black hole, photosynthesis, chloroplast, axial tilt, oblique rays).
- `workspace/` — `calc.py`, `demo.html`, `poem.txt`.
- `voices/` — `kokoro-v1.0.onnx` (325 MB), `voices-v1.0.bin` (28 MB). Bengali VITS folder
  `voices/vits-coqui-bn-custom_female` is expected by `tts.py` but is **not present**.
- `~/.litert-lm/models/` — installed on this machine: `functiongemma`, `functiongemma-lora`,
  `gemma4-e4b`. **No `gemma4-e2b` directory**, which is the default model for `cclocal.cmd`,
  `serve.py` sessions and the Android app's download target.

---

## 9. Model facts (as documented/measured in code)

| Model | Context | Tools | Notes |
|---|---|---|---|
| `gemma4-e2b` | 4096 | yes | Multimodal (vision + audio); default everywhere |
| `qwen3-4b-instruct` | 2048 (measured) | yes | Drops `tool` role content; tool tags in history can hang it |
| `lfm2.5-2.6b` | 4096 | no | — |
| `fn-garden` | 1024 | no | Emits valid `tool_calls` but no judgement; never drive a real loop |

`litert-lm describe` reports "Supports Function Call: NO" for every model — the flag is wrong.
Tool support is settled empirically (discrimination: right tool when needed, silence when not).
Gemma 4 E2B phone model: 2 588 147 712 bytes, SHA-256 `181938105e0e…39a63c`, pinned HF commit
`2e12bfd869b296…d534cc`.

---

## 10. Commands

```powershell
# Desktop UI (starts litert-lm too if missing)
python serve.py --spawn
python serve.py --host 0.0.0.0 --port 8000      # reachable from a phone
chat.cmd

# Claude Code against the local model
cclocal.cmd
cclocal.cmd qwen3-4b-instruct
cclocal.cmd -p "explain this repo"

# Bridge alone
python anthropic_bridge.py --port 3457 --model gemma4-e2b

# Android
cd mobile
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Health checks: `netstat -ano | findstr ":9379 :8000 :3457" | findstr LISTENING`.
`GET /v1/models` on `:9379` is the liveness probe used by `serve.py`.

---

## 11. Environment state verified 2026-11-21

- `litert-lm` is **not on PATH** and not installed in the active interpreter
  (`C:\Users\HP\AppData\Local\Python\pythoncore-3.14-64\python.exe`, `pip show litert-lm` → not found).
  However `~/.litert-lm/models/` contains `functiongemma`, `functiongemma-lora`, `gemma4-e4b`.
- Nothing was listening on `:9379`, `:8000` or `:3457` at check time.
- **This repository is the Android app** (`mobile/`), branch `main`. The camera/model-wake fix
  is committed as `057ec54` (version 1.0.1); before that, HEAD was `8a4babb`. The full workspace
  root is not a git repository.
- **The release signing key is not on this PC** (no `offlinegemma.jks`/`.properties` anywhere on
  C:/D:/E:). `assembleRelease` therefore produces `app-release-unsigned.apk`; it cannot install
  over the existing release app. Build/sign on the machine that has the original key
  (`C:\Users\monar\.android-keys\`), or uninstall + replace (loses the 2.6 GB model).
- Dev builds install as `com.offlinegemma.app.dev`. On MIUI, `adb install` fails with
  `INSTALL_FAILED_USER_RESTRICTED` while the screen is locked; wake it first. A reliable path is
  `adb push app-debug.apk /data/local/tmp/` then `adb shell pm install -r /data/local/tmp/app-debug.apk`.
- `.commandcode/taste/` exists (main `taste.md` plus an unusual `##-environment/taste.md` folder).

---

## 12. Gotchas worth remembering

1. **Over-long prompts fail silently**: litert-lm sends `{"error": ...}` inside a 200 SSE stream.
   Always check each chunk for `error` and terminate the stream properly.
2. **The context window must be measured per model** — qwen3-4b was 2048, not the assumed 4096.
3. **Tool schemas consume the prompt budget**; budget them (~25–33 %) and trim messages with the
   schema size already subtracted.
4. **Claude Code's system prompt is ~33k tokens** — larger than any model here. Strip
   `<system-reminder>` blocks and shorten the system message first, never the final user turn.
5. **HTTP/1.1 chunked SSE leaves the socket open** unless `Connection: close` is sent and
   `close_connection = True` is set on every exit path; otherwise clients hang.
6. **PowerShell `Start-Process -ArgumentList` mangles prompts with spaces** — quote or use a file.
7. **Shell redirection buffers bridge logs**; write/close per line instead.
8. **Windows batch**: `shift` is a no-op inside `if (...)`; use delayed expansion and re-pass
   `%2..%9` rather than `%*`.
9. **Android talk mode**: on loudspeaker, echo ≈ user voice; use tap-the-orb to interrupt, keep
   voice interrupt for headphones.
10. **R8**: keep `androidx.room.RoomDatabase` constructors or the release app crashes at start.
11. **Figure search**: long queries make Gemma approve wrong pictures; search with ≤5 key words
    plus the question's topic words.
12. **MIUI kills the app when the system camera opens.** `camopt_killer` SIGKILLs every
    background process, including one holding a short foreground service (measured on the Redmi
    Note 15 Pro+: `reason=2 SIGNALED status=9`, importance 125). Never launch
    `ACTION_IMAGE_CAPTURE` for photos; keep the preview in-process (`CameraCapture.kt`). This is
    also why the old pending-capture/MediaStore recovery path existed and why it was fragile.
13. **Android UID isolation**: the release app's downloaded E2B model cannot be read by the
    `.dev` build, and the device has no root. Reusing the model requires installing under the same
    package name with the original signing key. The PC's `~/.litert-lm` has E4B (3.4 GB), not E2B;
    E4B needs ~8.3 GB working set and is not a drop-in for an 8 GB phone. For a dev camera test,
    the 283 MB FunctionGemma bundle padded to the pinned byte size loads fine.
