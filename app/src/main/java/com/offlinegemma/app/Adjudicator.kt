package com.offlinegemma.app

/**
 * Picks the one passage that actually answers the question, before the model sees anything.
 *
 * Why this exists: recall was already fine and answers were still wrong. Retrieved verbatim from the
 * study pack (and placed FIRST in the prompt):
 *
 *   "প্রশ্ন: বাংলাদেশের মুক্তিযুদ্ধের সময় pertama কোন জেলা শত্রুমুক্ত হয়? … উত্তর: যশোর"
 *
 * and the model answered "ঝিনাইদহ… [4]", citing a web page. Nothing arbitrated: two sources were in
 * the prompt, the web block was three times longer (1919ch vs 642ch), and length won.
 *
 * Rules in the prompt telling the model which kind of source is authoritative are advisory, and a 4B
 * model under a large prompt ignores advice. So the choice is made here, deterministically, and a
 * study passage that answers the question is marked as binding — with the conflicting web text simply
 * not offered to the model. Cheap (no model call), explainable, and testable.
 */
object Adjudicator {
  /** A multiple-choice/answer-key passage is "প্রশ্ন: … উত্তর: X" — the pack is built from exam keys. */
  private val ANSWER_KEY = Regex("""উত্তর\s*[:：]\s*(.+)""")
  private val QUESTION = Regex("""প্রশ্ন\s*[:：]\s*(.+)""")

  /** What a passage says the answer is, when it is an answer-key passage. */
  data class Key(val answer: String, val question: String)

  fun answerKey(passage: Knowledge.Passage): Key? {
    val answer = ANSWER_KEY.find(passage.text)?.groupValues?.get(1)?.trim() ?: return null
    if (answer.isBlank() || answer.length > 80) return null
    val question = QUESTION.find(passage.text)?.groupValues?.get(1)?.trim().orEmpty()
    return Key(answer, question)
  }

  /**
   * Does an answer-key passage actually answer [question]?
   *
   * Word overlap on the question, ignoring the words every question contains. A key passage with no
   * notionally shared words is a passage that merely reached the same search result, and treating it
   * as binding would be worse than leaving the model to decide.
   */
  fun answers(key: Key, question: String): Boolean {
    val q = words(question)
    val p = words(key.question.ifBlank { key.answer })
    if (q.isEmpty() || p.isEmpty()) return false
    val shared = q.count { it in p }
    // Two shared content words is the bar: the pack's questions carry the same vocabulary as the user
    // asking them ("প্রথম কোন জেলা শত্রুমুক্ত"), while an unrelated exam question does not.
    return shared >= 2
  }

  /**
   * True when [answer] contradicts [key]: the key names something the answer never mentions.
   *
   * Compared by containment rather than equality because the key is a short option label ("যশোর")
   * and the answer is a sentence ("…প্রথম শত্রুমুক্ত জেলা হলো যশোর").
   */
  fun contradicts(answer: String, key: Key): Boolean {
    val said = words(answer)
    val want = words(key.answer)
    return want.isNotEmpty() && want.none { w -> said.any { it.startsWith(w) || w.startsWith(it) } }
  }

  private val STOP = setOf(
    "প্রথম", "কোন", "কোনটি", "কি", "কী", "কে", "কত", "হয়", "হয়েছে", "হলো", "এর", "এই", "সেই", "এবং",
    "the", "a", "an", "is", "are", "was", "were", "of", "in", "on", "to", "what", "which", "who",
  )

  private fun words(s: String): List<String> = s.lowercase()
    .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
    .split(' ')
    .filter { it.length >= 2 && it !in STOP }
}
