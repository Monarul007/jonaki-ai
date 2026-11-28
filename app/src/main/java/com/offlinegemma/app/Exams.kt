package com.offlinegemma.app

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * The exam papers, read from `exam.pack` in the app's assets (or the user's own copy).
 *
 * Built by tools/build_exam_pack.py from the same NTRCA/BCS study folders as knowledge.pack, but
 * structured rather than prose: four options, the key, the subject, and the paper's own
 * explanation when it has one. Loading follows Knowledge.kt: parse once, memoise by file stamp so
 * a re-pushed pack is picked up without a restart.
 *
 * If exam.pack is missing, the papers are rebuilt from knowledge.pack's answer-key passages
 * ("প্রশ্ন: … ক) … উত্তর: …"), which the knowledge index already contains. That fallback exists so
 * exam mode still works on a machine where only the older pack was built.
 */
object Exams {
  private const val TAG = "Exams"
  const val FILE_NAME = "exam.pack"
  private const val ASSET_NAME = "exam.pack"

  private class Pack(val papers: List<ExamPaper>)
  @Volatile private var pack: Pack? = null
  @Volatile private var loadedFor: Long = -1
  @Volatile private var fallbackTried = false
  @Volatile private var fallbackPack: Pack? = null

  fun file(context: Context) = File(context.filesDir, FILE_NAME)

  /**
   * Copies the pack bundled in the APK into app storage on first run, and again when a new build
   * ships a different one. A pack the user imported is left alone.
   */
  fun installBundled(context: Context) {
    val assetHash = runCatching { hashOfAsset(context) }.getOrNull() ?: return
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val key = "exam_pack_hash"
    if (prefs.getString(key, null) == assetHash && file(context).isFile) return
    runCatching {
      context.assets.open(ASSET_NAME).use { input ->
        file(context).outputStream().use { out -> input.copyTo(out) }
      }
      prefs.edit().putString(key, assetHash).apply()
      Log.i(TAG, "bundled exam pack installed (${file(context).length()} bytes)")
    }.onFailure {
      file(context).delete()
      Log.w(TAG, "could not install the bundled exam pack", it)
    }
  }

  private fun hashOfAsset(context: Context): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    context.assets.open(ASSET_NAME).use { input ->
      val buf = ByteArray(1 shl 16)
      while (true) {
        val n = input.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
      }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }

  /** Every paper, newest first. Cheap: this is the index, not the questions. */
  fun papers(context: Context): List<ExamPaper> = load(context)?.papers ?: emptyList()

  fun paper(context: Context, id: String): ExamPaper? =
    load(context)?.papers?.firstOrNull { it.id == id }

  fun categories(context: Context): List<String> =
    papers(context).map { it.category }.distinct().sorted()

  fun isInstalled(context: Context): Boolean = load(context) != null

  private fun load(context: Context): Pack? {
    val f = file(context)
    if (!f.isFile) return buildFallback(context)
    val stamp = f.length() * 31 + f.lastModified()
    pack?.let { if (loadedFor == stamp) return it }
    synchronized(this) {
      pack?.let { if (loadedFor == stamp) return it }
      val loaded = runCatching { parse(f) }.onFailure {
        Log.w(TAG, "could not read ${f.name}", it)
      }.getOrNull()
      if (loaded == null) return buildFallback(context)
      pack = loaded
      loadedFor = stamp
      Log.i(TAG, "loaded ${loaded.papers.size} papers")
      return loaded
    }
  }

  private fun parse(f: File): Pack {
    val text = GZIPInputStream(f.inputStream().buffered()).bufferedReader().use { it.readText() }
    val root = JSONObject(text)
    val index = root.optJSONArray("exams") ?: JSONArray()
    val byId = root.optJSONObject("questions") ?: JSONObject()
    val papers = ArrayList<ExamPaper>(index.length())
    for (i in 0 until index.length()) {
      val meta = index.optJSONObject(i) ?: continue
      val id = meta.optString("id").ifBlank { continue }
      val items = byId.optJSONArray(id) ?: continue
      val questions = ArrayList<ExamQuestion>(items.length())
      for (j in 0 until items.length()) {
        val q = items.optJSONObject(j) ?: continue
        val options = q.optJSONArray("options") ?: continue
        if (options.length() != 4) continue
        val answer = q.optInt("answer", -1)
        if (answer !in 0..3) continue
        questions.add(
          ExamQuestion(
            n = q.optInt("n", j + 1),
            subject = q.optString("subject"),
            text = q.optString("text"),
            options = (0 until 4).map { options.optString(it) },
            answer = answer,
            explain = q.optString("explain").takeIf { it.isNotBlank() && it != "null" },
          ),
        )
      }
      if (questions.isEmpty()) continue
      val subjects = ArrayList<ExamSubject>()
      val subjArr = meta.optJSONArray("subjects")
      if (subjArr != null) {
        for (k in 0 until subjArr.length()) {
          val s = subjArr.optJSONObject(k) ?: continue
          subjects.add(ExamSubject(s.optString("name"), s.optInt("count")))
        }
      }
      papers.add(
        ExamPaper(
          id = id,
          title = meta.optString("title", id),
          titleBn = meta.optString("titleBn").ifBlank { meta.optString("title", id) },
          category = meta.optString("category", "other"),
          durationMinutes = meta.optInt("durationMinutes", ExamScoring.paperMinutes(meta.optString("category"))),
          negative = meta.optDouble("negative", ExamScoring.paperNegative(meta.optString("category"))),
          subjects = subjects,
          questions = questions,
        ),
      )
    }
    return Pack(papers)
  }

  /** Builds papers from knowledge.pack's answer-key passages, when exam.pack is not installed. */
  private fun buildFallback(context: Context): Pack? {
    if (fallbackTried) return fallbackPack
    synchronized(this) {
      if (fallbackTried) return fallbackPack
      fallbackTried = true
      val passages = runCatching { Knowledge.passages(context) }.getOrDefault(emptyList())
      if (passages.isEmpty()) return null
      val grouped = LinkedHashMap<String, MutableList<ExamQuestion>>()
      for (p in passages) {
        val q = parsePassage(p) ?: continue
        grouped.getOrPut(p.title.ifBlank { "Study file" }) { mutableListOf() }.add(q)
      }
      val papers = grouped.filter { it.value.size >= 5 }.map { (title, questions) ->
        val numbered = questions.mapIndexed { i, q -> q.copy(n = i + 1) }
        val counts = LinkedHashMap<String, Int>()
        for (q in numbered) counts[q.subject] = (counts[q.subject] ?: 0) + 1
        val category = if (title.contains("NTRCA", true) || title.contains("এনটিআরসিএ")) "ntrca" else "bcs"
        ExamPaper(
          id = "kp-" + title.take(40).replace(Regex("[^A-Za-z0-9\\u0980-\\u09FF]+"), "-"),
          title = title,
          titleBn = title,
          category = category,
          durationMinutes = ExamScoring.paperMinutes(category),
          negative = ExamScoring.paperNegative(category),
          subjects = counts.map { ExamSubject(it.key, it.value) },
          questions = numbered,
        )
      }
      if (papers.isEmpty()) return null
      Log.i(TAG, "rebuilt ${papers.size} papers from knowledge.pack")
      fallbackPack = Pack(papers)
      return fallbackPack
    }
  }

  private val QUESTION = Regex("""প্রশ্ন\s*[:：]\s*(.+)""")
  private val ANSWER = Regex("""উত্তর\s*[:：]\s*(.+)""")
  private val EXPLAIN = Regex("""ব্যাখ্যা\s*[:：]\s*(.+)""")
  private val FIRST_OPTION = Regex("""[কখগঘ]\s*\)""")
  private val OPTION_RUN = Regex("""([কখগঘ])\s*\)\s*(.*?)(?=\s+[কখগঘ]\s*\)|$)""")

  /**
   * One knowledge.pack passage -> one question, or null when it is not a four-option MCQ.
   *
   * The passage was written by build_knowledge_pack.py as
   * `প্রশ্ন: … ` then `ক) …  খ) …  গ) …  ঘ) …` then `উত্তর: …`, all on one line because the builder
   * normalises whitespace. So the three parts are cut out by their markers rather than by line:
   * the stem runs up to the first option marker, the options run up to `উত্তর:`, and the key is
   * resolved by matching the answer text against the options (the passage records the answer's
   * words, not its index).
   *
   * Parsing the options off the whole passage would fold `উত্তর: পাঁচ` into the fourth option, which
   * is how a wrong key gets accepted - hence the explicit cut.
   */
  fun parsePassage(passage: Knowledge.Passage): ExamQuestion? {
    val text = passage.text
    val afterQuestion = QUESTION.find(text)?.groupValues?.get(1) ?: return null
    val answerText = ANSWER.find(text)?.groupValues?.get(1)
      ?.substringBefore("ব্যাখ্যা")
      ?.trim()
      ?: return null

    val firstOption = FIRST_OPTION.find(afterQuestion) ?: return null
    val stem = afterQuestion.substring(0, firstOption.range.first).trim()
    val optionEnd = ANSWER.find(afterQuestion)?.range?.first ?: afterQuestion.length
    val optionSegment = afterQuestion.substring(firstOption.range.first, optionEnd)

    val parsed = OPTION_RUN.findAll(optionSegment)
      .map { it.groupValues[1] to it.groupValues[2].trim() }
      .toList()
    if (parsed.size != 4) return null
    // The letters must be exactly ক খ গ ঘ in order: a duplicated marker would otherwise pass as
    // four options and silently shift the key.
    if (parsed.map { it.first } != listOf("ক", "খ", "গ", "ঘ")) return null
    val options = parsed.map { it.second }
    if (options.any { it.isBlank() } || stem.isBlank()) return null

    // Exact match first; then the option that contains the answer text (or is contained by it,
    // for an answer key written as a short label like "যশোর").
    var key = options.indexOfFirst { it == answerText }
    if (key < 0) key = options.indexOfFirst { it.contains(answerText) || answerText.contains(it) }
    if (key !in 0..3) return null
    val explain = EXPLAIN.find(text)?.groupValues?.get(1)?.trim()?.take(420)
    return ExamQuestion(
      n = 0,
      subject = "সাধারণ",
      text = stem.take(600),
      options = options,
      answer = key,
      explain = explain,
    )
  }
}
