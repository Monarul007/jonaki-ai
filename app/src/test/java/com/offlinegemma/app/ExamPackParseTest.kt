package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The knowledge.pack fallback: exam mode must still work when only the older prose pack exists,
 * because that is the pack most phones already have side-loaded.
 *
 * The two passage strings below are real ones, quoted in Adjudicator.kt's header.
 */
class ExamPackParseTest {

  private fun passage(text: String, title: String = "NTRCA 2022") =
    Knowledge.Passage(source = "biddabari", title = title, text = text)

  @Test
  fun aRealAnswerKeyPassageBecomesAFourOptionQuestion() {
    val q = Exams.parsePassage(
      passage(
        "প্রশ্ন: দুটি তল পরস্পরকে ছেদ করলে কী উৎপন্ন হয়?  " +
          "ক) বিন্দু  খ) রেখা  গ) স্থান  ঘ) রশ্মি  উত্তর: রেখা",
      ),
    )

    assertNotNull(q)
    requireNotNull(q)
    assertEquals("দুটি তল পরস্পরকে ছেদ করলে কী উৎপন্ন হয়?", q.text)
    assertEquals(listOf("বিন্দু", "রেখা", "স্থান", "রশ্মি"), q.options)
    assertEquals(1, q.answer)
    assertNull(q.explain)
  }

  @Test
  fun anExplanationLineIsCarriedWhenTheSourceHasOne() {
    val q = Exams.parsePassage(
      passage(
        "প্রশ্ন: ৫১° কোণের সম্পূরক কোণের এক-তৃতীয়াংশ কত?  " +
          "ক) ৪৩°  খ) ৮৬°  গ) ১২৯°  ঘ) ১৫৩°  উত্তর: ৪৩°  " +
          "ব্যাখ্যা: সম্পূরক কোণ ১৮০° − ৫১° = ১২৯°, তার এক-তৃতীয়াংশ ৪৩°।",
      ),
    )

    requireNotNull(q)
    assertEquals(0, q.answer)
    assertTrue(q.explain.orEmpty().contains("১২৯"))
  }

  @Test
  fun aPassageThatIsNotAnMcqIsRejectedRatherThanGuessed() {
    // A textbook window, not a question: no key, so it must not become one.
    assertNull(Exams.parsePassage(passage("সালোকসংশ্লেষণ উদ্ভিদের খাদ্য তৈরির প্রক্রিয়া।")))
    // Only three options: the shape is wrong, so no question either.
    assertNull(
      Exams.parsePassage(passage("প্রশ্ন: কী?  ক) এক  খ) দুই  গ) তিন  উত্তর: এক")),
    )
  }

  @Test
  fun anAnswerKeyThatMatchesNoOptionIsRejected() {
    // The key names something the options never contain: better no question than a wrong key.
    assertNull(
      Exams.parsePassage(
        passage("প্রশ্ন: কী?  ক) এক  খ) দুই  গ) তিন  ঘ) চার  উত্তর: পাঁচ"),
      ),
    )
  }

  @Test
  fun aShortKeyLabelStillResolvesAgainstALongerOption() {
    // "যশোর" against "প্রথম শত্রুমুক্ত জেলা যশোর" - the containment rule covers this.
    val q = Exams.parsePassage(
      passage(
        "প্রশ্ন: মুক্তিযুদ্ধের সময় pertama কোন জেলা শত্রুমুক্ত হয়?  " +
          "ক) ঝিনাইদহ  খ) প্রথম শত্রুমুক্ত জেলা যশোর  গ) কুষ্টিয়া  ঘ) চুয়াডাঙ্গা  উত্তর: যশোর",
      ),
    )

    requireNotNull(q)
    assertEquals(1, q.answer)
  }
}

/**
 * The pack builder's explanation cleaner. A handful of source explanations carried the coaching
 * site's promo block followed by its analytics and Cloudflare bootstrapping as literal script text
 * (~6 KB); shown under the answer in review mode that is a wall of JavaScript, not an explanation.
 */
class ExplanationCleanTest {

  @Test
  fun theRealPollutedExplanationIsCutAtThePromoBlock() {
    // The measured shape: a real reason, then the site's promo block, then script text.
    val raw = "সোনালী কাবিন আল-মাহমুদ রচিত সনেট জাতীয় কাব্য। " +
      "উত্তরণ Book Package প্রিলি, লিখিত, ভাইভা প্রতিটি টপিকের মৌলিক আলোচনা " +
      "window.dataLayer = window.dataLayer || []; function gtag(){} gtag('js', new Date());"
    val cleaned = cleanForTest(raw).orEmpty()

    assertTrue("the reason must survive", cleaned.contains("সোনালী কাবিন"))
    assertFalse("the promo block must be gone", cleaned.contains("Book Package"))
    assertFalse("the script must be gone", cleaned.contains("gtag"))
    assertTrue("and it must be short", cleaned.length < 200)
  }

  @Test
  fun anExplanationWithNoJunkIsKeptWhole() {
    val raw = "Adhere to (sth)- লেগে থাকা, অটল থাকা।"
    assertEquals(raw, cleanForTest(raw))
  }

  @Test
  fun anExplanationThatIsOnlyJunkBecomesNone() {
    assertNull(cleanForTest("window.dataLayer = window.dataLayer || []; gtag('js', new Date());"))
  }

  /** The same rule the builder applies, so the tests do not need the Python script. */
  private fun cleanForTest(text: String): String? {
    val junk = Regex(
      "(?:উত্তরণ\\s*Book Package|Book Package\\s*প্রিলি|Help Branch List|Explore BPSC" +
        "|window\\.dataLayer|gtag\\(|window\\.fbq|cdn-cgi|MathJax\\.Hub|\\$\\(document\\)\\.ready" +
        "|Copyright\\s*©|Concord Tower|Helpline:)",
      RegexOption.IGNORE_CASE,
    )
    val cut = junk.find(text) ?: return text.replace(Regex("\\s+"), " ").trim()
    val head = text.substring(0, cut.range.first).replace(Regex("\\s+"), " ").trim()
    return head.trimEnd(' ', ',', ';', ':', '`', '-', '*', '/', '\\').ifBlank { null }
  }
}
