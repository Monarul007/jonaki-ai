package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FiguresTest {
  @Test fun markersOfEveryKindAreNumbered() {
    val text = "Plants make food.\n[FIGURE: photosynthesis diagram]\nMore.\n- [Diagram 2: chloroplast structure]\nEnd [photo: leaf cross section] here."
    assertEquals(listOf("photosynthesis diagram", "chloroplast structure", "leaf cross section"), Tutor.figureQueries(text))
    val figs = Markdown.parse(text).filterIsInstance<Markdown.Block.Figure>()
    assertEquals(listOf(1, 2, 3), figs.map { it.n })
    // The bullet that only held the marker is gone.
    assertTrue(Markdown.parse(text).none { it is Markdown.Block.Item && it.text.isBlank() })
  }

  @Test fun verdictAndCaption() {
    val (ok, cap) = Figures.parseVerdict("**YES**\nCaption: The diagram shows a chloroplast.\nGreen parts are thylakoids.")
    assertEquals(true, ok)
    assertEquals("The diagram shows a chloroplast. Green parts are thylakoids.", cap)
    assertEquals(false, Figures.parseVerdict("NO - it is a logo").first)
    assertNull(Figures.parseVerdict("Maybe").first)
  }

  @Test fun readAloudSkipsMarkers() {
    assertFalse(speakable("Plants grow. [FIGURE: plant cell diagram] They need light.").contains("FIGURE"))
  }
}
