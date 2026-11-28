package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The explanation panel shows plain text, so inline LaTeX has to be converted before it is stored.
 *
 * Measured on the device: the maths explanation for "ক্রয়মূল্য ৫০০ টাকা, ২০% লাভে বিক্রয়মূল্য কত"
 * came back as "STEPS: 1. $500 \times (1 + 0.2)$ 2. $500 \times 1.2$ 3. $600$" and the panel rendered
 * the dollar signs and backslashes literally.
 */
class InlineMathTest {
  @Test fun inlineLatexBecomesReadableText() {
    val said = Markdown.inline("STEPS: 1. \$500 \\times (1 + 0.2)\$ 2. \$500 \\times 1.2\$ 3. \$600\$")
    assertFalse("no dollar signs", said.contains("$"))
    assertFalse("no backslash commands", said.contains("\\times"))
    assertTrue("the operator is a symbol", said.contains("×"))
    assertTrue("the numbers survive", said.contains("500") && said.contains("600"))
  }

  /** Prose outside the maths must be left exactly as written. */
  @Test fun proseIsNotTouched() {
    val src = "WHY: ক্রয়মূল্যকে (১ + লাভের শতাংশ) দিয়ে গুণ করা হয়েছে।"
    assertEquals(src, Markdown.inline(src))
  }

  /** A display-maths block ($$…$$) is converted too, and the fences removed. */
  @Test fun displayMathIsConverted() {
    val dollars = "$" + "$"
    val body = "\\frac{৫০০ \\times ১২০}{১০০}"
    val said = Markdown.inline("বিক্রয়মূল্য = $dollars$body$dollars")
    assertFalse("no fences left", said.contains(dollars))
    assertFalse("no raw command", said.contains("\\frac"))
    assertTrue("the division is a symbol", said.contains("÷"))
  }

  /** An unclosed dollar sign must not swallow the rest of the text. */
  @Test fun anUnclosedDollarIsLeftAlone() {
    val said = Markdown.inline("মূল্য ৫০০ টাকা $")
    assertTrue(said.contains("৫০০"))
  }
}
