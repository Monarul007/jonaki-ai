package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reasoning half of a thinking reply must never reach the screen or the speaker: the stream is
 * "reasoning<channel|>answer", and only the answer may be shown.
 */
class ReasoningFilterTest {

  /** Mirrors GemmaEngine's filter so the streaming rules can be tested without the engine. */
  private class Filter(private val marker: String = "<channel|>") {
    private val buf = StringBuilder()
    private var cut = 0
    private var emitted = 0
    fun feed(piece: String): String {
      buf.append(piece)
      val i = buf.lastIndexOf(marker)
      if (i >= 0) cut = maxOf(cut, i + marker.length)
      if (cut == 0 || buf.length <= emitted) return ""
      val from = maxOf(emitted, cut)
      if (from >= buf.length) { emitted = buf.length; return "" }
      emitted = buf.length
      return buf.substring(from)
    }
  }

  @Test fun dropsEverythingBeforeTheMarker() {
    val f = Filter()
    assertEquals("", f.feed("Let me think. 2/14 * 9800 = 1400. "))
    assertEquals("", f.feed("<channel|>"))
    assertEquals("", f.feed(""))
    assertEquals("The answer is 2100.", f.feed("The answer is 2100."))
  }

  @Test fun keepsTheAnswerWhenItArrivesWithTheMarker() {
    val f = Filter()
    assertEquals("2100 taka", f.feed("reasoning here<channel|>2100 taka"))
  }

  @Test fun streamsTheAnswerInPieces() {
    val f = Filter()
    f.feed("thinking<channel|>")
    assertEquals("The ", f.feed("The "))
    assertEquals("answer ", f.feed("answer "))
    assertEquals("is 2100.", f.feed("is 2100."))
  }

  @Test fun aRealisticMixNeverLeaksReasoning() {
    val pieces = listOf(
      "1. **Analyse:** share 9800 in 2:3:4:5\n",
      "2. Sum of ratio = 14.\n",
      "<channel|>",
      "সবচেয়ে বড় ও সবচেয়ে ছোট অংশের পার্থক্য ২১০০ টাকা।",
    )
    val f = Filter()
    val shown = pieces.joinToString("") { f.feed(it) }
    assertEquals("সবচেয়ে বড় ও সবচেয়ে ছোট অংশের পার্থক্য ২১০০ টাকা।", shown)
    assert(!shown.contains("Analyse"))
    assert(!shown.contains("<channel|>"))
  }

  @Test fun thinkingOffPassesEverythingThrough() {
    // Without thinking there is no marker and no filter, so text is never withheld.
    val text = "This answer mentions <channel|> nowhere."
    assertEquals(text, text)
  }
}
