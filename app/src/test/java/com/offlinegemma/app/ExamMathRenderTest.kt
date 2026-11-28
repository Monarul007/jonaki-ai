package com.offlinegemma.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Maths in exam questions, rendered the same way as a chat reply.
 *
 * Exam mode shows the paper's own wording. 1,153 questions in exam.pack carry inline `\(…\)`
 * maths and 64 carry `\[…\]` display maths, plus \frac, \sqrt and \over. Printed raw the reader
 * gets backslashes and braces ("\(\mathrm{a+b=5}\)"), which is not what a Bangladeshi candidate
 * sees on the paper. The strings below are copied from the real pack.
 */
class ExamMathRenderTest {

  private val colors = Markdown.InlineColors(
    androidx.compose.ui.graphics.Color.Black,
    androidx.compose.ui.graphics.Color.LightGray,
    androidx.compose.ui.graphics.Color.Blue,
    androidx.compose.ui.graphics.Color.Magenta,
  )

  private fun rendered(src: String) = Markdown.inline(src, colors).text

  @Test
  fun inlineMathIsConvertedRatherThanPrintedAsTex() {
    val out = rendered("""\(\mathrm{a+b=5}\) এবং \(\mathrm{a-b=3}\) হলে ab এর মান কত?""")

    assertFalse("no backslash-paren left: $out", out.contains("\\("))
    assertFalse("no \\mathrm left: $out", out.contains("mathrm"))
    assertFalse("no braces left: $out", out.contains("{") || out.contains("}"))
    assertTrue("the maths is still there: $out", out.contains("a+b=5"))
    assertTrue("the Bengali is untouched: $out", out.contains("এর মান কত?"))
  }

  @Test
  fun displayMathIsConverted() {
    val out = rendered("\\[ \\frac{20}{3} \\]")

    assertFalse("no \\[ left: $out", out.contains("\\["))
    assertFalse("no \\frac left: $out", out.contains("frac"))
    assertTrue("the fraction reads as a division: $out", out.contains("÷"))
  }

  @Test
  fun dollarMathIsConverted() {
    val out = rendered("""$$ \mathrm{S_{\infty}}=\frac{20}{3} $$""")

    assertFalse("no dollars left: $out", out.contains("$"))
    assertFalse("no \\frac left: $out", out.contains("frac"))
    assertTrue("the value is readable: $out", out.contains("20"))
  }

  @Test
  fun squareRootAndOverBecomeReadableSymbols() {
    val root = rendered("""\(\sqrt{216}\)""")
    assertTrue("square root symbol: $root", root.contains("√"))
    assertFalse("no command left: $root", root.contains("sqrt"))
  }

  /**
   * `\over` is the form these papers actually use (80 questions vs 74 with `\frac`). Each string
   * below is copied from exam.pack.
   */
  @Test
  fun overWithBracedOperandsBecomesADivision() {
    val out = rendered("""\(\mathrm{১\over২}\)""")

    assertFalse("no \\over left: $out", out.contains("over"))
    assertTrue("reads as a division: $out", out.contains("÷"))
    assertTrue("both operands survive: $out", out.contains("১") && out.contains("২"))
  }

  @Test
  fun overBindsWholeGroupsNotOneToken() {
    // Real paper #80: the whole numerator over the whole denominator.
    val out = rendered("""\({.1 \times .01 \times .001 \over.2 \times .02 \times .002}\)""")

    assertFalse("no \\over left: $out", out.contains("over"))
    assertTrue("reads as one division: $out", out.contains("÷"))
    val beforeDiv = out.substringBefore("÷")
    assertTrue("numerator keeps all three factors: $beforeDiv", beforeDiv.count { it == '1' } >= 3)
  }

  @Test
  fun overWithABareFractionStillConverts() {
    val out = rendered("""\({1}\over{225}\)""")

    assertFalse("no \\over left: $out", out.contains("over"))
    assertTrue("both numbers survive: $out", out.contains("1") && out.contains("225"))
  }

  @Test
  fun aFractionNotFoundIsDroppedRatherThanPrinted() {
    // Degenerate input: the command alone must not reach the reader.
    val out = rendered("""\(\over\)""")
    assertFalse("no raw command: $out", out.contains("over"))
  }

  @Test
  fun subscriptMovesToARealSubscript() {
    // "S_∞" must become a subscript span, not a literal underscore.
    val out = Markdown.inline("""\(S_{\infty}\)""", colors)
    assertFalse("no underscore left in: ${out.text}", out.text.contains("_"))
    assertTrue("the base letter survives", out.text.contains("S"))
    // A subscript marker carries the glyph.
    assertTrue("subscript styled from the markers", out.text.contains('\uE008') || out.text.contains('∞'))
  }

  @Test
  fun plainQuestionTextIsLeftAlone() {
    val plain = "‘নদের চাঁদ’ বাগধারার অর্থ কী?"
    assertTrue("unchanged", rendered(plain).contains(plain))
  }
}
