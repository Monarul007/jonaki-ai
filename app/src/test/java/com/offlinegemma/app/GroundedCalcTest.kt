package com.offlinegemma.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A calculator answer is only evidence if the expression was built from the question's own numbers.
 * The planner really did write `(9800 / 19) * 5` for a 2:3:4:5 share — 19 appears nowhere — and the
 * answer then presented that result as verified arithmetic.
 */
class GroundedCalcTest {

  private val ratio = "৯৮০০ টাকা ২ : ৩ : ৪ : ৫ অনুপাতে ভাগ করলে বৃহত্তর ও ক্ষুদ্রতর অংশের পার্থক্য কত?"

  @Test fun rejectsTheInventedCoefficient() {
    assertFalse(ChatController.groundedCalc(ratio, "(9800 / 19) * 5 - (9800 / 19) * 2"))
  }

  @Test fun acceptsAnExpressionBuiltFromTheQuestion() {
    // 9800, 2, 3, 4 and 5 are all given, so these need no invented constant.
    assertTrue(ChatController.groundedCalc(ratio, "9800 * 5"))
    assertTrue(ChatController.groundedCalc(ratio, "9800 * 5 - 9800 * 2"))
  }

  @Test fun rejectsADerivedTotalItCannotVerify() {
    // 14 is the ratio total (2+3+4+5). It is a legitimate number to *derive*, but it is not given,
    // so the calculator is not used for that step and the model shows its own working instead. Being
    // strict here is what stopped the invented 19 from being presented as verified arithmetic.
    assertFalse(ChatController.groundedCalc(ratio, "9800 / 14"))
  }

  @Test fun percentagesMayUseOneHundred() {
    assertTrue(ChatController.groundedCalc("৩৫০০ টাকার ১৫% কত?", "3500 * 15 / 100"))
  }

  @Test fun bengaliDigitsInTheQuestionAreUnderstood() {
    // ৯৮০০ must count as 9800, or every Bengali word problem would drop its calculation.
    assertTrue(ChatController.groundedCalc(ratio, "9800 * 5"))
    assertTrue(ChatController.numbersOf("৯৮০০ টাকা").contains("9800"))
  }

  @Test fun aQuestionWithoutNumbersGroundsNothing() {
    assertTrue(ChatController.numbersOf("অনুপাত কী?").isEmpty())
  }

  /**
   * The work-rate case: "3 men or 5 boys take 20 days, how long do 4 men and 10 boys take?" The
   * planner wrote `20 * 3 * 4 / (3 * 20 + 5 * 20)` = 1.5. Every number is in the question, so it
   * passed grounding — but it is the wrong *formula*, and the answer was built around 1.5 instead
   * of 6 days. A composed formula must never be presented as the answer.
   */
  @Test fun aComposedFormulaIsNotTreatedAsTheAnswer() {
    val work = "3 men or 5 boys can finish a job in 20 days. In how many days will 4 men and 10 boys finish it?"
    val expr = "20 * 3 * 4 / (3 * 20 + 5 * 20)"
    // Grounding alone cannot catch this: all the numbers are given.
    assertTrue(ChatController.groundedCalc(work, expr))
    // So the note must only offer it to be checked, not to be copied.
    assertFalse(ChatController.directCalc(expr))
    val note = ChatController.calcNoteFor(expr, "1.5")
    assertTrue(note, note.contains("may not be the right one"))
  }

  @Test fun aDirectCalculationIsStillAuthoritative() {
    assertTrue(ChatController.directCalc("9800 * 5"))
    val note = ChatController.calcNoteFor("9800 * 5", "49000")
    assertTrue(note, note.contains("copy that number exactly"))
  }
}
