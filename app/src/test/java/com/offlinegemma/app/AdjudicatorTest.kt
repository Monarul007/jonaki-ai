package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adjudicator decides whether a study passage settles a question. These cases are the real ones
 * from the device logs, because that decision is what made a wrong answer possible.
 */
class AdjudicatorTest {
  private fun p(text: String, source: String = "biddabari") = Knowledge.Passage(source, "exam", text)

  private val districtKey = p(
    "প্রশ্ন: বাংলাদেশের মুক্তিযুদ্ধের সময় প্রথম কোন জেলা শত্রুমুক্ত হয়? " +
      "ক) মাগুরা খ) মেহেরপুর গ) যশোর ঘ) ময়মনসিংহ উত্তর: যশোর",
  )

  @Test fun readsTheAnswerFromAnAnswerKey() {
    val key = Adjudicator.answerKey(districtKey)
    assertNotNull(key)
    assertEquals("যশোর", key!!.answer)
    assertTrue(key.question.contains("প্রথম কোন জেলা"))
  }

  @Test fun ordinaryProseIsNotAnAnswerKey() {
    assertNull(Adjudicator.answerKey(p("সালোকসংশ্লেষণ হলো সেই প্রক্রিয়া যেখানে উদ্ভিদ খাদ্য তৈরি করে।")))
  }

  /** The exact question that produced a wrong answer on the device. */
  @Test fun theDistrictQuestionIsRecognisedAsAnswered() {
    val key = Adjudicator.answerKey(districtKey)!!
    assertTrue(Adjudicator.answers(key, "বাংলাদেশের মুক্তিযুদ্ধের সময় প্রথম কোন জেলা শত্রুমুক্ত হয়"))
    // …and also when the ASR mangles a word, as it did: "কুন" for "কোন".
    assertTrue(Adjudicator.answers(key, "বাংলাদেশের মুক্তিযুদ্ধের সময় প্রথম কুন জেলা শত্রুমুক্ত হয়"))
  }

  @Test fun anUnrelatedQuestionIsNotClaimedAsAnswered() {
    val key = Adjudicator.answerKey(districtKey)!!
    assertFalse(Adjudicator.answers(key, "সালোকসংশ্লেষণ কী"))
    assertFalse(Adjudicator.answers(key, "what is the capital of France"))
  }

  /** The failure: the study key said যশোর and the answer said ঝিনাইদহ. */
  @Test fun aWrongAnswerContradictsTheKey() {
    val key = Adjudicator.answerKey(districtKey)!!
    assertTrue(Adjudicator.contradicts("মুক্তিযুদ্ধে প্রথম শত্রুমুক্ত জেলা হিসেবে ঝিনাইদহকে উল্লেখ করা হয়েছে।", key))
  }

  @Test fun theRightAnswerDoesNotContradictTheKey() {
    val key = Adjudicator.answerKey(districtKey)!!
    assertFalse(Adjudicator.contradicts("বাংলাদেশের মুক্তিযুদ্ধের সময় প্রথম শত্রুমুক্ত জেলা হলো যশোর।", key))
    // The option label alone is enough, which is how a short answer usually appears.
    assertFalse(Adjudicator.contradicts("যশোর", key))
  }
}
