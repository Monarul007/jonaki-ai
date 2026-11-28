package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a retrieved passage counts as evidence.
 *
 * An absolute cosine floor is not enough on a real study pack. Measured on the phone (2026-11-28):
 * "Tareq Rahman Bangladesh-er koto tomo prodhantromntri" scored **0.751** and **12 passages** cleared
 * the 0.70 gate, because the pack holds six million characters of exam papers where "প্রধানমন্ত্রী"
 * appears 132 times. The top three of that crowd included a passage about Tajuddin Ahmad, and the
 * answer announced it came from the user's study files. Relevance therefore has to mean "clearly
 * better than the field", which is what these cases pin.
 */
class RetrievalMarginTest {

  /** A query the pack really answers: one passage stands out, then a tail of near-misses. */
  @Test fun aStandoutPassageIsEvidence() {
    val kept = Semantic.margin(listOf(0.81f, 0.748f, 0.741f, 0.735f, 0.730f))
    assertEquals(listOf(0.81f), kept)
  }

  /** Two passages both stating the fact are fine; they tie above the rest. */
  @Test fun twoEquallyGoodPassagesBothSurvive() {
    val kept = Semantic.margin(listOf(0.83f, 0.825f, 0.760f, 0.755f))
    assertEquals(listOf(0.83f, 0.825f), kept)
  }

  /** The real failure: everything within a few thousandths, so nothing is actually an answer. */
  @Test fun aCrowdOfNearIdenticalScoresIsNotEvidence() {
    val crowd = listOf(0.755f, 0.754f, 0.753f, 0.752f, 0.751f, 0.750f, 0.748f, 0.747f)
    assertTrue("a 8-way tie was accepted as evidence", Semantic.margin(crowd).isEmpty())
  }

  /** A single passage with nothing else close is evidence even if it clears the gate barely. */
  @Test fun oneLoneHItIsEvidence() {
    assertEquals(listOf(0.705f), Semantic.margin(listOf(0.705f)))
  }

  @Test fun nothingAboveTheGateMeansNothingToKeep() {
    assertTrue(Semantic.margin(emptyList()).isEmpty())
  }

  /** Never hand the prompt more than it can hold, however flat the distribution. */
  @Test fun neverMoreThanThePromptBudget() {
    val many = (0..20).map { 0.95f - it * 0.001f }   // wide but shallow tail
    assertTrue(Semantic.margin(many).size <= Semantic.LIMIT)
  }
}
