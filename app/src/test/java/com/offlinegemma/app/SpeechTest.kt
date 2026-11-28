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

  /**
   * The forms actually seen on the device. A citation is usually a LIST, and an earlier fix matched
   * only a single number — so "[1, 2, 4, 5]" went to the engine and was read as "one two four five".
   */
  @Test fun citationListsAreNotReadAloud() {
    for (cite in listOf("[1, 2, 4, 5]", "[1, 2, 5]", "[4]", "[1]", "[10,11]", "[1, 2, 3, 4, 5]")) {
      val said = speakable("উত্তরটি হলো পাঁচটি $cite।")
      assertFalse("$cite should be removed", said.contains("["))
      assertFalse("$cite numbers should be gone", said.contains("4") || said.contains("5") || said.contains("1,"))
      assertTrue("the answer itself survives", said.contains("পাঁচটি"))
    }
  }

  @Test fun bengaliDigitsInACitationAreRemovedToo() {
    val said = speakable("উত্তর পাঁচটি [১, ২, ৫]।")
    assertFalse(said.contains("["))
    assertFalse(said.contains("১"))
  }

  @Test fun aRangedCitationIsRemoved() {
    assertFalse(speakable("দেখুন [1-3] এবং [২–৪]।").contains("["))
  }

  /** A real link must keep working: the text inside a pair of brackets is not a citation. */
  @Test fun aNoteInBracketsIsNotMistakenForACitation() {
    assertTrue(speakable("এটি [দ্রষ্টব্য] একটি নোট।").contains("দ্রষ্টব্য"))
  }

  /** A markdown link keeps its text; only the bare numeric citation is dropped. */
  @Test fun aLinkKeepsItsTextButABareCitationGoes() {
    assertEquals("সূত্র দেখুন", speakable("সূত্র [দেখুন](https://example.com)").trim())
    assertEquals("উত্তরটি এখানে।", speakable("উত্তরটি এখানে। [12]").trim())
  }
}
