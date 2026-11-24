package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrLinesTest {
  // What the phone actually read from the test page: the printed (i)/(ii)/(iii) came out wrong.
  private val page = """
    সৃজনশীল প্রশ্ন
    () পেন্রোলিয়াম + O2 → CO2 + H20 + শক্তি

    (i) 38U + on' → s6Ba + s6Kr + 3 on' + শক্তি
    (গ) উদ্দীপকের দ্বিতীয় বিক্রিয়াটি রাসায়নিক বিক্রিয়া নয়_ ব্যাখ্যা করো।
  """.trimIndent()

  @Test fun tagsEveryNonBlankLineByPositionAndKeepsItsText() {
    assertEquals(
      listOf(
        "[Line 1] সৃজনশীল প্রশ্ন",
        "[Line 2] () পেন্রোলিয়াম + O2 → CO2 + H20 + শক্তি",
        "[Line 3] (i) 38U + on' → s6Ba + s6Kr + 3 on' + শক্তি",
        "[Line 4] (গ) উদ্দীপকের দ্বিতীয় বিক্রিয়াটি রাসায়নিক বিক্রিয়া নয়_ ব্যাখ্যা করো।",
      ),
      Ocr.numberLines(page).lines(),
    )
  }

  @Test fun anyKindOfPageIsTaggedTheSameWay() {
    assertEquals("[Line 1] Menu\n[Line 2] Tea 20", Ocr.numberLines("Menu\n\nTea 20"))
  }
}
