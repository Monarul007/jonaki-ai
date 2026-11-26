package com.offlinegemma.app

import android.content.Context
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

enum class Who { USER, MODEL, NOTICE }

/**
 * A picture, voice clip or document attached to a message. [path] is a file inside the app.
 * [ocrPath] is an optional sharper copy of a picture, kept only so OCR can read small text.
 */
data class Attachment(
  val kind: Kind,
  val name: String,
  val path: String,
  val chars: Int = 0,
  val ocrPath: String? = null,
) {
  enum class Kind { IMAGE, AUDIO, DOC }
}

/** One tool the model used. [ok] null = still running. */
data class ToolUse(val name: String, val detail: String, val ok: Boolean? = null, val result: String = "")

data class Source(val title: String, val url: String)

/**
 * One chat bubble.
 * [display] is what the user sees; [text] is what the model got or gave
 * (it may include hidden tutor rules or extracted document text).
 */
data class ChatLine(
  val id: Long,
  val who: Who,
  val text: String,
  val display: String = text,
  val done: Boolean = true,
  val attachments: List<Attachment> = emptyList(),
  val tools: List<ToolUse> = emptyList(),
  val sources: List<Source> = emptyList(),
  /** The user's real question (typed or a NEXT question); "Check facts" searches the web for it. */
  val topic: String? = null,
  val seconds: Double? = null,
  val error: String? = null,
  // Only while the app is open:
  val demoError: String? = null,
  val autoFixing: Boolean = false,
  /** Text a code test printed (shown under the answer). */
  val runOutput: String? = null,
  /**
   * The model's own reasoning for this answer, when thinking was on. Shown only inside the
   * collapsed work panel, never as part of the answer.
   */
  val thought: String = "",
)

data class ChatSummary(val id: String, val title: String, val updated: Long)

/** Saves chats as small JSON files in the app's private folder. Nothing leaves the phone. */
class ChatStore(context: Context) {
  private val dir = File(context.filesDir, "chats").apply { mkdirs() }

  fun newId(): String = UUID.randomUUID().toString().take(12)

  /** Folder for one chat's pictures, voice clips and documents. */
  fun filesDir(id: String) = File(dir, id).apply { mkdirs() }

  fun list(): List<ChatSummary> =
    dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
      runCatching {
        val o = JSONObject(f.readText())
        ChatSummary(o.getString("id"), o.optString("title", "Chat"), o.optLong("updated"))
      }.getOrNull()
    }.sortedByDescending { it.updated }

  fun load(id: String): Pair<String, List<ChatLine>>? = runCatching {
    val o = JSONObject(File(dir, "$id.json").readText())
    val arr = o.getJSONArray("lines")
    o.optString("title", "Chat") to (0 until arr.length()).map { lineFrom(arr.getJSONObject(it)) }
  }.getOrNull()

  fun save(id: String, title: String, lines: List<ChatLine>) {
    val o = JSONObject()
      .put("id", id)
      .put("title", title)
      .put("updated", System.currentTimeMillis())
      .put("lines", JSONArray(lines.filter { it.done }.map(::lineTo)))
    // Write to a temp file first, so a crash never leaves a half-written chat.
    val tmp = File(dir, "$id.json.tmp")
    tmp.writeText(o.toString())
    tmp.renameTo(File(dir, "$id.json"))
  }

  fun rename(id: String, title: String) {
    val f = File(dir, "$id.json")
    val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return
    o.put("title", title)
    f.writeText(o.toString())
  }

  fun delete(id: String) {
    File(dir, "$id.json").delete()
    File(dir, id).deleteRecursively()
  }

  fun deleteAll() {
    dir.deleteRecursively()
    dir.mkdirs()
  }

  fun bytesUsed(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

  private fun lineTo(l: ChatLine) = JSONObject().apply {
    put("id", l.id)
    put("who", l.who.name)
    put("text", l.text)
    if (l.display != l.text) put("display", l.display)
    if (l.attachments.isNotEmpty()) put("att", JSONArray(l.attachments.map { a ->
      JSONObject().put("k", a.kind.name).put("n", a.name).put("p", a.path).put("c", a.chars).apply {
        a.ocrPath?.let { put("o", it) }
      }
    }))
    if (l.tools.isNotEmpty()) put("tools", JSONArray(l.tools.map {
      JSONObject().put("n", it.name).put("d", it.detail).put("ok", it.ok ?: false).put("r", it.result.take(2000))
    }))
    if (l.sources.isNotEmpty()) put("src", JSONArray(l.sources.map { JSONObject().put("t", it.title).put("u", it.url) }))
    l.topic?.let { put("topic", it) }
    l.seconds?.let { put("secs", it) }
    l.error?.let { put("err", it) }
    l.runOutput?.let { put("run", it) }
    if (l.thought.isNotBlank()) put("thought", l.thought.take(6000))
  }

  private fun lineFrom(o: JSONObject): ChatLine {
    fun <T> arr(key: String, f: (JSONObject) -> T): List<T> =
      o.optJSONArray(key)?.let { a -> (0 until a.length()).map { f(a.getJSONObject(it)) } } ?: emptyList()
    val text = o.optString("text")
    return ChatLine(
      id = o.getLong("id"),
      who = runCatching { Who.valueOf(o.getString("who")) }.getOrDefault(Who.NOTICE),
      text = text,
      display = o.optString("display", text),
      attachments = arr("att") {
        Attachment(
          Attachment.Kind.valueOf(it.getString("k")), it.optString("n"), it.optString("p"),
          it.optInt("c"), it.optString("o").ifEmpty { null },
        )
      },
      tools = arr("tools") { ToolUse(it.optString("n"), it.optString("d"), it.optBoolean("ok"), it.optString("r")) },
      sources = arr("src") { Source(it.optString("t"), it.optString("u")) },
      topic = o.optString("topic").ifEmpty { null },
      seconds = if (o.has("secs")) o.optDouble("secs") else null,
      error = o.optString("err").ifEmpty { null },
      runOutput = o.optString("run").ifEmpty { null },
      thought = o.optString("thought"),
    )
  }
}

/** A short chat title from the first question, like the PC app. */
fun titleFrom(text: String): String {
  val t = text.replace(Regex("\\s+"), " ").trim()
  return if (t.length <= 42) t.ifEmpty { "New chat" } else t.take(40).substringBeforeLast(' ').ifEmpty { t.take(40) } + "…"
}
