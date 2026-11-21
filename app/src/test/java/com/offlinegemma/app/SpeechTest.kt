package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTest {
  @Test fun bengaliIsDetected() {
    assertTrue(hasBengali("আমি ভাত খাই।"))
    assertFalse(hasBengali("I eat rice."))
  }

  @Test fun sameLanguageSentencesAreJoinedButLanguagesAreKeptApart() {
    val p = pieces("Hello there. How are you? আমি ভালো আছি। Great!", nothingPlaying = false)
    assertEquals(listOf("Hello there. How are you?", "আমি ভালো আছি।", "Great!"), p)
  }

  @Test fun piecesStayShort() {
    val long = (1..30).joinToString(" ") { "Sentence number $it is here." }
    assertTrue(pieces(long, nothingPlaying = false).all { it.length <= 220 })
  }

  @Test fun longFirstPieceIsCutAtTheFirstCommaForAFastStart() {
    val first = "Photosynthesis is how plants make food, using sunlight, water and carbon dioxide from the air around them every day."
    val p = pieces(first, nothingPlaying = true)
    assertEquals("Photosynthesis is how plants make food,", p[0])
    assertEquals(1, pieces(first, nothingPlaying = false).size)
  }
}
