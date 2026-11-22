package com.offlinegemma.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Html
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A picture found for a figure marker, with its caption and credits (CC BY-SA needs attribution). */
data class Figure(
  val query: String,
  val img: String,
  val caption: String,
  val title: String,
  val page: String,
  val file: String,
  val license: String,
  val credit: String,
  val checked: Boolean,
)

/**
 * Textbook figures, ported from figures.py:
 * 1. search Wikipedia (lead pictures) and Wikimedia Commons (free, licensed teaching pictures);
 * 2. save a small copy in the app, so it shows again offline;
 * 3. Gemma looks at it, says YES/NO, and writes a caption (the controller does that step).
 * Results are cached by query, so reopening a chat is instant.
 */
class Figures(context: Context) {
  private val dir = File(context.filesDir, "figures").apply { mkdirs() }
  private val indexFile = File(dir, "index.json")

  data class Candidate(
    val thumb: String,
    val file: String,
    val title: String,
    val page: String,
    val desc: String,
    var license: String = "",
    var credit: String = "",
  )

  // ---------- cache ----------

  private fun key(q: String) = q.lowercase().replace(Regex("\\s+"), " ").trim()

  @Synchronized
  private fun index(): JSONObject = runCatching { JSONObject(indexFile.readText()) }.getOrElse { JSONObject() }

  /** A saved picture for this query, or null. Never touches the network. */
  fun cached(query: String, exclude: Collection<String> = emptyList()): Figure? {
    val o = index().optJSONObject(key(query.take(120))) ?: return null
    val f = Figure(
      o.optString("query"), o.optString("img"), o.optString("caption"), o.optString("title"),
      o.optString("page"), o.optString("file"), o.optString("license"), o.optString("credit"), o.optBoolean("checked"),
    )
    return if (File(f.img).exists() && f.file !in exclude) f else null
  }

  @Synchronized
  fun save(f: Figure) {
    val idx = index()
    idx.put(
      key(f.query),
      JSONObject().put("query", f.query).put("img", f.img).put("caption", f.caption).put("title", f.title)
        .put("page", f.page).put("file", f.file).put("license", f.license).put("credit", f.credit).put("checked", f.checked),
    )
    val tmp = File(dir, "index.json.tmp")
    tmp.writeText(idx.toString())
    tmp.renameTo(indexFile)
  }

  fun bytesUsed() = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

  fun clear() {
    dir.deleteRecursively()
    dir.mkdirs()
  }

  // ---------- finding candidates ----------

  private fun getJson(url: String): JSONObject {
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
      connectTimeout = 15_000
      readTimeout = 15_000
      setRequestProperty("User-Agent", UA)
    }
    return try {
      JSONObject(c.inputStream.bufferedReader().readText())
    } finally {
      c.disconnect()
    }
  }

  private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

  /**
   * Data-driven translation: search Bengali Wikipedia for the topic, then follow its English
   * langlink. This works for any topic that has a Bengali article, with no word list.
   */
  private fun englishTitle(q: String): String? {
    val topic = q.replace(Regex("""\bdiagram\b""", RegexOption.IGNORE_CASE), " ").trim()
    if (topic.isEmpty()) return null
    val d = getJson(
      "https://bn.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrsearch=${enc(topic)}" +
        "&gsrlimit=1&prop=langlinks&lllang=en&lllimit=1",
    )
    val pages = d.optJSONObject("query")?.optJSONObject("pages") ?: return null
    for (k in pages.keys()) {
      val ll = pages.getJSONObject(k).optJSONArray("langlinks") ?: continue
      if (ll.length() > 0) {
        val o = ll.getJSONObject(0)
        val en = o.optString("*").ifBlank { o.optString("title") }
        if (en.isNotBlank()) return en
      }
    }
    return null
  }

  private fun wikiCandidates(q: String, n: Int = 3): List<Candidate> {
    val d = getJson(
      "https://en.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrsearch=${enc(q)}" +
        "&gsrlimit=$n&prop=pageimages%7Cinfo&inprop=url&piprop=thumbnail%7Cname&pithumbsize=720",
    )
    val pages = d.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
    return pages.keys().asSequence().map { pages.getJSONObject(it) }.sortedBy { it.optInt("index") }.mapNotNull { pg ->
      val th = pg.optJSONObject("thumbnail") ?: return@mapNotNull null
      if (minOf(th.optInt("width"), th.optInt("height")) < 160) return@mapNotNull null
      val title = pg.optString("title")
      Candidate(
        thumb = th.getString("source"),
        file = pg.optString("pageimage"),
        title = "$title (Wikipedia)",
        page = pg.optString("fullurl").ifEmpty { "https://en.wikipedia.org/wiki/" + enc(title.replace(' ', '_')) },
        desc = "Main picture of the Wikipedia article '$title'.",
      )
    }.toList()
  }

  private fun commonsCandidates(q: String, n: Int = 6): List<Candidate> {
    val d = getJson(
      "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search" +
        "&gsrsearch=${enc("$q filetype:bitmap|drawing")}&gsrnamespace=6&gsrlimit=$n" +
        "&prop=imageinfo&iiprop=url%7Cextmetadata%7Csize&iiurlwidth=720",
    )
    val pages = d.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
    return pages.keys().asSequence().map { pages.getJSONObject(it) }.sortedBy { it.optInt("index") }.mapNotNull { pg ->
      val ii = pg.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@mapNotNull null
      val m = ii.optJSONObject("extmetadata") ?: JSONObject()
      if (minOf(ii.optInt("width"), ii.optInt("height")) < 160 || ii.optString("thumburl").isEmpty()) return@mapNotNull null
      val file = pg.optString("title").removePrefix("File:")
      Candidate(
        thumb = ii.getString("thumburl"),
        file = file,
        title = file.substringBeforeLast('.'),
        page = ii.optString("descriptionurl"),
        desc = strip(meta(m, "ImageDescription")).take(300),
        license = strip(meta(m, "LicenseShortName")),
        credit = credit(meta(m, "Artist")),
      )
    }.toList()
  }

  private fun meta(m: JSONObject, k: String) = m.optJSONObject(k)?.optString("value").orEmpty()

  /** Licence and author of a Wikipedia lead picture (it lives on Commons). */
  fun fileLicense(c: Candidate) {
    runCatching {
      val d = getJson("https://commons.wikimedia.org/w/api.php?action=query&format=json&titles=${enc("File:" + c.file)}&prop=imageinfo&iiprop=extmetadata")
      val pages = d.optJSONObject("query")?.optJSONObject("pages") ?: return
      val pg = pages.getJSONObject(pages.keys().next())
      val m = pg.optJSONArray("imageinfo")?.optJSONObject(0)?.optJSONObject("extmetadata") ?: return
      c.license = strip(meta(m, "LicenseShortName"))
      c.credit = credit(meta(m, "Artist"))
    }
  }

  /**
   * [query] = Gemma's figure words, [question] = what the student asked.
   * Searches with a few key words plus the topic (long phrases make Wikipedia return odd pages).
   */
  suspend fun candidates(query: String, exclude: Collection<String>): List<Candidate> = withContext(Dispatchers.IO) {
    val words = query.trim()
    fun search(w: String) = buildList {
      runCatching { addAll(wikiCandidates(w)) }
      runCatching { addAll(commonsCandidates(w)) }
    }
    var found = search(words)
    // A Bengali query against English Wikipedia usually returns nothing. Follow the Bengali
    // article's English langlink, still with no hardcoded dictionary.
    if (found.isEmpty() && words.any { it in '\u0980'..'\u09FF' }) {
      val en = englishTitle(words)
      if (en != null) found = search(en)
    }
    val seen = exclude.toMutableSet()
    found.filter { c ->
      val k = c.file.ifEmpty { c.thumb }
      if (k in seen || FOREIGN.containsMatchIn(c.file) || JUNK.containsMatchIn(c.file)) false else { seen.add(k); true }
    }
  }

  /** Saves a JPEG/PNG copy (GIFs and other formats become JPEG). Returns the file, or null. */
  suspend fun download(c: Candidate): File? = withContext(Dispatchers.IO) {
    val conn = (URL(c.thumb).openConnection() as HttpURLConnection).apply {
      connectTimeout = 15_000
      readTimeout = 20_000
      setRequestProperty("User-Agent", UA)
    }
    val (data, type) = try {
      conn.inputStream.use { it.readNBytes(4_000_000) } to conn.contentType.orEmpty()
    } finally {
      conn.disconnect()
    }
    val name = sha1(c.thumb).take(16)
    when {
      type.startsWith("image/jpeg") -> File(dir, "$name.jpg").apply { writeBytes(data) }
      type.startsWith("image/png") -> File(dir, "$name.png").apply { writeBytes(data) }
      else -> {
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return@withContext null
        File(dir, "$name.jpg").apply { outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) } }
      }
    }
  }

  companion object {
    const val UA = "Jonaki/1.0 (Android study app; educational figures)"
    const val MAX_TRIES = 3

    // Diagrams translated into other languages: "Photosynthesis-ru.svg" (but "-en" is fine).
    private val FOREIGN = Regex("""[-_ (](?!en\b)[a-z]{2}(?:-[a-z]+)?\)?\.(svg|png|jpg|jpeg|gif)$""", RegexOption.IGNORE_CASE)
    private val JUNK = Regex("logo|icon|flag|coat of arms|signature|portrait|stamp|map of|locator", RegexOption.IGNORE_CASE)

    fun strip(html: String) = Html.fromHtml(html.replace(Regex("<[^>]+>"), " "), Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()

    /** Author name only: drop wiki signatures like "(talk) 13:49, 2 April 2012 (UTC)". */
    fun credit(html: String): String {
      var s = strip(html)
      s = s.replace(Regex("""\(\s*talk\s*\)|\d{1,2}:\d{2},? \d{1,2} \w+ \d{4} \(UTC\)""", RegexOption.IGNORE_CASE), "")
      return s.replace(Regex("\\s+"), " ").trim(' ', ',', ';', '-').take(80)
    }

    fun checkPrompt(topic: String, query: String, c: Candidate) =
      "A student asked: \"$topic\"\n" +
        "This picture is a candidate figure showing: $query.\n" +
        "Its file name is \"${c.file}\". Its own description says: \"${c.desc.ifEmpty { "none" }}\".\n\n" +
        "First line: YES if the picture clearly shows that and would help the student, or NO if it " +
        "does not (wrong subject, a photo of a person or place, a logo, or text in another language).\n" +
        "If YES, then write 2-3 short, simple sentences as a textbook caption: what the picture shows, " +
        "and how to read it (point to the parts, colours, arrows or labels you can actually see). " +
        "Do not invent labels that are not in the picture."

    /** "YES. The picture shows…" -> (true, caption). Null when the answer starts with neither. */
    fun parseVerdict(text: String): Pair<Boolean?, String> {
      val t = text.replace(Regex("^[\\s*#_]+"), "")
      val m = Regex("^(YES|NO)\\b[\\s.:,!*-]*", RegexOption.IGNORE_CASE).find(t) ?: return null to ""
      var cap = t.substring(m.range.last + 1).trim()
      cap = cap.replace(Regex("^(caption|figure caption)\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE), "").trim()
      cap = cap.replace(Regex("\\s*\\n+\\s*"), " ")
      return m.groupValues[1].equals("YES", true) to cap
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
  }
}
