package com.offlinegemma.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextLineTest {
  @Test fun readsThreeQuestionsAndHidesTheLine() {
    val text = "Gravity pulls.\n\nNEXT: Why do apples fall? | What is mass? | How strong is gravity on the Moon?"
    assertEquals(listOf("Why do apples fall?", "What is mass?", "How strong is gravity on the Moon?"), Tutor.nextQuestions(text))
    assertEquals("Gravity pulls.", Tutor.stripNext(text))
  }

  @Test fun dropsPlaceholdersAndMarkdown() {
    val text = "**NEXT:** *Why?* | <question 3> | question 2 | `What next?`"
    assertEquals(listOf("Why?", "What next?"), Tutor.nextQuestions(text))
  }

  @Test fun usesTheLastNextLine() {
    assertEquals(listOf("B"), Tutor.nextQuestions("NEXT: A\nmore\nnext: B"))
  }

  @Test fun shortAnswerWithoutNextHasNoButtons() {
    assertFalse(Tutor.wantsFollowups("Hi there"))
    assertTrue(Tutor.wantsFollowups("x".repeat(700)))
  }

  @Test fun demoBlockIsRecognised() {
    val parts = Markdown.parse("Look:\n```html\n<canvas id=c></canvas><script>1</script>\n```\nNEXT: a | b")
    assertTrue(parts.any { it is Markdown.Block.Demo && it.closed })
    val streaming = Markdown.parse("```html\n<div>")
    assertTrue(streaming.single() is Markdown.Block.Demo && !(streaming.single() as Markdown.Block.Demo).closed)
  }

  @Test fun fixPromptCarriesCappedCode() {
    val p = Tutor.fixPrompt("x is not defined", "a".repeat(5000))
    assertTrue(p.startsWith("The interactive demo shows this error: x is not defined. Here is its code:"))
    assertTrue(p.contains("a".repeat(3500)) && !p.contains("a".repeat(3501)))
  }
}

class ToolCallTest {
  @Test fun parsesProperJson() {
    val c = Tools.parseCalls("Let me look.<tool_call>{\"name\":\"web_search\",\"arguments\":{\"query\":\"gemma 4\"}}</tool_call>")
    assertEquals(listOf(Tools.Call("web_search", mapOf("query" to "gemma 4"))), c)
  }

  @Test fun acceptsUnnestedArguments() {
    val c = Tools.parseCalls("<tool_call>{\"name\":\"web_fetch\",\"url\":\"https://a.b\",\"query\":\"x\"}</tool_call>")
    assertEquals("https://a.b", c.single().args["url"])
  }

  @Test fun recoversBrokenJson() {
    // Raw quotes inside a value break the JSON; the forgiving parser still gets it.
    val c = Tools.parseCalls("<tool_call>{\"name\":\"web_search\",\"arguments\":{\"query\":\"the \"best\" phone\"}}</tool_call>")
    assertEquals("web_search", c.single().name)
    assertTrue(c.single().args["query"]!!.contains("best"))
  }

  @Test fun ignoresUnknownToolsAndHidesCalls() {
    assertTrue(Tools.parseCalls("<tool_call>{\"name\":\"run_shell\",\"arguments\":{}}</tool_call>").isEmpty())
    assertEquals("Hello", Tools.stripCalls("Hello <tool_call>{\"name\":\"web_search\""))
  }

  @Test fun routesWebQuestions() {
    assertTrue(Tools.wantsWeb("What is the latest Android version?"))
    assertFalse(Tools.wantsWeb("Tell me a joke"))
  }

  @Test fun picksMatchingLines() {
    val lines = listOf("Cookie banner", "The Moon's gravity is 1.62 m/s².", "Footer links", "Gravity on Mars is 3.71 m/s².")
    val out = Tools.pickLines(lines, "moon gravity", 40)
    assertTrue(out.contains("Moon"))
    assertFalse(out.contains("Footer"))
  }

  @Test fun findsBareDomainsAndUrls() {
    assertEquals(listOf("https://monarul.pro.bd"), Tools.findLinks("Analyze monarul.pro.bd"))
    assertEquals(listOf("https://daraz.com.bd"), Tools.findLinks("what is daraz.com.bd?"))
    assertEquals(listOf("https://example.com/a?b=1"), Tools.findLinks("open https://example.com/a?b=1 now"))
  }

  @Test fun ignoresFilesEmailsAndFileNames() {
    assertTrue(Tools.findLinks("read calc.py and plan.docx").isEmpty())
    assertTrue(Tools.findLinks("mail me at monarul@coder71.org").isEmpty())
    assertTrue(Tools.findLinks("use it, e.g. in code").isEmpty())
  }

  @Test fun keepsAtMostTwoLinks() {
    assertEquals(2, Tools.findLinks("a.com b.net c.org").size)
  }

  @Test fun linkQueryDropsFillerWords() {
    // "analyze <link>" only gives fillers, so the page is read from the top (" ").
    assertEquals(" ", Tools.linkQuery("Analyze monarul.pro.bd"))
    assertTrue(Tools.linkQuery("summarize pricing offers at example.com").contains("pricing"))
  }

  @Test fun routesRecentEvents() {
    assertTrue(Tools.wantsWeb("latest news in Dhaka"))
    assertTrue(Tools.wantsWeb("what is the weather today"))
    assertTrue(Tools.wantsWeb("who won the match yesterday"))
    assertFalse(Tools.wantsWeb("explain photosynthesis"))
  }

  @Test fun spotsRefusals() {
    assertTrue(Tools.REFUSAL_RX.containsMatchIn("I'm sorry, but I don't have information about that."))
    assertTrue(Tools.REFUSAL_RX.containsMatchIn("I cannot access the internet or browse websites."))
    assertTrue(Tools.REFUSAL_RX.containsMatchIn("As an AI model, my knowledge cutoff is 2024."))
    assertFalse(Tools.REFUSAL_RX.containsMatchIn("Monarul Islam is the developer at Coder71."))
  }
}

class MathTest {
  @Test fun texWordsAndUnderscores() {
    val s = Markdown.tex("\\max(a\\_1, \\log x) \\times 2")
    assertTrue(s.startsWith("max("))
    assertTrue(s.contains("log x"))
    assertTrue(s.contains("×"))
    assertFalse(s.contains("\\"))
  }

  @Test fun fractionsAndPowers() {
    val s = Markdown.tex("\\frac{1}{2} m v^2")
    assertTrue(s.startsWith("1/2"))
    assertTrue(s.contains("${Markdown.SUP_ON}2${Markdown.SUP_OFF}"))
  }
}

class VoiceTest {
  private fun frame(level: Double): ShortArray = ShortArray(512) { i -> ((if (i % 2 == 0) level else -level) * 32767).toInt().toShort() }

  /** Feeds [count] frames of [level] loudness, 32 ms apart, and returns the events. */
  private fun VoiceDetector.run(level: Double, count: Int, start: Long, mode: VoiceDetector.Mode, speakingFor: Long = 0) =
    (0 until count).map { feed(frame(level), start + it * 32L, mode, speakingFor + it * 32L) }

  @Test fun quietRoomDoesNotTrigger() {
    val v = VoiceDetector()
    assertTrue(v.run(0.003, 100, 0, VoiceDetector.Mode.LISTENING).all { it == VoiceDetector.Event.None })
  }

  @Test fun speechStartsThenEndsAfterQuiet() {
    val v = VoiceDetector()
    v.run(0.003, 20, 0, VoiceDetector.Mode.LISTENING)
    assertEquals(VoiceDetector.Event.Start, v.feed(frame(0.1), 1000, VoiceDetector.Mode.LISTENING))
    v.run(0.1, 30, 1032, VoiceDetector.Mode.RECORDING) // ~1 s of talking
    val after = v.run(0.002, 45, 2000, VoiceDetector.Mode.RECORDING) // 1.4 s of quiet
    val finish = after.filterIsInstance<VoiceDetector.Event.Finish>().first()
    assertTrue(finish.chunks.size > 30) // includes the pre-roll from before speech
  }

  @Test fun coughIsIgnored() {
    val v = VoiceDetector()
    v.feed(frame(0.1), 0, VoiceDetector.Mode.LISTENING)
    v.feed(frame(0.1), 32, VoiceDetector.Mode.RECORDING) // 64 ms of sound
    assertTrue(v.run(0.002, 45, 64, VoiceDetector.Mode.RECORDING).contains(VoiceDetector.Event.Discard))
  }

  @Test fun ownEchoOnLoudspeakerDoesNotInterrupt() {
    val v = VoiceDetector()
    // The reply comes back through the microphone at a steady 0.05.
    val events = v.run(0.05, 200, 0, VoiceDetector.Mode.SPEAKING)
    assertFalse(events.contains(VoiceDetector.Event.BargeIn))
  }

  @Test fun talkingOverTheReplyInterrupts() {
    val v = VoiceDetector()
    v.run(0.02, 40, 0, VoiceDetector.Mode.SPEAKING) // learn the echo
    val events = v.run(0.3, 20, 2000, VoiceDetector.Mode.SPEAKING, speakingFor = 2000)
    assertTrue(events.contains(VoiceDetector.Event.BargeIn))
  }

  @Test fun wavHeaderIs16kMono() {
    val wav = TalkController.encodeWav(listOf(ShortArray(1600)))
    val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals("RIFF", String(wav, 0, 4))
    assertEquals(16000, b.getInt(24))
    assertEquals(1, b.getShort(22).toInt())
    assertEquals(44 + 3200, wav.size)
  }
}
