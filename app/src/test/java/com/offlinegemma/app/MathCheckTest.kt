package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MathCheckTest {

  private fun wrong(text: String) = MathCheck.wrongClaims(text)

  /** The 2:3:4:5 share question: the model wrote ২/১৪ × ৯৮০০ = ১১৪২.৮৬, the truth is 1400. */
  @Test fun catchesTheRatioMistake() {
    val answer = "প্রথম অংশ: ২/১৪ × ৯৮০০ = ১১৪২.৮৬"
    val bad = wrong(answer)
    assertEquals(1, bad.size)
    assertEquals(1400.0, bad[0].actual, 0.5)
    assertTrue(bad[0].stated > 1100 && bad[0].stated < 1200)
  }

  @Test fun acceptsCorrectArithmetic() {
    val answer = "২/১৪ × ৯৮০০ = ১৪০০ এবং ৫/১৪ × ৯৮০০ = ৩৫০০"
    assertTrue(wrong(answer).isEmpty())
  }

  /** The interest answer: 50 and 61.051 were both right, so nothing should be flagged. */
  @Test fun acceptsTheInterestWork() {
    val answer = "সরল মুনাফা: 100 × 0.1 × 5 = 50. চক্রবৃদ্ধি: 161.051 - 100 = 61.051"
    assertTrue(wrong(answer).isEmpty())
  }

  @Test fun catchesAWrongPercentage() {
    val bad = wrong("17% of 2350 = 409.5")
    assertEquals(1, bad.size)
    assertEquals(399.5, bad[0].actual, 0.1)
  }

  @Test fun evaluatesChains() {
    assertEquals(50.0, MathCheck.evaluate("100 * 0.1 * 5")!!, 1e-9)
    assertEquals(14.0, MathCheck.evaluate("2 + 3 * 4")!!, 1e-9)
    assertEquals(6.0, MathCheck.evaluate("(2 + 1) * 2")!!, 1e-9)
    assertEquals(1400.0, MathCheck.evaluate("9800 - 3500 - 2100 - 2800")!!, 1e-9)
  }

  @Test fun refusesNonArithmetic() {
    assertNull(MathCheck.evaluate("9800 টাকা / 2"))
    assertNull(MathCheck.evaluate("H2SO4"))
    assertNull(MathCheck.evaluate(""))
  }

  @Test fun divisionByZeroIsNotAClaim() {
    assertNull(MathCheck.evaluate("5 / 0"))
  }

  @Test fun ignoresProseWithoutEquations() {
    assertTrue(wrong("উত্তর হলো ২১০০ টাকা।").isEmpty())
  }

  @Test fun findsBengaliAndAsciiDigits() {
    assertEquals(2, MathCheck.claims("২/১৪ × ৯৮০০ = ১৪০০ and 3/14 * 9800 = 2100").size)
    assertFalse(MathCheck.claims("1+1=2").isEmpty())
  }

  /**
   * The real regression: the answer wrote the question's 9800 as 98800 in LaTeX and compared with
   * "≈", so an "=" only check saw nothing and let a nonsense working through to the user.
   */
  @Test fun catchesLaTeXFractionAndApproxSign() {
    val answer = "বৃহত্তম অংশের মান = $5 \\times (৯৮৮০০)/(১৪) \\approx ৩২৮৫৬.৮$"
    val bad = wrong(answer)
    assertEquals(1, bad.size)
    assertEquals(35285.714, bad[0].actual, 0.01)
  }

  @Test fun catchesLatexFracMacro() {
    val answer = "$\\frac{৯৮৮০০}{১৪} \\approx ৬২$"
    val bad = wrong(answer)
    assertEquals(1, bad.size)
    // 98800/14 = 7057.14, nothing like 62.
    assertEquals(7057.14, bad[0].actual, 0.01)
  }

  @Test fun acceptsATrueApproximationWithinOnePercent() {
    // "≈" is an approximation, so 700 vs 7057 is still wrong, but 1400 vs 1401 is fine.
    assertTrue(wrong("9 × 14 = 126").isEmpty())
    assertTrue(wrong("9800/14 ≈ 700").isEmpty())
  }

  @Test fun normalizesLatexBeforeParsing() {
    assertEquals("5 × (98800)/(14) = 32856.8", MathCheck.normalized("$5 \\times (৯৮৮০০)/(১৪) \\approx ৩২৮৫৬.৮$"))
  }
}
