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

  /**
   * Measured: the ratio answer "১৫ : ২১ : ৯১" was spoken as "dupur 3te beje 21 minute 19" — the engine
   * reads spaced digits around a colon as a CLOCK TIME. Correct answer, unintelligible.
   */
  @Test fun aRatioIsNotReadAsAClockTime() {
    val said = speakable("অনুপাত হলো ১৫ : ২১ : ৯১।")
    assertFalse("a colon must not survive as a colon", said.contains(":"))
    assertTrue("the numbers stay", said.contains("১৫") && said.contains("৯১"))
    assertTrue("spoken as a ratio", said.contains("অনুপাত হলো ১৫ অনুপাত ২১ অনুপাত ৯১"))
  }

  /** A real clock time (no spaces) must be left alone — corrupting it would be worse than the bug. */
  @Test fun aClockTimeIsNotCalledARatio() {
    val said = speakable("সকাল ৯:৩০ এ শুরু হবে।")
    assertTrue("times keep their colon", said.contains("৯:৩০"))
  }

  @Test fun anEnglishRatioIsSpokenAsTo() {
    val said = speakable("The ratio is 15 : 21 : 91.")
    assertFalse(said.contains(":"))
    assertTrue(said.contains("15 to 21 to 91"))
  }

  /** A real link must keep working: the text inside a pair of brackets is not a citation. */
  @Test fun aNoteInBracketsIsNotMistakenForACitation() {
    assertTrue(speakable("এটি [দ্রষ্টব্য] একটি নোট।").contains("দ্রষ্টব্য"))
  }

  /**
   * Measured on the device: a ratio question answered "…হলো ১৫ : ২১ : ৯১।" — correct — then appended
   * "এই বিষয়ে আরও তথ্য জানতে আপনি নিম্নলিখিত সূত্রপাতটি দেখতে পারেন:" and a 200-character encoded URL.
   * Decode was 34.8 s for 124 tokens, most of it that tail, and the URL is unlistenable.
   */
  @Test fun aTrailingSourceOfferIsNotReadAloud() {
    val answer = "অনুপাতগুলোর ধারাবাহিক অনুপাত হলো ১৫ : ২১ : ৯১। " +
      "এই বিষয়ে আরও তথ্য জানতে আপনি নিম্নলিখিত সূত্রপাতটি দেখতে পারেন: " +
      "বহুরাশিক অনুপাত — https://sattacademy.com/guide/%E0%A6%AC%E0%A6%B9%E0%A7%81"
    val said = speakable(answer)
    assertFalse("the URL must go", said.contains("http"))
    assertFalse("the encoded mush must go", said.contains("%E0"))
    // The answer's numbers survive; the colons between them do not, because a spaced colon is spoken
    // as a ratio. A colon elsewhere ("পারেন:") is prose and correctly left alone — asserting "no colon
    // at all" was too broad and only failed on punctuation that should stay.
    assertTrue("the answer survives", said.contains("১৫") && said.contains("২১") && said.contains("৯১"))
    assertFalse("no digit-colon-digit reaches the engine", Regex("[\\d০-৯]\\s*:\\s*[\\d০-৯]").containsMatchIn(said))
  }

  @Test fun aSentenceThatNamesItsSourceIsNotReadOut() {
    assertFalse(speakable("উত্তরটি পাঁচ [সূত্র 1]।").contains("সূত্র"))
    assertFalse(speakable("উত্তরটি পাঁচ [source 2]।").contains("source"))
  }

  /**
   * The reason every earlier fix looked right in a test and still failed on the device.
   *
   * feed() cleans the whole answer, then pieces() SPLITS it. A split can land right after a citation,
   * or between two of them, so the text handed to the engine is a fragment that no longer matches a
   * pattern written against the original sentence. These are the fragments that were being spoken.
   */
  @Test fun aSplitFragmentCarryingACitationIsStillCleaned() {
    // What pieces() produces for: "দেশগুলো হলো [1, 2, 5]। চীন [4]।" — note the bracket left dangling.
    for (frag in listOf("[1, 2, 5]", "চীন [4]", "দেশগুলো [1, 2,", "5]", "[4]।")) {
      val spoken = stripCitations(frag).trim()
      assertFalse("'$frag' still speaks a bracket: '$spoken'", spoken.contains("["))
    }
    // …and a fragment that is nothing but a citation becomes empty, so it is skipped entirely.
    assertEquals("", stripCitations("[1, 2, 4, 5]").trim())
  }

  /** A markdown link keeps its text; only the bare numeric citation is dropped. */
  @Test fun aLinkKeepsItsTextButABareCitationGoes() {
    assertEquals("সূত্র দেখুন", speakable("সূত্র [দেখুন](https://example.com)").trim())
    assertEquals("উত্তরটি এখানে।", speakable("উত্তরটি এখানে। [12]").trim())
  }
}
