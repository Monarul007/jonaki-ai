package com.offlinegemma.app

/** Why an answer is not ready to be the final answer. */
internal enum class AnswerProblem { OK, UNSURE, MISSING_PARTS, NO_SOURCES, NOT_CITED }

internal data class AnswerCheck(val problem: AnswerProblem, val detail: String = "")

private val PART_RX = Regex("""\((ক|খ|গ|ঘ|[a-d]|iv|i{1,3}|v)\)""", RegexOption.IGNORE_CASE)

/**
 * The stopping rule for the research loop: an answer is final only when every requested part is
 * present, it does not admit uncertainty, it has sources when the question needs the web, and at
 * least one of those sources is named. Bounded by the caller (MAX_RESEARCH_ROUNDS).
 */
internal fun verifyAnswer(
  answer: String,
  question: String,
  sources: List<Source>,
  needsWeb: Boolean,
  offline: Boolean,
  webSearchOn: Boolean,
): AnswerCheck {
  val text = answer.trim()
  if (text.isBlank()) return AnswerCheck(AnswerProblem.UNSURE)
  // A live demo or drawing is not a textbook answer; skip the textbook checks.
  if (text.contains("```html") || text.contains("```svg")) return AnswerCheck(AnswerProblem.OK)
  if (Tools.isUnsure(text)) return AnswerCheck(AnswerProblem.UNSURE)

  val parts = PART_RX.findAll(question).map { it.groupValues[1] }.distinct().toList()
  val missing = parts.filterNot { mentionsPart(text, it) }
  if (missing.isNotEmpty()) {
    return AnswerCheck(AnswerProblem.MISSING_PARTS, missing.joinToString(", ") { "($it)" })
  }

  if (needsWeb && !offline && webSearchOn && sources.isEmpty()) {
    return AnswerCheck(AnswerProblem.NO_SOURCES)
  }
  if (sources.isNotEmpty() && sources.none { cites(text, it) }) {
    return AnswerCheck(AnswerProblem.NOT_CITED)
  }
  return AnswerCheck(AnswerProblem.OK)
}

/** "(ক)", "**ক.**" and "**$(\text{ক})$**" all count as answering part ক. */
private fun mentionsPart(answer: String, part: String): Boolean {
  val plain = answer.replace(Regex("""\\text\{([^{}]*)\}"""), "$1").replace("$", "")
  val pattern = Regex("""(?i)(^|[\s*])\(?\Q$part\E[\).]""", RegexOption.MULTILINE)
  return pattern.containsMatchIn(plain) || plain.contains("($part)", ignoreCase = true)
}

/** The answer names a source by its domain or by the first few words of its title. */
private fun cites(answer: String, source: Source): Boolean {
  val a = answer.lowercase()
  val domain = source.url.substringAfter("://", "").substringBefore("/").removePrefix("www.").lowercase()
  if (domain.length >= 4 && a.contains(domain)) return true
  val words = source.title.lowercase().split(Regex("\\s+")).take(4).joinToString(" ")
  return words.length >= 8 && a.contains(words)
}
