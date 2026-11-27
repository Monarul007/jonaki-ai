package com.offlinegemma.app

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Semantic search over the user's study pack, with EmbeddingGemma 2 on the CPU.
 *
 * Why this exists next to [Knowledge]'s BM25: BM25 only matches words that literally coincide. It
 * cannot see that "শতকরা" and "percentage" are the same question, and its relevance floor is
 * *relative* to its own best hit — so when every candidate is a bad one, the best of them still
 * passes. That is how chemistry passages were injected into a 2:3:4:5 ratio sum. A cosine gate is
 * an absolute number, so "irrelevant" becomes something we can actually test.
 *
 * Vectors are computed on the device with the shipped model, never on a PC: a different build's
 * tokenizer would produce vectors that do not line up with the query's, and nothing would look
 * broken — retrieval would just quietly get worse.
 */
object Semantic {
  private const val TAG = "Semantic"

  /** The embedder. Pinned so the file never changes under the index. */
  const val NAME = "EmbeddingGemma 2"
  const val FILE_NAME = "embeddinggemma-2-740m.litertlm"
  private const val REPO = "litert-community/embeddinggemma-2-740m-litert-lm"
  const val URL = "https://huggingface.co/$REPO/resolve/main/$FILE_NAME"
  const val SIZE_BYTES = 484_622_336L
  const val SHA256 = "e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c"

  /**
   * Absolute cosine floor, the same number the official sample uses for text retrieval. Below this
   * the passage is simply not shown to the model, however good it looked next to the others.
   */
  const val GATE = 0.70f

  /** How many passages may reach the prompt, best first. */
  const val LIMIT = 3

  /**
   * EmbeddingGemma's documented task prefixes. Documents and questions are embedded with different
   * ones, which is what makes the two comparable. Both sides must use the pair below or the scores
   * drift toward each other and the gate stops meaning anything.
   */
  private const val DOC_PREFIX = "title: none | text: "
  private const val QUERY_PREFIX = "task: search result | query: "

  /** A long passage is split on paragraph boundaries, never cut mid-sentence. */
  internal const val MAX_CHARS = 1200

  private const val MAGIC = 0x4A534931
  private const val VERSION = 2

  data class Hit(val passage: Knowledge.Passage, val score: Float)

  fun file(context: Context) = File(ModelInfo.modelsDir(context), FILE_NAME)
  fun partFile(context: Context) = File(ModelInfo.modelsDir(context), "$FILE_NAME.part")
  fun isDownloaded(context: Context) = file(context).length() == SIZE_BYTES

  /** Vectors live next to the pack they describe. */
  private fun indexFile(context: Context) = File(context.filesDir, "semantic.index")

  /** Kept while a build is in progress, so an interrupted run resumes where it stopped. */
  private fun partialFile(context: Context) = File(context.filesDir, "semantic.index.part")

  /** Every this many pieces the finished vectors are flushed, so a kill loses seconds, not minutes. */
  private const val CHECKPOINT = 500
  private const val PARTIAL_MAGIC = 0x4A534950

  @Volatile private var engine: EmbeddingEngine? = null
  private val lock = Mutex()
  /** Guards opening the embedder, so two chats asking at once cannot load it twice. */
  private val engineLock = Mutex()

  /** Progress of a build, for the UI. Null when nothing is building. */
  @Volatile var progress: Pair<Int, Int>? = null
    private set

  @Volatile private var ready = false

  /** The pack stamp the loaded vectors describe, so a pack replaced on disk is noticed. */
  @Volatile private var loadedStamp = -1L

  /** True once vectors for the current pack are loaded and a query can be embedded. */
  val isReady: Boolean get() = ready

  fun close() {
    runCatching { engine?.close() }
    engine = null
    ready = false
    loadedStamp = -1L
    vectors = FloatArray(0)
    entries = emptyList()
  }

  @Volatile private var vectors = FloatArray(0)
  @Volatile private var dim = 0
  @Volatile private var entries: List<Entry> = emptyList()

  /** One embedded piece of text, pointing back at the passage it came from. */
  private data class Entry(val passage: Int, val text: String)

  /**
   * Makes [isReady] true, indexing the pack if needed. Safe to call repeatedly; the work happens at
   * most once. Returns false when the embedder is missing or the pack is empty, so the caller keeps
   * using BM25.
   */
  /**
   * Fast path: loads a finished index for the current pack. Never embeds anything, so it is safe on
   * the UI path. Indexing is the worker's job.
   */
  suspend fun load(context: Context): Boolean = lock.withLock {
    if (ready) return true
    if (!isDownloaded(context)) return false
    withContext(Dispatchers.IO) {
      val passages = Knowledge.passages(context)
      if (passages.isEmpty()) return@withContext false
      val packStamp = Knowledge.packStamp(context) ?: return@withContext false
      // An index for a different pack, model or dimension is not reused.
      if (loadIndex(context, packStamp, passages.size)) {
        ready = true
        Log.i(TAG, "index loaded: ${entries.size} vectors, ${passages.size} passages")
        loadedStamp = packStamp
        true
      } else {
        false
      }
    }
  }

  /** True when the ready index still describes the pack currently on disk. */
  fun matchesCurrentPack(context: Context): Boolean {
    if (!ready) return false
    val stamp = Knowledge.packStamp(context) ?: return false
    return stamp == loadedStamp
  }

  /** True when the pack and embedder are here but a finished index is not. */
  fun needsIndex(context: Context): Boolean =
    !ready && isDownloaded(context) && Knowledge.isInstalled(context)

  /**
   * Builds the index, or resumes an interrupted one. Called from [SemanticIndexWorker] so a normal
   * phone session is never blocked, and so progress survives the app being closed.
   */
  suspend fun index(context: Context): Boolean = lock.withLock {
    if (ready) return true
    withContext(Dispatchers.IO) {
      if (!isDownloaded(context)) return@withContext false
      val passages = Knowledge.passages(context)
      if (passages.isEmpty()) return@withContext false
      val packStamp = Knowledge.packStamp(context) ?: return@withContext false
      if (loadIndex(context, packStamp, passages.size)) {
        ready = true
        loadedStamp = packStamp
        Log.i(TAG, "index loaded: ${entries.size} vectors, ${passages.size} passages")
        return@withContext true
      }
      // Split before opening the engine and publish the total straight away. Opening the embedder
      // takes seconds on its own, and until the first number appears the row looks like nothing
      // started — which is exactly what a user pressing the button twice reacts to.
      val index = ArrayList<Entry>(passages.size)
      for ((i, p) in passages.withIndex()) {
        for (piece in split(p.text)) index.add(Entry(i, piece))
      }
      progress = 0 to index.size
      try {
        buildIndex(context, packStamp, passages, index)
      } finally {
        // Never leave a stale "Indexing 0 / N" behind when the build did not finish.
        if (!ready) progress = null
      }
    }
  }

  private fun buildIndex(
    context: Context,
    packStamp: Long,
    passages: List<Knowledge.Passage>,
    index: List<Entry>,
  ): Boolean {
    val size = try {
      openEngine(context) ?: return false
    } catch (t: Throwable) {
      Log.w(TAG, "embedder did not load", t)
      return false
    }
    val total = index.size
    val keys = LongArray(total) { hashOf(index[it].text) }
    val all = FloatArray(total * size)
    val done = BooleanArray(total)
    // 1. An interrupted run of this exact pack continues where it stopped.
    val start = loadPartial(context, packStamp, size, total, keys, all)
    if (start > 0) for (i in 0 until start) done[i] = true
    // 2. Anything whose text has not changed keeps its vector. Adding a few resources to the pack
    //    then costs only the new passages instead of another full pass over all of them.
    else {
      val old = loadStore(context, size)
      if (old != null) {
        val at = HashMap<Long, Int>(old.hashes.size * 2)
        for (i in old.hashes.indices) at[old.hashes[i]] = i
        for (i in 0 until total) {
          val hit = at[keys[i]] ?: continue
          old.data.copyInto(all, i * size, hit * size, hit * size + size)
          done[i] = true
        }
      }
    }
    val known = done.count { it }
    progress = known to total
    Log.i(TAG, "indexing $total passages with $size dimensions ($known already known)")
    val started = System.currentTimeMillis()
    for (n in start until total) {
      if (done[n]) continue
      val v = embed(DOC_PREFIX + index[n].text)
      if (v.size != size) return false
      v.copyInto(all, n * size)
      done[n] = true
      progress = (n + 1) to total
      if ((n + 1) % CHECKPOINT == 0) {
        savePartial(context, packStamp, size, total, n + 1, keys, all)
        Log.i(TAG, "indexed ${n + 1}/$total")
      }
    }
    progress = null
    dim = size
    entries = index
    vectors = all
    saveIndex(context, packStamp, keys, size, all)
    partialFile(context).delete()
    ready = true
    loadedStamp = packStamp
    Log.i(TAG, "indexed $total pieces in ${(System.currentTimeMillis() - started) / 1000.0} s")
    return true
  }

  /** The finished vectors so far, flushed atomically so a crash never leaves a half-written file. */
  private fun savePartial(context: Context, packStamp: Long, d: Int, total: Int, done: Int, keys: LongArray, data: FloatArray) {
    runCatching {
      val f = partialFile(context)
      val tmp = File(f.parentFile, f.name + ".tmp")
      DataOutputStream(BufferedOutputStream(GZIPOutputStream(tmp.outputStream().buffered()))).use { out ->
        out.writeInt(PARTIAL_MAGIC)
        out.writeInt(VERSION)
        out.writeLong(packStamp)
        out.writeInt(d)
        out.writeInt(total)
        out.writeInt(done)
        for (i in 0 until done) {
          out.writeLong(keys[i])
          for (j in 0 until d) out.writeFloat(data[i * d + j])
        }
      }
      if (f.exists()) f.delete()
      if (!tmp.renameTo(f)) Log.w(TAG, "could not rename the checkpoint")
    }.onFailure { Log.w(TAG, "could not save the checkpoint", it) }
  }

  /** How many vectors the checkpoint holds for this pack/model/chunking; 0 when it does not fit. */
  private fun loadPartial(context: Context, packStamp: Long, d: Int, total: Int, keys: LongArray, into: FloatArray): Int {
    val f = partialFile(context)
    if (!f.isFile) return 0
    return runCatching {
      DataInputStream(BufferedInputStream(GZIPInputStream(f.inputStream().buffered()))).use { input ->
        if (input.readInt() != PARTIAL_MAGIC || input.readInt() != VERSION) return 0
        input.readLong() // pack stamp: the per-entry hashes below prove the checkpoint is current
        if (input.readInt() != d || input.readInt() != total) return 0
        val done = input.readInt()
        if (done <= 0 || done > total) return 0
        // Read aside and only keep it when every hash matches: a stale checkpoint must not leave
        // some vectors of an older pack mixed into the new index.
        val tmp = FloatArray(done * d)
        for (i in 0 until done) {
          if (input.readLong() != keys[i]) return 0
          for (j in 0 until d) tmp[i * d + j] = input.readFloat()
        }
        tmp.copyInto(into, 0, 0, done * d)
        done
      }
    }.getOrDefault(0)
  }

  /** Splits so nothing is truncated and no piece exceeds [MAX_CHARS]; a short passage stays whole. */
  internal fun split(text: String): List<String> {
    if (text.length <= MAX_CHARS) return listOf(text)
    val out = ArrayList<String>()
    val sb = StringBuilder()
    fun flush() {
      if (sb.isNotBlank()) out.add(sb.toString().trim())
      sb.setLength(0)
    }
    for (para in text.split('\n')) {
      // A paragraph longer than one embedding call is cut at a space: every character is kept,
      // which is the rule the official sample enforces by refusing over-length chunks outright.
      if (para.length > MAX_CHARS) {
        flush()
        var start = 0
        while (start < para.length) {
          var end = minOf(start + MAX_CHARS, para.length)
          if (end < para.length) {
            val space = para.lastIndexOf(' ', end)
            if (space > start) end = space
          }
          out.add(para.substring(start, end).trim())
          start = if (end > start) end else start + MAX_CHARS
        }
        continue
      }
      if (sb.length + para.length + 1 > MAX_CHARS && sb.isNotEmpty()) flush()
      sb.append(para).append('\n')
    }
    flush()
    return out.filter { it.isNotBlank() }
  }

  /**
   * The passages that clear [GATE] for [query], best first. Empty when nothing is relevant — which
   * is the point: the answer then comes from the model instead of from an unrelated page.
   */
  suspend fun search(context: Context, query: String, limit: Int = LIMIT): List<Hit> = withContext(Dispatchers.IO) {
    if (!ready) return@withContext emptyList()
    // A finished index is loaded at startup without touching the embedder (~0.5 GB, too much to
    // hold next to E4B before it is needed). The engine is opened on the first question that
    // actually needs retrieval and then kept for the session. Without this, a relaunch left
    // engine == null and every search silently returned nothing.
    val d = engineLock.withLock { openEngine(context) } ?: return@withContext emptyList()
    val q = embed(QUERY_PREFIX + query.trim()).takeIf { it.isNotEmpty() } ?: return@withContext emptyList()
    if (q.size != d) return@withContext emptyList()
    val passages = Knowledge.passages(context)
    val best = HashMap<Int, Float>()
    var top = -1f
    var i = 0
    while (i + d <= vectors.size) {
      var dot = 0f
      var k = 0
      while (k < d) { dot += q[k] * vectors[i + k]; k++ }
      val e = entries.getOrNull(i / d) ?: break
      if (dot > top) top = dot
      if (dot >= GATE && dot > (best[e.passage] ?: 0f)) best[e.passage] = dot
      i += d
    }
    // The best score even when nothing clears the gate: that is the only way to tell a query the
    // pack does not cover from a gate that is simply too high for the language.
    Log.i(TAG, "search '${query.take(40)}': top=${"%.3f".format(top)} aboveGate=${best.size} (gate=$GATE)")
    best.entries.sortedByDescending { it.value }.asSequence()
      .mapNotNull { en -> passages.getOrNull(en.key)?.let { Hit(it, en.value) } }
      .take(limit).toList()
  }

  private fun openEngine(context: Context): Int? {
    engine?.let { return dim.takeIf { it > 0 } }
    val e = EmbeddingEngine(
      EmbeddingEngineConfig(
        modelPath = file(context).path,
        backend = Backend.CPU(),
        cacheDir = context.cacheDir.path,
      ),
    )
    e.initialize()
    engine = e
    // The model reports its own width; one call settles it rather than assuming 768.
    val probe = embed("probe")
    if (probe.isEmpty()) { close(); return null }
    dim = probe.size
    return dim
  }

  private fun embed(text: String): FloatArray = runCatching {
    val e = engine ?: return FloatArray(0)
    val v = e.computeEmbedding(
      listOf(InputData.Text(text)),
      EmbeddingOptions(normalize = true, insertSpecialTokens = true),
    ).embedding
    // Normalised by the engine, but normalise again: a zero/near-zero vector would make every
    // cosine meaningless, and an un-normalised one would make the gate incomparable.
    var sum = 0.0
    for (x in v) sum += x.toDouble() * x
    val norm = sqrt(sum)
    if (norm < 1e-6) return FloatArray(0)
    FloatArray(v.size) { (v[it] / norm).toFloat() }
  }.getOrDefault(FloatArray(0))

  // --- index file -------------------------------------------------------------------------------

  private fun saveIndex(context: Context, packStamp: Long, keys: LongArray, d: Int, data: FloatArray) {
    runCatching {
      val f = indexFile(context)
      val tmp = File(f.parentFile, f.name + ".tmp")
      DataOutputStream(BufferedOutputStream(GZIPOutputStream(tmp.outputStream().buffered()))).use { out ->
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeLong(packStamp)
        out.writeInt(d)
        out.writeInt(keys.size)
        for (i in keys.indices) {
          out.writeLong(keys[i])
          for (j in 0 until d) out.writeFloat(data[i * d + j])
        }
      }
      if (f.exists()) f.delete()
      tmp.renameTo(f)
    }.onFailure { Log.w(TAG, "could not save the index", it) }
  }

  /** True only when the file describes exactly this pack, this model width and this chunking. */
  private fun loadIndex(context: Context, packStamp: Long, passageCount: Int): Boolean {
    val f = indexFile(context)
    if (!f.isFile) return false
    return runCatching {
      DataInputStream(BufferedInputStream(GZIPInputStream(f.inputStream().buffered()))).use { input ->
        if (input.readInt() != MAGIC || input.readInt() != VERSION) return false
        if (input.readLong() != packStamp) return false
        val d = input.readInt()
        val count = input.readInt()
        if (d <= 0 || count <= 0 || d > 4096 || count > 2_000_000) return false
        // Rebuild the entry list the same way it was written; if it no longer matches the pack,
        // the file is stale and the caller re-indexes.
        val rebuilt = ArrayList<Entry>(count)
        for ((i, p) in Knowledge.passages(context).withIndex()) for (piece in split(p.text)) rebuilt.add(Entry(i, piece))
        if (rebuilt.size != count) return false
        val data = FloatArray(count * d)
        for (i in 0 until count) {
          input.readLong() // the text hash; only the build needs it, to reuse vectors
          for (j in 0 until d) data[i * d + j] = input.readFloat()
        }
        dim = d
        entries = rebuilt
        vectors = data
        true
      }
    }.getOrDefault(false)
  }

  private class Store(val hashes: LongArray, val data: FloatArray)

  /**
   * The previous index, whatever pack it was built for, as long as the vectors are the same width.
   * Its hashes let a rebuild keep every passage that did not change.
   */
  private fun loadStore(context: Context, d: Int): Store? {
    val f = indexFile(context)
    if (!f.isFile) return null
    return runCatching {
      DataInputStream(BufferedInputStream(GZIPInputStream(f.inputStream().buffered()))).use { input ->
        if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
        input.readLong() // pack stamp: deliberately ignored, the hashes decide what is reused
        if (input.readInt() != d) return null
        val count = input.readInt()
        if (count <= 0 || count > 2_000_000) return null
        val hashes = LongArray(count)
        val data = FloatArray(count * d)
        for (i in 0 until count) {
          hashes[i] = input.readLong()
          for (j in 0 until d) data[i * d + j] = input.readFloat()
        }
        Store(hashes, data)
      }
    }.getOrNull()
  }

  /** A stable 64-bit fingerprint of a chunk's text; equal text never needs embedding twice. */
  private fun hashOf(text: String): Long {
    val d = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    var h = 0L
    for (i in 0 until 8) h = (h shl 8) or (d[i].toLong() and 0xFF)
    return h
  }
}
