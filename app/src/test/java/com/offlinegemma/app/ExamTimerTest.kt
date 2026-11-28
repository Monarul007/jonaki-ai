package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock and the resume decision.
 *
 * The failure these guard against: a countdown kept in memory silently resets when the app is
 * reopened, which hands the user their lost time back. Everything here derives from [deadlineAt].
 */
class ExamTimerTest {

  private fun attempt(deadlineAt: Long, status: String = ExamAttempt.RUNNING) = ExamAttempt(
    id = "a-1",
    examId = "45th-BCS",
    title = "45th BCS",
    category = "bcs",
    mode = "paper",
    startedAt = 1_000L,
    deadlineAt = deadlineAt,
    durationMinutes = 120,
    negative = 0.5,
    questionNumbers = (1..5).toList(),
    answers = emptyMap(),
    marked = emptySet(),
    visited = setOf(1),
    current = 1,
    status = status,
    submittedAt = null,
    score = null,
    explanations = emptyMap(),
    mockQuestions = null,
  )

  @Test
  fun remainingTimeComesFromTheDeadlineNotFromACounter() {
    val now = 10_000L
    val a = attempt(deadlineAt = now + 90_000L)

    assertEquals(90_000L, a.remainingMs(now))
    // Half a minute later, exactly 60 s are left - a reopened app reads the same answer.
    assertEquals(60_000L, a.remainingMs(now + 30_000L))
  }

  @Test
  fun aDeadlineInThePastLeavesNothingRatherThanGoingNegative() {
    val now = 100_000L
    val a = attempt(deadlineAt = now - 5_000L)

    assertEquals(0L, a.remainingMs(now))
  }

  @Test
  fun aSubmittedAttemptHasNoClockLeft() {
    val a = attempt(deadlineAt = System.currentTimeMillis() + 60_000L, status = ExamAttempt.SUBMITTED)

    assertEquals(0L, a.remainingMs())
    assertFalse(a.running)
  }

  @Test
  fun anExpiredRunningAttemptMustBeGradedNotResumed() {
    val now = System.currentTimeMillis()
    val expired = attempt(deadlineAt = now - 1L)
    val live = attempt(deadlineAt = now + 60_000L)

    // This is the decision ExamController.resume() makes.
    assertTrue("expired must not continue", expired.running && expired.deadlineAt <= now)
    assertFalse("a live paper must continue", live.deadlineAt <= now)
  }

  @Test
  fun theClockFormatsAsHoursMinutesSeconds() {
    assertEquals("01:58:43", formatClock((1 * 3600 + 58 * 60 + 43) * 1000L))
    assertEquals("00:00:00", formatClock(0))
    assertEquals("00:00:00", formatClock(-5000))
    assertEquals("00:00:59", formatClock(59_000))
    assertEquals("02:00:00", formatClock(2 * 3600 * 1000L))
  }

  @Test
  fun answeredCountIgnoresClearedAndMissingAnswers() {
    val a = attempt(deadlineAt = 0).copy(answers = mapOf(1 to 2, 2 to null, 3 to 0))

    assertEquals(2, a.answeredCount)
  }
}
