package com.offlinegemma.app

/**
 * Exam mode: real Bangladeshi job-exam papers answered under a clock, then graded and reviewed.
 *
 * The papers come from the study data already on the phone (`exam.pack`, built by
 * tools/build_exam_pack.py from the same NTRCA/BCS folders the knowledge pack uses). Nothing here
 * touches Android or the model: [score] is pure so the arithmetic that decides a result can be
 * tested without a device, and [Question] is plain data so the pack reader and the
 * knowledge.pack fallback both produce the same shape.
 */

data class ExamQuestion(
  val n: Int,
  val subject: String,
  val text: String,
  val options: List<String>,
  val answer: Int,
  /** The paper's own explanation, when it has one. Most NTRCA papers do not. */
  val explain: String? = null,
)

data class ExamSubject(val name: String, val count: Int)

data class ExamPaper(
  val id: String,
  val title: String,
  val titleBn: String,
  val category: String,
  val durationMinutes: Int,
  val negative: Double,
  val subjects: List<ExamSubject>,
  val questions: List<ExamQuestion>,
) {
  /** Bengali papers print ক/খ/গ/ঘ; English ones print A-D. */
  val labels: List<String>
    get() = if (ExamScoring.isBengali(questions.firstOrNull()?.text.orEmpty())) {
      listOf("ক", "খ", "গ", "ঘ")
    } else {
      listOf("A", "B", "C", "D")
    }
}

data class ExamSubjectScore(
  val subject: String,
  val correct: Int,
  val wrong: Int,
  val skipped: Int,
  val total: Int,
)

/**
 * A graded script.
 * [net] is what a result list would print: correct answers minus the paper's own deduction.
 * [accuracy] is over the whole paper (skips count against it), [attemptedAccuracy] only over the
 * questions actually answered - two different questions, so both are kept.
 */
data class ExamScore(
  val total: Int,
  val correct: Int,
  val wrong: Int,
  val skipped: Int,
  val negative: Double,
  val net: Double,
  val maxMarks: Int,
  val accuracy: Double,
  val attemptedAccuracy: Double,
  val bySubject: List<ExamSubjectScore>,
)

object ExamScoring {

  private val BENGALI = Regex("[\u0980-\u09FF]")

  fun isBengali(text: String): Boolean = BENGALI.containsMatchIn(text)

  /**
   * Grades [questions] against [answers], where the key is a question number and the value is the
   * chosen option index (null or absent = not answered).
   *
   * Negative marking follows the paper's own rule; not answering never deducts. A chosen index
   * outside 0..3 is treated as unanswered rather than as a wrong answer, because that is what it
   * means: no real option was selected.
   */
  fun score(
    questions: List<ExamQuestion>,
    answers: Map<Int, Int?>,
    negative: Double,
  ): ExamScore {
    val deduction = if (negative > 0) negative else 0.0
    var correct = 0
    var wrong = 0
    var skipped = 0
    val rows = LinkedHashMap<String, IntArray>()  // subject -> [correct, wrong, skipped, total]

    for (q in questions) {
      val chosen = answers[q.n]
      val row = rows.getOrPut(q.subject.ifBlank { "সাধারণ" }) { IntArray(4) }
      row[3]++
      when {
        chosen == null || chosen !in 0..3 -> { skipped++; row[2]++ }
        chosen == q.answer -> { correct++; row[0]++ }
        else -> { wrong++; row[1]++ }
      }
    }

    val total = questions.size
    val attempted = correct + wrong
    val net = ((correct - wrong * deduction) * 100).roundToLong() / 100.0
    return ExamScore(
      total = total,
      correct = correct,
      wrong = wrong,
      skipped = skipped,
      negative = deduction,
      net = net,
      maxMarks = total,
      accuracy = if (total == 0) 0.0 else (1000L * correct / total) / 10.0,
      attemptedAccuracy = if (attempted == 0) 0.0 else (1000L * correct / attempted) / 10.0,
      bySubject = rows.map { (name, r) ->
        ExamSubjectScore(name, r[0], r[1], r[2], r[3])
      },
    )
  }

  private fun Double.roundToLong(): Long = kotlin.math.round(this).toLong()

  /**
   * A fresh paper in the shape of a real one: the same subject mix, sampled across the papers of
   * [category]. Options are shuffled per question and the key moves with them, so a retake is not
   * answer recall. [seed] makes a specific mock reproducible (used by the tests).
   */
  fun mock(
    papers: List<ExamPaper>,
    category: String,
    count: Int,
    minutes: Int,
    seed: Long,
  ): ExamPaper? {
    val pool = papers.filter { it.category == category && it.questions.isNotEmpty() }
    if (pool.isEmpty()) return null
    val random = kotlin.random.Random(seed)

    // Weight by how many questions each subject contributes across the category's real papers,
    // then hand out the requested count by largest remainder so the proportions survive rounding.
    val weight = HashMap<String, Int>()
    for (paper in pool) {
      for (q in paper.questions) weight[q.subject] = (weight[q.subject] ?: 0) + 1
    }
    val totalWeight = weight.values.sum().toDouble()
    val shares = weight.entries.sortedByDescending { it.value }
      .map { it.key to (it.value / totalWeight * count) }
    val quota = shares.associate { it.first to it.second.toInt() }.toMutableMap()
    var left = count - quota.values.sum()
    var i = 0
    while (left > 0 && shares.isNotEmpty()) {
      val key = shares[i % shares.size].first
      quota[key] = (quota[key] ?: 0) + 1
      left--
      i++
    }

    val bySubject = HashMap<String, MutableList<ExamQuestion>>()
    for (paper in pool) {
      for (q in paper.questions) bySubject.getOrPut(q.subject) { mutableListOf() }.add(q)
    }

    val picked = mutableListOf<ExamQuestion>()
    val taken = HashSet<String>()
    for ((subject, want) in quota) {
      val items = bySubject[subject]?.shuffled(random) ?: continue
      for (q in items.take(want)) {
        if (!taken.add(q.text.take(60))) continue
        picked.add(shuffleOptions(q, random))
      }
    }
    if (picked.size < count) {
      for (q in pool.flatMap { it.questions }.shuffled(random)) {
        if (picked.size >= count) break
        if (!taken.add(q.text.take(60))) continue
        picked.add(shuffleOptions(q, random))
      }
    }
    // Only an empty result is a failure. A short mock is legitimate (the UI offers 25/50/100, and
    // a small category may have fewer), so the requested count is not re-imposed here.
    if (picked.isEmpty()) return null

    val numbered = picked.shuffled(random).mapIndexed { idx, q -> q.copy(n = idx + 1) }
    val counts = LinkedHashMap<String, Int>()
    for (q in numbered) counts[q.subject] = (counts[q.subject] ?: 0) + 1
    val first = pool.first()
    return ExamPaper(
      id = "mock-" + (1..8).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString(""),
      title = "Custom Mock - ${first.category.uppercase()}, ${numbered.size} questions",
      titleBn = "নিজস্ব মডেল টেস্ট - ${numbered.size} প্রশ্ন",
      category = category,
      durationMinutes = if (minutes > 0) minutes else paperMinutes(category),
      negative = paperNegative(category),
      subjects = counts.map { ExamSubject(it.key, it.value) },
      questions = numbered,
    )
  }

  /** Moves the key with the options, so the same question can appear in a different order. */
  private fun shuffleOptions(q: ExamQuestion, random: kotlin.random.Random): ExamQuestion {
    if (q.options.size != 4 || q.answer !in 0..3) return q
    val order = listOf(0, 1, 2, 3).shuffled(random)
    val options = order.map { q.options[it] }
    val answer = order.indexOf(q.answer)
    return q.copy(options = options, answer = answer)
  }

  /** The real running times for these examinations; a mock has no paper to copy them from. */
  fun paperMinutes(category: String): Int = when (category) {
    "bcs" -> 120
    "ntrca" -> 60
    else -> 60
  }

  /** BCS preliminary deducts half a mark per wrong answer; the others do not deduct. */
  fun paperNegative(category: String): Double = when (category) {
    "bcs" -> 0.5
    else -> 0.0
  }

  /**
   * The prompt that asks the model to justify a keyed answer.
   *
   * A 2B model asked why an answer is right will happily invent a reason, so the prompt states the
   * answer as given rather than asking for it, forbids drifting onto a neighbouring fact, and
   * allows a refusal - which the caller shows as "no explanation" instead of fabricating one.
   */
  fun explainPrompt(q: ExamQuestion, bengali: Boolean): String = buildString {
    val labels = if (isBengali(q.text)) listOf("ক", "খ", "গ", "ঘ") else listOf("A", "B", "C", "D")
    append("Exam question: ").append(q.text.take(600)).append("\n")
    q.options.take(4).forEachIndexed { i, text -> append("(").append(labels[i]).append(") ").append(text).append("\n") }
    if (q.answer in q.options.indices) {
      append("\nCorrect answer: (").append(labels[q.answer]).append(") ").append(q.options[q.answer]).append("\n")
    }
    append("\nIn 2-3 short sentences say why that answer is correct and, where it helps, why the ")
    append("closest wrong option is wrong. Use only what the question and the options themselves ")
    append("contain. Do not add a different fact about the topic. If you cannot justify it simply ")
    append("and correctly, reply with exactly: CANNOT_EXPLAIN\n")
    if (bengali) append("Write in Bengali, in simple words.") else append("Write in English.")
  }

  /** True when the model declined rather than answering, or said nothing at all. */
  fun declined(explanation: String): Boolean =
    explanation.isBlank() || explanation.contains("CANNOT_EXPLAIN", ignoreCase = true)
}
