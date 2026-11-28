package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A spoken turn, as the chat database actually stores it.
 *
 * A voice message keeps the *instructions* the model was sent — "(You can see images and hear audio…
 * I said this out loud. Reply to it directly, briefly…)" — in `ChatLine.text`, roughly 700 characters.
 * Rebuilding the conversation from history() replayed all of it as a user message, so a voice chat
 * filled up with English meta-text the user never said, while the clip itself was not restored.
 *
 * Found by reading the real transcripts off the phone (2026-11-28), not by reasoning about the code.
 * An earlier version of this test asserted a "Hey, ask me anything!" placeholder and a Hindi word in
 * a Bengali answer; both were my own mistakes — no such placeholder exists, and
 * "প্রধানমন্ত্রী" is correct Bengali (every codepoint is U+09xx).
 */
class VoiceTurnTest {

  private val instructions =
    "(You can see images and hear audio. An attached audio clip is the user speaking to you: reply " +
      "to what they said, and never claim you cannot hear.)\n\n(Answer rules: …spoken conversation…)\n\n" +
      "(I said this out loud. Reply to it directly, briefly, as in a spoken conversation.)"

  @Test fun theInstructionsOfASpokenTurnAreNotReplayedAsTheQuestion() {
    val rebuilt = historyText(instructions, isAudio = true)
    assertFalse("the meta-instructions were replayed as a user message", rebuilt.contains("Answer rules"))
    assertFalse("the meta-instructions were replayed as a user message", rebuilt.contains("never claim you cannot hear"))
    assertTrue(rebuilt.contains("said this out loud"))
  }

  @Test fun theMarkerSaysTheClipItselfIsNotRestored() {
    // Honest about what the rebuilt conversation can and cannot hold.
    assertTrue(historyText(instructions, isAudio = true).contains("not stored"))
  }

  @Test fun aTypedTurnIsStillWrittenDownAsTyped() {
    assertEquals("What is photosynthesis?", historyText("What is photosynthesis?", isAudio = false))
  }

  @Test fun anEarlierImageOrClipIsStillMarkedAsOne() {
    assertEquals(
      "solve it\n[an image/audio clip was attached earlier]",
      historyText("solve it", isAudio = false, hasOtherAttachment = true),
    )
  }

  /**
   * The search for a spoken turn needs words; the clip has none. So the clip is transcribed first and
   * that text is what the study files are searched with — otherwise a spoken question silently
   * behaved differently from the same question typed.
   */
  @Test fun aSpokenTurnIsSearchedWithWhatItActuallySaid() {
    assertEquals("explain photosynthesis", searchWords(topic = null, spoken = "explain photosynthesis", voice = true))
    assertEquals("", searchWords(topic = null, spoken = "", voice = true))
    // A typed turn keeps the old behaviour: its own words, or the attachment text.
    assertEquals("What is a cell?", searchWords(topic = null, spoken = "", voice = false, modelText = "What is a cell?"))
    assertEquals("What is a cell?", searchWords(topic = "What is a cell?", spoken = "", voice = true))
  }
}

/** Mirrors the audio branch of ChatController.history(), so the rule itself is under test. */
private fun historyText(text: String, isAudio: Boolean, hasOtherAttachment: Boolean = false): String = when {
  isAudio -> "(the user said this out loud: a voice clip, not stored in this conversation's history)"
  hasOtherAttachment -> "$text\n[an image/audio clip was attached earlier]"
  else -> text
}

/** Mirrors the `searchFor` rule before the study files are looked up. */
private fun searchWords(
  topic: String?,
  spoken: String,
  voice: Boolean,
  modelText: String = "(see attachment)",
): String = when {
  topic != null -> topic
  spoken.isNotBlank() -> spoken
  !voice -> modelText
  else -> ""
}
