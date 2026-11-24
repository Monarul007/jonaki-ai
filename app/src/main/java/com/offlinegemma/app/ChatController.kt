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

  /**
   * One chat: its messages, title and message-id counter. The screen shows [shown]; an answer and its
   * picture search work on [running], the chat they started in, so the user can read other chats while
   * an answer is still being written there.
   */
  class ChatRef(val id: String, title: String, initial: List<ChatLine> = emptyList()) {
    val lines = mutableStateListOf<ChatLine>().apply { addAll(initial) }
    var title by mutableStateOf(title)
    var nextId = (initial.maxOfOrNull { it.id } ?: 0) + 1
  }
  private var shown by mutableStateOf(ChatRef(store.newId(), "New chat"))
  /** The chat an answer or picture search is working on; null when idle. */
  private var running by mutableStateOf<ChatRef?>(null)
  private val work: ChatRef get() = running ?: shown

  /** The messages on screen. */
  val lines: List<ChatLine> get() = shown.lines
  val chatId: String get() = shown.id
  val title: String get() = shown.title
  /** The chat an answer is being written in (may differ from the one on screen). */
  val runningChatId: String? get() = running?.id
  val runningTitle: String? get() = running?.title
  /** True when the work in progress belongs to the chat on screen. */
  val busyHere: Boolean get() = busy && (running == null || running === shown)

  /** Textbook pictures: what each figure card shows, keyed by "<chat id>|<answer id>|<figure number>". */
  sealed class FigState {
    data class Busy(val message: String) : FigState()
    data class Done(val figure: Figure) : FigState()
    data class None(val reason: String, val retry: Boolean) : FigState()
  }
  val figStates = androidx.compose.runtime.mutableStateMapOf<String, FigState>()
  val figures = Figures(app)
  val chats = mutableStateListOf<ChatSummary>()

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

  private fun newLineId(ref: ChatRef = work) = ref.nextId++
  private fun indexOf(id: Long, ref: ChatRef = work) = ref.lines.indexOfFirst { it.id == id }
  /** Changes one message of [ref] (by default the chat being worked on, not the one on screen). */
  private fun update(id: Long, ref: ChatRef = work, f: (ChatLine) -> ChatLine) {
    val i = indexOf(id, ref)
    if (i >= 0) ref.lines[i] = f(ref.lines[i])
  }
  private fun lineOf(id: Long, ref: ChatRef = work) = ref.lines.getOrNull(indexOf(id, ref))

  /**
   * Work waiting for the model, which runs one job at a time: answers first (in the order asked), then
   * picture searches. [userLineId] marks where the queued question starts in its chat, so the model's
   * context can be rebuilt from the messages before it.
   */
  private class Queued(val target: ChatRef, val answerId: Long, val userLineId: Long?, val figures: Boolean, val start: () -> Unit)
  private val queue = mutableStateListOf<Queued>()
  private var runningFigures = false
  private var figuresPreempted = false

  /** Answers waiting for their turn (pictures are not counted). */
  val waitingAnswers: Int get() = queue.count { !it.figures }
  /** This chat has a question waiting for its turn. */
  val waitingHere: Boolean get() = queue.any { !it.figures && it.target === shown }
  /** How many answers run before answer [answerId] of the chat on screen; null when it is not waiting. */
  fun queuePosition(answerId: Long): Int? {
    val asks = queue.filter { !it.figures }
    val i = asks.indexOfFirst { it.target === shown && it.answerId == answerId }
    return if (i < 0) null else i + (if (running != null) 1 else 0)
  }

  /** The job (answer and picture search) finished: start the next one, or free the model. */
  private fun endRun(ran: ChatRef) {
    running = null
    runningFigures = false
    val next = queue.firstOrNull { !it.figures } ?: queue.firstOrNull()
    if (next != null) {
      queue.remove(next)
      if (!next.figures && ready) {
        // The next question may be in another chat: give the model that chat's messages before it.
        val before = next.target.lines.takeWhile { it.id != next.userLineId }
        chatReset = scope.launch { gemma.startChat(history(before)) }
      }
      next.start()
      return
    }
    AnswerService.finish(app)
    // The user moved to another chat meanwhile: the model still holds the finished chat's context.
    if (shown !== ran && ready) chatReset = scope.launch { gemma.startChat(history(shown.lines)) }
  }

  /** A new question while a picture search runs: the search steps aside and goes back in the queue. */
  private fun preemptFigures() {
    if (!runningFigures) return
    figuresPreempted = true
    gemma.stop()
    job?.cancel()
  }

  /** Cancels this chat's questions that are still waiting for their turn. */
  fun cancelWaitingHere() {
    val mine = queue.filter { !it.figures && it.target === shown }
    queue.removeAll(mine)
    for (q in mine) update(q.answerId, q.target) { it.copy(done = true, error = "Cancelled before it started.") }
    save(shown)
  }

  /** The ongoing notification: what runs now and how many wait. */
  private fun showProgress(ref: ChatRef) {
    val waiting = waitingAnswers
    AnswerService.progress(
      app,
      "Answering in “${ref.title}”" + if (waiting > 0) " · $waiting waiting" else "",
    )
  }

  /** Answer ready: tell the user unless they are already looking at that chat. */
  private fun notifyReady(ref: ChatRef, answerId: Long) {
    val line = lineOf(answerId, ref) ?: return
    if (line.text.isBlank()) return
    val visible = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
      .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    if (visible && shown === ref) return
    AnswerService.ready(app, ref.id, ref.title, line.text)
  }

  // ---------- chats ----------

  /** A fresh chat on screen. Allowed while an answer runs elsewhere: it keeps going in its own chat. */
  fun newChat() {
    draft.clear()
    shown = ChatRef(store.newId(), "New chat")
    autoFixes = 0
    if (!busy) chatReset = scope.launch { if (ready) gemma.startChat(emptyList()) }
  }

  /**
   * The model conversation is rebuilt in the background after New chat / opening a chat. A message
   * sent before that finished used to start on the old conversation, which was then closed under it
   * ("Conversation is not alive"). sendToModel waits for this first.
   */
  private var chatReset: Job? = null

  /**
   * Shows chat [id]. While an answer is running (here or in another chat) the model's conversation is
   * left alone; [endRun] rebuilds it for the chat on screen when the answer is done. Opening the
   * chat that is being answered shows its live messages, not the older saved copy.
   */
  fun openChat(id: String) {
    if (id == chatId) return
    val ref = running?.takeIf { it.id == id }
      ?: store.load(id)?.let { (t, saved) -> ChatRef(id, t, saved) }
      ?: return
    shown = ref
    autoFixes = 0
    if (!busy) chatReset = scope.launch { if (ready) gemma.startChat(history(ref.lines)) }
  }

  fun renameChat(id: String, newTitle: String) {
    val t = newTitle.trim().ifEmpty { return }
    store.rename(id, t)
    if (id == chatId) shown.title = t
    running?.takeIf { it.id == id }?.title = t
    refreshChats()
  }

  fun deleteChat(id: String) {
    if (running?.id == id) {
      // Deleting the chat being answered: stop the answer first, and let its last save finish so it
      // cannot write the chat back after the delete.
      val j = job
      stop()
      scope.launch { j?.join(); lastSave?.join(); deleteChat(id) }
      return
    }
    store.delete(id)
    if (id == chatId) newChat()
    refreshChats()
  }

  fun deleteAllChats() {
    if (busy) return
    store.deleteAll()
    newChat()
    refreshChats()
  }

  private var lastSave: Job? = null

  private fun save(ref: ChatRef = work) {
    if (ref.lines.none { it.who == Who.USER }) return
    if (ref.title == "New chat") ref.title = titleFrom(ref.lines.first { it.who == Who.USER }.display)
    val snapshot = ref.lines.toList()
    val id = ref.id
    val t = ref.title
    lastSave = scope.launch(Dispatchers.IO) {
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
  fun canSend() = ready && !busy && !importing && running == null && queue.isEmpty()

  /** A typed question can always be sent once the model is loaded: it waits in the queue if needed. */
  fun canAsk() = ready && !importing

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
    if ((text.isBlank() && atts.isEmpty()) || !canAsk()) return
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
    // Offer the tools whenever agent mode, web search and internet are all on. The model decides
    // whether to search: a keyword gate here hid the tools from exactly the chemistry/physics
    // questions that need research, so the model could not choose to look anything up.
    var agent = prefs.agent && prefs.webSearch && !codeProblem && online
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
    shown.lines.add(ChatLine(newLineId(shown), Who.NOTICE, text))
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
    target: ChatRef = shown,
  ) {
    // Everything below works on the chat the question was asked in, even if the user opens another.
    val userLine = ChatLine(newLineId(target), Who.USER, modelText, display, topic = topic, attachments = attachments)
    target.lines.add(userLine)
    val answerId = newLineId(target)
    target.lines.add(ChatLine(answerId, Who.MODEL, "", done = false))
    // Saved now so a brand-new chat is in the history list while it is being answered.
    save(target)
    val begin = { runAnswer(target, answerId, display, modelText, topic, rebuild, sources, attachments, voice, testCode, codeFixed, agent, links, linkQuery, offline) }
    if (running != null || busy) {
      // The model is busy: wait for the turn (a running picture search steps aside).
      queue.add(Queued(target, answerId, userLine.id, figures = false, start = begin))
      preemptFigures()
      running?.let { showProgress(it) }
    } else {
      begin()
    }
  }

  /** The answer job itself, started when the model is free (see [endRun]). */
  private fun runAnswer(
    target: ChatRef,
    answerId: Long,
    display: String,
    modelText: String,
    topic: String?,
    rebuild: List<ChatLine>?,
    sources: List<Source>,
    attachments: List<Attachment>,
    voice: Boolean,
    testCode: Boolean,
    codeFixed: Boolean,
    agent: Boolean,
    links: List<String>,
    linkQuery: String,
    offline: Boolean,
  ) {
    running = target
    busy = true
    showProgress(target)
    var fixCode: String? = null
    var findFigures: Pair<Long, String>? = null
    job = scope.launch {
      try {
        chatReset?.join()
        if (rebuild != null) gemma.startChat(history(rebuild))
        // Pictures and voice go to the model only if this phone loaded the image/audio parts.
        val imageAtts = attachments.filter { it.kind == Attachment.Kind.IMAGE }
        val images = if (gemma.canSee) imageAtts.mapNotNull { readBytes(it) } else emptyList()
        val audio = if (gemma.canHear) attachments.filter { it.kind == Attachment.Kind.AUDIO }.mapNotNull { readBytes(it) } else emptyList()

        // Read the text in the picture on the phone (Bengali + English) whenever a picture is sent.
        // Without vision this is all the model gets; with vision it gets both, because the small
        // model's own look at a page (shrunk to a fixed size) could not read small print at all.
        var ocrText = ""
        if (imageAtts.isNotEmpty()) {
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

        val found = sources.toMutableList()
        // A photographed page is a study question the small model often gets confidently wrong,
        // and it rarely chooses to search by itself. So the app always looks it up first. The
        // trigger is the modality (a page read by OCR), not any word in it; the model writes the query.
        val research = if (ocrText.isNotBlank() && prefs.webSearch && Tools.online(app)) {
          researchPage(answerId, ocrText, modelText, topic ?: display, found)
        } else ""

        val skippedAudio = attachments.count { it.kind == Attachment.Kind.AUDIO } - audio.size
        val text = buildString {
          append(modelText)
          if (ocrText.isNotBlank()) {
            append("\n\n").append(ocrRule()).append("\n")
            if (images.isNotEmpty()) {
              append("[The picture itself is attached as well. Use it to check the layout and anything ")
              append("the text reading may have got wrong, such as printed numbering; for small print the ")
              append("text reading is usually more reliable.]\n")
            }
            append(ocrText)
            if (research.isNotBlank()) {
              append("\n\n[Web results for checking the facts. Where they disagree with your memory, ")
              append("trust them. Name the source you used.]\n")
              append(research)
            }
            // The user's instruction is the only scope; keep it last, after the page text.
            append("\n\n[Now follow my instruction, and only it: \"")
            append(modelText.take(300))
            append("\". Answer only what it asks; if it asks for one part, answer only that part. If a ")
            append("diagram or picture would clearly help, add one line [FIGURE: English search words for ")
            append("it]. The page text above is reference only.]")
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
            // The visible answer was cut mid-sentence: say so instead of passing it off as complete.
            if (!rewriting) update(answerId) {
              it.copy(text = it.text.trimEnd() + "\n\n_" + (if (bengaliAnswer) STOPPED_BN else STOPPED_EN) + "_")
            }
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
            // Length invariant, not wording: a rewrite far shorter than the answer it replaces is a
            // comment ("please specify…"), not a corrected answer. Keep the answer on screen.
            val current = lineOf(answerId)?.text.orEmpty()
            if (out.isNotBlank() && out.length * 3 < current.length) {
              Log.i("ChatController", "rewrite rejected: ${out.length} chars vs ${current.length}")
              out = current
            } else if (out.isNotBlank()) {
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
          // A very short answer (<300 chars) is already the whole answer; a second full model
          // pass doubles the wait for little gain. Longer one-part answers still get the review.
          if (usedOcr && !reviewed && out.isNotBlank() && out.length > 300) {
            reviewed = true
            rewriting = true
            status = "Checking the answer…"
            // Fresh compact context: the accumulated OCR prompt + full answer would overflow the window.
            val prev = out.take(2500)
            gemma.startChat(emptyList(), "")
            resetChat = true
            prompt = "My question:\n${(topic ?: display).take(300)}\n\n" +
              "Page text:\n${ocrText.take(if (research.isNotBlank()) 1200 else 1500)}\n\n" +
              (if (research.isNotBlank()) "Web results:\n${research.take(1500)}\n\n" else "") +
              "Your previous answer:\n$prev\n\n" + reviewRule(research.isNotBlank())
            if (agent) prompt = "(${Tools.PROMPT})\n\n$prompt"
            continue
          }
          // Bounded confidence loop: every part answered, no uncertainty, sources present for a
          // web question, and at least one source named. Stops after MAX_RESEARCH_ROUNDS.
          var check = verifyAnswer(
            out,
            found,
            needsWeb = links.isNotEmpty(),
            offline = offline,
            webSearchOn = prefs.webSearch && !sourceSearchTried,
          )
          // A meta-answer ("please specify…") must not become the final answer.
          if (check.problem == AnswerProblem.OK && out.length < 120) {
            resetChat = true
            status = "Checking the answer…"
            val answered = runCatching {
              gemma.oneShot(
                "The user asked:\n${(topic ?: display).take(400)}\n\nThe answer:\n${out.take(800)}\n\n" +
                  "Does this answer answer exactly what was asked? Reply YES or NO.",
                6,
              ).trim().uppercase().startsWith("Y")
            }.getOrDefault(true)
            if (!answered) check = AnswerCheck(AnswerProblem.UNSURE)
          }
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
              // The round starts a fresh conversation: without the question the model only saw its
              // own answer and replied "please provide the question".
              append("My question:\n${(topic ?: display).take(600)}\n\n")
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
        if (resetChat || shown !== target) gemma.startChat(history(lines))
        // Pictures are found after the answer, one at a time; never while talk mode is speaking.
        if (!voice) findFigures = answerId to (topic ?: display)
        if (testCode) fixCode = testAnswer(answerId, codeFixed)
      } catch (t: Throwable) {
        update(answerId) { it.copy(done = true, error = friendlyError(t)) }
      } finally {
        update(answerId) { if (it.done) it else it.copy(done = true) }
        busy = false
        status = null
        save(target)
        lineOf(answerId, target)?.let { onAnswerDone?.invoke(it) }
        notifyReady(target, answerId)
        val err = fixCode
        val fig = findFigures
        when {
          // The code crashed: ask once for a fix, and test the fixed code too (same as agent.py _check_code).
          err != null -> sendToModel(
            "🔧 Fix the code (automatic)",
            "I ran your code and it failed:\n${err.takeLast(1200)}\nGive the corrected full code in one ```javascript block. Keep it short.",
            testCode = true,
            codeFixed = true,
            target = target,
          )
          // Questions are waiting: they go first, the picture search queues after them.
          fig != null && queue.any { !it.figures } -> {
            queue.add(Queued(target, fig.first, null, figures = true) { startFigures(fig.first, fig.second, ref = target) })
            endRun(target)
          }
          fig != null -> startFigures(fig.first, fig.second, ref = target)
          else -> endRun(target)
        }
      }
    }
  }

  /** What a figure card shows. Saved chats show cached pictures instantly and never re-check them. */
  fun figState(answer: ChatLine, n: Int, query: String): FigState? {
    val k = "$chatId|${answer.id}|$n"
    figStates[k]?.let { return it }
    if (!answer.done) return null // still streaming: "comes when the answer is done"
    val used = (1 until n).mapNotNull { (figStates["$chatId|${answer.id}|$it"] as? FigState.Done)?.figure?.file }
    val hit = figures.cached(query, used)
    // Not stored here: this runs while the screen draws (the screen remembers it).
    return if (hit != null) FigState.Done(hit) else FigState.None("no picture saved yet", retry = true)
  }

  /** Finds pictures for every figure in an answer (after it finishes). */
  private fun startFigures(answerId: Long, question: String, only: Int? = null, ref: ChatRef = work) {
    val answer = lineOf(answerId, ref)
    if (answer == null || answer.text.isBlank() || answer.text.contains("```")) {
      if (running === ref) endRun(ref)
      return
    }
    running = ref
    busy = true
    runningFigures = true
    figuresPreempted = false
    status = "Finding a picture…"
    job = scope.launch {
      var usedModel = false
      try {
        var queries = Tutor.figureQueries(answer.text)
        if (queries.isEmpty() && answer.text.length > 100) {
          // Model-driven fallback: one short call decides whether a picture would help.
          status = "Looking for a picture…"
          val marker = runCatching {
            gemma.oneShot(
              "A student asked: ${question.take(300)}\nThe answer:\n${answer.text.take(1600)}\n\n" +
                "If one diagram or picture would clearly help this answer, reply with exactly one line " +
                "like [FIGURE: photosynthesis diagram], replacing the words with this topic's English " +
                "search words. Otherwise reply NO.",
              40,
            )
          }.getOrDefault("")
          usedModel = true
          val tag = Tutor.figureQueries(marker).firstOrNull()
          if (tag != null) {
            val withTag = answer.text.trimEnd() + "\n\n[FIGURE: $tag]"
            update(answerId) { it.copy(text = withTag) }
            save()
            queries = listOf(tag)
          }
        }
        if (queries.isEmpty()) return@launch
        Log.i("Figures", "start id=$answerId queries=$queries")
        val used = mutableListOf<String>()
        for ((i, q) in queries.withIndex()) {
          val n = i + 1
          val k = "${ref.id}|$answerId|$n"
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
        if (figuresPreempted) {
          // A new question took the model: show the cards as waiting and search again afterwards.
          figStates.keys.filter { it.startsWith("${ref.id}|$answerId|") && figStates[it] is FigState.Busy }
            .forEach { figStates[it] = FigState.Busy("Waiting for the next answer to finish…") }
          queue.add(Queued(ref, answerId, null, figures = true) { startFigures(answerId, question, only, ref) })
        } else {
          figStates.keys.filter { it.startsWith("${ref.id}|$answerId|") && figStates[it] is FigState.Busy }
            .forEach { figStates[it] = FigState.None("stopped", retry = true) }
        }
        throw t
      } finally {
        // The picture checks used the model on their own; give the chat its memory back.
        withContext(kotlinx.coroutines.NonCancellable) { if (usedModel) gemma.startChat(history(lines)) }
        Log.i("Figures", "finish id=$answerId")
        busy = false
        status = null
        endRun(ref)
      }
    }
  }

  /** One figure: search, download, let Gemma check it (YES/NO + caption), up to 3 tries. */
  private suspend fun findOne(k: String, q: String, question: String, used: List<String>, onModel: () -> Unit): Figure? {
    val started = System.currentTimeMillis()
    figStates[k] = FigState.Busy("Searching for a picture of “$q”…")
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
    Log.i("Figures", "find '$q': ${cands.size} candidates, ${System.currentTimeMillis() - started}ms")
    if (cands.isEmpty()) { figStates[k] = FigState.None("No free picture found for this.", retry = true); return null }
    var tried = 0
    for (c in cands) {
      if (tried >= Figures.MAX_TRIES) break
      figStates[k] = FigState.Busy("Downloading picture ${tried + 1}…")
      val td = System.currentTimeMillis()
      val file = runCatching { figures.download(c) }.getOrNull() ?: continue
      tried++
      Log.i("Figures", "download '${c.file}': ${System.currentTimeMillis() - td}ms")
      var caption = ""
      if (gemma.canSee) {
        figStates[k] = FigState.Busy("Checking picture $tried of up to ${Figures.MAX_TRIES}…")
        onModel()
        val tv = System.currentTimeMillis()
        val verdict = runCatching {
          Figures.parseVerdict(gemma.oneShot(Figures.checkPrompt(question, q, c), 260, file.readBytes()))
        }.getOrDefault(null to "")
        Log.i("Figures", "vision check $tried: ${System.currentTimeMillis() - tv}ms")
        if (verdict.first == false) { file.delete(); continue }
        caption = verdict.second
      }
      if (caption.isBlank()) caption = c.desc.ifBlank { c.title } // no caption: the file's own words
      if (c.file.isNotEmpty() && c.license.isEmpty()) {
        figStates[k] = FigState.Busy("Getting its source…")
        val tl = System.currentTimeMillis()
        withContext(Dispatchers.IO) { figures.fileLicense(c) }
        Log.i("Figures", "licence '${c.file}': ${System.currentTimeMillis() - tl}ms")
      }
      val f = Figure(q, file.path, caption.take(600), c.title, c.page, c.file, c.license, c.credit, gemma.canSee)
      figures.save(f)
      Log.i("Figures", "saved '$q' total ${System.currentTimeMillis() - started}ms")
      return f
    }
    figStates[k] = FigState.None("None of the pictures found matched well enough.", retry = true)
    return null
  }

  /** "🖼️ Find picture" on one figure card. */
  fun findPicture(answerId: Long, n: Int) {
    if (!canSend()) return
    val q = lines.subList(0, indexOf(answerId, shown).coerceAtLeast(0)).lastOrNull { it.who == Who.USER }?.let { it.topic ?: it.display } ?: ""
    startFigures(answerId, q, only = n, ref = shown)
  }

  /**
   * App-run research for a photographed page: the model writes one search query for exactly what
   * the user asked, the app searches and reads the top result. Returns the evidence text ("" when
   * nothing usable came back). Shown as tool chips like any other tool call.
   */
  private suspend fun researchPage(
    answerId: Long, page: String, ask: String, question: String, found: MutableList<Source>,
  ): String {
    // Two small steps a small model can do. Step 1: copy the page lines the question is about (it
    // may point at them by number or position). Step 2: a search phrase for that content. The app
    // only passes the copied text along; it never interprets the reply.
    status = "Finding what the question is about…"
    val about = runCatching {
      gemma.oneShot(
        "Page text (each line tagged with its position):\n${page.take(1500)}\n\nMy instruction: ${ask.take(300)}\n\n" +
          "Copy, word for word, the page lines that hold what my instruction's question is about, " +
          "including any lines the question points to by number or position. Reply with those lines only.",
        160,
      ).trim()
    }.getOrDefault("")
    Log.i("ChatController", "page research about='${about.take(200)}'")
    status = "Planning what to look up…"
    val raw = runCatching {
      gemma.oneShot(
        "My instruction: ${ask.take(300)}\n\n" +
          (if (about.isNotBlank()) "It is about these lines from a photographed page (the photo reading may have garbled some characters):\n${about.take(800)}\n\n"
          else "Page text:\n${page.take(1200)}\n\n") +
          "Write one short English web search query (3 to 8 words) that names the subject itself, so " +
          "that a search engine, which cannot see the page, finds the facts needed. Reply with the query only.",
        40,
      )
    }.getOrDefault("")
    // oneShot closed the chat; reopen it without the turn being answered now.
    gemma.startChat(history(work.lines.dropLast(2)))
    val query = raw.lineSequence().map { it.trim().trim('"', '`', '*', '\'').trim() }
      .firstOrNull { it.isNotBlank() }.orEmpty()
      .let { l -> if (l.indexOf(':') in 0..24) l.substringAfter(':').trim().trim('"', '\'') else l }
      .take(120)
    Log.i("ChatController", "page research query='$query'")
    status = null
    if (query.isBlank()) return ""
    val before = found.size
    val results = runTool(answerId, Tools.Call("web_search", mapOf("query" to query)), question, found)
    if (results.isBlank() || results.startsWith("Error") || results.startsWith("No results")) return ""
    // The first result of THIS search (found may already hold sources from earlier).
    val top = found.drop(before).firstOrNull { it.url.startsWith("http") }
    val pageText = top?.let {
      runTool(answerId, Tools.Call("web_fetch", mapOf("url" to it.url, "query" to query)), question, found)
    }.orEmpty()
    val usablePage = pageText.isNotBlank() && !pageText.startsWith("Error") &&
      !pageText.startsWith("The page came back almost empty")
    return buildString {
      // Tell the answer step which page lines the question is about.
      if (about.isNotBlank()) append("The question is about these page lines:\n").append(about.take(800)).append("\n\n")
      append("Tool result for web_search:\n").append(results.take(1000))
      if (usablePage) append("\n\nTool result for web_fetch (${top?.url}):\n").append(pageText.take(1400))
    }
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
    val answer = lineOf(answerId)?.text ?: return null
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
    work.lines.lastOrNull()?.let { last -> if (!last.done) update(last.id) { it.copy(done = true) } }
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
    while (shown.lines.size > lastUser) shown.lines.removeAt(shown.lines.lastIndex)
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
    val past = work.lines.dropLast(2).filter { (it.who == Who.USER || it.who == Who.MODEL) && it.done && it.error == null }
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
    val i = indexOf(answerId, shown)
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
    val line = lines.getOrNull(indexOf(answerId, shown)) ?: return
    if (line.demoError != null) return // already handled (the WebView may reload when scrolled)
    // Fix on its own only while this reply is still the newest one; never interrupt a later chat (a quiz).
    val newest = lines.lastOrNull { it.who == Who.MODEL }?.id == answerId
    val auto = newest && autoFixes < 1
    update(answerId, shown) { it.copy(demoError = error, autoFixing = auto) }
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
    val line = lines.getOrNull(indexOf(answerId, shown)) ?: return
    if (!canSend()) return
    sendToModel("🔧 Fix this demo", Tutor.fixPrompt(line.demoError ?: "", Tutor.firstDemo(line.text)))
  }

  /** Developer builds only: drop a canned tutor answer in, to test demos without waiting for the model. */
  fun injectTest(kind: String) {
    shown.lines.add(ChatLine(newLineId(shown), Who.USER, "Show me a pendulum demo", "Show me a pendulum demo (test)"))
    shown.lines.add(ChatLine(newLineId(shown), Who.MODEL, if (kind == "net") TestDemos.netProbe() else TestDemos.reply(kind == "broken")))
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
      append("[The text below was read from my picture by on-device OCR, line by line; the app tagged ")
      append("each line with its position as [Line 1], [Line 2], … OCR makes mistakes: words can be ")
      append("misread, and small raised or lowered characters, symbols and printed numbering are often ")
      append("wrong. Silently repair what the context makes clear. Never copy garbled text into your ")
      append("answer: write it correctly, or describe it in words if you are not sure. When a question ")
      append("points to part of the page by number or position, count the lines it means by their ")
      append("position, because the printed numbering may be misread. If the page holds several ")
      append("questions, answer only the one I ask for, or each separately if I ask for all. Start each ")
      append("answer with one sentence that answers it directly, then explain the main reason in a few ")
      append("sentences; add a formula, example, steps or code only when the question needs it. Stay on ")
      append("what was asked. For an explanatory answer, add one line [FIGURE: English search words] ")
      append("where a picture would help. Write in one language only (technical names in brackets are ")
      append("fine). Do not say that no picture was attached.]")
    }

    /** After an OCR answer: a generic correctness pass. */
    internal fun reviewRule(withWeb: Boolean = false): String = buildString {
      if (withWeb) {
        append("Check every fact in your previous answer against the web results above. ")
        append("Where they disagree, the web results win. Name the source you used. ")
      }
      append("Rewrite your previous answer, correcting any factual mistake. Do not comment on this ")
      append("request or mention that you are checking; output only the corrected answer. Keep every ")
      append("part the user asked for: if the previous answer covered every part, the corrected answer ")
      append("must cover all of them and never drop one. If my question asked for only one part, keep ")
      append("only that part and do not add the others. Check the answer against the page and the ")
      append("sources: names, numbers, units and any formula or code, symbol by symbol. An example must ")
      append("not contradict the definition. If the question asks for a comparison, say which one and ")
      append("why. Do not invent names, data or formulas. Keep any [FIGURE: ...] marker and ")
      append("write its search words in English. Write in the same language as your previous answer, ")
      append("one language only, and keep it about as short as before. ")
    }

    /** Added to an answer the time limit cut off, so it is never passed off as complete. */
    const val STOPPED_EN = "(This answer was stopped because it took too long. Ask again for the rest.)"
    const val STOPPED_BN = "(উত্তরটি বেশি সময় নেওয়ায় এখানে থামানো হয়েছে। বাকি অংশের জন্য আবার জিজ্ঞাসা করুন।)"

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
        // Indic scripts other than Bengali; the danda । and double danda ॥ are Bengali's full stops.
        (code !in 0x0900..0x0DFF || code == 0x0964 || code == 0x0965) &&
        code !in 0x0E00..0x0E7F && // Thai
        code !in 0x10A0..0x10FF && // Georgian
        code !in 0x1100..0x11FF && // Hangul Jamo
        code !in 0x3040..0x30FF && // Japanese kana
        code !in 0x3400..0x4DBF && code !in 0x4E00..0x9FFF && // Chinese/Japanese kanji
        code !in 0xAC00..0xD7AF    // Hangul
      )
  }

