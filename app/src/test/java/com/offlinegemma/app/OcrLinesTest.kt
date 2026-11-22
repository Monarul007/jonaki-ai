package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrLinesTest {
  // What the phone actually read from the test page: the printed (i)/(ii)/(iii) came out wrong.
  private val page = """
    সৃজনশীল প্রশ্ন
    ১.
    () পেন্রোলিয়াম + O2 → CO2 + H20 + শক্তি
    (
    (i) 38U + on' → s6Ba + s6Kr + 3 on' + শক্তি
    (ii) Zn + CuCla → ZnCl2 + Cu + শক্তি
    (ক) ইলেকট্রোপ্লেটিং কী?
    (খ) তড়িৎ রাসায়নিক কোষে লবণ সেতু ব্যবহার করা হয় কেন?
    (গ) উদ্দীপকের দ্বিতীয় বিক্রিয়াটি রাসায়নিক বিক্রিয়া নয়_ ব্যাখ্যা করো।
    (ঘ) শক্তি উৎপাদনে (i) ও (॥) এর বিক্রিয়া তুলনা করো।
  """.trimIndent()

  @Test fun numbersFormulaLinesByPositionAndDropsMisreadMarkers() {
    val lines = Ocr.numberFormulaLines(page).lines()
    assertEquals("[Formula line 1] পেন্রোলিয়াম + O2 → CO2 + H20 + শক্তি", lines[2])
    assertEquals("[Formula line 2] 38U + on' → s6Ba + s6Kr + 3 on' + শক্তি", lines[4])
    assertEquals("[Formula line 3] Zn + CuCla → ZnCl2 + Cu + শক্তি", lines[5])
  }

  @Test fun leavesBengaliQuestionsAlone() {
    val lines = Ocr.numberFormulaLines(page).lines()
    assertEquals("(ক) ইলেকট্রোপ্লেটিং কী?", lines[6])
    assertEquals("(ঘ) শক্তি উৎপাদনে (i) ও (॥) এর বিক্রিয়া তুলনা করো।", lines[9])
    assertEquals("সৃজনশীল প্রশ্ন", lines[0])
  }

  @Test fun pageWithoutFormulasIsUnchanged() {
    val text = "১. এসিড মিশ্রিত পানিকে তড়িৎ বিশ্লেষ্য পরিবাহী বলা হয় কেন? ব্যাখ্যা করো।\n২. কপার সালফেট তীব্র তড়িৎ বিশ্লেষ্য পদার্থ কেন?"
    assertEquals(text, Ocr.numberFormulaLines(text))
  }
}
