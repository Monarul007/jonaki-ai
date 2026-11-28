package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which study passage wins when several match.
 *
 * The measured failure, from the real pack: "দক্ষিণ আফ্রিকার মুদ্রার নাম কী" put a passage about
 * SOUTH KOREA first (BM25 16.63) with the correct African one second (11.42). Both share the common
 * words দক্ষিণ / মুদ্রার / নাম, so counting shared terms ties them, and the Korean passage is shorter
 * so BM25 rewards its density. The only distinguishing word is the rare "আফ্রিকার" (idf ≈ 4.9), which
 * appears in the correct passage alone.
 */
class KnowledgeRankTest {
  // Passage 0 = the correct African one; passage 1 = the Korean one that used to win.
  private val scores = mapOf(0 to 11.42, 1 to 16.63)
  private val matched = mapOf(0 to 3, 1 to 3)          // a tie on term count
  private val rarity = mapOf(0 to 4.9 + 1.1 + 1.4, 1 to 1.6 + 1.1 + 1.4)

  private fun rank(scores: Map<Int, Double> = this.scores, rarity: Map<Int, Double> = this.rarity) =
    Knowledge.order(scores, matched, rarity, need = 2, best = scores.values.max(), limit = 3)

  @Test
  fun thePassageSharingTheRareWordWins() {
    assertEquals(listOf(0, 1), rank())
  }

  /** What it did before: ordering by raw BM25 alone put the wrong passage first. */
  @Test
  fun rawBm25AloneWouldHaveChosenTheWrongPassage() {
    assertEquals(1, scores.entries.maxByOrNull { it.value }!!.key)
  }

  /** A passage sharing only common words must not outrank one sharing the rare word. */
  @Test
  fun commonWordsDoNotBeatOneDistinctiveWord() {
    // passage 2 matches four common terms; passage 0 matches one rare one.
    val s = mapOf(0 to 11.42, 2 to 14.0)
    val m = mapOf(0 to 3, 2 to 4)
    val r = mapOf(0 to 7.4, 2 to 4.3)
    assertEquals(listOf(0, 2), Knowledge.order(s, m, r, need = 2, best = 14.0, limit = 3))
  }

  /** The floors still apply: a weak match and a passage sharing too few terms are both dropped. */
  @Test
  fun weakOrThinMatchesAreDropped() {
    val s = mapOf(0 to 11.42, 3 to 1.0, 4 to 9.0)
    val m = mapOf(0 to 3, 3 to 3, 4 to 1)
    val r = mapOf(0 to 7.4, 3 to 8.0, 4 to 9.0)
    assertEquals(listOf(0), Knowledge.order(s, m, r, need = 2, best = 11.42, limit = 3))
  }
}
