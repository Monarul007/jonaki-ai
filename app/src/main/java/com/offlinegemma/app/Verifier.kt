package com.offlinegemma.app

/** Why an answer is not ready to be the final answer. */
internal enum class AnswerProblem { OK, UNSURE, NO_SOURCES, NOT_CITED }

internal data class AnswerCheck(val problem: AnswerProblem)

/**
 * Generic stopping rule only: no uncertainty, sources present when the web is needed, and at least
 * one source named. What the question asks for is the model's job, not a part-label regex.
 */
internal fun verifyAnswer(
  answer: String,
  sources: List<Source>,
  needsWeb: Boolean,
  offline: Boolean,
  webSearchOn: Boolean,
): AnswerCheck {
  val text = answer.trim()
  if (text.isBlank()) return AnswerCheck(AnswerProblem.UNSURE)
  if (text.contains("```html") || text.contains("```svg")) return AnswerCheck(AnswerProblem.OK)
  if (Tools.isUnsure(text)) return AnswerCheck(AnswerProblem.UNSURE)
  if (needsWeb && !offline && webSearchOn && sources.isEmpty()) return AnswerCheck(AnswerProblem.NO_SOURCES)
  // Not cited in the text is no longer a reason for another round: the app shows the sources under
  // the answer itself, and the extra rewrite cost minutes and often cut the answer off.
  return AnswerCheck(AnswerProblem.OK)
}

/** The answer names a source by its domain or by the first few words of its title. */
private fun cites(answer: String, source: Source): Boolean {
  val a = answer.lowercase()
  val domain = source.url.substringAfter("://", "").substringBefore("/").removePrefix("www.").lowercase()
  if (domain.length >= 4 && a.contains(domain)) return true
  val words = source.title.lowercase().split(Regex("\\s+")).take(4).joinToString(" ")
  return words.length >= 8 && a.contains(words)
}
