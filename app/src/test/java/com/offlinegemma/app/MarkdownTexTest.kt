package com.offlinegemma.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader must never show raw TeX. Gemma writes Bengali formulas with \text{} inside \frac{},
 * which a flat regex cannot match, so those leaked as "\text{...}" in the answer bubble.
 */
class MarkdownTexTest {

  @Test
  fun `text command keeps its words`() {
    val out = Markdown.tex("""3 \text{জন পুরুষ} = 5 \text{জন বালক}""")
    assertFalse(out, out.contains("\\"))
    assertFalse(out, out.contains("{"))
    assertTrue(out, out.contains("জন পুরুষ"))
  }

  @Test
  fun `equiv renders as the symbol`() {
    val out = Markdown.tex("""৩ জন পুরুষ $\equiv$ ৫ জন বালক""")
    assertTrue(out, out.contains("≡"))
    assertFalse(out, out.contains("equiv"))
  }

  @Test
  fun `text nested inside a fraction does not leak`() {
    val out = Markdown.tex("""\frac{\text{প্রথম} \times \text{দিন}}{\text{মোট কাজ}}""")
    assertFalse(out, out.contains("\\text"))
    assertFalse(out, out.contains("\\frac"))
    assertTrue(out, out.contains("÷"))
    assertTrue(out, out.contains("প্রথম"))
  }

  @Test
  fun `two levels of nested fractions still convert`() {
    val out = Markdown.tex("""\frac{(\frac{50}{3} \text{ বালক}) \times D}{\text{মোট কাজ}}""")
    assertFalse(out, out.contains("\\frac"))
    assertFalse(out, out.contains("\\text"))
    assertTrue(out, out.contains("50"))
  }

  @Test
  fun `cut off formula drops the command but keeps the text`() {
    val out = Markdown.tex("""\text{মোট কাজ""")
    assertFalse(out, out.contains("\\"))
    assertTrue(out, out.contains("মোট কাজ"))
  }

  @Test
  fun `simple fraction reads as division`() {
    assertTrue(Markdown.tex("""\frac{5}{3}""").contains("5 ÷ 3"))
  }

  @Test
  fun `text command outside maths is unwrapped too`() {
    val out = Markdown.tex("""\text{মোট কাজ} উভয় পক্ষ থেকে বাতিল""")
    assertFalse(out, out.contains("\\text"))
    assertTrue(out, out.contains("মোট কাজ"))
  }
}
