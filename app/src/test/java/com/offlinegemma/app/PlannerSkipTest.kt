package com.offlinegemma.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planner is a whole model round-trip (6.5 s on E4B) before any answer token exists. It may only
 * be skipped when it would have had nothing to contribute — and must never be skipped when a search
 * query, or the calculator's expression, is the point of asking.
 */
class PlannerSkipTest {

  private fun skip(question: String, canSearch: Boolean) = Rules.skipPlanner(question, canSearch)

  @Test fun aQuestionThatMightNeedLookingUpAlwaysPlans() {
    // Options are readable locally, but "who is X" needs a query only the model can write.
    assertFalse(skip("Who is the current Prime Minister of Bangladesh?", canSearch = true))
    assertFalse(skip("কোনটি সঠিক? (ক) ক (খ) খ (গ) গ (ঘ) ঘ", canSearch = true))
  }

  @Test fun optionsWithNoSearchSkipThePlanner() {
    assertTrue(skip("বাংলা বর্ণমালায় পরাশ্রয়ী বর্ণ কয়টি? (ক) ৩ (খ) ৪ (গ) ৫ (ঘ) ৬", canSearch = false))
  }

  @Test fun aSumAlwaysPlansSoTheCalculatorStillRuns() {
    // The important negative: skipping would also drop the calculator, and a self-worked expression
    // is exactly what a small model gets wrong (17% of 2350 once came out as 409.5).
    assertFalse(skip("17% of 2350", false))
    assertFalse(skip("(3.5 * 12) / 4", false))
  }

  @Test fun aWordProblemAlwaysPlans() {
    // Building the set-up is the planner's whole purpose.
    assertFalse(skip("3 men or 5 boys can finish a job in 20 days. In how many days will 4 men and 10 boys finish it together?", false))
    assertFalse(skip("পিতা ও পুত্রের বয়সের গড় ৩০ বছর। ৬ বছর পর অনুপাত ৫:১। পুত্রের বয়স কত?", false))
  }

  @Test fun plainProseSkipsThePlanner() {
    // No search, no options, nothing numeric: there is nothing for the planner to decide.
    assertTrue(skip("বৃষ্টি নিয়ে দুই লাইনের একটি কবিতা লেখো", false))
    assertTrue(skip("Hi! How are you today?", false))
    assertTrue(skip("Translate to Bengali: The library opens at nine in the morning.", false))
  }
}