package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A multiple-choice answer must name one option, and the same one throughout. Jonaki shipped an
 * answer that opened with "(খ) অম্বর" — the wrong option — and then argued for "অবনী" underneath,
 * so the first line a reader saw contradicted the reasoning below it.
 */
class ChoiceTest {

  private val question = "'পৃথিবী' শব্দের সমার্থক কোনটি? (ক) অবনী (খ) অম্বর (গ) অনল (ঘ) অর্ণব"
  private val wrongFirst = "(খ) অম্বর\n\n'পৃথিবী' শব্দের সমার্থক শব্দ হিসেবে 'অবনী' সঠিক। অম্বর অর্থ আকাশ।"

  @Test fun readsTheOptionsFromTheQuestion() {
    val opts = Tools.optionsOf(question)
    assertEquals(4, opts.size)
    assertEquals("অবনী", opts[0].second)
    assertEquals("অম্বর", opts[1].second)
  }

  @Test fun rewritesAnOpeningThatContradictsTheBody() {
    val fixed = Tools.settleChoice(wrongFirst, question)
    // Both the letter and the word must change: "(খ) অবনী" still names the wrong option.
    assertTrue("still opens with the wrong option: $fixed", fixed.startsWith("(ক) অবনী"))
    assertFalse(fixed.startsWith("(খ)"))
  }

  /**
   * The label replacement must be literal. With replaceFirst(String, String) the parentheses were a
   * regex group, so "(ঘ)" matched just "ঘ" and the answer shipped as "(ঘ) অবনী" — right word, wrong
   * option letter.
   */
  @Test fun replacesTheLabelLiterally() {
    val fromFourth = "(ঘ) অর্ণব\n\n'অবনী' সঠিক। অর্ণব অর্থ সমুদ্র।"
    val fixed = Tools.settleChoice(fromFourth, question)
    assertTrue(fixed, fixed.startsWith("(ক) অবনী"))
  }

  @Test fun leavesAConsistentAnswerAlone() {
    val consistent = "(ক) অবনী\n\n'অবনী' পৃথিবীর সমার্থক।"
    assertEquals(consistent, Tools.settleChoice(consistent, question))
  }

  @Test fun doesNothingWithoutOptions() {
    val plain = "উত্তর হলো ২১০০ টাকা।"
    assertEquals(plain, Tools.settleChoice(plain, "কত টাকা?"))
  }
}
