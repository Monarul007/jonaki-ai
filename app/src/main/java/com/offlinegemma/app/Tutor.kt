package com.offlinegemma.app

import android.text.Html
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Interactive tutor" rules, ported from the desktop app
 * (agent.py TEACH_RX / TEACH_RULES and chat.html addFollowups / NEXT_RE).
 */
object Tutor {
  /** Same words as agent.py TEACH_RX: does this look like a science/maths "explain" question? */
  private val TEACH_RX = Regex(
    "explain|interactive|simulat|visuali|show me|how does|how do|why does|why do|what is|what are|" +
      "physics|chemi|cosmolog|biolog|astronom|quantum|black hole|galax|planet|atom|molecul|reaction|" +
      "energy|gravity|relativity|wave|teach|understand|learn|lesson|diagram",
    RegexOption.IGNORE_CASE,
  )

  fun looksLikeTeaching(text: String) = TEACH_RX.containsMatchIn(text)

  private const val RULE_ACCURATE =
    "When explaining a science or maths topic: be accurate, use short sections and one everyday " +
      "analogy. If unsure about a fact, say so."

  // Same words as agent.py TEACH_RULES: real pictures, like a printed textbook.
  private const val RULE_FIGURE =
    "Like a printed textbook, add 1 or 2 real pictures where they help: put a line on its own " +
      "like [FIGURE: photosynthesis diagram] right after the paragraph it illustrates (2-5 English " +
      "search words naming what the picture should show). The app finds a real picture and writes " +
      "its caption, so do not describe the picture yourself; you may call it Figure 1."

  // Demos now only on request (agent.py TEACH_RULES); 80 -> 60 lines because phones are slower.
  private const val RULE_DEMO =
    "Only if the user asks for something interactive, a simulation or something to play with, add ONE small interactive " +
      "demo in a ```html block: plain HTML with <script>, a <canvas> or <svg>, and labelled " +
      "<input type=\"range\"> sliders that change the picture live. No external files or libraries, " +
      "under 60 lines, and it must fit a 500px-wide box (width:100%, no fixed wide sizes). " +
      "The app shows it as a live mini-page right in the chat, so never tell the user to save or " +
      "open a file."

  private const val RULE_NO_DEMO = "Do not include any code or HTML demo in this answer."

  private const val RULE_NEXT =
    "Finish with a last line that starts with NEXT: followed by three short real questions about " +
      "this topic that the user might want to ask next, separated by | (write the actual questions)."

  /** Talk mode is spoken: no pictures, no figure markers, no code or demos. */
  private const val RULE_VOICE =
    "This is a spoken conversation: answer briefly and naturally, in short sentences, in one " +
      "language. Do not add [FIGURE: ...] markers, do not search for or offer pictures, diagrams, " +
      "images or photos, and do not include code blocks or HTML demos."

  /**
   * Identity answers are facts, not model guesses. A 2B model otherwise calls itself a Google
   * language model and drops the developer's site; the app answers these questions itself.
   */
  private val IDENTITY_RX = Regex(
    "(?i)(who\\s+(created|made|developed|built|owns|is\\s+behind)\\s+(you|jonaki|this\\s+app))|" +
      "((who|what)\\s+(are|is)\\s+you)|(who\\s+is\\s+jonaki)|(what\\s+is\\s+jonaki)|" +
      "(who\\s+(is|are)\\s+(your|the)\\s+(creator|developer|maker|owner|founder))|" +
      "((your|the)\\s+(creator|developer|maker|owner|founder))|" +
      "(tell\\s+me\\s+about\\s+(yourself|jonaki|the\\s+developer|monarul))|" +
      "(what\\s+model\\s+(do\\s+you\\s+use|are\\s+you))|(who\\s+trained\\s+you)|" +
      "(are\\s+you\\s+(google|gemini|chatgpt|claude|openai|made\\s+by\\s+google))|" +
      "(who\\s+is\\s+monarul)|(what'?s\\s+your\\s+name)|(what\\s+is\\s+your\\s+name)|" +
      "(introduce\\s+yourself)|" +
      "(তুমি\\s*কে|আপনি\\s*কে|তুই\\s*কে|" +
      "তোমাকে\\s*কে\\s*(তৈরি|বানিয়েছে|বানাল)|আপনাকে\\s*কে\\s*(তৈরি|বানিয়েছে|বানাল)|" +
      "কে\\s*(তৈরি|বানিয়েছে|বানাল)\\s*(করেছে|করেছেন)?|" +
      "(তোমার|আপনার)\\s*(নির্মাতা|ডেভেলপার|ক্রিয়েটর|স্রষ্টা)|" +
      "ডেভেলপার\\s*কে|জোনাকি\\s*কে|মনারুল\\s*কে|" +
      "(তুমি|আপনি)\\s*কি\\s*(গুগল|জেমিনি|চ্যাটজিপিটি|ক্লড)|" +
      "কোন\\s*মডেল|নিজের\\s*সম্পর্কে\\s*বলো)"
  )

  private const val IDENTITY_EN =
    "I’m **Jonaki AI**, an offline AI assistant created and developed by **Monarul Islam " +
      "(Monarul007)** — an AI enthusiast. I run entirely on your phone using Google’s **Gemma** " +
      "model through **LiteRT-LM**, so your chats stay on your device. The app itself is made by " +
      "Monarul Islam, not Google. Learn more about the developer at **https://monarul.pro.bd**."

  private const val IDENTITY_BN =
    "আমি **জোনাকি এআই (Jonaki AI)** — একটি অফলাইন এআই অ্যাসিস্ট্যান্ট। " +
      "আমাকে তৈরি ও বিকশিত করেছেন **মনারুল ইসলাম (Monarul007)** — একজন এআই উৎসাহী। " +
      "আমি আপনার ফোনেই Google-এর **Gemma** মডেল **LiteRT-LM**-এর মাধ্যমে চালাই, তাই আপনার " +
      "চ্যাট ফোনেই থাকে। অ্যাপটি Google-এর নয় — এটি মনারুল ইসলামের তৈরি। ডেভেলপার সম্পর্কে " +
      "আরও জানতে: **https://monarul.pro.bd**।"

  fun identityAnswer(question: String, bengali: Boolean): String? =
    if (IDENTITY_RX.containsMatchIn(question)) (if (bengali) IDENTITY_BN else IDENTITY_EN) else null

  /**
   * LiteRT-LM's system prompt is fixed for a whole chat, so the rules ride along
   * with this one message. Only the user's own words are shown on screen.
   */
  fun wrap(userText: String, demos: Boolean, codeProblem: Boolean = false, voice: Boolean = false): String {
    val rules = when {
      voice -> listOf(RULE_ACCURATE, RULE_VOICE, RULE_NO_DEMO)
      codeProblem -> listOf(RULE_CODE)
      looksLikeTeaching(userText) -> listOf(RULE_ACCURATE, RULE_FIGURE, if (demos) RULE_DEMO else RULE_NO_DEMO, RULE_NEXT)
      else -> return userText
    }
    return "(Answer rules: ${rules.joinToString(" ")})\n\n$userText"
  }

  private val CODE_RX = Regex(
    "solve|problem|leetcode|algorithm|puzzle|exercise|homework|function|\\bcode\\b|program|answer this",
    RegexOption.IGNORE_CASE,
  )
  private val CODE_WORDS = Regex("code|program|function|algorithm|leetcode", RegexOption.IGNORE_CASE)

  /** A coding problem, often a screenshot with "solve it" (trigger words from agent.py ROUTES "code"). */
  fun looksLikeCodeProblem(text: String, hasImage: Boolean) =
    CODE_RX.containsMatchIn(text) && (hasImage || CODE_WORDS.containsMatchIn(text))

  private val NUMBER_RX = Regex("[0-9০-৯]")
  private val MATH_WORD_RX = Regex(
    "(?i)(শতকরা|শতাংশ|শতাংশ|অনুপাত|ভাগ|গড়|বয়স|সুদ|চক্রবৃদ্ধি|সরল|ক্ষেত্রফল|আয়তন|বেগ|সময়|" +
      "লাভ|ক্ষতি|ক্রয়মূল্য|বিক্রয়|কত|percent|percentage|ratio|average|age|interest|area|" +
      "speed|profit|loss|solve|calculate|sum|একসাথে|দিনে|ঘণ্টায়|টাকা|মিটার|কিলোমিটার|কেজি|" +
      "\\d\\s*[+\\-*/×÷%]|[+\\-*/×÷%]\\s*\\d|=|²|³)",
  )

  /**
   * Does this question need arithmetic worked out? True for a sum the model has to set up itself
   * (a word problem), which is exactly where a small model invents plausible numbers: it answered
   * a 2:3:4:5 share question with "1142.86" instead of 1400. [calc] is the planner's expression.
   */
  fun looksNumeric(question: String, calc: String? = null): Boolean {
    if (!calc.isNullOrBlank()) return true
    if (!NUMBER_RX.containsMatchIn(question)) return false
    return MATH_WORD_RX.containsMatchIn(question)
  }

  /** The rule that stopped the model from choosing an answer just because it was in the options. */
  const val NO_BACKSOLVE =
    "Never pick an option because it is listed, and never reason backwards from the options. If my " +
      "message needs arithmetic, show the calculation and let the number decide the option."

  // agent.py's coding-problem rule. The phone has no Python, so the answer is JavaScript,
  // which the app runs in its sandbox and shows the output (like the PC runs Python).
  private const val RULE_CODE =
    "For a coding problem (often a screenshot): explain the idea in 2-4 sentences, then give ONE " +
      "```javascript block with the full solution followed by console.log() lines that run the " +
      "problem's examples. Write the code only once. The app runs that block and shows the output."

  /** Fixed tutor buttons (label, prompt). Check facts is handled specially. */
  const val CHECK_FACTS = "✅ Check facts"
  val BUTTONS = listOf(
    "🧒 Simpler" to "Explain that again more simply, like I'm 10, with an everyday analogy.",
    "🔬 Go deeper" to "Go deeper: explain the real science behind that, with the key equations and what each part means.",
    "❓ Quiz me" to "Quiz me on this. Ask me ONE multiple-choice question (A–D) with exactly one correct option, " +
      "then stop and wait for my answer. After I answer, tell me if I'm right and ask the next one (3 in total).",
  )

  /**
   * Same words as the PC app. The demo's own code is always included (a small model
   * may have lost track of it), capped at 3500 characters. No tools are offered in a fix turn.
   */
  fun fixPrompt(error: String, html: String) =
    "The interactive demo shows this error: $error. Here is its code:\n```html\n${html.take(3500)}\n```\n" +
      "Fix the bug and send the corrected full demo in one ```html block. Keep everything else the same."

  /** The first demo's code in a reply, for the fix prompt. */
  fun firstDemo(text: String): String = split(text).filterIsInstance<Part.Demo>().firstOrNull()?.html ?: ""

  // ---- Figures: [FIGURE: search words] ----

  private const val FIG_WORDS = "(?:FIGURE|FIG\\.?|IMAGE|PICTURE|DIAGRAM|PHOTO)"
  /** A marker anywhere in a line (lifted onto its own line before reading). */
  val FIG_INLINE = Regex("[*_]*(\\[\\s*$FIG_WORDS\\s*\\d*\\s*:[^\\]\\n]{2,140}\\])[*_]*", RegexOption.IGNORE_CASE)
  /** A marker on its own line; group 1 = the search words. */
  val FIG_LINE = Regex("^\\s*\\[\\s*$FIG_WORDS\\s*\\d*\\s*:\\s*([^\\]\\n]+?)\\s*\\]\\s*$", RegexOption.IGNORE_CASE)

  /** The figure search words in a reply, in order. */
  fun figureQueries(text: String): List<String> =
    text.replace(FIG_INLINE, "\n\n$1\n\n").lines().mapNotNull { FIG_LINE.find(it)?.groupValues?.get(1)?.trim() }

  // ---- NEXT line ----

  private val NEXT_LINE = Regex("""^\s*[*_]*\s*NEXT\s*[*_]*\s*:(.*)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
  private val PLACEHOLDER = Regex("""^(follow-?up )?question \d$""", RegexOption.IGNORE_CASE)

  fun stripNext(text: String) = text.replace(NEXT_LINE, "").trimEnd()

  /** Up to three real follow-up questions from the last NEXT line; placeholders dropped. */
  fun nextQuestions(text: String): List<String> {
    val m = NEXT_LINE.findAll(text).lastOrNull() ?: return emptyList()
    return m.groupValues[1].split("|")
      .map { it.replace(Regex("[*_`]"), "").trim() }
      .filter { it.isNotEmpty() && !(it.startsWith("<") && it.endsWith(">")) && !PLACEHOLDER.matches(it) }
      .take(3)
  }

  /** Same rule as desktop: buttons only for a NEXT line or a long reply. */
  fun wantsFollowups(text: String) = nextQuestions(text).isNotEmpty() || text.length >= 700

  // ---- Splitting a reply into text, code and demos ----

  sealed class Part {
    data class Text(val text: String) : Part()
    data class Code(val lang: String, val body: String) : Part()
    /** [closed] = false while the block is still streaming. */
    data class Demo(val html: String, val closed: Boolean) : Part()
  }

  private val DEMO_TAGS = Regex("""<(script|canvas|input|svg|button)\b""", RegexOption.IGNORE_CASE)

  fun split(raw: String): List<Part> {
    val lines = stripNext(raw.replace("\r\n", "\n")).split("\n")
    val parts = mutableListOf<Part>()
    val para = StringBuilder()
    fun flush() {
      val t = para.toString().trim('\n')
      if (t.isNotBlank()) parts.add(Part.Text(t))
      para.clear()
    }
    var i = 0
    while (i < lines.size) {
      val line = lines[i]
      val fence = Regex("""^\s*```\s*(\w*)""").find(line)
      if (fence != null) {
        flush()
        val lang = fence.groupValues[1]
        val buf = mutableListOf<String>()
        i++
        while (i < lines.size && !lines[i].trimStart().startsWith("```")) buf.add(lines[i++])
        val closed = i < lines.size
        i++
        val body = buf.joinToString("\n")
        val isHtml = lang.equals("html", true) || lang.equals("htm", true)
        when {
          isHtml && (!closed || DEMO_TAGS.containsMatchIn(body)) -> parts.add(Part.Demo(body, closed))
          else -> parts.add(Part.Code(lang, body))
        }
        continue
      }
      para.append(line).append('\n')
      i++
    }
    flush()
    return parts
  }

  // ---- Web search for "Check facts" (same DuckDuckGo method as agent.py web_search) ----

  /** [snippets] holds each source's short search-result text, in the same order as [sources]. */
  data class SearchResult(val text: String, val sources: List<Source>, val snippets: List<String> = emptyList())

  suspend fun webSearch(query: String, max: Int = 5): SearchResult = withContext(Dispatchers.IO) {
    val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
      connectTimeout = 15_000
      readTimeout = 15_000
      setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Jonaki")
    }
    val page = try {
      conn.inputStream.bufferedReader().readText()
    } finally {
      conn.disconnect()
    }
    val heads = Regex("""<a[^>]+class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
      .findAll(page).toList()
    val out = mutableListOf<String>()
    val found = mutableListOf<Source>()
    val snippets = mutableListOf<String>()
    for ((k, m) in heads.withIndex()) {
      var link = unescape(m.groupValues[1])
      Regex("uddg=([^&]+)").find(link)?.let { link = URLDecoder.decode(it.groupValues[1], "UTF-8") }
      if (link.startsWith("//")) link = "https:$link"
      if ("duckduckgo.com/y.js" in link) continue // ad
      val title = unescape(m.groupValues[2].replace(Regex("<[^>]+>"), "")).trim()
      val end = if (k + 1 < heads.size) heads[k + 1].range.first else page.length
      val snip = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        .find(page.substring(m.range.last + 1, end))?.groupValues?.get(1)
        ?.let { unescape(it.replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim().take(240) } ?: ""
      if (title.isNotEmpty()) {
        out.add("${out.size + 1}. $title\n   $snip\n   $link")
        found.add(Source(title, link))
        snippets.add(snip)
      }
      if (out.size >= max) break
    }
    SearchResult(if (out.isEmpty()) "No results for '$query'." else "Results for '$query':\n" + out.joinToString("\n"), found, snippets)
  }

  private fun unescape(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()

  fun checkFactsPrompt(results: String) =
    "Check the facts in your last answer against these web search results. List anything that was " +
      "wrong or unclear and give the corrected facts with the source link. If it was all correct, say so.\n\n$results"
}
