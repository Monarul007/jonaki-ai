package com.offlinegemma.app

/**
 * The prompt for an on-demand explanation.
 *
 * Kept separate from ChatController so the shape can be read and tested without a device: the
 * explanation is only as good as what it is handed, and the first version handed it the wrong things.
 *
 * Measured failure that this exists to prevent. Asked to explain "সালোকসংশ্লেষণ কী" (what is
 * photosynthesis), the model replied:
 *
 *   ANSWER:  photosynthesis is the process by which plants make glucose…
 *   REASON:  spectroscopy has proved that RED LIGHT is the most effective in photosynthesis…
 *   CONCEPT: plants use sunlight to make food, like a kitchen uses heat…
 *
 * The REASON is about a different fact — true, but it does not justify the answer. The cause was not a
 * bad model: the prompt carried no question ("My question: " with nothing after it), so there was
 * nothing for the reasoning to be reasoning ABOUT. A model asked to justify an answer, with no
 * question in view, will reach for whatever textbook fact sits nearest the topic.
 */
object Explanations {

  /** The user message this answer replies to: its own `topic`, else the nearest USER line above. */
  fun questionFor(lines: List<ChatLine>, answerIndex: Int): String {
    val line = lines.getOrNull(answerIndex) ?: return ""
    line.topic?.takeIf { it.isNotBlank() }?.let { return it }
    // Walk back to the nearest user turn. Using lines[0] would attach the first question ever asked to
    // a follow-up, which is worse than nothing: it invites an explanation of the wrong thing.
    for (i in (answerIndex - 1) downTo 0) {
      val prev = lines[i]
      if (prev.who == Who.USER) return (prev.topic ?: prev.display ?: prev.text).orEmpty()
    }
    return ""
  }

  /**
   * The prompt. [question] must be non-blank — the caller should refuse rather than send a prompt with
   * no question in it, which is what produced an explanation of a different fact.
   *
   * [maths] switches the shape from a justification to a worked derivation, because one sentence
   * cannot carry a calculation.
   */
  fun prompt(
    question: String,
    answer: String,
    evidence: String,
    maths: Boolean,
    bengali: Boolean,
  ): String = buildString {
    append("My question: ${question.trim().take(300)}\n\n")
    append("My answer: ${answer.trim().take(700)}\n")
    if (evidence.isNotBlank()) append(evidence)
    if (maths) {
      append("\nExplain how this answer was worked out. Follow this shape exactly:\n")
      append("STEPS: the working, as numbered lines. One operation per line, with the actual ")
      append("numbers in it, ending with the final value. Use the calculator's expression and ")
      append("result above as given — do not re-derive a different number.\n")
      append("WHY: in one sentence, why that operation is the right one for this question.\n")
      append("CONCEPT: the idea being tested, in one or two sentences a beginner could follow.\n")
      append("Every line must follow from the line above it. If you cannot show working that ")
      append("actually reaches the answer, reply with this one line instead: CANNOT_EXPLAIN")
    } else {
      append("\nExplain MY QUESTION, using MY ANSWER as the thing to justify. ")
      append("Follow this shape exactly:\n")
      append("ANSWER: restate, in one sentence, what my answer says. No new facts.\n")
      append("REASON: one sentence saying why THAT is the answer to MY QUESTION above. ")
      append("It must be a reason for this answer to this question — not a different fact about ")
      append("the same topic. If the answer is a definition, say what makes it the definition; ")
      append("do not bring in an unrelated detail such as what light is best, who discovered it, ")
      append("or where it happens.\n")
      append("CONCEPT: the idea behind it, in one or two sentences a beginner could follow.\n")
      append("Do not introduce any fact that my answer does not already contain. ")
      append("If you cannot justify the answer without adding a different fact, reply with this ")
      append("one line instead: CANNOT_EXPLAIN")
    }
    if (bengali) append("\nWrite in Bengali, in simple words.")
  }

  /**
   * Does the REASON line actually justify the answer, or has it wandered onto a different fact?
   *
   * Measured failure: the answer defined photosynthesis, and the reason offered was "spectroscopy has
   * proved RED LIGHT is the most effective" — true, on topic, and no justification at all. A prompt
   * instruction alone did not stop it, so there is a second check here.
   *
   * The test is word overlap: a real justification reuses the answer's own vocabulary (plants,
   * glucose, process). Wandering onto a neighbouring fact brings in words the answer never used.
   * Crude, but it needs no model call and it catches the observed case.
   */
  fun reasonStaysOnTopic(answer: String, explanation: String): Boolean {
    val reason = field(explanation, "REASON") ?: return true      // nothing to judge
    val answerWords = contentWords(answer)
    if (answerWords.size < 3) return true                          // too short to judge fairly
    val reasonWords = contentWords(reason)
    if (reasonWords.isEmpty()) return true
    val shared = reasonWords.count { w -> answerWords.any { it.startsWith(w) || w.startsWith(it) } }
    // At least a third of the reason's content words should come from the answer. A justification
    // built from the answer's own terms clears this easily; an unrelated fact does not.
    return shared * 3 >= reasonWords.size
  }

  /** The text after "NAME:" in an explanation, up to the next field. */
  fun field(explanation: String, name: String): String? {
    val re = Regex("(?im)^\\s*$name\\s*:\\s*(.+?)(?=\\n\\s*[A-Z]+\\s*:|$)", RegexOption.DOT_MATCHES_ALL)
    val m = re.find(explanation) ?: return null
    val body = m.groupValues[1].trim()
    return body.ifBlank { null }
  }

  private val BENGALI_STOP = setOf(
    "এই", "সেই", "এবং", "বা", "ও", "এর", "একটি", "একটা", "এক", "হয়", "হয়েছে", "হলো", "থেকে",
    "জন্য", "সাথে", "করে", "করা", "বলে", "যায়", "থাকে", "তার", "কিন্তু", "আর", "সব", "কিছু",
    "the", "and", "for", "with", "that", "this", "from", "which", "what", "is", "are", "was", "were",
  )

  private fun contentWords(s: String): List<String> = s.lowercase()
    .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
    .split(' ')
    .filter { it.length >= 3 && it !in BENGALI_STOP }
}
