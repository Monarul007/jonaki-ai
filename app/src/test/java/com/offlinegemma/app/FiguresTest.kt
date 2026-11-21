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

  @Test fun topicWordsDropFiller() {
    assertEquals("photosynthesis", Tutor.topicQuery("How does photosynthesis work?"))
    assertEquals("we have seasons on Earth", Tutor.topicQuery("Why do we have seasons on Earth?"))
  }

  @Test fun lessonWithoutPictureGetsOneAfterFirstParagraph() {
    val out = "Photosynthesis is how plants make food.\n\n" + "More text. ".repeat(60)
    val withFig = Tutor.ensureFigure(out, "How does photosynthesis work?")
    assertTrue(withFig.contains("Photosynthesis is how plants make food.\n\n[FIGURE: photosynthesis diagram]\n"))
  }

  @Test fun noBackupPictureForButtonsShortRepliesOrDemos() {
    val long = "x ".repeat(400)
    assertEquals(long, Tutor.ensureFigure(long, null)) // a tutor button or quiz
    assertEquals("short", Tutor.ensureFigure("short", "How does photosynthesis work?"))
    val demo = "```html\n<canvas></canvas>\n```\n" + long
    assertEquals(demo, Tutor.ensureFigure(demo, "Explain gravity"))
    assertEquals(long, Tutor.ensureFigure(long, "Tell me a joke"))
  }

  @Test fun verdictAndCaption() {
    val (ok, cap) = Figures.parseVerdict("**YES**\nCaption: The diagram shows a chloroplast.\nGreen parts are thylakoids.")
    assertEquals(true, ok)
    assertEquals("The diagram shows a chloroplast. Green parts are thylakoids.", cap)
    assertEquals(false, Figures.parseVerdict("NO - it is a logo").first)
    assertNull(Figures.parseVerdict("Maybe").first)
  }

  @Test fun searchWordsAreShortAndOnTopic() {
    assertEquals(
      "seasons Earth Solar geometry angles sunlight hitting",
      Figures.searchWords("Solar geometry showing different angles of sunlight hitting the Earth", "Why do we have seasons on Earth?"),
    )
    assertEquals("photosynthesis diagram", Figures.searchWords("photosynthesis diagram", "How does photosynthesis work?"))
  }

  @Test fun readAloudSkipsMarkers() {
    assertFalse(speakable("Plants grow. [FIGURE: plant cell diagram] They need light.").contains("FIGURE"))
  }
}
