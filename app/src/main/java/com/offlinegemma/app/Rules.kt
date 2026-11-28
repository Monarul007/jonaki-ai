package com.offlinegemma.app

/**
 * Decisions that can be made from the message text alone, kept free of Android so they can be tested.
 */
object Rules {

  /**
   * Whether the pre-answer planner call can be skipped because the message already settles
   * everything it would have been asked.
   *
   * The planner is a whole model round-trip of its own — create a conversation, prefill, decode — and
   * measured 6.5 s on E4B before a single token of the answer existed. Two of its three outputs are
   * decided locally: whether the message offers options (a regex over "(ক)...(ঘ)"), and that no search
   * can run at all. Only "what should I search for", and the calculator expression, actually need it.
   *
   * Skipping is only safe when the planner would have had nothing to contribute. A sum is never
   * skipped even though the model's expression is often unusable, because skipping also drops the
   * calculator: on "17% of 2350" that alone was 76 s of the answer, and an expression a small model
   * works out for itself is what goes wrong (409.5 instead of 399.5).
   */
  fun skipPlanner(question: String, canSearch: Boolean): Boolean {
    // Something might need looking up, so only the model can write the query.
    if (canSearch) return false
    // Options are readable from the text itself, so there is nothing left to decide.
    if (Tools.optionsOf(question).size >= 2) return true
    // Any digit at all means arithmetic may be involved, and the planner is what supplies the
    // calculator its expression. The numeric test is deliberately broader than Tutor.looksNumeric:
    // "3 men or 5 boys ... 4 men and 10 boys" carries no maths keyword and would otherwise slip
    // through and lose its calculator.
    if (Regex("[0-9০-৯]").containsMatchIn(question)) return false
    // No search, no options, no numbers: the planner has nothing to return.
    return true
  }

  /**
   * Whether the message is already a good web query, so the planner's 6-17 s round-trip buys nothing.
   *
   * The planner writes a 3-10 word search phrase. For a real question — "বাংলাদেশের মুক্তিযুদ্ধের সময়
   * প্রথম কোন জেলা শত্রুমুক্ত হয়" — the message IS that phrase, and the model mostly rewrites it into
   * something worse: measured, one such question produced "ইতিহাস বিষয়ে অভিজ্ঞ ব্যক্তি এক কথায় কী হবে"
   * for a query that should have been the message verbatim, and the fetched pages had to be discarded.
   *
   * Deliberately conservative, because skipping costs retrieval quality when it is wrong:
   *  - at least [MIN_WORDS] words, so a bare "র‌্যান্ড" or a greeting never qualifies;
   *  - no conversational lead-in ("আমাকে বলো", "can you explain"), since those do not belong in a
   *    search phrase and a search engine would have to ignore them;
   *  - no digits, so nothing numeric loses its calculator;
   *  - either a question word, or enough substantial words to stand alone as a search phrase.
   */
  fun messageIsGoodQuery(question: String, canSearch: Boolean): Boolean {
    if (!canSearch) return false
    if (Tools.optionsOf(question).size >= 2) return false      // an exam question: let the planner decide
    if (Regex("[0-9০-৯]").containsMatchIn(question)) return false
    val words = question.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.size < MIN_WORDS) return false
    val lower = question.lowercase()
    if (LEAD_INS.any { lower.contains(it) }) return false
    // Either a question word, or enough words that the message is plainly a searchable phrase.
    //
    // Requiring a question word rejected "রবীন্দ্রনাথ ঠাকুরের সোনার তরী কবিতাটি পড়ে শোনাও" — a request
    // whose subject is a perfectly good query. At MIN_WORDS+ substantial words with no conversational
    // lead-in, searching the message is right whether or not it is phrased as a question.
    if (QUESTION_WORDS.any { lower.contains(it) }) return true
    return words.size >= DECLARATIVE_WORDS
  }

  /** Short of this, a message is a phrase to be interpreted rather than a search query. */
  private const val MIN_WORDS = 4

  /** A statement (not a question) needs to be at least this long before it stands as a query. */
  private const val DECLARATIVE_WORDS = 6

  /**
   * "Tell me…" / "explain…" phrasing. A search engine has to discard these, and their presence is the
   * best signal that the message is a request rather than a query.
   */
  private val LEAD_INS = listOf(
    "আমাকে বল", "বলুন তো", "বলতো", "ব্যাখ্যা কর", "আমাকে জান", "জানতে চাই", "কি বলতে",
    "tell me", "can you", "could you", "explain", "what do you", "please",
  )

  /** A question word: what makes the message a query worth searching rather than chatter. */
  private val QUESTION_WORDS = listOf(
    "কী", "কি", "কেন", "কীভাবে", "কিভাবে", "কোথায়", "কবে", "কত", "কোন", "কে", "কার",
    "what", "why", "how", "where", "when", "which", "who", "whose",
  )
}