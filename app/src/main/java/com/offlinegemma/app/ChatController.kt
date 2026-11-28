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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
  /** The user's own study material is installed on this phone. */
  var knowledgeReady by mutableStateOf(false); private set

  /**
   * The last photo was read poorly (Tesseract's own confidence was low). The answer still comes, but
   * the screen says so plainly, because a bad scan is the most common reason an answer is wrong.
   */
  var lowReadQuality by mutableStateOf(false); private set
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
    knowledgeReady = Knowledge.isInstalled(app)
    warmSemantic()
  }

  /**
   * Builds (or loads) the semantic index in the background. Indexing the whole pack takes minutes
   * on a CPU embedder, so it must never block a turn: until it is ready, search stays on BM25.
   */
  private fun warmSemantic() {
    if (!Semantic.isDownloaded(app) || !Knowledge.isInstalled(app)) return
    scope.launch {
      val ok = runCatching { Semantic.load(app) }.getOrDefault(false)
      Log.i("ChatController", "semantic index ready=$ok (${Semantic.progress ?: "idle"})")
      // Missing or stale (a pack was rebuilt): build or resume it in the background, where a swipe
      // away does not throw the work out and the next launch carries on.
      if (!ok) SemanticIndexWorker.start(app)
    }
  }

  /** Called when the embedder download finishes, so search upgrades without a restart. */
  fun buildSemanticIndex() = warmSemantic()

  /** After the pack is imported or removed in Settings. */
  fun refreshKnowledge() {
    knowledgeReady = Knowledge.isInstalled(app)
    // A new pack invalidates the vectors; prepare() notices the stamp changed and re-indexes.
    warmSemantic()
  }

  /**
   * Called when the app comes back to the front. A pack replaced on disk (copied over USB, restored
   * from a backup) changes its stamp without going through Settings, so compare and re-index if so.
   * While a build runs, its own progress is the guard: nothing is queued twice.
   */
  fun refreshKnowledgeIfStale() {
    if (Semantic.matchesCurrentPack(app)) return
    if (Semantic.progress != null) return
    if (!Semantic.isDownloaded(app) || !Knowledge.isInstalled(app)) return
    warmSemantic()
  }

  /** After the user changes the thinking setting, so it applies to the next answer. */
  fun onThinkingChanged() {
    if (!ready || busy) return
    chatReset = scope.launch { gemma.startChat(history(shown.lines)) }
  }

  private val SELF_INTRO = Regex(
    "(?i)^\\s*jonaki a[i1]\\s*[,—-]?\\s*made by monarul[^\\n]*(\\.|।)\\s*",
  )

  /**
   * Drops a self-introduction the model prepended to an unrelated answer, and any reasoning
   * channel text that leaked into a reply. Reasoning must never be shown or spoken.
   */
  private fun pruneReasoning() {
    for (ref in listOfNotNull(shown, running)) {
      for (i in ref.lines.indices) {
        val line = ref.lines[i]
        if (line.who != Who.MODEL) continue
        var text = line.text
        text = SELF_INTRO.replace(text, "")
        // A reasoning block that slipped through as plain text.
        text = text.replace(Regex("(?s)<\\|channel>thought\\n.*?<\\|channel\\|>"), "").trim()
        if (text != line.text) ref.lines[i] = line.copy(text = text)
      }
    }
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

  /** Removes just the chosen chats (the chat being answered is skipped, like deleteChat). */
  fun deleteChats(ids: Collection<String>) {
    if (ids.isEmpty()) return
    val live = ids.filter { it != running?.id }
    if (live.size != ids.size) {
      val j = job
      stop()
      scope.launch { j?.join(); lastSave?.join(); deleteChats(live) }
      return
    }
    live.forEach { store.delete(it) }
    if (chatId in live) newChat()
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
      withContext(Dispatchers.Main) { refreshChats(); pruneReasoning() }
    }
  }

  /** What the model should remember: user and model turns only (notices are for the screen). */
  private fun history(from: List<ChatLine>): List<Message> = from.filter { it.done && it.error == null }.mapNotNull {
    when (it.who) {
      // A spoken turn stores the ~700 characters of *instructions* the model was given ("you can
      // hear audio", the answer rules), because that is what it had to be sent. Replaying them as a
      // user message is what filled a voice chat's context with English meta-text that the user never
      // said; the clip itself is not in the rebuilt conversation either, so only a marker is honest.
      Who.USER -> Message.user(
        when {
          it.attachments.any { a -> a.kind == Attachment.Kind.AUDIO } ->
            "(the user said this out loud: a voice clip, not stored in this conversation's history)"
          it.attachments.any { a -> a.kind != Attachment.Kind.DOC } ->
            "${it.text}\n[an image/audio clip was attached earlier]"
          else -> it.text
        },
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
    // A spoken clip is turned into an ordinary text message here, before anything else looks at it.
    // The words are the message; the recording rides along only so it can be played. This matters
    // beyond tidiness: the planner is skipped when a turn carries attachments, so while the words
    // lived in the attachment a spoken question could never reach it — which is how a question about
    // who holds an office was answered from a frozen model, ungated and without a search.
    val heard = if (!voice || text.isNotBlank()) "" else transcribeFirst(atts)
    val said = text.ifBlank { heard }
    val isVoiceText = voice && said.isNotBlank()
    // Creator/identity facts should never be left to a small model to guess.
    if (atts.none { it.kind != Attachment.Kind.AUDIO } && !busy) {
      Tutor.identityAnswer(said, prefs.answerBengali || hasBengali(said))?.let { reply ->
        answerIdentity(said, reply, isVoiceText)
        return
      }
    }
    autoFixes = 0
    draft.clear()
    val docs = atts.filter { it.kind == Attachment.Kind.DOC }
      .joinToString("\n\n") { Attachments.docForModel(it.name, docTexts.remove(it.path) ?: "") }
    val ask = said.ifBlank {
      if (isVoiceText) "(I said this out loud. Reply to it directly, briefly, as in a spoken conversation. " +
        "If the clip has no understandable speech — silence, noise, or you cannot tell what was said — " +
        "reply with exactly: Sorry, I didn't catch that.)" else "(see attachment)"
    }
    val hasImage = atts.any { it.kind == Attachment.Kind.IMAGE }
    val rules = inLanguage(Tutor.wrap(ask, prefs.demos, codeProblem = Tutor.looksLikeCodeProblem(ask, hasImage), voice = voice))
    val withDocs = if (docs.isEmpty()) rules else "$docs\n\n$rules"
    val hasAudio = atts.any { it.kind == Attachment.Kind.AUDIO }
    val modelText = if (hasAudio) "(${GemmaEngine.VOICE_RULE})\n\n$withDocs" else withDocs
    val display = said.ifBlank { if (isVoiceText) "Voice message" else "" }
    val codeProblem = Tutor.looksLikeCodeProblem(ask, hasImage)
    val online = Tools.online(app)
    // A link the user typed, or words about recent events, always goes to the web tools —
    // even when agent mode is off (agent.py find_links / ROUTES "web").
    val links = if (said.isNotBlank() && prefs.webSearch) Tools.findLinks(said) else emptyList()
    val wantsWeb = said.isNotBlank() && (links.isNotEmpty() || Tools.wantsWeb(said))
    // Offer the tools whenever agent mode, web search and internet are all on. The model decides
    // whether to search: a keyword gate here hid the tools from exactly the chemistry/physics
    // questions that need research, so the model could not choose to look anything up.
    var agent = prefs.agent && prefs.webSearch && !codeProblem && online
    val offline = said.isNotBlank() && !online
    if (offline && wantsWeb) {
      notice("📴 You're offline — live information (weather, news, prices) isn't available.")
    }
    sendToModel(
      display, modelText, topic = said.ifBlank { null }, attachments = atts, voice = isVoiceText,
      testCode = codeProblem, agent = agent, links = links, linkQuery = Tools.linkQuery(said), offline = offline,
    )
  }

  /**
   * What a voice clip said, written down, before anything else processes the turn.
   *
   * Runs in a throwaway conversation, so the chat it interrupted is reopened afterwards. Empty when
   * the clip had no clear speech, which leaves the turn as a plain voice message.
   */
  private fun transcribeFirst(atts: List<Attachment>): String {
    // A supplied transcript, so a spoken turn can be compared with the same question typed.
    forcedSpeech?.let { return it }
    val clip = atts.firstOrNull { it.kind == Attachment.Kind.AUDIO } ?: return ""
    val bytes = readBytes(clip) ?: return ""
    val said = runCatching {
      kotlinx.coroutines.runBlocking {
        gemma.oneShot(TRANSCRIBE_PROMPT, maxOutput = 80, audio = bytes)
      }
    }.getOrDefault("").trim()
    // oneShot closed the open conversation; rebuild it without this still-unanswered turn.
    kotlinx.coroutines.runBlocking {
      gemma.startChat(history(shown.lines.dropLast(0)).dropLast(0))
    }
    Log.i("ChatController", "voice clip says: '${said.take(120)}'")
    return said.takeIf { it.length in 2..200 && !it.equals("nothing", ignoreCase = true) } ?: ""
  }

  /** A fixed, accurate answer to "who are you / who made you". */
  private fun answerIdentity(ask: String, answer: String, voice: Boolean) {
    val target = shown
    target.lines.add(ChatLine(newLineId(target), Who.USER, ask, ask))
    val id = newLineId(target)
    val line = ChatLine(id, Who.MODEL, answer, done = true)
    target.lines.add(line)
    save(target)
    onAnswerStart?.invoke(id, voice)
    onAnswerText?.invoke(id, answer)
    onAnswerDone?.invoke(line)
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
    val begin = { runAnswer(target, answerId, display, modelText, topic, rebuild, sources, attachments, voice, testCode, codeFixed, agentAllowed = agent, links, linkQuery, offline) }
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
    agentAllowed: Boolean,
    links: List<String>,
    linkQuery: String,
    offline: Boolean,
  ) {
    running = target
    busy = true
    // When the user's message arrived, before the queue and the model load: the number a reader
    // experiences as "how long did that take".
    val enteredAt = System.currentTimeMillis()
    showProgress(target)
    // What the model should treat as "my question". A spoken turn now carries its words as the
    // message, so they are used directly; the old placeholder ("the spoken message I just sent") is
    // only right when nothing could be heard from the clip at all.
    val asked = if (voice) display.ifBlank { "the spoken message I just sent" } else display
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
          var worst = 100
          ocrText = imageAtts.mapNotNull { a ->
            val text = Ocr.readText(app, a.ocrPath ?: a.path)
            if (Ocr.lastConfidence >= 0) worst = minOf(worst, Ocr.lastConfidence)
            text.takeIf { it.length >= 12 }
          }.joinToString("\n\n")
          lowReadQuality = worst < 55
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
        pageFocus = ""
        val research = if (ocrText.isNotBlank() && prefs.webSearch && Tools.online(app)) {
          researchPage(answerId, ocrText, modelText, topic ?: asked, found)
        } else ""

        val skippedAudio = attachments.count { it.kind == Attachment.Kind.AUDIO } - audio.size
        Log.i(
          "ChatController",
          "turn: voice=$voice images=${images.size} audio=${audio.size}/" +
            "${attachments.count { it.kind == Attachment.Kind.AUDIO }} skipped=$skippedAudio",
        )
        // The user's own study material (Biddabari/Uttoron/NCTB/BPSC) searched on the phone.
        // Only the best few passages go in, so a 4k-token window still has room to answer.
        // A spoken turn is searched the same way as a typed one: its transcript is now the message
        // (see transcribeFirst), so `topic` carries the words and nothing special is needed here.
        // Asking the same thing out loud used to skip the study files entirely, which read as the app
        // ignoring what you had fed it.
        var knowledge = ""
        val searchFor = when {
          topic != null -> topic
          !voice -> modelText      // typed with no words of its own (attachment only), as before
          else -> ""
        }
        if (searchFor.isNotBlank()) {
          val q = searchFor.take(400)
          // A sum must not be handed chemistry prose: the passage has to carry a number to be
          // relevant to arithmetic at all.
          val numericHint = Tutor.looksNumeric(searchFor)
          // Semantic search first: an absolute cosine gate (0.70), so an unrelated page is dropped
          // however good it looked beside the others. BM25 is the fallback when the embedder or its
          // index is not ready, never a second opinion after the gate rejected everything.
          val semantic = if (Semantic.isReady) {
            runCatching { Semantic.search(app, q, Semantic.LIMIT) }.getOrDefault(emptyList())
              .map { it.passage }
          } else null
          // BM25 has only a *relative* floor, so it always returns its best few candidates. For a
          // spoken turn that would inject unrelated passages whenever the clip says nothing on topic;
          // there, only a passage the semantic gate accepted counts.
          val rawHits = semantic
            ?: if (voice) emptyList()
            else runCatching { Knowledge.search(app, q, KNOWLEDGE_PASSAGES) }.getOrDefault(emptyList())
          // The evidence rules apply to both paths. BM25's floor is relative to its own best hit, so
          // with every candidate weak the best of them still passed — that is how chemistry prose
          // reached a ratio sum. For a numeric question the passage must at least carry a number,
          // and preferably the question's own most specific one (9800, not just any digit).
          fun hasDigit(p: Knowledge.Passage) = p.text.any { c -> c in '0'..'9' || c in '০'..'৯' }
          val keyNumber = numbersOf(searchFor).maxByOrNull { it.length }
          val hits = when {
            !numericHint -> rawHits
            keyNumber != null -> rawHits.filter {
              MathCheck.asciiDigits(it.text).contains(keyNumber)
            }.ifEmpty { rawHits.filter(::hasDigit) }
            else -> rawHits.filter(::hasDigit)
          }
          if (hits.isNotEmpty()) {
            knowledge = buildString {
              // "Say it comes from my study files" was an instruction to *claim*, not to check: a
              // question the pack cannot answer still got "according to your study files" over a
              // passage that had only a word in common (Tajuddin Ahmad for Tareq Rahman). Now the
              // credit is conditional on the passage actually containing the fact.
              append("[Reference material from my own study files, copied onto this phone. ")
              append("Use a passage ONLY if it actually states what was asked. If the passages do ")
              append("not contain the answer, ignore them completely and answer from your own ")
              append("knowledge instead. Only when you take a fact from a passage may you say it ")
              append("comes from my study files; if you did not, never mention my study files at ")
              append("all. Never credit a website or book that is not in the list, and do not ")
              append("invent a source.]\n")
              hits.forEach { h ->
                append("\n<${h.source} · ${h.title}>\n")
                append(h.text.take(KNOWLEDGE_CHARS))
                append("\n")
              }
            }
            update(answerId) {
              val where = hits.map { h -> h.source }.distinct().joinToString(", ")
              it.copy(tools = it.tools + ToolUse("knowledge", where, true, hits.first().text.take(400)))
            }
            Log.i("ChatController", "knowledge: ${hits.size} passages (${if (semantic != null) "semantic" else "bm25"}) for '${q.take(80)}'")
          }
        }
        // The model decided this is a pure calculation, so the study passages were never the
        // evidence for it. Drop them: a ratio worked out from the question needs no prose, and
        // unrelated material only gives the model something to copy from.
        var pureSum = false

        // A typed question is looked up first, like a search engine's AI mode: the model says what
        // to search for (or that no search would help), the app reads the best few pages at once,
        // and the answer cites them by number. Photos, files, links and voice have their own paths.
        // One short planning step also says whether the answer needs a calculation: a small model
        // gets sums wrong while sounding sure (17% of 2350 came out as 409.5), so the phone works
        // the number out exactly and the answer uses it. Offline too: the calculator needs no internet.
        val numbered = mutableListOf<Source>()
        var calcNote = ""
        var choices = false
        var numericQ = false
        // The sum the planner asked the phone to compute, if any. When this is set the number is
        // already exact, so a reasoning pass over it would only cost time.
        var planForCalc: String? = null
        val canSearch = !offline && prefs.webSearch && Tools.online(app)
        // The long, silent part of a spoken turn starts here: plan, search, first token. Firing this
        // from the answer job (not from the microphone) means the same path is exercised by the
        // voice_clip test hook and by really speaking.
        if (voice) onPreparing?.invoke(answerId)
        // A spoken turn's words are now the message itself, so it is planned exactly like a typed
        // one. The gate used to be `attachments.isEmpty()`, which no voice turn could ever satisfy
        // (it always carries its clip), so a spoken question was never planned, never searched, and
        // was answered from a model trained before the answer changed.
        val blocksPlan = attachments.any { it.kind != Attachment.Kind.AUDIO }
        val webBlock = if (topic != null && !blocksPlan && links.isEmpty() && !testCode) {
          if (Rules.skipPlanner(topic, canSearch)) {
            // The message already settles what the planner would have been asked for, so its
            // 6.5 s round-trip is skipped. Options are read straight off the message.
            choices = Tools.optionsOf(topic).size >= 2
            // With no planner there is no calculator expression, so this is the local hint only.
            numericQ = Tutor.looksNumeric(topic, null)
            Log.i("ChatController", "plan skipped (nothing to decide): choices=$choices numeric=$numericQ")
            ""
          } else {
          val plan = planAnswer(topic, canSearch, spoken = voice)
          choices = plan.choices
          planForCalc = plan.calc
          // Record the decision itself: it is the step a reader most wants to see ("why did it
          // search that?"). Ordered before the tool calls it caused.
          val decided = buildList {
            plan.query?.let { add("search: “$it”") }
            plan.calc?.let { add("calculate: $it") }
            if (plan.choices) add("pick from my options")
          }
          update(answerId) {
            it.copy(
              tools = listOf(
                ToolUse(
                  "plan",
                  if (decided.isEmpty()) "answer directly" else decided.joinToString(" · "),
                  true,
                ),
              ) + it.tools,
            )
          }
          // A sum the model has to set up itself (a word problem) is where a small model invents
          // arithmetic. That is also the case thinking helps, so it is what the gate keys off.
          numericQ = Tutor.looksNumeric(topic, plan.calc)
          // A pure calculation is worked out, not looked up. The planner decided this needs
          // arithmetic, so the retrieved prose is not the evidence for it and would only give the
          // model something to copy the final number from.
          if (numericQ && plan.calc != null) {
            pureSum = true
            if (knowledge.isNotBlank()) {
              knowledge = ""
              update(answerId) { line ->
                line.copy(tools = line.tools.filterNot { t -> t.name == "knowledge" })
              }
              Log.i("ChatController", "study passages dropped: this is a calculation")
            }
          }
          // The planner invents coefficients: for a 2:3:4:5 share it wrote "(9800 / 19) * 5", and a
          // number like 19 appears nowhere in the question. Handing that to the model as "verified"
          // is worse than no calculator at all, so an expression may only use numbers the question
          // itself gave (percentages bring their own 100).
          plan.calc?.let { expr ->
            if (groundedCalc(topic ?: "", expr)) calcNote = calculate(answerId, expr)
            else Log.i("ChatController", "calc dropped, not grounded in the question: $expr")
          }
          // A sum needs the calculator, not the web: searching a word problem returned
          // calculator.net and "long division" pages, which only added noise the model then
          // tried to reconcile with its own working.
          if (canSearch && plan.query != null && !numericQ) {
            researchWeb(answerId, topic, plan.query, numbered, found, if (knowledge.isNotBlank()) 1300 else 2100)
          } else ""
          }
        } else ""
        // Thinking costs a lot of time on a phone (~5 tokens/s), so it is opt-in and only for
        // questions with numbers in them, never for voice or a plain chat turn. It is switched on
        // the message itself (`gemma.send(..., think = ...)`), not by rebuilding the conversation,
        // so the history is not re-read just to reason about one question.
        //
        // Skipped when the phone has already computed the sum exactly: reasoning over a number it
        // was handed adds nothing, and on E4B a reasoning pass is 158 s against 45 s for the same
        // answer. It is kept for word problems, where the model has to build the set-up itself and
        // that set-up is what a small model gets wrong.
        var thinkNow = numericQ && prefs.thinking && !voice && planForCalc == null
        // Reasoning streams into the work panel as it is produced, so the user can watch the
        // model think instead of staring at a spinner. Throttled: every piece would redraw the list.
        gemma.onThought = { piece -> if (voice) Unit else update(answerId) { it.copy(thought = gemma.lastThought.toString()) } }
        // With sources already in hand, the tool-calling instructions only cost room and time.
        val agent = agentAllowed && webBlock.isBlank()
        val text = buildString {
          // A spoken turn's words are already the message text (see transcribeFirst), so there is
          // nothing to re-state here.
          append(modelText)
          // Verified numbers come first: a note placed after a page of study material was ignored.
          if (calcNote.isNotBlank()) append("\n\n").append(calcNote)
          if (knowledge.isNotBlank()) append("\n\n").append(knowledge)
          if (webBlock.isNotBlank()) {
            append("\n\n").append(webBlock)
            append("\n\n[Now answer exactly what my message above asks, using these sources. Right after each fact you took ")
            append("from a source, put its number in brackets, like [1]. If the sources do not cover ")
            append("something, say so in one short sentence and answer that part from your own knowledge. ")
            append("Never invent a source or a number. Do not add a list of sources or links at the end: ")
            append("the app shows them under your answer.]")
          }
          if (numericQ) {
            // A number with no work behind it cannot be checked, and a small model's number is
            // wrong often enough that the steps are the point, not decoration.
            append("\n\n[This question needs a calculation. Show every step with its numbers, then ")
            append("state the final answer. Never answer with just the result, and never pick a ")
            append("number because it appears among the options. ")
            // Two specific habits this model shows on rate/work word problems. Both were observed:
            // it "re-evaluated" and restated its own numbers until it was cut off mid-sentence, and it
            // repeated the question's figures ("4 men and 1010 boys").
            append("Work the answer out once. Do not re-check, re-evaluate or restate your own ")
            append("steps halfway through, and write each number once and correctly: copy the ")
            append("question's figures exactly rather than re-typing them. If the answer is a ")
            append("number of days, name it plainly.]")
          }
          if (choices) {
            // The planning step said the message gives options to choose from (an exam question).
            //
            // This instruction used to contradict itself: it said "start your answer with that
            // option" and then "show the reasoning before you name the option". The model obeyed the
            // second half, so answers opened with "**Calculation:**" or a paragraph of working, and
            // the option appeared on line 2 or later. That defeated the guard that repairs an answer
            // naming the wrong option, because the guard reads the opening line. The order is now
            // stated once, and it is the order an exam answer actually uses.
            append("\n\n[My message gives answer options. Pick exactly ONE of those options. Begin your ")
            append("answer with that option, written exactly as in my message (its label and text), then ")
            append("explain in two or three sentences why it is right and, briefly, why the others are not. ")
            append("The option must be the very first line, before any working: the reader is checking ")
            append("their answer sheet, not reading a derivation. ")
            // The failure this addresses: asked "logx= -2 হলে x এর মান কত?" (a question whose own
            // text is incomplete), the model silently rewrote it as "log_x(1/4) = -2" and then
            // answered the equation it had invented. Solving a question you changed is not solving
            // it. If a figure or symbol is missing, say so instead.
            append("Use the question's own numbers and equation exactly as written; do not introduce a ")
            append("value it does not contain. If the question is incomplete or unreadable, say that in ")
            append("one sentence instead of guessing what it meant. ")
            append("Do not answer with something that is not one of the options.]")
          }
          if (ocrText.isNotBlank()) {
            append("\n\n").append(ocrRule()).append("\n")
            if (images.isNotEmpty()) {
              append("[The picture itself is attached as well. Use it to check the layout and anything ")
              append("the text reading may have got wrong, such as printed numbering; for small print the ")
              append("text reading is usually more reliable.]\n")
            }
            // Only the lines the question is about, when the research step found them: a smaller
            // prompt answers faster and stays in one language.
            val focus = pageFocus.takeIf { it.isNotBlank() && it.length < ocrText.length }
            if (focus != null) append("(Only the page lines the question is about are shown.)\n").append(focus)
            else append(ocrText)
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
        compactIfNeeded(text, images.size * IMAGE_TOKENS + audio.sumOf { it.size } / 32000 * AUDIO_TOKENS_PER_SEC)
        val start = System.currentTimeMillis()
        onAnswerStart?.invoke(answerId, voice)
        // The agent loop (agent.py agent_chat): answer, or call a tool, read its result, and try again.
        var prompt = if (agent) "(${Tools.PROMPT})\n\n$text" else text
        if (offline) {
          val live = Tools.wantsWeb(topic ?: asked)
          prompt = "(${if (live) OFFLINE_LIVE_RULE else OFFLINE_RULE})\n\n$prompt"
        }
        // A link in the message is read BEFORE the model answers (agent.py pre-fetch). Small
        // models often skip the fetch and then claim they "can't browse".
        if (links.isNotEmpty() && !offline) {
          val pages = links.map { url ->
            "Tool result for web_fetch:\n" +
              runTool(
                answerId, Tools.Call("web_fetch", mapOf("url" to url, "query" to linkQuery)),
                topic ?: asked, found,
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
        var sourceSearchTried = webBlock.isNotBlank()
        var blankRetried = false
        var rewriting = false
        var resetChat = false
        var mathRepaired = false
        val usedOcr = ocrText.isNotBlank()
        // A Bengali question must get a Bengali answer; the model sometimes mixes in Tamil/Hindi.
        val bengaliAnswer = prefs.answerBengali || modelText.any { it in '\u0980'..'\u09FF' }
        while (true) {
          var out = ""
          try {
            // A multiple-choice answer is a label plus a few sentences. Left uncapped, E4B wrote a
            // full derivation for a one-option question: the NTRCA run took 409 s and 331 s on two
            // of them, at the ceiling of 2048 tokens. The question and options are already in the
            // prompt, so a tighter ceiling costs nothing but the rambling.
            val cap = if (choices) CHOICE_MAX_OUTPUT else null
            val flow = gemma.send(prompt, if (first) images else emptyList(), if (first) audio else emptyList(), think = thinkNow, maxTokens = cap)
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
          // A silent or noisy clip gets one plain answer and nothing else: there are no words to
          // search or rewrite. A clip whose words *were* understood is an ordinary question now, so
          // it goes through the same sources/rewrite path as a typed one.
          if (voice && asked == "the spoken message I just sent") {
            val spoken = out.trim()
            if (spoken.isBlank()) {
              val miss = "Sorry, I didn't catch that."
              update(answerId) { it.copy(text = miss) }
              onAnswerText?.invoke(answerId, miss)
            }
            break
          }
          // A thinking turn can end with reasoning only: the model writes its whole reply to the
          // thought channel and never starts the answer, so the visible text is empty. Seen after a
          // tool result ("7 × 8" → calculate → blank answer), and the empty-answer retry further
          // down kept thinking on and failed the same way. One retry with thinking off gets it out.
          if (out.isBlank() && thinkNow) {
            thinkNow = false
            status = null
            Log.i("ChatController", "thinking produced no answer; retrying without it")
            if (turn > 0) prompt = "Now answer my question using the tool results above."
            continue
          }
          if (agent) {
            val calls = Tools.parseCalls(out)
            if (calls.isNotEmpty() && turn < MAX_TOOL_TURNS) {
              turn++
              // The reasoning has already been paid for: what is left is turning the calculator's
              // result into a sentence, and reasoning over it only adds ~40 s on a phone.
              thinkNow = false
              val parts = mutableListOf<String>()
              for (c in calls) parts.add("Tool result for ${c.name}:\n" + runTool(answerId, c, topic ?: asked, found).take(2400))
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
          if (!refused && webBlock.isNotBlank() && out.isNotBlank() && Tools.isUnsure(out)) {
            refused = true
            update(answerId) { it.copy(text = "") }
            onAnswerStart?.invoke(answerId, voice) // restart the speech without the refusal
            status = "Reading the sources again…"
            prompt = "The numbered sources above are real web pages read for you just now, so you do have " +
              "this information. Answer my question from them: give the exact facts and figures they show, " +
              "each followed by its source number like [1]. If they truly do not contain the answer, say " +
              "which source to open and what it does show."
            continue
          }
          if (!refused && researchRounds == 0 && !reviewed && !offline && prefs.webSearch && webBlock.isBlank() &&
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
                Tools.Call("web_search", mapOf("query" to (topic ?: asked).take(200))),
                topic ?: asked, found,
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
            prompt = "My question:\n${(topic ?: asked).take(300)}\n\n" +
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
                "The user asked:\n${(topic ?: asked).take(400)}\n\nThe answer:\n${out.take(800)}\n\n" +
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
                    Tools.Call("web_search", mapOf("query" to (topic ?: asked).take(200))),
                    topic ?: asked, found,
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
                    Tools.Call("web_search", mapOf("query" to (topic ?: asked).take(200))),
                    topic ?: asked, found,
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
              append("My question:\n${(topic ?: asked).take(600)}\n\n")
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
          // Arithmetic the answer itself states is re-checked here. A small model writes
          // "2/14 × 9800 = 1142.86" (true value 1400) and then trusts its own wrong number, so a
          // wrong line gets exactly one repair pass with the correct value named.
          if (!mathRepaired && out.isNotBlank()) {
            val bad = runCatching { MathCheck.wrongClaims(out) }.getOrDefault(emptyList())
            if (bad.isNotEmpty()) {
              mathRepaired = true
              val lines = bad.take(3).joinToString("\n") {
                "${it.expression} = ${fmtNum(it.stated)} should be ${fmtNum(it.actual)}"
              }
              Log.i("ChatController", "math repair:\n$lines")
              update(answerId) {
                it.copy(tools = it.tools + ToolUse("calculate", "recheck", false, lines))
              }
              rewriting = true
              status = "Checking the arithmetic…"
              val prev = out.take(2500)
              gemma.startChat(emptyList(), "")
              resetChat = true
              prompt = "My question:\n${(topic ?: asked).take(600)}\n\n" +
                "Your previous answer:\n$prev\n\n" +
                "The arithmetic in it is wrong on these lines (computed by the phone):\n$lines\n\n" +
                "Fix those numbers in the answer. Keep it the SAME length or shorter: do not start " +
                "the working again and do not re-derive anything that was already correct, because a " +
                "longer answer will simply be cut off again, which is what happened above. Do not " +
                "mention this check, and do not restate the same wrong numbers. Write in the same " +
                "language as before."
              continue
            }
          }
          break
        }
        val secs = (System.currentTimeMillis() - start) / 1000.0
        // Where the wall time actually went, in one line. Asking "why was that answer slow?" used to
        // mean guessing between prefill, the planner, the tools and decode; now every step is named
        // with its own duration, so the slow one is identifiable instead of being blamed on "the model".
        run {
          val finishedAt = System.currentTimeMillis()
          val prepMs = start - enteredAt
          val steps = lineOf(answerId)?.tools.orEmpty().mapNotNull { t ->
            t.ms?.let { "${t.name}=${it}ms" }
          }
          val toolMs = lineOf(answerId)?.tools.orEmpty().sumOf { it.ms ?: 0L }
          val decodeMs = finishedAt - start - toolMs
          val answerChars = lineOf(answerId)?.text?.length ?: 0
          Log.i(
            "ChatController",
            "turn timing: prep=${prepMs}ms model=${decodeMs}ms tools=${toolMs}ms total=${finishedAt - enteredAt}ms " +
              "engine=${gemma.model.name} think=$thinkNow rounds=${turn + 1} chars=$answerChars " +
              "steps=[${steps.joinToString(" ")}]",
          )
        }
        // A multiple-choice answer must name ONE option, and the same one throughout. The model
        // opened with "(খ) অম্বর" and then argued for "অবনী" in the body, so the visible first line
        // contradicted the reasoning below it.
        if (choices && !testCode && !voice) {
          update(answerId) { line ->
            val fixed = Tools.settleChoice(line.text, topic ?: asked)
            if (fixed != line.text) {
              Log.i("ChatController", "choice settled: ${line.text.take(40)} -> ${fixed.take(40)}")
              line.copy(text = fixed)
            } else line
          }
        }
        if (!testCode) update(answerId) {
          // Never leave an empty bubble: a last-resort message is better than silence.
          val shown = if (it.text.isBlank()) {
            if (bengaliAnswer) "দুঃখিত, এই মুহূর্তে উত্তরটি তৈরি করা যায়নি। অনুগ্রহ করে আবার চেষ্টা করুন।"
            else "Sorry, I couldn't produce an answer. Please try again."
          } else it.text
          it.copy(text = shown)
        }
        update(answerId) {
          val shownSources = if (numbered.isNotEmpty()) numbered.toList() else found.distinctBy { s -> s.url }.take(4)
          it.copy(done = true, seconds = secs, sources = shownSources)
        }
        // Voice answers are spoken: never leave a picture marker (or a figure card) behind.
        if (voice) update(answerId) { line ->
          val clean = line.text.replace(Tutor.FIG_INLINE, "").replace(Regex("\\n{3,}"), "\n\n").trimEnd()
          if (clean != line.text) line.copy(text = clean) else line
        }
        // A review/verifier reset used a compact context; give the chat its full memory back.
        if (resetChat || shown !== target) gemma.startChat(history(lines))
        // Pictures are found after a typed answer, never in voice chat.
        if (!voice) findFigures = answerId to (topic ?: asked)
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
    runningFigures = true
    figuresPreempted = false
    // Deliberately NOT busy, and no status line. The answer is already written and read; fetching a
    // picture for it is background decoration, and marking it busy left a spinner over a finished
    // answer for as long as the picture search ran.
    job = scope.launch {
      var usedModel = false
      try {
        var queries = Tutor.figureQueries(answer.text)
        if (queries.isEmpty() && answer.text.length > 100) {
          // Model-driven fallback: one short call decides whether a picture would help.
          // No status line: the answer is already delivered, so the header must stay quiet.
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
          // The status line is the header's "what am I waiting for". This is not the answer, so it
          // stays empty and the progress shows on the figure card itself.
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
        runningFigures = false
        // This path no longer owns `busy`: it runs after the answer is delivered, and clearing it
        // here would also cut a real answer short if a new turn had started meanwhile.
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
  /** The page lines the research step found the question to be about ("" = use the whole page). */
  private var pageFocus = ""

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
          "Copy, word for word, the question my instruction asks about and the page lines it refers " +
          "to (by number or position). Reply with those lines only, each with its [Line N] tag.",
        160,
      ).trim()
    }.getOrDefault("")
    Log.i("ChatController", "page research about='${about.take(200)}'")
    pageFocus = about
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
      // Short on purpose: photo + whole page + long web results filled a 4096-token window, and the
      // model slowed to the time limit and drifted into other languages.
      append("Tool result for web_search:\n").append(results.take(600))
      if (usablePage) append("\n\nTool result for web_fetch (${top?.url}):\n").append(pageText.take(700))
    }
  }

  /**
   * The AI-mode lookup for a typed question. One short model step decides what to search for
   * (or NONE when a search would not help, e.g. a greeting or a poem); the app searches, reads up
   * to [MAX_WEB_PAGES] result pages at the same time, and returns them numbered [1], [2], … for the
   * answer to cite. [numbered] gets the same sources in the same order. "" = nothing to add.
   */
  private class AnswerPlan(val query: String?, val calc: String?, val choices: Boolean)

  /**
   * One short model step before a typed question is answered: what to search for (or NONE, e.g. for
   * a greeting or a poem) and, when the answer needs a sum, a JavaScript expression for it. The
   * model decides both; the app only reads the two labelled lines it asked for.
   */
  private suspend fun planAnswer(question: String, canSearch: Boolean, spoken: Boolean = false): AnswerPlan {
    status = "Planning the answer…"
    val before = work.lines.dropLast(2)
    // The last exchange, so a follow-up ("and his wife?") becomes a query that stands on its own.
    val recent = before.filter { it.done && it.error == null && (it.who == Who.USER || it.who == Who.MODEL) }
      .takeLast(2)
      .joinToString("\n") { l -> if (l.who == Who.USER) "Me: " + l.display.take(200) else "You: " + l.text.take(300) }
    val ask = buildString {
      if (recent.isNotBlank()) append("Our conversation so far:\n$recent\n\n")
      append("Today is ${today()}. My new message: ${question.take(400)}\n\n")
      append("Plan your answer. Reply with exactly three lines.\n")
      if (canSearch) {
        append("SEARCH: one web search query (3 to 10 words) that would find the facts needed, in the language ")
        append("that will find the best pages; or NONE. A search helps for facts, people, places, events, dates, ")
        append("prices, news, how things work, and anything you are not fully sure about. It does not help for ")
        append("greetings, chit-chat, jokes, stories or poems, rewriting or translating my own text, or pure sums. ")
        append("If my message gives answer options, search for what decides between them.\n")
      } else {
        append("SEARCH: NONE\n")
      }
      // Only a sum my message already spells out: for a word problem the model wrote a wrong
      // set-up ((30*5)/6 for an age puzzle) and the exact-looking result made the answer worse.
      // A spoken question rarely states a sum and never offers answer options, so those two lines are
      // not asked in voice: this is a time saving, not a capability cut. SEARCH is always asked,
      // because that is the whole reason a spoken turn is planned at all.
      if (!spoken) {
        append("CALC: only if my message itself states a plain calculation whose numbers and operations ")
        append("are all given (a percentage, a unit conversion, an arithmetic expression), that sum as one ")
        append("JavaScript expression: numbers, + - * / ( ) and Math functions only; no words, no units, no = ")
        append("sign, not the result. For a word problem that has to be set up first (unknown ages, shares, ")
        append("work and time, equations), and for everything else, NONE.\n")
        append("CHOICES: YES if my message gives answer options to choose from (such as (a)…(d) or (ক)…(ঘ)), else NO.\n")
        append("Example of the format:\nSEARCH: NONE\nCALC: (3.5 * 12) / 4\nCHOICES: NO")
      } else {
        append("Example of the format:\nSEARCH: NONE")
      }
    }
    val startedAt = System.currentTimeMillis()
    val raw = runCatching { gemma.oneShot(ask, 90) }.getOrDefault("")
    val tookMs = System.currentTimeMillis() - startedAt
    status = null
    fun field(name: String) = raw.lineSequence().map { it.trim().trim('*', '`', '-').trim() }
      .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
      ?.substringAfter(':')?.trim()?.trim('"', '`', '\'')?.trim()
      ?.takeIf { it.isNotBlank() && !it.trimEnd('.', '!').equals("NONE", ignoreCase = true) }
    val plan = AnswerPlan(
      field("SEARCH")?.take(150)?.takeIf { canSearch },
      field("CALC")?.trimEnd(';')?.take(300),
      field("CHOICES")?.startsWith("Y", ignoreCase = true) == true,
    )
    // oneShot closed the chat; reopen it without the turn being answered now. Arithmetic gets the
    // tighter sampler here, at the one rebuild this turn already needs, so no extra one is paid.
    gemma.startChat(history(before), numeric = plan.calc != null)
    Log.i("ChatController", "plan: search='${plan.query}' calc='${plan.calc}' choices=${plan.choices} (${tookMs} ms)")
    return plan
  }

  /** Works out [expr] in the sandboxed JavaScript runner (no network, no files). "" = no result. */
  private suspend fun calculate(answerId: Long, first: String): String {
    var expr = first
    var value = ""
    var ok = false
    // A sum that does not run (words or units in it) gets one fix from the model, with the error.
    for (attempt in 0..1) {
      if (!JsRunner.runnable(expr)) return ""
      status = "Calculating…"
      val calcStarted = System.currentTimeMillis()
      update(answerId) { it.copy(tools = it.tools + ToolUse("calculate", expr)) }
      val r = runCatching { JsRunner.run(app, "console.log($expr)") }
        .getOrElse { JsRunner.Result(false, "Error: ${it.message}") }
      val calcMs = System.currentTimeMillis() - calcStarted
      value = r.output.trim()
      ok = r.ok && value.isNotBlank() && !value.startsWith("Error") &&
        value != "undefined" && value != "NaN" && value != "(the code printed nothing)"
      val shown = expr
      update(answerId) { line -> line.copy(tools = line.tools.dropLast(1) + ToolUse("calculate", shown, ok, value.take(500), calcMs)) }
      if (ok || attempt == 1) break
      val fixed = runCatching {
        gemma.oneShot(
          "This JavaScript expression failed:\n$expr\nError: ${value.take(200)}\n\n" +
            "Rewrite it as one valid JavaScript expression that computes the same number: numbers, + - * / ( ) " +
            "and Math functions only; no words, no units, no = sign. Reply with the expression only.",
          60,
        )
      }.getOrDefault("").lineSequence().map { it.trim().trim('`').trim() }.firstOrNull { it.isNotBlank() }.orEmpty().trimEnd(';')
      gemma.startChat(history(work.lines.dropLast(2)))
      if (fixed.isBlank() || fixed == expr) break
      expr = fixed.take(300)
    }
    status = null
    Log.i("ChatController", "calc: $expr = $value ($ok)")
    // Rounded here: the model copies a long number like 42.164708 wrongly; 42.1647 it gets right.
    value = Tools.tidyNumber(value)
    // Phrased as an instruction to copy, not as background: told it was "background, not to be
    // quoted", the model ignored the number and re-typed the question's 9800 as 98800.
    return if (ok) calcNoteFor(expr, value) else ""
  }

  /**
   * The AI-mode lookup for a typed question: searches [query], reads up to [MAX_WEB_PAGES] result
   * pages at the same time, and returns the results numbered [1], [2], … for the answer to cite.
   * [numbered] gets the same sources in the same order. "" = nothing to add.
   */
  private suspend fun researchWeb(
    answerId: Long, question: String, query: String, numbered: MutableList<Source>, found: MutableList<Source>, budget: Int,
  ): String {
    status = "Searching the web…"
    update(answerId) { it.copy(tools = it.tools + ToolUse("web_search", query)) }
    val searchStarted = System.currentTimeMillis()
    val search = runCatching { Tutor.webSearch(query) }.getOrNull()
    val searchMs = System.currentTimeMillis() - searchStarted
    val results = search?.text ?: "Error: the search did not answer"
    update(answerId) { line ->
      line.copy(tools = line.tools.dropLast(1) + ToolUse("web_search", query, !search?.sources.isNullOrEmpty(), results.take(2000), searchMs))
    }
    if (search == null || search.sources.isEmpty()) { status = null; return "" }
    found.addAll(search.sources)
    // Every result is a numbered source. Its short search text is always kept: it often holds the
    // answer even when the page itself cannot be read (a video, or a page that needs JavaScript).
    val hits = search.sources.zip(search.snippets + List(search.sources.size) { "" }).take(MAX_WEB_SOURCES)
      .filter { (s, _) -> s.url.startsWith("http") }
    // One page per website: three pages from the same site are one opinion, not three.
    val picks = hits.map { it.first }
      .distinctBy { runCatching { java.net.URI(it.url).host }.getOrNull() ?: it.url }.take(MAX_WEB_PAGES)
    status = "Reading ${picks.size} pages…"
    val pagesStarted = System.currentTimeMillis()
    val pages = kotlinx.coroutines.coroutineScope {
      picks.map { s ->
        async {
          s.url to runCatching {
            kotlinx.coroutines.withTimeout(WEB_PAGE_TIMEOUT_MS) { Tools.webFetch(app, s.url, "$query $question", budget) }
          }.getOrNull()
        }
      }.awaitAll()
    }.toMap()
    val pagesMs = System.currentTimeMillis() - pagesStarted
    status = null
    val read = pages.filterValues { p -> p != null && Tools.usablePage(p.text) }.mapValues { it.value!!.text }
    update(answerId) { line ->
      line.copy(tools = line.tools + picks.map { s ->
        val p = pages[s.url]
        ToolUse("web_fetch", s.url, s.url in read, p?.text?.take(2000) ?: "could not open the page", pagesMs / picks.size.coerceAtLeast(1))
      })
    }
    Log.i("ChatController", "web research: ${read.size}/${picks.size} pages read for '$query' (search ${searchMs} ms, pages ${pagesMs} ms)")
    // The room left after the short search texts is shared by the pages that could be read.
    val snippetRoom = hits.sumOf { it.second.length + it.first.title.length + 12 }
    val perPage = ((budget - snippetRoom) / read.size.coerceAtLeast(1)).coerceAtLeast(300)
    return buildString {
      append("[Web sources for my message, found just now. They are numbered; cite them by number. ")
      append("Today is ${today()}. Some sources may be old: when they disagree, trust the newest one.]\n")
      hits.forEachIndexed { i, (s, snip) ->
        numbered.add(s)
        append("\n[${i + 1}] ${s.title}\n")
        if (snip.isNotBlank()) append(snip).append("\n")
        read[s.url]?.let { append(it.take(perPage).trim()).append("\n") }
      }
    }
  }

  /** Today's date in words, so the model can tell old sources from new ("current", "latest", "this year"). */
  private fun today(): String =
    java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.ENGLISH).format(java.util.Date())

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
  /**
   * Called when a spoken answer is about to be prepared (transcribe, plan, search — ~19 s before the
   * first word). Talk mode says one short line so the wait is not silence.
   */
  var onPreparing: ((Long) -> Unit)? = null
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

  /** A number as a person would read it: no trailing ".0", at most 4 decimals. */
  private fun fmtNum(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return "?"
    return if (v == v.toLong().toDouble()) v.toLong().toString()
    else "%.4f".format(v).trimEnd('0').trimEnd('.')
  }

  private fun friendlyError(t: Throwable): String {
    val m = t.message ?: t.javaClass.simpleName
    return if ("token" in m.lowercase()) "This chat is too long for the model. Tap New chat to start fresh." else m
  }

  // ---------- keeping the chat inside the model's memory ----------

  /**
   * When the window is nearly full, older turns are summarised so the chat
   * can go on (same idea as agent.py fit_context). The newest turns stay word for word.
   */
  private suspend fun compactIfNeeded(text: String, extraTokens: Int = 0) {
    val reserve = 600 // room for the reply
    val perToken = charsPerToken(text)
    val need = gemma.tokensUsed + (text.length / perToken).toInt() + extraTokens + reserve
    if (need <= gemma.maxTokens) return
    // Everything before the user turn just added.
    val past = work.lines.dropLast(2).filter { (it.who == Who.USER || it.who == Who.MODEL) && it.done && it.error == null }
    if (past.size < 2) return
    val budget = ((gemma.maxTokens - reserve) * perToken).toInt()
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

    /**
     * How a spoken clip is written down.
     *
     * The first version ("write down what this says") came back Romanised every time — measured on the
     * phone: `Tariq Rahman Bangladesh-er koto tomo prodhantromntri`. That text is what the study files
     * are searched with, and the study files are Bengali, so the words that carry the question
     * ("কত তম" — how many-th) were lost: the search matched "প্রথম" (first) and answered about
     * Tajuddin Ahmad. Naming the script is the cheapest fix; the model still has to hear the words.
     */
    const val TRANSCRIBE_PROMPT =
      "Write down exactly what this voice recording says. " +
        "Write Bengali in Bengali letters — never in English letters (no \"Bangladesh-er\", no " +
        "\"koto\"). Write English words in English letters. Keep the speaker's words unchanged; do " +
        "not translate and do not tidy the grammar. If there is no clear speech, answer: nothing"

    /**
     * Developer builds only: pretend a voice clip said exactly this.
     *
     * Decides whether a wrong spoken answer comes from the transcriber or from everything downstream
     * of it. A perfect transcript can be fed while the audio is still supplied, so the two runs differ
     * only in the words the study files are searched with. Also the harness for comparing a real ASR
     * model against the current 2B-model transcription.
     */
    @Volatile var forcedSpeech: String? = null

    /** Every number of two or more digits in [text], ASCII-normalised. */
    fun numbersOf(text: String): List<String> =
      Regex("[0-9]{2,}").findAll(MathCheck.asciiDigits(text)).map { it.value }.toList()

    /**
     * Does [expr] only use numbers the question actually gave? A calculator result is only worth
     * trusting when the expression was built from the question's own figures; an invented constant
     * (9800 / **19**) silently poisons an answer that is then told the number is verified.
     *
     * A legitimately derived value (the ratio total 2+3+4+5 = 14) is also rejected, on purpose: the
     * calculator is then skipped for that step and the model shows its own working, which it does
     * correctly. Percentages may use 100, which the question implies without writing it.
     */
    fun groundedCalc(question: String, expr: String): Boolean {
      val available = numbersOf(question).toSet() + "100"
      return numbersOf(expr).all { it in available }
    }

    /**
     * A direct calculation the model could read off the question, as opposed to a formula it composed.
     *
     * This is the difference between arithmetic being verified and the *setup* being verified. For
     * "3 men or 5 boys take 20 days, how long do 4 men and 10 boys take?" the planner wrote
     * `20 * 3 * 4 / (3 * 20 + 5 * 20)` = 1.5, and every number in it appears in the question, so
     * `groundedCalc` accepted it. The phone then confirmed 1.5 is the right answer *to that
     * expression* — and the answer dutifully built a derivation around 1.5 instead of 6 days.
     *
     * A grouped sub-expression with an operator inside ("(3 * 20 + 5 * 20)") is a composed formula,
     * so its result is only ever offered to the model as something to check, never as the answer.
     */
    fun directCalc(expr: String): Boolean {
      val e = expr.trim()
      if (e.isEmpty()) return false
      // A grouped sub-expression that itself contains an operator means the model built a formula.
      if (Regex("""\([^()]*[+\-*/×÷][^()]*\)""").containsMatchIn(e)) return false
      return true
    }

    /** How the calculator's result is described to the model. */
    fun calcNoteFor(expr: String, value: String): String =
      if (directCalc(expr)) {
        "[Verified arithmetic from the phone's calculator, which cannot be wrong: $expr = $value. " +
          "Wherever this calculation appears in your answer, copy that number exactly as written " +
          "here. Do not recompute it, do not re-type it from memory, and do not change a single " +
          "digit. Work out any other steps separately.]"
      } else {
        "[The phone evaluated this expression: $expr = $value. The arithmetic is correct, but the " +
          "expression itself may not be the right one for the question. Check that it models what " +
          "the question asks. If it does, use the number. If it does not, ignore it completely and " +
          "work the answer out yourself from the question.]"
      }

    /**
     * Characters per token for this text. Four is a Latin-script figure; Bengali packs far more
     * characters into a token's worth of bytes, so counting a Bengali prompt at 4 under-estimates it
     * and the window can overflow without the app ever noticing. All-Latin gives 4.0, all-Bengali 2.0.
     */
    fun charsPerToken(text: String): Double {
      if (text.isEmpty()) return CHARS_PER_TOKEN.toDouble()
      var bengali = 0
      for (c in text) if (c in '\u0980'..'\u09FF') bengali++
      val share = bengali.toDouble() / text.length
      return CHARS_PER_TOKEN - 2.0 * share
    }
    const val BENGALI_RULE = "Answer in Bengali (বাংলা), even if I write in English. Use Bengali words; " +
      "keep only technical terms, formulas, code, [FIGURE: ...] search words and the NEXT: line's format " +
      "as they are; write the NEXT questions in Bengali."
    const val MAX_TOOL_TURNS = 4
    /** Pages read at the same time for a typed question (AI-mode lookup). */
    const val MAX_WEB_PAGES = 3
    /** Search results given to the answer as numbered sources (their short text, plus the page if read). */
    const val MAX_WEB_SOURCES = 5
    const val WEB_PAGE_TIMEOUT_MS = 20_000L
    /** Extra look-up/rewrite rounds after the first answer (the refusal retry counts as one). */
    const val MAX_RESEARCH_ROUNDS = 3
    const val FIRST_TIMEOUT_MS = 300_000L
    const val ROUND_TIMEOUT_MS = 180_000L
    /**
     * Output ceiling for a multiple-choice answer: the option, then a short reason. The observed
     * failure was the opposite of terse — two NTRCA options questions ran to 331 s and 409 s because
     * the model wrote a page of working for a question that only needed a choice.
     */
    const val CHOICE_MAX_OUTPUT = 512
    const val IMAGE_TOKENS = 280 // Gemma's vision budget per picture (same as agent.py)
    const val AUDIO_TOKENS_PER_SEC = 25
    /** Reference passages per answer, and how much of each to include. */
    const val KNOWLEDGE_PASSAGES = 3
    const val KNOWLEDGE_CHARS = 900

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
internal fun keepBengali(text: String): String = healGarbled(text).filter { c ->
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

/**
 * Repairs a Bengali letter glued onto a Latin word ("ধvariables"), which the model produces when it
 * starts writing a Bengali heading and switches to an English term mid-word. `keepBengali` cannot see
 * this: every character is Bengali or Latin, so nothing is foreign. The stray letter is dropped and
 * the English word kept, which is what the sentence was reaching for.
 */
internal fun healGarbled(text: String): String {
  if (text.isEmpty()) return text
  // Bengali letter(s) directly followed by two or more Latin letters, with no infix vowel sign after.
  val glued = Regex("""([\u0980-\u09FF]+)([A-Za-z]{2,})""")
  var out = glued.replace(text) { m ->
    val bengali = m.groupValues[1]
    val latin = m.groupValues[2]
    // Keep the Bengali when it is a real word attached to a unit ("৩৫kg" is fine; "ধvariables" is not).
    if (bengali.all { it in '\u0995'..'\u09B9' } && bengali.length <= 1) latin else "$bengali$latin"
  }
  // The reverse shape: a Latin word running straight into Bengali letters ("variablesধ").
  out = Regex("""([A-Za-z]{2,})([\u0980-\u09FF]{1,2})\b""").replace(out) { m ->
    if (m.groupValues[2].length <= 1) m.groupValues[1] else m.value
  }
  return out
}

