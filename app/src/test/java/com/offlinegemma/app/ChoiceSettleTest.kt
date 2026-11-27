package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MCQ guard, against the answer E4B actually produced on the phone.
 *
 * Asked "ভূপৃষ্ঠের প্রতিশব্দ কোনটি? (ক) অবনী (খ) অম্বর ..." it opened with "(খ) অম্বর" and then
 * wrote that "অবনী ... পৃথিবী অর্থে ব্যবহৃত হয়". The earlier guard could not see it: it only trusted
 * a sentence shaped "X সঠিক", and this answer starts its commitment with "সমার্থক হলো অম্বর".
 */
class ChoiceSettleTest {

  private val q = "ভূপৃষ্ঠের প্রতিশব্দ কোনটি? (ক) অবনী (খ) অম্বর (গ) অনিল (ঘ) অগ্নি"

  /** The exact text the device returned for the Earth question. */
  private val realAnswer = """
    (খ) অম্বর

    ভূপৃষ্ঠের প্রতিশব্দ হলো অম্বর, কারণ অম্বর শব্দের অর্থ হলো আকাশমণ্ডল বা পৃথিবী। 'অবনী' এবং
    'ধরিত্রী'ও পৃথিবী অর্থে ব্যবহৃত হয়, কিন্তু প্রদত্ত বিকল্পে 'অম্বর' শব্দটিই সঠিক প্রতিশব্দে
    ব্যবহৃত হয়েছে। 'অনিল' শব্দের অর্থ হলো বায়ু এবং 'অগ্নি' শব্দের অর্থ হলো আগ্নি।
  """.trimIndent()

  @Test fun keepsAnAnswerThatAgreesWithItself() {
    // This answer is wrong about the subject but consistent about which option it picks, and the
    // guard must not silently rewrite it. Rewriting the opener here would be inventing an answer.
    val fixed = Tools.settleChoice(realAnswer, q)
    assertEquals(realAnswer, fixed)
  }

  @Test fun followsAConclusionThatNamesTheOption() {
    // The shape that was missed: the answer settles on an option in its closing line.
    val settling = """
      (খ) অম্বর

      অম্বর অর্থ আকাশ। তবে 'অবনী' শব্দটি পৃথিবী অর্থে ব্যবহৃত হয়, যা ভূপৃষ্ঠের প্রতিশব্দ।
      অতএব সঠিক উত্তর (ক) অবনী।
    """.trimIndent()
    val fixed = Tools.settleChoice(settling, q)
    assertTrue("opener not corrected: $fixed", fixed.startsWith("(ক) অবনী"))
  }

  @Test fun englishConclusionWorksTheSameWay() {
    // The answer still opens with a label (that is what the guard rewrites); the commitment is in
    // English, which is what a model answering a Bengali question in English-mode produces.
    val settling = "(খ) অম্বর\n\nSky is related, therefore (ক) অবনী is the correct synonym."
    val fixed = Tools.settleChoice(settling, q)
    assertTrue("opener not corrected: $fixed", fixed.startsWith("(ক) অবনী"))
  }

  @Test fun anAnswerThatDoesNotOpenWithAnOptionIsLeftAlone() {
    // Nothing to rewrite, and guessing which option a prose answer chose would be inventing one.
    val prose = "The answer is (খ) অম্বর, therefore (ক) অবনী is also relevant."
    assertEquals(prose, Tools.settleChoice(prose, q))
  }

  @Test fun proseOpenedAnswerGetsItsClosingChoicePrepended() {
    // The exact v03 shape: the answer opens with working, closes "তাই সঠিক উত্তর (খ) 90°".
    val rhombusQ = "রম্বসের কর্ণদ্বয়ের অন্তর্ভুক্ত কোণ কত? (ক) 45° (খ) 90° (গ) 120° (ঘ) 150°"
    val prose = """
      রম্বসের কর্ণদ্বয় পরস্পরকে সমদ্বিখণ্ডিত করে এবং এই কর্ণদ্বয়ের অন্তর্ভুক্ত কোণগুলি
      পরস্পরকে লম্বভাবে ছেদ করে। তাই সঠিক উত্তর হল: **(খ) 90°**।
    """.trimIndent()
    val fixed = Tools.settleChoice(prose, rhombusQ)
    assertTrue("choice not surfaced: $fixed", fixed.startsWith("(খ) 90°"))
    // The reasoning must still be there, unchanged, under the surfaced choice.
    assertTrue("reasoning lost", fixed.contains("সমদ্বিখণ্ডিত"))
  }

  @Test fun proseOpenedAnswerWithClearCommitmentGetsPrepended() {
    // The v09 shape: prose opens, "সঠিক উত্তরটি হলো (গ) 10" closes.
    val factorQ = "x2 + 5x + 6 এবং x2 + 3x + 2 এর গ.সা.গু? (ক) 2 (খ) 6 (গ) 10 (ঘ) 12"
    val prose = """
      প্রদত্ত রাশিমালা দুটি হলো: x^2 + 5x + 6 = (x+2)(x+3) এবং x^2 + 3x + 2 = (x+1)(x+2)।
      সঠিক উত্তরটি হলো **(গ) 10**।
    """.trimIndent()
    val fixed = Tools.settleChoice(prose, factorQ)
    assertTrue("choice not surfaced: $fixed", fixed.startsWith("(গ) 10"))
  }

  @Test fun aMentionedOptionIsNotAChoice() {
    // Every option gets discussed; that alone must never move the opener.
    val discussing = """
      (ক) অবনী

      অবনী পৃথিবী অর্থে ব্যবহৃত হয়। অনিল অর্থ বায়ু, যেমন (গ) অনিল আলোচিত হয়েছে। অগ্নি অর্থ আগুন।
    """.trimIndent()
    assertEquals(discussing, Tools.settleChoice(discussing, q))
  }
}