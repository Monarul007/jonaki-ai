package com.offlinegemma.app

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * A saved exam attempt - the state that must survive a phone call, a rotation, or MIUI killing the
 * process mid-paper.
 *
 * [deadlineAt] is an absolute epoch-millis instant, not a countdown. That is what makes resume
 * honest: a 60-minute paper resumed after 20 minutes away has 40 left, and one reopened after the
 * clock ran out is graded rather than continued. The same JSON keys are used by the web build, so
 * an attempt written by either side reads in the other.
 */
data class ExamAttempt(
  val id: String,
  val examId: String,
  val title: String,
  val category: String,
  val mode: String,
  val startedAt: Long,
  val deadlineAt: Long,
  val durationMinutes: Int,
  val negative: Double,
  val questionNumbers: List<Int>,
  val answers: Map<Int, Int?>,
  val marked: Set<Int>,
  val visited: Set<Int>,
  val current: Int,
  val status: String,
  val submittedAt: Long?,
  val score: ExamScore?,
  val explanations: Map<Int, String>,
  /** A custom mock has no paper in the pack, so the paper's questions are stored with it. */
  val mockQuestions: List<ExamQuestion>?,
) {
  val running: Boolean get() = status == RUNNING
  val answeredCount: Int get() = answers.values.count { it != null }

  fun remainingMs(now: Long = System.currentTimeMillis()): Long =
    if (!running) 0L else (deadlineAt - now).coerceAtLeast(0L)

  companion object {
    const val RUNNING = "running"
    const val SUBMITTED = "submitted"
  }
}

enum class ExamPhase { LIST, BRIEF, RUNNING, RESULT, REVIEW }

/**
 * Attempts on disk: one JSON file each, rewritten atomically per save.
 *
 * Unlike the chat transcripts (append-only), an attempt is a moving snapshot - the current
 * question, the answers so far, the marks - so it is replaced rather than appended to.
 */
class ExamStore(private val context: Context) {
  private val dir: File get() = File(context.filesDir, "exams").apply { mkdirs() }

  fun path(id: String) = File(dir, "$id.json")

  fun save(attempt: ExamAttempt) {
    val json = toJson(attempt)
    val tmp = File(dir, "${attempt.id}.json.tmp")
    tmp.writeText(json.toString())
    val dest = path(attempt.id)
    if (!tmp.renameTo(dest)) {
      dest.writeText(json.toString())
      tmp.delete()
    }
  }

  fun load(id: String): ExamAttempt? {
    val f = path(id)
    if (!f.isFile) return null
    return runCatching { fromJson(JSONObject(f.readText())) }.getOrNull()
  }

  fun delete(id: String) {
    path(id).delete()
  }

  /** Attempts, newest activity first. */
  fun list(): List<ExamAttempt> =
    dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
      ?.mapNotNull { f -> runCatching { fromJson(JSONObject(f.readText())) }.getOrNull() }
      ?.sortedByDescending { it.submittedAt ?: it.startedAt }
      ?: emptyList()

  private fun toJson(a: ExamAttempt): JSONObject = JSONObject().apply {
    put("id", a.id)
    put("examId", a.examId)
    put("title", a.title)
    put("category", a.category)
    put("mode", a.mode)
    put("startedAt", a.startedAt)
    put("deadlineAt", a.deadlineAt)
    put("durationMinutes", a.durationMinutes)
    put("negative", a.negative)
    put("questionNumbers", JSONArray(a.questionNumbers))
    put("answers", JSONObject().apply {
      for ((k, v) in a.answers) put(k.toString(), v ?: JSONObject.NULL)
    })
    put("marked", JSONArray(a.marked.toList()))
    put("visited", JSONArray(a.visited.toList()))
    put("current", a.current)
    put("status", a.status)
    put("submittedAt", a.submittedAt ?: JSONObject.NULL)
    put("explanations", JSONObject().apply {
      for ((k, v) in a.explanations) put(k.toString(), v)
    })
    put("score", a.score?.let { scoreJson(it) } ?: JSONObject.NULL)
    put("mockQuestions", a.mockQuestions?.let { questionsJson(it) } ?: JSONObject.NULL)
  }

  private fun scoreJson(s: ExamScore): JSONObject = JSONObject().apply {
    put("total", s.total)
    put("correct", s.correct)
    put("wrong", s.wrong)
    put("skipped", s.skipped)
    put("negative", s.negative)
    put("net", s.net)
    put("maxMarks", s.maxMarks)
    put("accuracy", s.accuracy)
    put("attemptedAccuracy", s.attemptedAccuracy)
    put("bySubject", JSONArray().apply {
      for (r in s.bySubject) put(JSONObject().apply {
        put("subject", r.subject)
        put("correct", r.correct)
        put("wrong", r.wrong)
        put("skipped", r.skipped)
        put("total", r.total)
      })
    })
  }

  private fun questionsJson(questions: List<ExamQuestion>): JSONArray = JSONArray().apply {
    for (q in questions) put(JSONObject().apply {
      put("n", q.n)
      put("subject", q.subject)
      put("text", q.text)
      put("options", JSONArray(q.options))
      put("answer", q.answer)
      put("explain", q.explain ?: JSONObject.NULL)
    })
  }

  private fun fromJson(o: JSONObject): ExamAttempt {
    val answers = HashMap<Int, Int?>()
    o.optJSONObject("answers")?.let { map ->
      for (key in map.keys()) {
        answers[key.toIntOrNull() ?: continue] = if (map.isNull(key)) null else map.optInt(key)
      }
    }
    val explanations = HashMap<Int, String>()
    o.optJSONObject("explanations")?.let { map ->
      for (key in map.keys()) {
        explanations[key.toIntOrNull() ?: continue] = map.optString(key)
      }
    }
    return ExamAttempt(
      id = o.optString("id"),
      examId = o.optString("examId"),
      title = o.optString("title"),
      category = o.optString("category"),
      mode = o.optString("mode", "paper"),
      startedAt = o.optLong("startedAt"),
      deadlineAt = o.optLong("deadlineAt"),
      durationMinutes = o.optInt("durationMinutes"),
      negative = o.optDouble("negative", 0.0),
      questionNumbers = o.optJSONArray("questionNumbers").toIntList(),
      answers = answers,
      marked = o.optJSONArray("marked").toIntList().toSet(),
      visited = o.optJSONArray("visited").toIntList().toSet(),
      current = o.optInt("current", 1),
      status = o.optString("status", ExamAttempt.RUNNING),
      submittedAt = if (o.isNull("submittedAt")) null else o.optLong("submittedAt"),
      score = o.optJSONObject("score")?.let { s ->
        ExamScore(
          total = s.optInt("total"),
          correct = s.optInt("correct"),
          wrong = s.optInt("wrong"),
          skipped = s.optInt("skipped"),
          negative = s.optDouble("negative", 0.0),
          net = s.optDouble("net", 0.0),
          maxMarks = s.optInt("maxMarks"),
          accuracy = s.optDouble("accuracy", 0.0),
          attemptedAccuracy = s.optDouble("attemptedAccuracy", 0.0),
          bySubject = s.optJSONArray("bySubject").let { arr ->
            (0 until (arr?.length() ?: 0)).mapNotNull { i ->
              arr?.optJSONObject(i)?.let { r ->
                ExamSubjectScore(
                  r.optString("subject"), r.optInt("correct"), r.optInt("wrong"),
                  r.optInt("skipped"), r.optInt("total"),
                )
              }
            }
          },
        )
      },
      explanations = explanations,
      mockQuestions = o.optJSONArray("mockQuestions")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
          val q = arr.optJSONObject(i) ?: return@mapNotNull null
          val options = q.optJSONArray("options") ?: return@mapNotNull null
          ExamQuestion(
            n = q.optInt("n"),
            subject = q.optString("subject"),
            text = q.optString("text"),
            options = (0 until options.length()).map { options.optString(it) },
            answer = q.optInt("answer"),
            explain = q.optString("explain").takeIf { it.isNotBlank() && it != "null" },
          )
        }
      },
    )
  }
}

private fun JSONArray?.toIntList(): List<Int> =
  if (this == null) emptyList() else (0 until length()).map { optInt(it) }
