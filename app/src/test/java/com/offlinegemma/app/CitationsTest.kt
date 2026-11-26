package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationsTest {
  private val sources = listOf(Source("Moon", "https://a.example/moon"), Source("Sun", "https://b.example/sun"))

  @Test fun citationsBecomeLinksToTheirSource() {
    val out = Tools.linkCitations("The Moon has no air [1]. The Sun is a star [2].", sources)
    assertEquals("The Moon has no air [[1]](https://a.example/moon). The Sun is a star [[2]](https://b.example/sun).", out)
  }

  @Test fun aListOfNumbersBecomesOneLinkEach() {
    assertEquals("Won [[1]](https://a.example/moon)[[2]](https://b.example/sun).", Tools.linkCitations("Won [1, 2].", sources))
  }

  @Test fun bengaliDigitsWorkToo() {
    val out = Tools.linkCitations("চাঁদে বাতাস নেই [১]।", sources)
    assertEquals("চাঁদে বাতাস নেই [[১]](https://a.example/moon)।", out)
  }

  @Test fun unknownNumbersAndExistingLinksStayAsTheyAre() {
    assertEquals("See [3].", Tools.linkCitations("See [3].", sources))
    assertEquals("[1](https://x.example)", Tools.linkCitations("[1](https://x.example)", sources))
    assertEquals("No sources [1].", Tools.linkCitations("No sources [1].", emptyList()))
  }

  @Test fun aLinkLabelledWithBracketsStaysOneLink() {
    val inline = Markdown.inline(
      Tools.linkCitations("Air [1].", sources),
      Markdown.InlineColors(
        androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.Gray,
        androidx.compose.ui.graphics.Color.Blue, androidx.compose.ui.graphics.Color.Red,
      ),
    )
    assertEquals("Air [1].", inline.text)
    assertTrue(inline.getLinkAnnotations(0, inline.length).isNotEmpty())
  }

  @Test fun bengaliQueriesPickMatchingLines() {
    val lines = listOf("প্রথম লাইন কিছু নয়", "সূর্য একটি নক্ষত্র", "আরও কিছু লেখা এখানে")
    val out = Tools.pickLines(lines, "সূর্য", 20)
    assertEquals("সূর্য একটি নক্ষত্র", out)
  }
}

class ToolSummaryTest {
  @Test fun allStepsFitOnOneLine() {
    val tools = listOf(
      ToolUse("knowledge", "biddabari", true),
      ToolUse("web_search", "finance minister", true),
      ToolUse("web_fetch", "https://a.example", true),
      ToolUse("web_fetch", "https://b.example", false),
      ToolUse("web_fetch", "https://c.example", true),
    )
    assertEquals("Study files · searched the web · read 2 pages", toolSummary(tools))
  }

  @Test fun failedPagesSaySo() {
    assertEquals("Searched the web · couldn't open the pages",
      toolSummary(listOf(ToolUse("web_search", "q", true), ToolUse("web_fetch", "https://a.example", false))))
  }
}

class TidyNumberTest {
  @Test fun roundsFloatNoiseAndLongDecimals() {
    assertEquals("399.5", Tools.tidyNumber("399.50000000000006"))
    assertEquals("42.1647", Tools.tidyNumber("42.164708"))
    assertEquals("1200", Tools.tidyNumber("1200"))
    assertEquals("not a number", Tools.tidyNumber("not a number"))
  }
}
