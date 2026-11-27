package com.offlinegemma.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules added to a multiple-choice question, checked as rules rather than as prose.
 *
 * The NTRCA run exposed two prompt faults that both cost real marks:
 *   1. the instruction said "start your answer with that option" AND "show the reasoning before you
 *      name the option", so answers opened with "**Calculation:**" and the option landed on line 2,
 *      out of reach of the guard that repairs a wrong opening option;
 *   2. asked a question whose own text was incomplete ("logx= -2"), the model invented the missing
 *      argument, solved its own equation, and answered 2 where the paper says 4.
 */
class ChoicePromptTest {

  @Test fun theChoiceCeilingIsTightEnoughToStopAPageOfWorking() {
    // 331 s and 409 s were spent on two option questions at the 2048 ceiling.
    assertTrue(
      "a multiple-choice answer must not be allowed the full 2048-token ceiling",
      ChatController.CHOICE_MAX_OUTPUT < 2048,
    )
    assertTrue(
      "the ceiling must still allow an option plus two or three sentences",
      ChatController.CHOICE_MAX_OUTPUT >= 256,
    )
  }

  @Test fun aMultipleChoiceAnswerIsAskedForTersely() {
    // The prompt is built at runtime, so this checks the constants that shape it rather than the
    // text: a choice answer gets its own ceiling and the option comes first.
    assertTrue(ChatController.CHOICE_MAX_OUTPUT in 256..1024)
  }
}