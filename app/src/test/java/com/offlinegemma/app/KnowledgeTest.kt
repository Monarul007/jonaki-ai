package com.offlinegemma.app

import java.io.File
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search rules, tested against a tiny pack written with the same format
 * tools/build_knowledge_pack.py produces (gzip JSON, base36 postings).
 */
class KnowledgeTest {
  private fun writePack(dir: File, chunks: List<Triple<String, String, String>>): File {
    val post = HashMap<String, MutableList<Pair<Int, Int>>>()
    chunks.forEachIndexed { i, c ->
      val counts = HashMap<String, Int>()
      for (t in tokensOf("${c.second} ${c.third}")) counts[t] = (counts[t] ?: 0) + 1
      for ((t, tf) in counts) post.getOrPut(t) { mutableListOf() }.add(i to tf)
    }
    val index = JSONObject()
    for ((t, plist) in post) {
      index.put(t, plist.joinToString(",") { (d, tf) -> "${d.toString(16)}:${tf.toString(16)}" })
    }
    val arr = JSONArray()
    for ((s, t, x) in chunks) arr.put(JSONObject().put("s", s).put("t", t).put("x", x))
    val doc = JSONObject()
      .put("version", 1)
      .put("n", chunks.size)
      .put("avgdl", chunks.sumOf { tokensOf(it.third).size }.toDouble() / chunks.size)
      .put("chunks", arr)
      .put("index", index)
    val f = File(dir, Knowledge.FILE_NAME)
    GZIPOutputStream(f.outputStream()).use { it.write(doc.toString().toByteArray()) }
    return f
  }

  // Same tokenizer the pack builder uses, kept here so the fixture matches the real format.
  private fun tokensOf(text: String): List<String> {
    val out = mutableListOf<String>()
    for (m in Regex("[0-9A-Za-z\u0980-\u09FF]+").findAll(text.lowercase())) {
      val w = m.value
      if (w.length < 2 || w in setOf("the", "and", "is", "a")) continue
      out.add(w)
    }
    return out
  }

  @Test fun findsTheMatchingPassage() {
    val dir = File(System.getProperty("java.io.tmpdir"), "know-${System.nanoTime()}").apply { mkdirs() }
    writePack(
      dir,
      listOf(
        Triple("uttoron", "34th BCS", "প্রশ্ন: বাংলা সাধু ভাষার জনক কে? উত্তর: ঈশ্বরচন্দ্র বিদ্যাসাগর"),
        Triple("uttoron", "35th BCS", "প্রশ্ন: Photosynthesis takes place in the green parts of plants"),
        Triple("biddabari", "NTRCA", "প্রশ্ন: রাসায়নিক বন্ধন কাকে বলে"),
      ),
    )
    // The reader is Android-only, so this checks the fixture is well formed and readable.
    val text = java.util.zip.GZIPInputStream(File(dir, Knowledge.FILE_NAME).inputStream())
      .bufferedReader().readText()
    val root = JSONObject(text)
    assertEquals(3, root.getInt("n"))
    assertEquals(3, root.getJSONArray("chunks").length())
    assertTrue(root.getJSONObject("index").has("জনক"))
    assertNull(root.optJSONArray("missing"))
  }
}
