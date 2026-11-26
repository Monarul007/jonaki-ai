package com.offlinegemma.app

/**
 * Checks the arithmetic a model wrote in an answer.
 *
 * A small model states numbers with total confidence and gets them wrong: asked to share 9800 in
 * 2:3:4:5 it wrote "২/১৪ × ৯৮০০ = ১১৪২.৮৬" (the true value is 1400) while the final difference was
 * right by luck, because it then matched its own wrong number to the printed options. Re-running
 * the arithmetic the answer itself shows is cheap and catches exactly that.
 *
 * Kept free of Android so the rules can be unit-tested.
 */
object MathCheck {

  /** One "a op b = c" (or longer chain) the answer asserted. */
  data class Line(val expression: String, val stated: Double, val actual: Double, val approx: Boolean = false) {
    val wrong: Boolean get() = !close(stated, actual, if (approx) 0.01 else 1e-3)
  }

  private fun close(a: Double, b: Double, tol: Double): Boolean {
    if (a.isNaN() || b.isNaN() || a.isInfinite() || b.isInfinite()) return false
    val scale = maxOf(1.0, kotlin.math.abs(a), kotlin.math.abs(b))
    return kotlin.math.abs(a - b) <= scale * tol
  }

  /** Bengali digits -> ASCII, so "১১৪২.৮৬" can be parsed. */
  fun asciiDigits(s: String): String {
    val b = StringBuilder(s.length)
    for (c in s) b.append(if (c in '০'..'৯') ('0' + (c - '০')) else c)
    return b.toString()
  }

  /**
   * Turns the shapes Gemma actually writes into plain arithmetic: LaTeX fractions and operators,
   * maths delimiters, and the Bengali "=" glyphs. Without this the worst answers slip through —
   * the model wrote "\frac{৯৮৮০০}{১৪} ≈ ৬২" (98800 instead of 9800) in plain LaTeX.
   */
  fun normalized(text: String): String {
    var s = asciiDigits(text).replace('\u00A0', ' ')
    s = s.replace("\\times", "×").replace("\\div", "÷").replace("\\cdot", "×")
    // \frac{a}{b} -> (a)/(b). Both the macro and a bare "frac" (a lost backslash) must work.
    s = Regex("""\\?d?frac\s*\{([^{}]*)\}\s*\{([^{}]*)\}""").replace(s) { "( ${it.groupValues[1]} ) / ( ${it.groupValues[2]} )" }
    s = s.replace("\\approx", "=").replace("$", " ").replace("≈", "=").replace("\\", " ")
    // Tidy the spaces the substitutions leave behind, so comparisons are readable.
    s = s.replace(Regex("""\s*=\s*"""), " = ").replace(Regex("""[ \t]{2,}"""), " ")
    return s.trim()
  }

  private val NUMBER = """\d+(?:[.,]\d+)?"""
  // A term is a number, optionally in parentheses (a normalised \frac looks like "( 98800 ) / ( 14 )").
  private val TERM = """\(?\s*$NUMBER\s*\)?"""
  // "2/14 × 9800 = 1142.86"  |  "5 × (98800)/(14) = 32856.8"  |  "(9800)/(14) = 700"
  private val EQUATION = Regex("""($TERM(?:\s*[+\-*/×÷]\s*$TERM)+)\s*=\s*(-?$NUMBER)""")
  private val PERCENT_OF = Regex("""($NUMBER)\s*%\s*(?:of|এর)\s*($NUMBER)\s*=\s*(-?$NUMBER)""", RegexOption.IGNORE_CASE)

  /**
   * Every arithmetic claim in [text] that can be re-checked. Percent-of lines are turned into the
   * equivalent multiplication, so they compare like any other chain.
   */
  fun claims(text: String): List<Line> {
    val src = normalized(text)
    val out = mutableListOf<Line>()
    for (m in PERCENT_OF.findAll(src)) {
      val p = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
      val n = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: continue
      val stated = m.groupValues[3].replace(',', '.').toDoubleOrNull() ?: continue
      out.add(Line("($p/100)*$n", stated, p / 100.0 * n, true))
    }
    for (m in EQUATION.findAll(src)) {
      val expr = m.groupValues[1].replace('×', '*').replace('÷', '/').replace(',', '.')
      val stated = m.groupValues[2].toDoubleOrNull() ?: continue
      val actual = evaluate(expr) ?: continue
      out.add(Line(expr, stated, actual))
    }
    return out
  }

  /** Only the lines whose stated result disagrees with the arithmetic. */
  fun wrongClaims(text: String): List<Line> = claims(text).filter { it.wrong }

  /**
   * Evaluates a plain arithmetic chain (+ - * / and parentheses, * / before + -).
   * Returns null when the expression is not simple arithmetic, so the caller can ignore it.
   */
  fun evaluate(expr: String): Double? {
    // Anything that is not a number or an operator means this was not a sum (units, words).
    if (Regex("""[\d+\-*/().\s]""").replace(expr, "").trim().isNotEmpty()) return null
    val tokens = Regex("""\d+(?:\.\d+)?|[+\-*/()]""").findAll(expr).map { it.value }.toList()
    if (tokens.isEmpty()) return null
    return Parser(tokens).parse()
  }

  /** Recursive-descent parser, written as a class so the three levels can call each other. */
  private class Parser(private val tokens: List<String>) {
    private var pos = 0
    private fun peek(): String? = tokens.getOrNull(pos)
    private fun take(): String? = tokens.getOrNull(pos++)

    fun parse(): Double? {
      val v = sum()
      return if (v != null && pos == tokens.size) v else null
    }

    private fun sum(): Double? {
      var v = term() ?: return null
      while (peek() == "+" || peek() == "-") {
        val op = take()
        val r = term() ?: return null
        v = if (op == "+") v + r else v - r
      }
      return v
    }

    private fun term(): Double? {
      var v = factor() ?: return null
      while (peek() == "*" || peek() == "/") {
        val op = take()
        val r = factor() ?: return null
        v = if (op == "*") v * r else if (r == 0.0) return null else v / r
      }
      return v
    }

    private fun factor(): Double? {
      val t = take() ?: return null
      if (t == "(") {
        val v = sum() ?: return null
        return if (take() == ")") v else null
      }
      return t.toDoubleOrNull()
    }
  }
}
