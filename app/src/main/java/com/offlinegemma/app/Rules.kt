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
}