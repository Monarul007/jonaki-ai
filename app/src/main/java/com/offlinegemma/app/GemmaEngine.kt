package com.offlinegemma.app

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Holds the loaded Gemma model and one chat. Load once, reuse. */
class GemmaEngine(private val context: Context) {

  /** Where the model file lives. The downloader saves it here. */
  val modelFile: File = ModelInfo.modelFile(context)
  private val prefs = Prefs(context)

  /** How much chat the model remembers. Bigger phones get more (tutor demos are long). */
  private val maxTokens = if (ModelInfo.totalRamBytes(context) >= ModelInfo.MIN_RAM_BYTES) 8192 else 4096

  private var engine: Engine? = null
  private var conversation: Conversation? = null

  /** Which mode actually loaded, e.g. "GPU + image + audio". */
  var mode: String = ""
    private set

  /**
   * Loads the model on a background thread. Tries the fastest setup first and
   * falls back, as LiteRT-LM's Android guide asks:
   * GPU+media -> CPU+media -> CPU text-only.
   */
  val isLoaded: Boolean get() = engine != null

  suspend fun load(): String = loadLock.withLock { withContext(Dispatchers.IO) { loadLocked() } }

  private val loadLock = Mutex()

  private fun loadLocked(): String {
    engine?.let { return mode } // already loaded (e.g. screen rotated)
    require(modelFile.exists()) { "Model file not found at ${modelFile.path}" }
    val attempts = listOf(
      "GPU + image + audio" to EngineConfig(
        modelPath = modelFile.path,
        backend = Backend.GPU(),
        visionBackend = Backend.GPU(),
        audioBackend = Backend.CPU(),
        maxNumTokens = maxTokens,
        cacheDir = context.cacheDir.path,
      ),
      "CPU + image + audio" to EngineConfig(
        modelPath = modelFile.path,
        backend = Backend.CPU(),
        visionBackend = Backend.CPU(),
        audioBackend = Backend.CPU(),
        maxNumTokens = maxTokens,
        cacheDir = context.cacheDir.path,
      ),
      "CPU text-only" to EngineConfig(
        modelPath = modelFile.path,
        backend = Backend.CPU(),
        maxNumTokens = maxTokens,
        cacheDir = context.cacheDir.path,
      ),
    )
    // Try the mode that worked last time first, so a phone doesn't re-try failing modes every launch.
    val ordered = attempts.sortedByDescending { it.first == prefs.lastMode }
    var lastError: Throwable? = null
    for ((name, config) in ordered) {
      val candidate = Engine(config)
      try {
        val start = System.currentTimeMillis()
        candidate.initialize()
        engine = candidate
        mode = name
        prefs.lastMode = name
        conversation = candidate.createConversation(conversationConfig())
        val secs = (System.currentTimeMillis() - start) / 1000.0
        Log.i(TAG, "Model loaded in $secs s using $name")
        return name
      } catch (t: Throwable) {
        Log.w(TAG, "Load failed with $name, trying next", t)
        lastError = t
        runCatching { candidate.close() }
      }
    }
    throw IllegalStateException("Could not load model: ${lastError?.message}", lastError)
  }

  /** Sends a message; returns the answer piece by piece (streaming). */
  fun send(text: String): Flow<String> {
    val chat = checkNotNull(conversation) { "Model not loaded yet" }
    return chat.sendMessageAsync(text).map { it.toString() }
  }

  /** "Stop" button. */
  fun stop() {
    conversation?.cancelProcess()
  }

  /** "New chat" button: keeps the model loaded, forgets the old chat. */
  fun newChat() {
    val e = engine ?: return
    conversation?.close()
    conversation = e.createConversation(conversationConfig())
  }

  fun close() {
    runCatching { conversation?.close() }
    runCatching { engine?.close() }
    conversation = null
    engine = null
  }

  private fun conversationConfig() = ConversationConfig(
    systemInstruction = Contents.of("You are a helpful, friendly assistant. Keep answers short and clear."),
    samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0),
  )

  companion object {
    private const val TAG = "GemmaEngine"
  }
}
