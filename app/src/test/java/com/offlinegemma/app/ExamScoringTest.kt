package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic that decides a result. Real BCS numbers: 200 questions, 0.5 deducted per wrong
 * answer, so a script with 60 right and 40 wrong is 40.0 net - not 60, and not 20.
 */
class ExamScoringTest {

  private fun question(n: Int, answer: Int, subject: String = "Bangla", bengali: Boolean = true) =
    ExamQuestion(
      n = n,
      subject = subject,
      text = if (bengali) "প্রশ্ন $n কী?" else "Question $n?",
      options = listOf("এক", "দুই", "তিন", "চার"),
      answer = answer,
    )

  @Test
  fun bcsNegativeMarkingIsHalfPerWrong() {
    val questions = (1..200).map { question(it, it % 4) }
    val answers = HashMap<Int, Int?>()
    for (q in questions.take(60)) answers[q.n] = q.answer          // 60 right
    for (q in questions.drop(60).take(40)) answers[q.n] = (q.answer + 1) % 4  // 40 wrong
    // the remaining 100 are unanswered

    val score = ExamScoring.score(questions, answers, 0.5)

    assertEquals(60, score.correct)
    assertEquals(40, score.wrong)
    assertEquals(100, score.skipped)
    assertEquals(40.0, score.net, 0.001)
    assertEquals(200, score.total)
    assertEquals(200, score.maxMarks)
    assertEquals(30.0, score.accuracy, 0.001)
    assertEquals(60.0, score.attemptedAccuracy, 0.001)
  }

  @Test
  fun noNegativeMarkingMeansWrongCostsNothing() {
    val questions = (1..10).map { question(it, 0) }
    val answers = mapOf(1 to 0, 2 to 1, 3 to 2)

    val score = ExamScoring.score(questions, answers, 0.0)

    assertEquals(1, score.correct)
    assertEquals(2, score.wrong)
    assertEquals(1.0, score.net, 0.001)
  }

  @Test
  fun anEmptyScriptScoresZeroAndDeductsNothing() {
    val questions = (1..50).map { question(it, 2) }

    val score = ExamScoring.score(questions, emptyMap(), 0.5)

    assertEquals(0, score.correct)
    assertEquals(0, score.wrong)
    assertEquals(50, score.skipped)
    assertEquals(0.0, score.net, 0.001)
    assertEquals(0.0, score.accuracy, 0.001)
  }

  @Test
  fun everyAnswerRightIsTheFullMark() {
    val questions = (1..20).map { question(it, it % 4) }
    val answers = questions.associate { it.n to it.answer }

    val score = ExamScoring.score(questions, answers, 0.5)

    assertEquals(20.0, score.net, 0.001)
    assertEquals(100.0, score.accuracy, 0.001)
  }

  @Test
  fun aWrongAnswerCanPushTheNetBelowZero() {
    val questions = (1..4).map { question(it, 0) }
    val answers = questions.associate { it.n to 1 }

    val score = ExamScoring.score(questions, answers, 0.5)

    assertEquals(0, score.correct)
    assertEquals(4, score.wrong)
    assertEquals(-2.0, score.net, 0.001)
  }

  @Test
  fun anOutOfRangeChoiceCountsAsUnanswered() {
    // Given by an old attempt or a corrupt save: no real option was picked, so it is a skip,
    // not a wrong answer that costs half a mark.
    val questions = (1..3).map { question(it, 1) }
    val answers = mapOf(1 to 7, 2 to -1, 3 to 1)

    val score = ExamScoring.score(questions, answers, 0.5)

    assertEquals(1, score.correct)
    assertEquals(0, score.wrong)
    assertEquals(2, score.skipped)
  }

  @Test
  fun perSubjectTotalsAddUpToThePaper() {
    val questions = listOf(
      question(1, 0, "Bangla"), question(2, 1, "Bangla"),
      question(3, 2, "English"), question(4, 3, "English"),
      question(5, 0, "Math"),
    )
    val answers = mapOf(1 to 0, 3 to 0, 5 to 0)

    val score = ExamScoring.score(questions, answers, 0.5)

    assertEquals(5, score.bySubject.sumOf { it.total })
    assertEquals(score.correct, score.bySubject.sumOf { it.correct })
    assertEquals(score.wrong, score.bySubject.sumOf { it.wrong })
    assertEquals(score.skipped, score.bySubject.sumOf { it.skipped })
    val bangla = score.bySubject.first { it.subject == "Bangla" }
    assertEquals(1, bangla.correct)
    assertEquals(2, bangla.total)
  }

  @Test
  fun labelsFollowTheQuestionScript() {
    assertTrue(ExamScoring.isBengali("নদের চাঁদ বাগধারার অর্থ কী?"))
    assertFalse(ExamScoring.isBengali("She insisted on leaving the house."))
    val paper = ExamPaper("x", "t", "t", "bcs", 120, 0.5, emptyList(), listOf(question(1, 0)))
    assertEquals(listOf("ক", "খ", "গ", "ঘ"), paper.labels)
  }

  @Test
  fun aMockKeepsThePapersSubjectProportionsAndIsGradable() {
    // Two papers, 6 Bangla + 2 Math each: a 4-question mock should stay mostly Bangla.
    val papers = listOf(
      paper("a", (1..6).map { question(it, 0, "Bangla") } + (7..8).map { question(it, 1, "Math") }),
      paper("b", (1..6).map { question(it, 0, "Bangla") } + (7..8).map { question(it, 1, "Math") }),
    )

    val mock = ExamScoring.mock(papers, "bcs", count = 4, minutes = 10, seed = 7)
    assertNotNull(mock)
    requireNotNull(mock)
    assertEquals(4, mock.questions.size)
    assertEquals("bcs", mock.category)
    assertEquals(10, mock.durationMinutes)
    assertEquals(0.5, mock.negative, 0.001)
    assertTrue("Bangla should dominate a 6:2 mix", mock.questions.count { it.subject == "Bangla" } >= 3)
    // Every question keeps a valid key after the option shuffle.
    assertTrue(mock.questions.all { it.answer in 0..3 && it.options.size == 4 })
    // Numbering is 1..n with no gaps: the palette depends on it.
    assertEquals((1..4).toList(), mock.questions.map { it.n })
  }

  @Test
  fun aMockShufflesOptionsSoTheLetterIsNotMemorised() {
    val questions = (1..40).map { q -> ExamQuestion(q, "S", "same-ish $q", listOf("a", "b", "c", "d"), 0) }
    val mock = ExamScoring.mock(listOf(paper("a", questions)), "bcs", 20, 10, seed = 3)
    requireNotNull(mock)

    // The key always pointed at option 0 ("a") before shuffling. What must hold is that the key
    // still points at the *correct text*: the letter moves, the answer does not.
    assertTrue("every keyed option must still read 'a'", mock.questions.all { it.options[it.answer] == "a" })
    // And the shuffle really happened: some question has "a" no longer first.
    assertTrue(
      "the shuffled position should differ for at least one question",
      mock.questions.any { it.answer != 0 },
    )
  }

  @Test
  fun anEmptyCategoryGivesNoMock() {
    assertNull(ExamScoring.mock(emptyList(), "bcs", 10, 10, 1))
  }

  @Test
  fun theExplanationPromptStatesTheAnswerAndAllowsRefusal() {
    val prompt = ExamScoring.explainPrompt(question(1, 2), bengali = true)

    assertTrue(prompt.contains("Correct answer: (গ)"))
    assertTrue(prompt.contains("CANNOT_EXPLAIN"))
    assertTrue(prompt.contains("Write in Bengali"))
  }

  @Test
  fun aRefusalIsRecognisedInAnyCase() {
    assertTrue(ExamScoring.declined(""))
    assertTrue(ExamScoring.declined("   "))
    assertTrue(ExamScoring.declined("cannot_explain"))
    assertFalse(ExamScoring.declined("কারণ রেখা দুই তলের ছেদ।"))
  }

  private fun paper(id: String, questions: List<ExamQuestion>) = ExamPaper(
    id = id,
    title = id,
    titleBn = id,
    category = "bcs",
    durationMinutes = 120,
    negative = 0.5,
    subjects = emptyList(),
    questions = questions,
  )
}
