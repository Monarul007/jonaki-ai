package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How many tokens a prompt costs. Counting every script at four characters per token under-estimates
 * Bengali badly, and the app used to trust that number when deciding whether a chat still fits.
 */
class BudgetTest {

  @Test fun latinTextCountsFourCharsPerToken() {
    assertEquals(4.0, ChatController.charsPerToken("the quick brown fox"), 0.001)
  }

  @Test fun bengaliTextCountsAboutTwoCharsPerToken() {
    // A real sentence carries spaces, which are not Bengali letters, so it lands slightly above 2.
    val sentence = "অনুপাত হলো দুই বা তার বেশি রাশির তুলনা শতকরা হিসাব"
    val v = ChatController.charsPerToken(sentence)
    assertTrue("expected between 2 and 2.6, got $v", v >= 2.0 && v <= 2.6)
    // With no spaces at all it is exactly the Bengali figure.
    assertEquals(2.0, ChatController.charsPerToken("অ".repeat(50)), 0.001)
  }

  @Test fun mixedTextLandsBetween() {
    val mixed = "ratio অনুপাত of two numbers"
    val v = ChatController.charsPerToken(mixed)
    assertTrue("expected between 2 and 4, got $v", v > 2.0 && v < 4.0)
  }

  @Test fun emptyTextFallsBackToLatin() {
    assertEquals(4.0, ChatController.charsPerToken(""), 0.001)
  }

  /**
   * The regression this exists for: a 3,000-character Bengali prompt must be budgeted as well over
   * a thousand tokens. At the old flat rate of 4 it looked like 750 and slipped through the window.
   */
  @Test fun bengaliPromptIsNotUnderestimated() {
    val prompt = "অ".repeat(3000)
    val tokens = (prompt.length / ChatController.charsPerToken(prompt)).toInt()
    assertTrue("3000 Bengali chars budgeted as only $tokens tokens", tokens >= 1200)
  }
}
