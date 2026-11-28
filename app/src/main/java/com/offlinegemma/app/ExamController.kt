package com.offlinegemma.app

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Exam mode's state machine: pick a paper, answer under a clock, grade, review.
 *
 * Lives in [App] alongside the chat controller, so a rotation never restarts a timer and the
 * attempt on screen is the same object the ticker is counting down.
 *
 * Two rules the code exists to keep:
 *  - the clock reads from `deadlineAt`, never from a decremented counter, so backgrounding the app
 *    cannot buy time;
 *  - an attempt is written to disk on every change, so a process death mid-paper loses nothing.
 */
class ExamController(
  private val app: App,
  private val store: ExamStore,
) {
  private val scope = CoroutineScope(Dispatchers.Main + Job())

  var phase by mutableStateOf(ExamPhase.LIST)
    private set
  var paper: ExamPaper? by mutableStateOf(null)
    private set
  var attempt: ExamAttempt? by mutableStateOf(null)
    private set
  var review by mutableStateOf(false)
    private set
  /** Milliseconds left, refreshed by the ticker so Compose recomposes the header. */
  var remainingMs by mutableStateOf(0L)
    private set
  var saving by mutableStateOf(false)
    private set
  var explaining by mutableStateOf(false)
    private set
  var saved by mutableStateOf(store.list())
    private set

  private var ticker: Job? = null
  private var submitLocked = false

  val bengali: Boolean get() = app.prefs.answerBengali

  /** A short bilingual label pair: Bengali first, English second, per the app's language setting. */
  fun label(bn: String, en: String): String = if (bengali) bn else en

  fun refreshSaved() {
    saved = store.list()
  }

  fun openBrief(p: ExamPaper) {
    paper = p
    attempt = null
    review = false
    phase = ExamPhase.BRIEF
  }

  fun backToList() {
    stopTicker()
    paper = null
    attempt = null
    review = false
    phase = ExamPhase.LIST
    refreshSaved()
  }

  fun start(p: ExamPaper? = paper) {
    if (p == null) return
    val now = System.currentTimeMillis()
    val a = ExamAttempt(
      id = "a-" + now.toString(36) + (100..999).random(),
      examId = p.id,
      title = p.titleBn.ifBlank { p.title },
      category = p.category,
      mode = if (p.id.startsWith("mock-")) "mock" else "paper",
      startedAt = now,
      deadlineAt = now + p.durationMinutes * 60_000L,
      durationMinutes = p.durationMinutes,
      negative = p.negative,
      questionNumbers = p.questions.map { it.n },
      answers = emptyMap(),
      marked = emptySet(),
      visited = setOf(p.questions.first().n),
      current = p.questions.first().n,
      status = ExamAttempt.RUNNING,
      submittedAt = null,
      score = null,
      explanations = emptyMap(),
      mockQuestions = if (p.id.startsWith("mock-")) p.questions else null,
    )
    paper = p
    attempt = a
    review = false
    phase = ExamPhase.RUNNING
    persist()
    startTicker()
  }

  fun resume(saved: ExamAttempt) {
    // A saved attempt has no paper object; a bank paper is looked up, a mock carries its own
    // questions because it never existed in the pack.
    val p = if (saved.mockQuestions != null) {
      ExamPaper(
        id = saved.examId,
        title = saved.title,
        titleBn = saved.title,
        category = saved.category,
        durationMinutes = saved.durationMinutes,
        negative = saved.negative,
        subjects = emptyList(),
        questions = saved.mockQuestions,
      )
    } else {
      Exams.paper(app, saved.examId)
    }
    if (p == null) {
      backToList()
      return
    }
    paper = p
    attempt = saved
    when {
      saved.running && saved.deadlineAt > System.currentTimeMillis() -> {
        review = false
        phase = ExamPhase.RUNNING
        startTicker()
      }
      // Reopened after the clock ran out: grade what is on disk instead of continuing.
      saved.running -> {
        review = false
        phase = ExamPhase.RUNNING
        submit(auto = true)
      }
      else -> {
        review = true
        phase = ExamPhase.REVIEW
      }
    }
  }

  fun currentQuestion(): ExamQuestion? {
    val a = attempt ?: return null
    return paper?.questions?.firstOrNull { it.n == a.current } ?: paper?.questions?.firstOrNull()
  }

  fun pick(index: Int) {
    val a = attempt ?: return
    val q = currentQuestion() ?: return
    val chosen = if (a.answers[q.n] == index) null else index
    attempt = a.copy(answers = a.answers + (q.n to chosen))
    persist()
  }

  fun goto(n: Int) {
    val a = attempt ?: return
    attempt = a.copy(current = n, visited = a.visited + n)
    persist()
  }

  fun step(delta: Int) {
    val a = attempt ?: return
    val questions = paper?.questions ?: return
    val i = questions.indexOfFirst { it.n == a.current }
    val next = questions.getOrNull((i + delta).coerceIn(0, questions.size - 1)) ?: return
    goto(next.n)
  }

  fun toggleMark() {
    val a = attempt ?: return
    val marked = if (a.current in a.marked) a.marked - a.current else a.marked + a.current
    attempt = a.copy(marked = marked)
    persist()
  }

  fun submit(auto: Boolean = false) {
    val a = attempt ?: return
    if (!a.running || submitLocked) return
    submitLocked = true
    stopTicker()
    val score = ExamScoring.score(paper?.questions ?: emptyList(), a.answers, a.negative)
    attempt = a.copy(
      status = ExamAttempt.SUBMITTED,
      submittedAt = System.currentTimeMillis(),
      score = score,
    )
    review = false
    phase = ExamPhase.RESULT
    persist()
    submitLocked = false
    if (auto) Log.i("Exams", "auto-submitted ${a.id} at the deadline")
  }

  fun openReview() {
    val a = attempt ?: return
    review = true
    phase = ExamPhase.REVIEW
    attempt = a.copy(current = paper?.questions?.firstOrNull()?.n ?: a.current)
  }

  /** One more attempt at the same paper. */
  fun retry() {
    val p = paper ?: return
    start(p)
  }

  fun leaveReview() {
    review = false
    phase = ExamPhase.RESULT
  }

  fun delete(id: String) {
    store.delete(id)
    if (attempt?.id == id) backToList() else refreshSaved()
  }

  /**
   * The explanation for the question on screen: the paper's own note when it has one, else a cached
   * model answer, else one model call.
   *
   * `gemma.oneShot` closes the open conversation, so the chat is rebuilt afterwards exactly as
   * ChatController does - otherwise the next chat answer would run on a closed conversation.
   */
  fun explain() {
    val a = attempt ?: return
    val q = currentQuestion() ?: return
    if (q.explain != null || a.explanations.containsKey(q.n) || explaining) return
    explaining = true
    scope.launch {
      val ask = ExamScoring.explainPrompt(q, bengali = bengali)
      val out = withContext(Dispatchers.IO) {
        runCatching { app.gemma.oneShot(ask, 260) }.getOrDefault("")
      }
      app.chat.resumeModel()
      val text = out.trim()
      explaining = false
      if (ExamScoring.declined(text)) {
        // Remembered as empty: a question that cannot be justified should not be asked again and
        // again, and "no explanation" is a truthful answer where an invented one is not.
        attempt = attempt?.copy(explanations = (attempt?.explanations ?: emptyMap()) + (q.n to ""))
      } else {
        attempt = attempt?.copy(explanations = (attempt?.explanations ?: emptyMap()) + (q.n to text))
      }
      persist()
    }
  }

  fun explanationFor(q: ExamQuestion): String? {
    val a = attempt ?: return null
    if (q.explain != null) return q.explain
    return a.explanations[q.n]
  }

  fun explanationAsked(q: ExamQuestion): Boolean {
    val a = attempt ?: return false
    return q.explain != null || a.explanations.containsKey(q.n)
  }

  private fun persist() {
    val a = attempt ?: return
    scope.launch {
      saving = true
      withContext(Dispatchers.IO) { runCatching { store.save(a) } }
      saving = false
      saved = store.list()
    }
  }

  private fun startTicker() {
    stopTicker()
    ticker = scope.launch {
      while (true) {
        val a = attempt
        if (a == null || !a.running) break
        val left = a.remainingMs()
        remainingMs = left
        if (left <= 0L) {
          // The clock is the authority, not a counter: one check of the real deadline per second.
          submit(auto = true)
          break
        }
        delay(500)
      }
    }
  }

  private fun stopTicker() {
    ticker?.cancel()
    ticker = null
  }

  /** Called when the app comes back to the foreground, so the header is right immediately. */
  fun resyncClock() {
    val a = attempt ?: return
    if (!a.running) return
    remainingMs = a.remainingMs()
    if (remainingMs <= 0L) submit(auto = true) else startTicker()
  }
}

/** 01:58:43 as Arabic digits, then translated for display. */
fun formatClock(ms: Long): String {
  val total = (ms / 1000).coerceAtLeast(0)
  val h = total / 3600
  val m = (total % 3600) / 60
  val s = total % 60
  return "%02d:%02d:%02d".format(h, m, s)
}
