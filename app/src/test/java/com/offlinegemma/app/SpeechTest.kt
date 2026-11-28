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

  /**
   * A web answer is cited with bare [1]/[2]/[3]. Spoken aloud those become "one", "three" — an answer
   * that came back with a [3] after almost every line was read as a poem punctuated by numbers.
   */
  @Test fun citationNumbersAreNotReadAloud() {
    val said = speakable("মনে হল, কালো রঙে একটা গভীর দূরত্ব [3]\nঘনিয়ে নিয়েছে নিজের চার দিকে, [3]")
    assertFalse(said.contains("[3]"))
    assertFalse(said.contains("3"))
    // The words themselves survive.
    assertTrue(said.contains("কালো রঙে"))
  }

  /** A markdown link keeps its text; only the bare numeric citation is dropped. */
  @Test fun aLinkKeepsItsTextButABareCitationGoes() {
    assertEquals("সূত্র দেখুন", speakable("সূত্র [দেখুন](https://example.com)").trim())
    assertEquals("উত্তরটি এখানে।", speakable("উত্তরটি এখানে। [12]").trim())
  }
}
