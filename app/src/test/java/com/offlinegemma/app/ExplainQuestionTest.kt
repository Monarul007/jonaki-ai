package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the "My question:" line for an explanation comes from. */
class ExplainQuestionTest {
  private fun lines(vararg l: ChatLine) = l.toList()

  @Test fun theQuestionIsTheUserMessageBeforeTheAnswer() {
    val ls = lines(
      ChatLine(1, Who.USER, "সালোকসংশ্লেষণ কী", topic = "সালোকসংশ্লেষণ কী"),
      ChatLine(2, Who.MODEL, "সালোকসংশ্লেষণ হলো…"),
    )
    assertEquals("সালোকসংশ্লেষণ কী", Explanations.questionFor(ls, 1))
  }

  /**
   * Measured on the device: a line stored with no `topic` produced "QUESTION: (none)", so the model
   * was asked to explain an answer without being told what the question was — and it wandered onto a
   * different fact.
   */
  @Test fun aMissingTopicFallsBackToTheUserText() {
    val ls = lines(
      ChatLine(1, Who.USER, "সালোকসংশ্লেষণ কী"),
      ChatLine(2, Who.MODEL, "সালোকসংশ্লেষণ হলো…"),
    )
    assertEquals("সালোকসংশ্লেষণ কী", Explanations.questionFor(ls, 1))
  }

  /** A follow-up question must use the follow-up, not the first thing ever asked. */
  @Test fun theNearestUserMessageWins() {
    val ls = lines(
      ChatLine(1, Who.USER, "প্রথম প্রশ্ন"),
      ChatLine(2, Who.MODEL, "প্রথম উত্তর"),
      ChatLine(3, Who.USER, "দ্বিতীয় প্রশ্ন"),
      ChatLine(4, Who.MODEL, "দ্বিতীয় উত্তর"),
    )
    assertEquals("দ্বিতীয় প্রশ্ন", Explanations.questionFor(ls, 3))
  }

  @Test fun nothingIsBetterThanTheWrongQuestion() {
    assertEquals("", Explanations.questionFor(lines(ChatLine(1, Who.MODEL, "উত্তর")), 0))
  }
}

/**
 * The explanation that was actually produced on the device, and why it was worse than the answer.
 *
 * The answer defined photosynthesis. The REASON talked about red light being the most effective —
 * true, on-topic, and no justification at all. A prompt instruction alone did not stop it.
 */
class ExplainStaysOnTopicTest {
  private val answer =
    "সালোকসংশ্লেষণ হলো সবুজ উদ্ভিদ, শৈবাল ও কিছু ব্যাকটেরিয়ার এমন একটি প্রক্রিয়া যার মাধ্যমে আলোক শক্তি " +
      "ব্যবহার করে কার্বন ডাই অক্সাইড ও জল থেকে গ্লুকোজ তৈরি হয়।"

  /** The real drifting explanation, verbatim from the chat file. */
  private val wandered =
    "ANSWER: সালোকসংশ্লেষণ হলো একটি প্রক্রিয়া যার মাধ্যমে সবুজ উদ্ভিদ সূর্যের আলো ব্যবহার করে গ্লুকোজ তৈরি করে।\n" +
      "REASON: বর্ণালিবীক্ষণ যন্ত্রের সাহায্যে প্রমাণিত হয়েছে যে, দৃশ্যমান আলোর মধ্যে লাল আলোই " +
      "সালোকসংশ্লেষণ প্রক্রিয়ায় সবচেয়ে বেশি কার্যকরী।\n" +
      "CONCEPT: গাছপালা সূর্যালোক ব্যবহার করে খাবার তৈরি করে।"

  @Test fun theRedLightReasonIsRejected() {
    assertEquals(false, Explanations.reasonStaysOnTopic(answer, wandered))
  }

  @Test fun aRealJustificationIsAccepted() {
    val good =
      "ANSWER: সালোকসংশ্লেষণ হলো খাদ্য তৈরির প্রক্রিয়া।\n" +
        "REASON: কারণ এটি আলোক শক্তি ব্যবহার করে কার্বন ডাই অক্সাইড ও জল থেকে গ্লুকোজ তৈরি করে।\n" +
        "CONCEPT: গাছ নিজের খাবার নিজেই বানায়।"
    assertEquals(true, Explanations.reasonStaysOnTopic(answer, good))
  }

  @Test fun aMissingReasonIsNotRejected() {
    assertEquals(true, Explanations.reasonStaysOnTopic(answer, "STEPS: 1. 500 x 1.2 = 600"))
  }

  /** The field reader used by the check has to survive the real multi-line shape. */
  @Test fun fieldsAreReadFromAMultiLineExplanation() {
    assertEquals("second", Explanations.field("A: first\nB: second\nC: third", "B"))
    assertEquals("only", Explanations.field("ONLY: only", "ONLY"))
  }
}
