package com.offlinegemma.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.Html
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Agent mode: the tools Gemma may use on the phone (ported from agent.py).
 * Only web_search and web_fetch: the shell and project-folder tools are PC-only.
 * Code is tested separately (JsRunner), the way the PC auto-tests answers.
 */
object Tools {
  const val TC_OPEN = "<tool_call>"
  const val TC_CLOSE = "</tool_call>"

  /**
   * agent.py ROUTES "web": words that mean the answer may need the internet.
   * A link typed by the user also counts (see [findLinks]); both route to the web tools
   * even when agent mode is off.
   */
  private val WEB_RX = Regex(
    """latest|news|today|tonight|yesterday|this week|this month|this year|right now|currently|current|""" +
      """price of|price|weather|forecast|score|who won|search|look up|google|find out|release|version|""" +
      """who is|what is|when did|where is|how much|breaking|trending|update|""" +
      """আজকের|আজ|এখন|বর্তমান|সাম্প্রতিক|খবর|সংবাদ|দাম|আবহাওয়া|স্কোর|আপডেট|সর্বশেষ|কে জিতেছে|""" +
      """https?://|www\.|website|web ?page|online|internet""",
    RegexOption.IGNORE_CASE,
  )

  fun wantsWeb(text: String) = WEB_RX.containsMatchIn(text)

  // Links typed by the user: full URLs or bare names like "monarul.pro.bd".
  // File names ("calc.py", "plan.docx") are not websites (agent.py find_links).
  private val FILE_TLDS = Regex(
    """(py|js|ts|tsx|jsx|md|json|csv|tsv|txt|pdf|docx?|xlsx?|pptx?|html?|css|php|png|jpe?g|gif|""" +
      """webp|svg|bmp|wav|mp3|mp4|ogg|exe|zip|rar|log|ya?ml|toml|ini|cfg|bat|cmd|ps1|sh|kt|java|""" +
      """cpp|go|rs|rb|lock|xml|sql|db|env|apk|ipynb)""",
    RegexOption.IGNORE_CASE,
  )
  private val LINK_RX = Regex(
    """(https?://[^\s<>"'`)\]]+)|(?<![@\w/.-])((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+""" +
      """[a-z]{2,24})(/[^\s<>"'`)\]]*)?(?![\w@-])""",
    RegexOption.IGNORE_CASE,
  )
  private val LINK_FILLER = setOf(
    "analyze", "analyse", "analysis", "review", "summarize", "summarise", "summary", "check",
    "visit", "open", "read", "tell", "about", "website", "site", "page", "this", "that",
    "please", "what", "the", "and", "can", "you", "for", "from", "with", "give", "full",
    "details", "detail", "link", "url",
  )

  /** Website addresses in the user's own words, at most [limit] of them. */
  fun findLinks(text: String, limit: Int = 2): List<String> {
    val out = mutableListOf<String>()
    for (m in LINK_RX.findAll(text)) {
      val url = if (m.groups[1] != null) {
        m.groupValues[1].trimEnd('.', ',', ';', ':', '!', '?')
      } else {
        val host = m.groupValues[2]
        if (FILE_TLDS.matches(host.substringAfterLast('.')) || host.lowercase().startsWith("uploads.")) continue
        "https://" + host + m.groupValues[3].trimEnd('.', ',', ';', ':', '!', '?').let { if (it == "/") "" else it }
      }
      if (url !in out) out.add(url)
      if (out.size >= limit) break
    }
    return out
  }

  /** What to look for on a linked page; blank = read the page from the top (agent.py _link_query). */
  fun linkQuery(text: String): String {
    val rest = LINK_RX.replace(text, " ")
    val words = Regex("""[^\W\d_]{3,}""").findAll(rest.lowercase())
      .map { it.value }.filter { it !in LINK_FILLER }.toList()
    return if (words.size >= 2) words.take(12).joinToString(" ") else " "
  }

  /** agent.py REFUSAL_RX: "I don't have information / cannot browse". Never the final word. */
  val REFUSAL_RX = Regex(
    """(don'?t|do not|cannot|can'?t|unable to|not able to)\s+(have|access|browse|open|visit|""" +
      """retrieve|find|look up)[^.\n]{0,80}(information|internet|real[- ]time|website|web|details|""" +
      """data|link|url|page|site)|knowledge cut-?off|as an ai( language)? model|""" +
      """(no|not have any) (specific |real-time |current )?information (about|on)""",
    RegexOption.IGNORE_CASE,
  )

  /** Low-confidence wording, English and Bengali: look it up and attach a figure. */
  val UNSURE_RX = Regex(
    """i'?m not sure|i am not sure|not (entirely |completely )?certain|i (really )?(don'?t|do not) know|""" +
      """no reliable information|i cannot verify|i can'?t verify|i (cannot|can'?t) (tell|know|say|answer)|""" +
      """i am unable to (tell|know|answer)|my knowledge (may be|is) (outdated|limited)|""" +
      """জানি না|জানা নেই|নিশ্চিত নই|নিশ্চিত না|তথ্য নেই|সঠিক তথ্য নেই|""" +
      """যথেষ্ট তথ্য নেই|পর্যাপ্ত তথ্য নেই|জানতে পারি না|পারি না|পারছি না|পারব না""",
    RegexOption.IGNORE_CASE,
  )

  /** True when the answer admits it is not confident (refusal or "I'm not sure"). */
  fun isUnsure(text: String): Boolean {
    val head = text.take(600)
    return REFUSAL_RX.containsMatchIn(head) || UNSURE_RX.containsMatchIn(head)
  }

  fun online(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
  }

  /** Same tool descriptions, call format and rules as agent.py build_system_prompt. */
  val PROMPT = """
## Tools
- web_search: Search the web (DuckDuckGo). Returns titles, short snippets and links. Args: query.
- web_fetch: Read a web page. Args: url, query (what you are looking for; keeps matching parts).

To call a tool, output EXACTLY:
$TC_OPEN{"name":"tool_name","arguments":{"arg":"value"}}$TC_CLOSE

## Rules
- If the answer may be recent or you are not sure, use web_search, then web_fetch a good link. Name the source link in your answer.
- Never reply that you have no information or cannot browse. Look it up with the tools; if they fail or there is no internet, answer from your own knowledge and say it may be out of date.
- Do the task with sensible defaults. Ask the user a question only when it is truly impossible to continue.
- After tool results arrive, answer the user clearly. If no tool is needed, just answer.
""".trim()

  data class Call(val name: String, val args: Map<String, String>)

  private val NAMES = mapOf("web_search" to listOf("query", "num_results"), "web_fetch" to listOf("url", "query", "max_chars"))

  /** Finds <tool_call>{…}</tool_call> in the model's text (agent.py parse_react_calls). */
  fun parseCalls(text: String): List<Call> {
    val pat = Regex(Regex.escape(TC_OPEN) + "\\s*(\\{.*?)\\s*" + Regex.escape(TC_CLOSE), RegexOption.DOT_MATCHES_ALL)
    return pat.findAll(text).mapNotNull { m ->
      val raw = m.groupValues[1]
      runCatching {
        val o = JSONObject(raw)
        val name = o.getString("name")
        val a = o.optJSONObject("arguments") ?: JSONObject().also { j -> o.keys().forEach { k -> if (k != "name") j.put(k, o.get(k)) } }
        Call(name, a.keys().asSequence().associateWith { a.get(it).toString() })
      }.getOrNull() ?: looseCall(raw)
    }.filter { it.name in NAMES }.toList()
  }

  /**
   * Recover a call from almost-JSON (agent.py _loose_call): small models write the
   * arguments un-nested or break the JSON with raw quotes. Take the name, then each
   * known argument as the text between its key and the next key.
   */
  fun looseCall(raw: String): Call? {
    val name = Regex("\"name\"\\s*:\\s*\"(\\w+)\"").find(raw)?.groupValues?.get(1) ?: return null
    val params = NAMES[name] ?: return null
    val keys = Regex("\"(" + params.joinToString("|") { Regex.escape(it) } + ")\"\\s*:\\s*").findAll(raw).toList()
    val args = mutableMapOf<String, String>()
    for ((i, k) in keys.withIndex()) {
      val end = if (i + 1 < keys.size) keys[i + 1].range.first else raw.length
      var v = raw.substring(k.range.last + 1, end).trim().trimEnd('}', ',').trim().trimEnd(',').trim()
      if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length - 1)
      v = runCatching { JSONObject("{\"v\":\"$v\"}").getString("v") }.getOrElse { v.replace("\\n", "\n").replace("\\\"", "\"") }
      args[k.groupValues[1]] = v
    }
    return Call(name, args)
  }

  fun stripCalls(text: String): String =
    text.replace(Regex(Regex.escape(TC_OPEN) + ".*?(" + Regex.escape(TC_CLOSE) + "|$)", RegexOption.DOT_MATCHES_ALL), "").trim()

  /** "I'll search…" with no call: the model announced a step but stopped (agent.py "Go ahead" nudge). */
  fun announcedStep(text: String) =
    Regex("\\b(I'?ll|I will|I need to|let me|next,? I|now I'?ll|first,? I'?ll)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text.takeLast(400))

  // ---------- web_fetch ----------

  private const val UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

  private const val APP_UA = "JonakiAI/1.0 (Android study assistant)"

  data class Page(val title: String, val text: String)

  suspend fun webFetch(context: Context, url0: String, query: String, maxChars: Int = 3000): Page = withContext(Dispatchers.IO) {
    val url = if (Regex("^https?://").containsMatchIn(url0)) url0 else "https://$url0"
    // Some sites refuse an app that calls itself a browser (Wikipedia answers 429); others refuse
    // anything that is not one. Ask as a browser first, and once more under the app's own name.
    fun get(agent: String): Triple<Int, String, ByteArray?> {
      val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 15_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", agent)
      }
      return try {
        val code = conn.responseCode
        Triple(code, conn.contentType.orEmpty(), if (code in 200..299) conn.inputStream.use { s -> s.readNBytes(3_000_000) } else null)
      } finally {
        conn.disconnect()
      }
    }
    var got = get(UA)
    if (got.first == 403 || got.first == 429) got = get(APP_UA)
    val ctype = got.second
    val bytes = got.third ?: throw java.io.IOException("the site answered HTTP ${got.first}")
    // A PDF goes through the phone's document reader, like an attached file.
    if ("pdf" in ctype.lowercase() || url.lowercase().substringBefore('?').endsWith(".pdf")) {
      val f = File(context.cacheDir, "web_${UUID.randomUUID().toString().take(8)}.pdf")
      try {
        f.writeBytes(bytes)
        val text = Attachments.extractText(context, f, "page.pdf", "application/pdf")
        return@withContext Page(
          url,
          if (text.replace(Regex("\\[page \\d+/\\d+]"), "").isBlank())
            "This PDF has no text layer (it is probably scanned pictures). Try another result."
          else text.take(maxChars),
        )
      } finally {
        f.delete()
      }
    }
    val raw = bytes.toString(Charsets.UTF_8)
    val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(raw)
      ?.groupValues?.get(1)?.let { unescape(it).trim().take(90) } ?: url
    val lines = if ("<" in raw.take(2000)) htmlToLines(raw) else raw.lines()
    val out = pickLines(lines, query, maxChars)
    Page(
      title,
      if (out.length < 80) "The page came back almost empty. It probably needs JavaScript to show its content. Try another search result instead."
      else out,
    )
  }

  private fun unescape(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()

  /** False for the app's own "could not read this page" messages from [webFetch]. */
  fun usablePage(text: String) = text.isNotBlank() &&
    !text.startsWith("The page came back almost empty") && !text.startsWith("This PDF has no text layer")

  /**
   * Turns the answer's citations [1], [2] (any digits, Bengali ones too) into links to those
   * sources, so a tap opens the page. A number with no matching source stays plain text.
   */
  fun linkCitations(text: String, sources: List<Source>): String {
    if (sources.isEmpty()) return text
    // "[1]", and lists such as "[1, 5]" (each number becomes its own link).
    return Regex("""\[(\p{Nd}{1,2}(?:\s*[,،]\s*\p{Nd}{1,2})*)\](?!\()""").replace(text) { m ->
      val nums = m.groupValues[1].split(',', '،').map { it.trim() }
      val urls = nums.map { d ->
        val n = d.fold(0) { acc, ch -> acc * 10 + Character.getNumericValue(ch) }
        sources.getOrNull(n - 1)?.url?.takeIf { it.startsWith("http") }
      }
      if (urls.any { it == null }) m.value else nums.zip(urls).joinToString("") { (d, u) -> "[[$d]]($u)" }
    }
  }

  /** agent.py _html_to_lines: drop scripts and page chrome, keep the article, one line per block. */
  fun htmlToLines(html: String): List<String> {
    var c = html.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    c = c.replace(Regex("<(script|style|noscript|svg|nav|header|footer|aside|form)\\b[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
    Regex("<(article|main)\\b[^>]*>(.*?)</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(c)?.let {
      if (it.groupValues[2].length > 500) c = it.groupValues[2]
    }
    c = c.replace(Regex("</?(p|div|br|li|h[1-6]|tr|section|pre|blockquote)\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")
    val t = c.replace(Regex("<[^>]+>"), " ")
    return t.split("\n").map { unescapeEntities(it).replace(Regex("[ \\t\\r\\u000B\\f]+"), " ").trim() }.filter { it.length > 2 }
  }

  private fun unescapeEntities(s: String) = if ('&' in s) unescape(s) else s

  /** agent.py _pick_lines: keep the lines that match the question best, in page order. */
  fun pickLines(lines: List<String>, query: String, maxChars: Int): String {
    val text = lines.joinToString("\n")
    if (text.length <= maxChars || query.isBlank()) return text.take(maxChars)
    val words = Regex("[\\p{L}\\p{M}\\p{Nd}]{3,}").findAll(query.lowercase()).map { it.value }.toSet()
    if (words.isEmpty()) return text.take(maxChars)
    val scored = lines.mapIndexedNotNull { i, ln -> val low = ln.lowercase(); val s = words.count { it in low }; if (s > 0) s to i else null }
    if (scored.isEmpty()) return text.take(maxChars)
    val keep = sortedSetOf<Int>()
    var size = 0
    for ((_, i) in scored.sortedWith(compareBy({ -it.first }, { it.second }))) {
      if (size + lines[i].length > maxChars) continue
      keep.add(i)
      size += lines[i].length + 1
    }
    return keep.joinToString("\n") { lines[it] }
  }
}
