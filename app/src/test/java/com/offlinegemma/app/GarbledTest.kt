package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The model sometimes starts a Bengali heading and switches to an English term mid-word, producing
 * "ধvariables". Neither character is foreign, so keepBengali cannot see it.
 */
class GarbledTest {

  @Test fun dropsAStrayBengaliLetterGluedToAnEnglishWord() {
    assertEquals("**variables:**", healGarbled("**ধvariables:**"))
  }

  @Test fun keepsRealBengaliWords() {
    assertEquals("পুত্রের বয়স", healGarbled("পুত্রের বয়স"))
  }

  @Test fun keepsAMixedSentenceIntact() {
    val s = "সমীকরণ (১): F + S = 60"
    assertEquals(s, healGarbled(s))
  }

  @Test fun worksThroughTheAnswerCleaner() {
    assertEquals("variables:", keepBengali("ধvariables:"))
  }
}
