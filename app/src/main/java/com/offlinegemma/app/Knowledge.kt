package com.offlinegemma.app

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.ln
import org.json.JSONObject

/**
 * The user's own study material, searched on the phone.
 *
 * The pack (built by tools/build_knowledge_pack.py) is side-loaded into the app's
 * private folder; it is never shipped in the APK or the repository, because every
 * source it was built from is marked "personal study only, do not redistribute".
 *
 * Search is BM25 over pre-computed postings, so nothing but plain Kotlin is needed and
 * the model is only given the few passages that match. That keeps the answer inside the
 * 4k/8k token window instead of trying to feed a whole textbook to a 2B model.
 */
object Knowledge {
  private const val TAG = "Knowledge"
  const val FILE_NAME = "knowledge.pack"

  /** One passage of the user's material. */
  data class Passage(val source: String, val title: String, val text: String)

  private class Pack(
    val passages: List<Passage>,
    val postings: Map<String, List<Pair<Int, Int>>>,
    val avgLength: Double,
  )
  @Volatile private var pack: Pack? = null
  @Volatile private var loadedFor: Long = -1

  fun file(context: Context) = File(context.filesDir, FILE_NAME)

  fun isInstalled(context: Context) = file(context).isFile

  /** Every passage, for the semantic index. Empty when the pack is not installed. */
  fun passages(context: Context): List<Passage> = load(context)?.passages ?: emptyList()

  /**
   * Identifies this exact pack file, so a semantic index can tell whether it still describes it.
   * Null when there is no pack.
   */
  fun packStamp(context: Context): Long? {
    val f = file(context)
    if (!f.isFile) return null
    return f.length() * 31 + f.lastModified()
  }

  /** How many passages and their total size, for Settings. */
  fun stats(context: Context): String? {
    val p = load(context) ?: return null
    val chars = p.passages.sumOf { it.text.length }
    val sources = p.passages.map { it.source }.distinct().sorted()
    return "${p.passages.size} passages · ${formatBytes(chars.toLong())} of text · ${sources.joinToString(", ")}"
  }

  /** Loads the pack once per file version (a re-pushed pack is picked up on next load). */
  private fun load(context: Context): Pack? {
    val f = file(context)
    if (!f.isFile) return null
    val stamp = f.length() * 31 + f.lastModified()
    pack?.let { if (loadedFor == stamp) return it }
    synchronized(this) {
      pack?.let { if (loadedFor == stamp) return it }
      val started = System.currentTimeMillis()
      val loaded = runCatching { parse(f) }.onFailure {
        Log.w(TAG, "Could not read ${f.name}", it)
      }.getOrNull() ?: return null
      pack = loaded
      loadedFor = stamp
      Log.i(TAG, "Loaded ${loaded.passages.size} passages in ${System.currentTimeMillis() - started} ms")
      return loaded
    }
  }

  private fun parse(f: File): Pack {
    val text = GZIPInputStream(f.inputStream().buffered()).bufferedReader().use { it.readText() }
    val root = JSONObject(text)
    val arr = root.getJSONArray("chunks")
    val passages = ArrayList<Passage>(arr.length())
    for (i in 0 until arr.length()) {
      val o = arr.getJSONObject(i)
      passages.add(Passage(o.optString("s"), o.optString("t"), o.optString("x")))
    }
    val idx = root.getJSONObject("index")
    val postings = HashMap<String, List<Pair<Int, Int>>>(idx.length() * 2)
    for (term in idx.keys()) {
      val raw = idx.optString(term)
      if (raw.isBlank()) continue
      val list = ArrayList<Pair<Int, Int>>(8)
      for (entry in raw.split(',')) {
        val colon = entry.indexOf(':')
        if (colon <= 0) continue
        val doc = entry.substring(0, colon).toIntOrNull(16) ?: continue
        val tf = entry.substring(colon + 1).toIntOrNull(16) ?: continue
        if (doc in passages.indices) list.add(doc to tf)
      }
      postings[term] = list
    }
    return Pack(passages, postings, root.optDouble("avgdl", 40.0))
  }

  /**
   * The passages that best match [query], best first. Empty when the pack is not
   * installed or nothing matches, so the caller can simply answer from the model.
   */
  fun search(context: Context, query: String, limit: Int = 3): List<Passage> {
    val p = load(context) ?: return emptyList()
    val terms = tokens(query).distinct()
    if (terms.isEmpty()) return emptyList()

    // Score, and remember which query terms each passage actually matched. A high score with
    // a single common word matched almost everything, which was worse than no reference at all.
    val n = p.passages.size.toDouble()
    val k1 = 1.4
    val b = 0.75
    val scores = HashMap<Int, Double>()
    val matched = HashMap<Int, Int>()
    for (term in terms) {
      val plist = p.postings[term] ?: continue
      val idf = ln(1.0 + (n - plist.size + 0.5) / (plist.size + 0.5))
      for ((doc, tf) in plist) {
        val len = p.passages[doc].text.length.toDouble()
        val denom = tf + k1 * (1 - b + b * len / p.avgLength)
        scores[doc] = (scores[doc] ?: 0.0) + idf * (tf * (k1 + 1)) / denom
        matched[doc] = (matched[doc] ?: 0) + 1
      }
    }
    if (scores.isEmpty()) return emptyList()

    // A passage must cover a real share of the question: with two or more words, one word in
    // common is a coincidence ("Capital of Australia" matched a passage about capital letters).
    val need = if (terms.size >= 2) 2 else 1
    val best = scores.values.max()
    return scores.entries
      .filter { (matched[it.key] ?: 0) >= need }
      .filter { it.value >= MIN_SCORE && it.value >= best * 0.45 }
      .sortedByDescending { it.value }
      .take(limit)
      .map { p.passages[it.key] }
  }

  /** Below this the "match" is noise, and adding it to the prompt makes answers worse. */
  private const val MIN_SCORE = 2.0

  private val WORD = Regex("[0-9A-Za-z\u0980-\u09FF]+")
  private val BENGALI = Regex("[\u0980-\u09FF]")
  private val SUFFIXES = listOf("গুলো", "গুলি", "দের", "কে", "ের", "ে", "টি", "রা", "তে", "য়")

  /** Same tokenizer as the pack builder, so the index and the query agree. */
  private fun tokens(text: String): List<String> {
    val out = ArrayList<String>()
    for (m in WORD.findAll(asciiDigits(text.lowercase()))) {
      val w = m.value
      if (w.length < 2 || w in STOP) continue
      out.add(w)
      // "35তম" -> also "35": a question with only the number still finds the paper.
      val digits = Regex("^(\\d+)\\D+$").find(w)?.groupValues?.get(1)
      if (digits != null) out.add(digits)
      if (BENGALI.containsMatchIn(w)) {
        for (suf in SUFFIXES) {
          if (w.length > suf.length + 3 && w.endsWith(suf)) {
            out.add(w.dropLast(suf.length))
            break
          }
        }
      }
    }
    return out
  }

  /** Bengali digits are indexed as ASCII ("৩৫তম" and "35th" both become 35). */
  private fun asciiDigits(s: String): String {
    val b = StringBuilder(s.length)
    for (c in s) b.append(if (c in '০'..'৯') ('0' + (c - '০')) else c)
    return b.toString()
  }

  private val STOP = setOf(
    "the", "and", "for", "are", "was", "were", "what", "which", "who", "when", "where",
    "why", "how", "does", "did", "this", "that", "these", "those", "with", "from",
    // Bengali function words appear in nearly every question and paper.
    "না", "কি", "কী", "কে", "কেন", "কত", "কোন", "কোনটি", "এই", "সেই", "এবং", "বা", "ও",
    "এর", "একটি", "একটা", "এক", "হয়", "হবে", "ছিল", "থেকে", "জন্য", "সাথে", "করে", "করা",
    "করে", "করেন", "বলে", "বলা", "যায়", "যাবে", "পাওয়া", "থাকে", "থাকবে", "তবে", "কিন্তু",
    "আর", "তার", "পরে", "আগে", "উপর", "নিচে", "মধ্যে", "সব", "সকল", "অনেক", "কিছু", "এটি",
    "তা", "এটা", "যেমন", "তেমন", "হলে", "যদি",
  )
}
