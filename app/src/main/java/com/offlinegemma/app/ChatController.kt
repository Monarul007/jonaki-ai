package com.offlinegemma.app

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The chat "brain". Lives as long as the app (not the screen), so turning the phone
 * or switching apps never loses a chat or stops an answer.
 */
class ChatController(private val app: Application, val gemma: GemmaEngine, val store: ChatStore, val prefs: Prefs) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  val lines = mutableStateListOf<ChatLine>()

  /** Textbook pictures: what each figure card shows, keyed by "<answer id>|<figure number>". */
  sealed class FigState {
    data class Busy(val message: String) : FigState()
    data class Done(val figure: Figure) : FigState()
    data class None(val reason: String, val retry: Boolean) : FigState()
  }
  val figStates = androidx.compose.runtime.mutableStateMapOf<String, FigState>()
  val figures = Figures(app)
  val chats = mutableStateListOf<ChatSummary>()

  var chatId by mutableStateOf(store.newId()); private set
  var title by mutableStateOf("New chat"); private set
  var ready by mutableStateOf(false); private set
  var loading by mutableStateOf(false); private set
  var loadError by mutableStateOf<String?>(null); private set
  var busy by mutableStateOf(false); private set
  /** Short status under the top bar, e.g. "Compacting 6 earlier messages…". */
  var statusStartedAt by mutableStateOf(0L); private set
  private var statusState by mutableStateOf<String?>(null)
  var status: String?
    get() = statusState
    private set(value) {
      if (value == null) {
        statusState = null
        statusStartedAt = 0L
      } else {
        if (statusState != value) statusStartedAt = System.currentTimeMillis()
        statusState = value
      }
    }

  private var nextId = 1L
  private var job: Job? = null
  // Auto-fix happens at most once per message the user sends (same as desktop).
  private var autoFixes = 0
  private var pendingFix: String? = null

  init {
    refreshChats()
  }

  fun refreshChats() {
    chats.clear()
    chats.addAll(store.list())
  }

  /** Loads Gemma once. Safe to call again (rotation, coming back to the app). */
  fun loadModel() {
    if (ready || loading) return
    loading = true
    loadError = null
    scope.launch {
      try {
        gemma.load()
        ready = true
        // Re-open the chat on screen (e.g. after the model was closed under memory pressure).
        if (lines.isNotEmpty()) gemma.startChat(history(lines))
      } catch (t: Throwable) {
        loadError = t.message ?: "Could not load the model"
      } finally {
        loading = false
      }
    }
  }

  /** Frees the model (e.g. the phone is very low on memory while the app is in the background). */
  fun unloadModel() {
    if (busy) return
    gemma.close()
    ready = false
  }

  private fun newLineId() = nextId++
  private fun indexOf(id: Long) = lines.indexOfFirst { it.id == id }
  private fun update(id: Long, f: (ChatLine) -> ChatLine) {
    val i = indexOf(id)
    if (i >= 0) lines[i] = f(lines[i])
  }

  // ---------- chats ----------

  fun newChat() {
    if (busy) return
    lines.clear()
    draft.clear()
    chatId = store.newId()
    title = "New chat"
    autoFixes = 0
    scope.launch { if (ready) gemma.startChat(emptyList()) }
  }

  fun openChat(id: String) {
    if (busy || id == chatId) return
    val (t, saved) = store.load(id) ?: return
    lines.clear()
    lines.addAll(saved)
    nextId = (saved.maxOfOrNull { it.id } ?: 0) + 1
    chatId = id
    title = t
    autoFixes = 0
    scope.launch { if (ready) gemma.startChat(history(saved)) }
  }

  fun renameChat(id: String, newTitle: String) {
    val t = newTitle.trim().ifEmpty { return }
    store.rename(id, t)
    if (id == chatId) title = t
    refreshChats()
  }

  fun deleteChat(id: String) {
    if (id == chatId) {
      if (busy) return
      store.delete(id)
      newChat()
    } else {
      store.delete(id)
    }
    refreshChats()
  }

  fun deleteAllChats() {
    if (busy) return
    store.deleteAll()
    newChat()
    refreshChats()
  }

  private fun save() {
    if (lines.none { it.who == Who.USER }) return
    if (title == "New chat") title = titleFrom(lines.first { it.who == Who.USER }.display)
    val snapshot = lines.toList()
    val id = chatId
    val t = title
    scope.launch(Dispatchers.IO) {
      store.save(id, t, snapshot)
      withContext(Dispatchers.Main) { refreshChats() }
    }
  }

  /** What the model should remember: user and model turns only (notices are for the screen). */
  private fun history(from: List<ChatLine>): List<Message> = from.filter { it.done && it.error == null }.mapNotNull {
    when (it.who) {
      Who.USER -> Message.user(
        if (it.attachments.any { a -> a.kind != Attachment.Kind.DOC }) "${it.text}\n[an image/audio clip was attached earlier]" else it.text,
      )
      Who.MODEL -> if (it.text.isNotBlank()) Message.model(it.text) else null
      Who.NOTICE -> null
    }
  }

  // ---------- sending ----------

  // Wait for an attachment import too: sending in the middle of a copy used to drop the picture.
  fun canSend() = ready && !busy && !importing

  /** Pictures, voice clips and documents waiting to be sent with the next message. */
  val draft = mutableStateListOf<Attachment>()
  private val docTexts = HashMap<String, String>()
  var importing by mutableStateOf(false); private set

  fun addImage(uri: android.net.Uri) = import { listOf(Attachments.importImage(app, uri, store.filesDir(chatId))) }
  fun addBitmap(bmp: android.graphics.Bitmap, name: String) = import {
    withContext(Dispatchers.IO) { listOf(Attachments.saveBitmap(bmp, store.filesDir(chatId), name)) }
  }

  /** Any file: pictures and sound are kept as they are, documents are read as text. */
  fun addFile(uri: android.net.Uri) = import {
    val type = app.contentResolver.getType(uri).orEmpty()
    when {
      type.startsWith("image/") -> listOf(Attachments.importImage(app, uri, store.filesDir(chatId)))
      type.startsWith("audio/") -> listOf(Attachments.importAudio(app, uri, store.filesDir(chatId)))
      else -> {
        val (a, text) = Attachments.importDoc(app, uri, store.filesDir(chatId))
        docTexts[a.path] = text
        listOf(a)
      }
    }
  }

  /** Text shared into the app from another app goes into the message box (handled by the screen). */
  var sharedText by mutableStateOf<String?>(null)

  private fun import(block: suspend () -> List<Attachment>) {
    importing = true
    scope.launch {
      runCatching { block() }
        .onSuccess { draft.addAll(it) }
        .onFailure {
          android.util.Log.w("ChatController", "Import failed", it)
          notice("Couldn't open that file. Try adding it again with the + button.")
        }
      importing = false
    }
  }

  fun removeDraft(a: Attachment) {
    draft.remove(a)
    docTexts.remove(a.path)
  }

  /**
   * A message the user typed (or a follow-up question they tapped), with any attachments.
   * [voice] = it came from talk mode.
   */
  fun sendTyped(text: String, voice: Boolean = false, extra: List<Attachment> = emptyList()) {
    val atts = draft.toList() + extra
    if ((text.isBlank() && atts.isEmpty()) || !canSend()) return
    autoFixes = 0
    draft.clear()
    val docs = atts.filter { it.kind == Attachment.Kind.DOC }
      .joinToString("\n\n") { Attachments.docForModel(it.name, docTexts.remove(it.path) ?: "") }
    val ask = text.ifBlank {
      if (voice) "(I said this out loud. Reply to it directly, briefly, as in a spoken conversation.)" else "(see attachment)"
    }
    val hasImage = atts.any { it.kind == Attachment.Kind.IMAGE }
    val rules = inLanguage(Tutor.wrap(ask, prefs.demos, codeProblem = Tutor.looksLikeCodeProblem(ask, hasImage)))
    val withDocs = if (docs.isEmpty()) rules else "$docs\n\n$rules"
    val hasAudio = atts.any { it.kind == Attachment.Kind.AUDIO }
    val modelText = if (hasAudio) "(${GemmaEngine.VOICE_RULE})\n\n$withDocs" else withDocs
    val display = text.ifBlank { if (voice) "🎤 Voice message" else "" }
    val codeProblem = Tutor.looksLikeCodeProblem(ask, hasImage)
    val online = Tools.online(app)
    // A link the user typed, or words about recent events, always goes to the web tools —
    // even when agent mode is off (agent.py find_links / ROUTES "web").
    val links = if (text.isNotBlank() && prefs.webSearch) Tools.findLinks(text) else emptyList()
    val wantsWeb = text.isNotBlank() && (links.isNotEmpty() || Tools.wantsWeb(text))
    var agent = prefs.webSearch && !codeProblem && wantsWeb
    if (agent && !online) agent = false
    val offline = text.isNotBlank() && !online
    if (offline && wantsWeb) {
      notice("📴 You're offline — live information (weather, news, prices) isn't available.")
    }
    sendToModel(
      display, modelText, topic = text.ifBlank { null }, attachments = atts, voice = voice,
      testCode = codeProblem, agent = agent, links = links, linkQuery = Tools.linkQuery(text), offline = offline,
    )
  }

  /** A tutor button such as "🧒 Simpler". */
  fun sendButton(label: String, prompt: String) {
    if (!canSend()) return
    sendToModel(label, inLanguage(Tutor.wrap(prompt, prefs.demos)))
  }

  /**
   * The BN/EN switch. In Bengali mode every message carries a short note asking for a Bengali answer
   * (the system prompt is fixed for the whole chat, so the note rides along with each message).
   * Code, maths and picture search words stay in English so they keep working.
   */
  private fun inLanguage(modelText: String) =
    if (prefs.answerBengali) "($BENGALI_RULE)\n\n$modelText" else modelText

  fun notice(text: String) {
    lines.add(ChatLine(newLineId(), Who.NOTICE, text))
  }

  /**
   * Shows [display] as the user's bubble, sends [modelText] to Gemma and streams the answer.
   * [history] is non-null when the conversation must be rebuilt first (regenerate).
   */
  private fun sendToModel(
    display: String,
    modelText: String,
    topic: String? = null,
    rebuild: List<ChatLine>? = null,
    sources: List<Source> = emptyList(),
    attachments: List<Attachment> = emptyList(),
    voice: Boolean = false,
    testCode: Boolean = false,
    codeFixed: Boolean = false,
    agent: Boolean = false,
    links: List<String> = emptyList(),
    linkQuery: String = "",
    offline: Boolean = false,
  ) {
    val userLine = ChatLine(newLineId(), Who.USER, modelText, display, topic = topic, attachments = attachments)
    lines.add(userLine)
    val answerId = newLineId()
    lines.add(ChatLine(answerId, Who.MODEL, "", done = false))
    busy = true
    var fixCode: String? = null
    var findFigures: Pair<Long, String>? = null
    job = scope.launch {
      try {
        if (rebuild != null) gemma.startChat(history(rebuild))
        // Pictures and voice go to the model only if this phone loaded the image/audio parts.
        val imageAtts = attachments.filter { it.kind == Attachment.Kind.IMAGE }
        val images = if (gemma.canSee) imageAtts.mapNotNull { readBytes(it) } else emptyList()
        val audio = if (gemma.canHear) attachments.filter { it.kind == Attachment.Kind.AUDIO }.mapNotNull { readBytes(it) } else emptyList()

        // No vision on this load: read the text in the picture on the phone (Bengali + English).
        // Without this the photo used to be dropped silently and the model said "no image".
        var ocrText = ""
        if (!gemma.canSee && imageAtts.isNotEmpty()) {
          status = "Reading the text in the picture…"
          ocrText = imageAtts.mapNotNull { a ->
            Ocr.readText(app, a.ocrPath ?: a.path).takeIf { it.length >= 12 }
          }.joinToString("\n\n")
          status = null
          update(answerId) {
            it.copy(tools = it.tools + if (ocrText.isNotBlank()) {
              ToolUse("ocr", "read the picture", true, ocrText.take(2000))
            } else {
              ToolUse("ocr", "read the picture", false, "no readable text")
            })
          }
        }

        val skippedAudio = attachments.count { it.kind == Attachment.Kind.AUDIO } - audio.size
        val text = buildString {
          append(modelText)
          if (ocrText.isNotBlank()) {
            append("\n\n").append(ocrRule()).append("\n")
            append(ocrText)
          } else if (!gemma.canSee && imageAtts.isNotEmpty()) {
            append("\n[This phone could not read the attached picture. Tell me that the picture's text could ")
            append("not be read on this phone; do not say that no picture was attached.]")
          }
          if (skippedAudio > 0) {
            append("\n[$skippedAudio audio clip(s) skipped: this phone loaded Jonaki AI without sound.]")
          }
        }
        compactIfNeeded(text.length + images.size * IMAGE_TOKENS * CHARS_PER_TOKEN + audio.sumOf { it.size } / 32000 * AUDIO_TOKENS_PER_SEC * CHARS_PER_TOKEN)
        val start = System.currentTimeMillis()
        onAnswerStart?.invoke(answerId, voice)
        val found = sources.toMutableList()
        // The agent loop (agent.py agent_chat): answer, or call a tool, read its result, and try again.
        var prompt = if (agent) "(${Tools.PROMPT})\n\n$text" else text
        if (offline) {
          val live = Tools.wantsWeb(topic ?: display)
          prompt = "(${if (live) OFFLINE_LIVE_RULE else OFFLINE_RULE})\n\n$prompt"
        }
        // A link in the message is read BEFORE the model answers (agent.py pre-fetch). Small
        // models often skip the fetch and then claim they "can't browse".
        if (links.isNotEmpty() && !offline) {
          val pages = links.map { url ->
            "Tool result for web_fetch:\n" +
              runTool(
                answerId, Tools.Call("web_fetch", mapOf("url" to url, "query" to linkQuery)),
                topic ?: display, found,
              ).take(2400)
          }
          prompt = pages.joinToString("\n\n") + "\n\n" + LINK_RULE + "\n\n" + prompt
        }
        var first = true
        var turn = 0
        var nudged = false
        var pushed = false
        var refused = false
        var reviewed = false
        var researchRounds = 0
        var sourceSearchTried = false
        var blankRetried = false
        var rewriting = false
        var resetChat = false
        val usedOcr = ocrText.isNotBlank()
        // A Bengali question must get a Bengali answer; the model sometimes mixes in Tamil/Hindi.
        val bengaliAnswer = prefs.answerBengali || modelText.any { it in '\u0980'..'\u09FF' }
        while (true) {
          var out = ""
          try {
            val flow = gemma.send(prompt, if (first) images else emptyList(), if (first) audio else emptyList())
              .catch { e -> update(answerId) { it.copy(error = friendlyError(e)) } }
            val limit = if (rewriting) ROUND_TIMEOUT_MS else FIRST_TIMEOUT_MS
            kotlinx.coroutines.withTimeout(limit) {
              flow.collect { piece ->
                out += piece
                // A review/verification rewrite is buffered off-screen; the first answer stays visible.
                if (!rewriting) {
                  val shown = if (agent) Tools.stripCalls(out) else out
                  val safe = if (bengaliAnswer) keepBengali(shown) else shown
                  update(answerId) { it.copy(text = safe) }
                  onAnswerText?.invoke(answerId, safe)
                }
              }
            }
          } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
            // A stuck round must not hang the chat: stop the model and keep the best answer so far.
            gemma.stop()
            status = null
            break
          }
          first = false
          if (agent) {
            val calls = Tools.parseCalls(out)
            if (calls.isNotEmpty() && turn < MAX_TOOL_TURNS) {
              turn++
              val parts = mutableListOf<String>()
              for (c in calls) parts.add("Tool result for ${c.name}:\n" + runTool(answerId, c, topic ?: display, found).take(2400))
              val results = parts.joinToString("\n\n")
              prompt = "$results\n\nNow answer my question using the tool results above."
              update(answerId) { it.copy(text = "") }
              continue
            }
            if (out.isBlank() && turn > 0 && !nudged) {
              // Small models sometimes go quiet after reading tool output; one nudge gets the answer out.
              nudged = true
              prompt = "Now answer my question using the tool results above."
              continue
            }
            if (turn > 0 && !pushed && Tools.announcedStep(out) && turn < MAX_TOOL_TURNS) {
              // It announced a step but stopped instead of calling the tool.
              pushed = true
              prompt = "Go ahead: do that now by calling the tool. Don't describe it."
              update(answerId) { it.copy(text = "") }
              continue
            }
          }
          // Swap in a buffered review/verification rewrite only when it is complete.
          if (rewriting) {
            rewriting = false
            if (out.isNotBlank()) {
              onAnswerStart?.invoke(answerId, voice)
              val safe = if (bengaliAnswer) keepBengali(out) else out
              update(answerId) { it.copy(text = safe) }
              onAnswerText?.invoke(answerId, safe)
            }
          }
          // Refusal safety net (agent.py REFUSAL_RX): never let "I have no information" be the
          // final word. Throw the refusal away (do not save or speak it), look it up, ask once more.
          // Only throw the answer away when a lookup can actually add evidence. Offline (or with
          // web search off), the honest answer is better than a retry that may come back empty.
          if (!refused && researchRounds == 0 && !reviewed && !offline && prefs.webSearch &&
            out.isNotBlank() && Tools.isUnsure(out)
          ) {
            refused = true
            researchRounds++
            turn++
            update(answerId) { it.copy(text = "") }
            onAnswerStart?.invoke(answerId, voice) // restart the speech without the refusal
            var results = ""
            if (!offline && prefs.webSearch) {
              sourceSearchTried = true
              results = runTool(
                answerId,
                Tools.Call("web_search", mapOf("query" to (topic ?: display).take(200))),
                topic ?: display, found,
              )
            }
            val usable = results.isNotBlank() && !results.startsWith("Error") && !results.startsWith("No results")
            prompt = (if (usable) "Tool result for web_search:\n$results\n\n" else "") + REFUSAL_RULE +
              (if (offline) " " + OFFLINE_RULE else "")
            continue
          }
          // A page read by OCR can carry mistakes and the small model can slip on basic facts.
          // One review turn fixes definitions and equations before the answer is final.
          // One-part requests are short and already checked by the verifier; a full review pass
          // is a second model run and makes them feel stuck on a CPU phone.
          // A short answer is already the whole answer; a second full model pass doubles the wait
          // for little gain. Full pages still get the review.
          if (usedOcr && !reviewed && out.isNotBlank() && out.length > 600) {
            reviewed = true
            rewriting = true
            status = "Checking the answer…"
            // Fresh compact context: the accumulated OCR prompt + full answer would overflow the window.
            val prev = out.take(2500)
            gemma.startChat(emptyList(), "")
            resetChat = true
            prompt = "Page text:\n${ocrText.take(1500)}\n\nYour previous answer:\n$prev\n\n" + reviewRule()
            continue
          }
          // Bounded confidence loop: every part answered, no uncertainty, sources present for a
          // web question, and at least one source named. Stops after MAX_RESEARCH_ROUNDS.
          val check = verifyAnswer(
            out,
            found,
            needsWeb = links.isNotEmpty() || Tools.wantsWeb(topic ?: display),
            offline = offline,
            webSearchOn = prefs.webSearch && !sourceSearchTried,
          )
          if (check.problem != AnswerProblem.OK && researchRounds < MAX_RESEARCH_ROUNDS) {
            researchRounds++
            turn++
            rewriting = true
            status = when (check.problem) {
              AnswerProblem.NOT_CITED -> "Checking sources…"
              else -> "Looking it up…"
            }
            when (check.problem) {
              AnswerProblem.UNSURE -> {
                if (out.isBlank()) {
                  if (blankRetried) break // one retry for an empty answer, then the fallback below
                  blankRetried = true
                  prompt = "Answer the question now. Do not return an empty answer."
                } else if (!offline && prefs.webSearch && found.isEmpty() && !sourceSearchTried) {
                  sourceSearchTried = true
                  val results = runTool(
                    answerId,
                    Tools.Call("web_search", mapOf("query" to (topic ?: display).take(200))),
                    topic ?: display, found,
                  )
                  val usable = results.isNotBlank() && !results.startsWith("Error") && !results.startsWith("No results")
                  prompt = (if (usable) "Tool result for web_search:\n$results\n\n" else "") + REFUSAL_RULE
                } else if (found.isNotEmpty()) {
                  prompt = "Rewrite the complete answer using the sources above. Keep every part. Do not " +
                    "say you don't know. Name at least one source."
                } else {
                  // No sources and a lookup already failed: stop with the honest answer + a figure.
                  break
                }
              }
              AnswerProblem.NO_SOURCES -> {
                val results = if (!offline && prefs.webSearch && !sourceSearchTried) {
                  sourceSearchTried = true
                  runTool(
                    answerId,
                    Tools.Call("web_search", mapOf("query" to (topic ?: display).take(200))),
                    topic ?: display, found,
                  )
                } else ""
                val usable = results.isNotBlank() && !results.startsWith("Error") && !results.startsWith("No results")
                prompt = if (usable) {
                  "Tool result for web_search:\n$results\n\nRewrite the complete answer using these results. " +
                    "Keep every part and name at least one source."
                } else {
                  REFUSAL_RULE
                }
              }
              AnswerProblem.NOT_CITED -> {
                prompt = "Rewrite the complete answer, keep every part, and name at least one source link " +
                  "from the sources above. Do not invent a source."
              }
              AnswerProblem.OK -> {}
            }
            // Each verification round gets a bounded context too: page + previous answer + sources.
            val prev = out.take(2500)
            val context = buildString {
              if (ocrText.isNotBlank()) append("Page text:\n${ocrText.take(1200)}\n\n")
              append("Your previous answer:\n$prev\n\n")
              if (found.isNotEmpty()) {
                append("Sources:\n")
                found.take(4).forEach { append("- ${it.title} — ${it.url}\n") }
                append("\n")
              }
            }
            gemma.startChat(emptyList(), "")
            resetChat = true
            prompt = context + prompt
            continue
          }
          break
        }
        val secs = (System.currentTimeMillis() - start) / 1000.0
        if (!testCode) update(answerId) {
          // Never leave an empty bubble: a last-resort message is better than silence.
          val shown = if (it.text.isBlank()) {
            if (bengaliAnswer) "দুঃখিত, এই মুহূর্তে উত্তরটি তৈরি করা যায়নি। অনুগ্রহ করে আবার চেষ্টা করুন।"
            else "Sorry, I couldn't produce an answer. Please try again."
          } else it.text
          it.copy(text = shown)
        }
        update(answerId) { it.copy(done = true, seconds = secs, sources = found.distinctBy { s -> s.url }.take(4)) }
        // A review/verifier reset used a compact context; give the chat its full memory back.
        if (resetChat) gemma.startChat(history(lines))
        // Pictures are found after the answer, one at a time; never while talk mode is speaking.
        if (!voice) findFigures = answerId to (topic ?: display)
        if (testCode) fixCode = testAnswer(answerId, codeFixed)
      } catch (t: Throwable) {
        update(answerId) { it.copy(done = true, error = friendlyError(t)) }
      } finally {
        update(answerId) { if (it.done) it else it.copy(done = true) }
        busy = false
        status = null
        save()
        lines.getOrNull(indexOf(answerId))?.let { onAnswerDone?.invoke(it) }
        findFigures?.let { (id, q) -> if (fixCode == null) startFigures(id, q) }
        // The code crashed: ask once for a fix, and test the fixed code too (same as agent.py _check_code).
        fixCode?.let { err ->
          sendToModel(
            "🔧 Fix the code (automatic)",
            "I ran your code and it failed:\n${err.takeLast(1200)}\nGive the corrected full code in one ```javascript block. Keep it short.",
            testCode = true,
            codeFixed = true,
          )
        }
      }
    }
  }

  /** What a figure card shows. Saved chats show cached pictures instantly and never re-check them. */
  fun figState(answer: ChatLine, n: Int, query: String): FigState? {
    val k = "${answer.id}|$n"
    figStates[k]?.let { return it }
    if (!answer.done) return null // still streaming: "comes when the answer is done"
    val used = (1 until n).mapNotNull { (figStates["${answer.id}|$it"] as? FigState.Done)?.figure?.file }
    val hit = figures.cached(query, used)
    // Not stored here: this runs while the screen draws (the screen remembers it).
    return if (hit != null) FigState.Done(hit) else FigState.None("no picture saved yet", retry = true)
  }

  /** Finds pictures for every figure in an answer (after it finishes). */
  private fun startFigures(answerId: Long, question: String, only: Int? = null) {
    val answer = lines.getOrNull(indexOf(answerId)) ?: return
    val queries = Tutor.figureQueries(answer.text)
    if (queries.isEmpty()) return
    busy = true
    job = scope.launch {
      var usedModel = false
      try {
        val used = mutableListOf<String>()
        for ((i, q) in queries.withIndex()) {
          val n = i + 1
          val k = "$answerId|$n"
          val cachedHit = figures.cached(q, used)
          if (cachedHit != null) { figStates[k] = FigState.Done(cachedHit); used.add(cachedHit.file); continue }
          if (only != null && n != only) { (figStates[k] as? FigState.Done)?.let { used.add(it.figure.file) }; continue }
          if (!Tools.online(app)) { figStates[k] = FigState.None("Pictures need internet", retry = true); continue }
          figStates[k] = FigState.Busy("Finding a real picture of “$q” and checking it fits…")
          status = "Finding a picture…"
          val f = findOne(k, q, question, used) { usedModel = true }
          if (f != null) { figStates[k] = FigState.Done(f); used.add(f.file) }
        }
      } catch (t: kotlinx.coroutines.CancellationException) {
        figStates.keys.filter { it.startsWith("$answerId|") && figStates[it] is FigState.Busy }
          .forEach { figStates[it] = FigState.None("stopped", retry = true) }
        throw t
      } finally {
        // The picture checks used the model on their own; give the chat its memory back.
        withContext(kotlinx.coroutines.NonCancellable) { if (usedModel) gemma.startChat(history(lines)) }
        busy = false
        status = null
      }
    }
  }

  /** One figure: search, download, let Gemma check it (YES/NO + caption), up to 3 tries. */
  private suspend fun findOne(k: String, q: String, question: String, used: List<String>, onModel: () -> Unit): Figure? {
    var cands = runCatching { figures.candidates(q, used) }.getOrDefault(emptyList())
    // The model sometimes mixes Bengali into the marker; use the English words it supplied.
    if (cands.isEmpty() && q.any { it.code > 127 }) {
      val ascii = q.filter { it.code < 128 }
        .replace(Regex("[^A-Za-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ").trim()
      if (ascii.isNotBlank() && ascii != q) {
        cands = runCatching { figures.candidates(ascii, used) }.getOrDefault(emptyList())
      }
    }
    // Last general fallback: let the model translate an unknown Bengali topic for the image search.
    if (cands.isEmpty() && q.any { it in '\u0980'..'\u09FF' }) {
      onModel()
      val en = runCatching {
        gemma.oneShot("Translate this study topic to English for a picture search. Reply with 1-4 English words only: $q", 40)
      }.getOrDefault("").trim().removeSurrounding("\"").trim()
      if (en.isNotBlank() && en.none { it in '\u0980'..'\u09FF' }) {
        cands = runCatching { figures.candidates(en, used) }.getOrDefault(emptyList())
      }
    }
    if (cands.isEmpty()) { figStates[k] = FigState.None("No free picture found for this.", retry = true); return null }
    var tried = 0
    for (c in cands) {
      if (tried >= Figures.MAX_TRIES) break
      val file = runCatching { figures.download(c) }.getOrNull() ?: continue
      tried++
      var caption = ""
      if (gemma.canSee) {
        figStates[k] = FigState.Busy("Checking picture $tried of up to ${Figures.MAX_TRIES}…")
        onModel()
        val verdict = runCatching {
          Figures.parseVerdict(gemma.oneShot(Figures.checkPrompt(question, q, c), 260, file.readBytes()))
        }.getOrDefault(null to "")
        if (verdict.first == false) { file.delete(); continue }
        caption = verdict.second
      }
      if (caption.isBlank()) caption = c.desc.ifBlank { c.title } // no caption: the file's own words
      if (c.file.isNotEmpty() && c.license.isEmpty()) withContext(Dispatchers.IO) { figures.fileLicense(c) }
      val f = Figure(q, file.path, caption.take(600), c.title, c.page, c.file, c.license, c.credit, gemma.canSee)
      figures.save(f)
      return f
    }
    figStates[k] = FigState.None("None of the pictures found matched well enough.", retry = true)
    return null
  }

  /** "🖼️ Find picture" on one figure card. */
  fun findPicture(answerId: Long, n: Int) {
    if (!canSend()) return
    val q = lines.subList(0, indexOf(answerId).coerceAtLeast(0)).lastOrNull { it.who == Who.USER }?.let { it.topic ?: it.display } ?: ""
    startFigures(answerId, q, only = n)
  }

  /** Runs one tool call and shows it as a chip (green = worked, red = failed). */
  private suspend fun runTool(answerId: Long, c: Tools.Call, question: String, found: MutableList<Source>): String {
    val detail = when (c.name) {
      "web_search" -> c.args["query"]?.ifBlank { null } ?: question.take(200)
      else -> c.args["url"].orEmpty()
    }
    status = if (c.name == "web_search") "Searching the web…" else "Reading a page…"
    update(answerId) { it.copy(tools = it.tools + ToolUse(c.name, detail)) }
    val (ok, result) = try {
      when (c.name) {
        "web_search" -> {
          val r = Tutor.webSearch(detail)
          found.addAll(r.sources)
          (r.sources.isNotEmpty()) to r.text
        }
        "web_fetch" -> {
          val page = Tools.webFetch(app, detail, c.args["query"]?.ifBlank { null } ?: question)
          val good = !page.text.startsWith("The page came back almost empty")
          if (good) found.add(0, Source(page.title, if (detail.startsWith("http")) detail else "https://$detail"))
          good to page.text
        }
        else -> false to "Error: unknown tool ${c.name}"
      }
    } catch (t: Throwable) {
      false to "Error: ${t.message ?: t.javaClass.simpleName}"
    }
    update(answerId) { line ->
      line.copy(tools = line.tools.dropLast(1) + ToolUse(c.name, detail, ok, result.take(2000)))
    }
    status = null
    return result
  }

  /**
   * Runs the answer's JavaScript the way a careful person would and shows the output as a tool chip.
   * Returns the error when it crashed and no fix was tried yet.
   */
  private suspend fun testAnswer(answerId: Long, alreadyFixed: Boolean): String? {
    val answer = lines.getOrNull(indexOf(answerId))?.text ?: return null
    val code = JsRunner.codeIn(answer) ?: return null
    if (!JsRunner.runnable(code)) return null
    status = "Testing the code…"
    update(answerId) { it.copy(tools = it.tools + ToolUse("run_js", "running your code")) }
    val r = JsRunner.run(app, code)
    update(answerId) {
      it.copy(
        tools = it.tools.dropLast(1) + ToolUse("run_js", "running your code", r.ok, r.output),
        runOutput = (if (r.ok) "✅ Tested — output when run:\n" else if (alreadyFixed) "❌ It still fails:\n" else "❌ The code crashed — asking for a fix:\n") + r.output.take(1500),
      )
    }
    status = null
    return if (!r.ok && !alreadyFixed) r.output else null
  }

  /** Talk mode and read-aloud listen to answers as they stream. */
  var onAnswerStart: ((Long, Boolean) -> Unit)? = null
  var onAnswerText: ((Long, String) -> Unit)? = null
  var onAnswerDone: ((ChatLine) -> Unit)? = null

  private fun readBytes(a: Attachment) = runCatching { java.io.File(a.path).readBytes() }.getOrNull()

  fun stop() {
    gemma.stop()
    job?.cancel()
    lines.lastOrNull()?.let { last -> if (!last.done) update(last.id) { it.copy(done = true) } }
    busy = false
    status = null
    save()
  }

  /** Asks again for the last answer (same question, fresh reply). */
  fun regenerate() {
    if (!canSend()) return
    val lastUser = lines.indexOfLast { it.who == Who.USER }
    if (lastUser < 0) return
    val user = lines[lastUser]
    val before = lines.subList(0, lastUser).toList()
    while (lines.size > lastUser) lines.removeAt(lines.lastIndex)
    sendToModel(user.display, user.text, user.topic, rebuild = before, attachments = user.attachments)
  }

  private fun friendlyError(t: Throwable): String {
    val m = t.message ?: t.javaClass.simpleName
    return if ("token" in m.lowercase()) "This chat is too long for the model. Tap New chat to start fresh." else m
  }

  // ---------- keeping the chat inside the model's memory ----------

  /**
   * When the 4096-token window is nearly full, older turns are summarised so the chat
   * can go on (same idea as agent.py fit_context). The newest turns stay word for word.
   */
  private suspend fun compactIfNeeded(newChars: Int) {
    val reserve = 1100 // room for the reply
    val need = gemma.tokensUsed + newChars / CHARS_PER_TOKEN + reserve
    if (need <= gemma.maxTokens) return
    // Everything before the user turn just added.
    val past = lines.dropLast(2).filter { (it.who == Who.USER || it.who == Who.MODEL) && it.done && it.error == null }
    if (past.size < 2) return
    val budget = (gemma.maxTokens - reserve) * CHARS_PER_TOKEN
    var keepFrom = past.size
    var size = 0
    for (i in past.indices.reversed()) {
      size += past[i].text.length
      if (size > budget * 0.45) break
      keepFrom = i
    }
    // Keep whole question/answer pairs: never start the kept part with an answer.
    while (keepFrom < past.size && past[keepFrom].who == Who.MODEL) keepFrom++
    val old = past.subList(0, keepFrom)
    val recent = past.subList(keepFrom, past.size)
    if (old.isEmpty()) return
    status = "Compacting ${old.size} earlier messages…"
    val transcript = old.joinToString("\n") { "${if (it.who == Who.USER) "user" else "assistant"}: ${it.display.take(400)}" }
      .take(budget / 3)
    val digest = runCatching {
      gemma.oneShot(
        "Summarize this conversation in a few sentences. Keep names, file paths, decisions, " +
          "numbers and any open questions. Drop small talk.\n\n$transcript",
      ).trim()
    }.getOrDefault("")
    val summary = if (digest.isNotEmpty()) "[Earlier conversation summary]\n${digest.take(1200)}"
    else "[Earlier conversation, condensed]\n" + old.takeLast(8).joinToString("\n") { "${it.who.name.lowercase()}: ${it.display.take(160)}" }
    Log.i("ChatController", "Compacted ${old.size} messages")
    gemma.startChat(history(recent), summary)
    status = null
  }

  // ---------- tutor ----------

  fun checkFacts(answerId: Long) {
    if (!canSend()) return
    if (!prefs.webSearch) {
      notice("✅ Check facts uses web search, which is off. Turn it on in Settings.")
      return
    }
    val i = indexOf(answerId)
    val question = lines.subList(0, i.coerceAtLeast(0)).lastOrNull { it.topic != null }?.topic
    if (question == null) {
      notice("✅ Check facts: ask a question first, then I can check the answer.")
      return
    }
    busy = true
    status = "Searching the web…"
    scope.launch {
      val results = runCatching { Tutor.webSearch(question.take(150)) }
      busy = false
      status = null
      results.onSuccess { (text, found) ->
        // The answer always ends with real links (small models forget to write them).
        sendToModel(Tutor.CHECK_FACTS, inLanguage(Tutor.checkFactsPrompt(text)), sources = found.take(4))
      }.onFailure {
        notice("✅ Check facts needs the internet. Connect and try again.")
      }
    }
  }

  fun onDemoError(answerId: Long, error: String) {
    val line = lines.getOrNull(indexOf(answerId)) ?: return
    if (line.demoError != null) return // already handled (the WebView may reload when scrolled)
    // Fix on its own only while this reply is still the newest one; never interrupt a later chat (a quiz).
    val newest = lines.lastOrNull { it.who == Who.MODEL }?.id == answerId
    val auto = newest && autoFixes < 1
    update(answerId) { it.copy(demoError = error, autoFixing = auto) }
    if (auto) {
      autoFixes++
      pendingFix = Tutor.fixPrompt(error, Tutor.firstDemo(line.text))
      trySendFix()
    }
  }

  private fun trySendFix() {
    val p = pendingFix ?: return
    if (!canSend()) {
      scope.launch {
        while (busy) kotlinx.coroutines.delay(300)
        trySendFix()
      }
      return
    }
    pendingFix = null
    // Sent without tutor rules: with Demos off they would say "no demo" and fight the fix request.
    sendToModel("🔧 Fix the demo (automatic)", p)
  }

  fun fixDemo(answerId: Long) {
    val line = lines.getOrNull(indexOf(answerId)) ?: return
    if (!canSend()) return
    sendToModel("🔧 Fix this demo", Tutor.fixPrompt(line.demoError ?: "", Tutor.firstDemo(line.text)))
  }

  /** Developer builds only: drop a canned tutor answer in, to test demos without waiting for the model. */
  fun injectTest(kind: String) {
    lines.add(ChatLine(newLineId(), Who.USER, "Show me a pendulum demo", "Show me a pendulum demo (test)"))
    lines.add(ChatLine(newLineId(), Who.MODEL, if (kind == "net") TestDemos.netProbe() else TestDemos.reply(kind == "broken")))
  }

  companion object {
    const val CHARS_PER_TOKEN = 4
    const val BENGALI_RULE = "Answer in Bengali (বাংলা), even if I write in English. Use Bengali words; " +
      "keep only technical terms, formulas, code, [FIGURE: ...] search words and the NEXT: line's format " +
      "as they are; write the NEXT questions in Bengali."
    const val MAX_TOOL_TURNS = 4
    /** Extra look-up/rewrite rounds after the first answer (the refusal retry counts as one). */
    const val MAX_RESEARCH_ROUNDS = 3
    const val FIRST_TIMEOUT_MS = 300_000L
    const val ROUND_TIMEOUT_MS = 180_000L
    const val IMAGE_TOKENS = 280 // Gemma's vision budget per picture (same as agent.py)
    const val AUDIO_TOKENS_PER_SEC = 25

    /** agent.py pre-fetch line: what to do with a page the user linked, without asking first. */
    const val LINK_RULE =
      "Above is the page I linked. Do what I asked using it. If I only gave the link, explain what " +
        "the site is, who it is for, its key content and offers, and a few honest suggestions. Do " +
        "not ask me what I want first."

    /** agent.py refusal retry line: never let a refusal be the final answer. */
    const val REFUSAL_RULE =
      "Answer my question now using those results and what you know. Never say you have no " +
        "information: if the results are thin, say what you found and give your best answer."

    /** Prompt for answers read from a picture; generic, so the model owns the subject. */
    internal fun ocrRule(): String = buildString {
      append("[The text below was read from my picture by on-device OCR and may contain mistakes. ")
      append("First silently repair obvious OCR errors using the subject and context: a Latin-looking ")
      append("fragment inside a Bengali sentence is usually a misread Bengali word; a garbled word ")
      append("beside a known term is that term; broken formulas should be rebuilt from the surrounding ")
      append("names. Do not repeat garbled text. If the page has numbered statements (i), (ii), (iii) ")
      append("and lettered questions (ক), (খ), (গ), (ঘ), the numbered lines are the stimulus: answer the ")
      append("lettered questions only, not the equations. If my question asks for only one part (for ")
      append("example only (ক)), answer only that part. Otherwise answer every visible part separately in ")
      append("Bengali, 2-4 sentences each, with precise textbook definitions. For an explanatory ")
      append("answer, add one line exactly like [FIGURE: 2-5 English search words] where a picture would ")
      append("help; the picture search uses English. Write only Bengali words ")
      append("(English technical names in brackets are fine); never use words from other languages. ")
      append("Do not write an essay; do not say that no picture was attached.]")
    }

    /** After an OCR answer: a generic correctness pass. */
    internal fun reviewRule(): String = buildString {
      append("Rewrite your previous answer, correcting any factual mistake. Do not comment on this ")
      append("request or mention that you are checking; output only the corrected answer. Keep every ")
      append("part the user asked for: if the previous answer covered every part, the corrected answer ")
      append("must cover all of them and never drop one. If my question asked for only one part, keep ")
      append("only that part and do not add the others. Check the answer against the passage: ")
      append("definitions, examples, equations, units and numbers. An example must not contradict the ")
      append("definition. Do not invent names, data or formulas. Keep any [FIGURE: ...] marker and ")
      append("write its search words in English. Write only Bengali words, never words ")
      append("from other languages. Keep each part to 2-4 sentences, in Bengali. ")
    }

    /** When the phone has no internet: answer anyway, but say it may be old. */
    const val OFFLINE_RULE =
      "I am offline right now. Answer from your own knowledge and add a short note that this may " +
        "be out of date. Never say you have no information."

    /** Offline + a question that needs live data: be honest, never invent it. */
    const val OFFLINE_LIVE_RULE =
      "I am offline right now. This question needs current information (news, weather, prices, scores), " +
        "which I cannot fetch. Do not invent an answer. Say clearly that the phone is offline and that " +
        "I need the internet for the latest information; add only general guidance if it helps."
  }
}

/**
 * Drops Indic-script letters that are not Bengali. The small model occasionally slips in a
 * Tamil or Hindi word; a Bengali answer should not show them. Latin text, digits and symbols
 * (chemical formulas, arrows) are kept.
 */
internal fun keepBengali(text: String): String =
  text.filter { c ->
    val code = c.code
    // Keep Bengali; drop the foreign writing systems the small model sometimes slips into.
    code in 0x0980..0x09FF || (
      code !in 0x0530..0x06FF &&   // Armenian, Hebrew, Arabic
        code !in 0x0900..0x0DFF && // Indic scripts other than Bengali
        code !in 0x0E00..0x0E7F && // Thai
        code !in 0x10A0..0x10FF && // Georgian
        code !in 0x1100..0x11FF && // Hangul Jamo
        code !in 0x3040..0x30FF && // Japanese kana
        code !in 0x3400..0x4DBF && code !in 0x4E00..0x9FFF && // Chinese/Japanese kanji
        code !in 0xAC00..0xD7AF    // Hangul
      )
  }

