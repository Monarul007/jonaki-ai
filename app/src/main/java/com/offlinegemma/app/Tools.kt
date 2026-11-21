package com.offlinegemma.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.Html
import java.net.HttpURLConnection
import java.net.URL
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

  /** agent.py ROUTES "web": words that mean the answer may need the internet. */
  private val WEB_RX = Regex(
    "search|google|latest|news|today|current|price|weather|who is|what is|when did|" +
      "https?://|www\\.|website|web ?page|online|internet|look up|find out|release|version",
    RegexOption.IGNORE_CASE,
  )

  fun wantsWeb(text: String) = WEB_RX.containsMatchIn(text)

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

  data class Page(val title: String, val text: String)

  suspend fun webFetch(url0: String, query: String, maxChars: Int = 3000): Page = withContext(Dispatchers.IO) {
    val url = if (Regex("^https?://").containsMatchIn(url0)) url0 else "https://$url0"
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
      connectTimeout = 15_000
      readTimeout = 15_000
      instanceFollowRedirects = true
      setRequestProperty("User-Agent", UA)
    }
    val raw = try {
      conn.inputStream.use { s -> s.readNBytes(3_000_000) }.toString(Charsets.UTF_8)
    } finally {
      conn.disconnect()
    }
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
    val words = Regex("\\w{3,}").findAll(query.lowercase()).map { it.value }.toSet()
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
