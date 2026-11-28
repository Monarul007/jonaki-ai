package com.offlinegemma.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the message is already a good web query, the planner's 6-17 s round-trip is skipped.
 *
 * Being wrong in the permissive direction costs retrieval quality, so these cases are the real
 * questions from the device logs, plus the shapes that must NOT qualify.
 */
class GoodQueryTest {
  private fun good(q: String, canSearch: Boolean = true) = Rules.messageIsGoodQuery(q, canSearch)

  @Test fun aRealBengaliQuestionIsItsOwnQuery() {
    assertTrue(good("বাংলাদেশের মুক্তিযুদ্ধের সময় প্রথম কোন জেলা শত্রুমুক্ত হয়"))
    assertTrue(good("দক্ষিণ আফ্রিকার মুদ্রার নাম কী"))
    assertTrue(good("জাতিসংঘের নিরাপত্তা পরিষদের স্থায়ী সদস্য সংখ্যা কত"))
    assertTrue(good("রবীন্দ্রনাথ ঠাকুরের সোনার তরী কবিতাটি পড়ে শোনাও"))
  }

  @Test fun anEnglishQuestionQualifies() {
    assertTrue(good("what is the capital of australia"))
  }

  /** "Tell me…" phrasing is a request, not a query — a search engine would discard it. */
  @Test fun conversationalLeadInsAreNotQueries() {
    assertFalse(good("আমাকে বলো সালোকসংশ্লেষণ কী"))
    assertFalse(good("explain what photosynthesis is please"))
    assertFalse(good("can you tell me about the moon"))
  }

  @Test fun shortMessagesAreNotQueries() {
    assertFalse(good("কী"))
    assertFalse(good("হ্যালো"))
    assertFalse(good("what is this"))
  }

  /** Digits mean arithmetic may be involved, and the planner supplies the calculator expression. */
  @Test fun anythingNumericKeepsThePlanner() {
    assertFalse(good("১৭% of ২৩৫০ কত"))
    assertFalse(good("what is 15 percent of 2400"))
  }

  @Test fun anExamQuestionKeepsThePlanner() {
    val q = "সালোকসংশ্লেষণে কী উৎপন্ন হয়? (ক) গ্লুকোজ (খ) প্রোটিন (গ) লিপিড (ঘ) ভিটামিন"
    assertFalse(good(q))
  }

  @Test fun withoutSearchThereIsNothingToSkip() {
    assertFalse(good("বাংলাদেশের রাজধানী কী", canSearch = false))
  }
}
