package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chunking rules behind the semantic index. The official sample refuses to index a chunk over
 * the embedder's input limit rather than truncating it, because a passage cut mid-sentence reads as
 * authoritative while missing the half that mattered. Jonaki's pack once had exactly that problem.
 */
class SemanticTest {

  @Test fun shortPassageStaysWhole() {
    val text = "অনুপাত হলো দুই বা তার বেশি রাশির তুলনা।"
    assertEquals(listOf(text), Semantic.split(text))
  }

  @Test fun longPassageSplitsOnParagraphBoundaries() {
    val para = "ক" .repeat(400)
    val text = listOf(para, para, para, para).joinToString("\n")
    val pieces = Semantic.split(text)
    assertTrue("expected more than one piece, got ${pieces.size}", pieces.size > 1)
    // Nothing may exceed the budget of a single embedding call.
    for (p in pieces) assertTrue("piece of ${p.length} chars is too long", p.length <= Semantic.MAX_CHARS)
  }

  @Test fun nothingIsLostWhenSplitting() {
    val words = (1..900).joinToString("\n") { "শব্দ$it" }
    val pieces = Semantic.split(words)
    val rejoined = pieces.joinToString("\n")
    // Every word must survive somewhere: this is the "nothing is truncated" rule.
    for (i in 1..900) assertTrue("শব্দ$i vanished", rejoined.contains("শব্দ$i"))
  }

  @Test fun blankPiecesAreDropped() {
    val text = "প্রথম\n\n\n\n" + "খ".repeat(1500)
    for (p in Semantic.split(text)) {
      assertTrue("blank piece kept", p.isNotBlank())
      assertTrue("piece of ${p.length} chars exceeds the budget", p.length <= Semantic.MAX_CHARS)
    }
  }

  @Test fun aSingleHugeParagraphIsCutAtSpacesNotMidWord() {
    val words = (1..400).joinToString(" ") { "শব্দ$it" }
    val pieces = Semantic.split(words)
    assertTrue(pieces.size > 1)
    for (p in pieces) {
      assertTrue("piece too long: ${p.length}", p.length <= Semantic.MAX_CHARS)
      assertTrue("piece starts mid-word: ${p.take(20)}", p.startsWith("শব্দ"))
    }
  }

  @Test fun gateIsAnAbsoluteNumber() {
    // The gate must not be relative to whatever the best hit happened to score: that is what let
    // the best of a set of irrelevant passages through.
    assertEquals(0.70f, Semantic.GATE, 0.0001f)
    assertTrue(Semantic.GATE > 0.5f)
  }
}
