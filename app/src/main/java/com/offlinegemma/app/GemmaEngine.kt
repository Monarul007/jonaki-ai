package com.offlinegemma.app

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Holds the loaded Gemma model (app-wide, so turning the phone never reloads it)
 * and the one open conversation.
 */
class GemmaEngine(private val context: Context, private val prefs: Prefs) {

  val modelFile: File = ModelInfo.modelFile(context)

  /** How many tokens (word pieces) the model can hold. Bigger phones get more. */
  val maxTokens = if (ModelInfo.totalRamBytes(context) >= ModelInfo.MIN_RAM_BYTES) 8192 else 4096

  private var engine: Engine? = null
  private var conversation: Conversation? = null
  private val lock = Mutex()

  /** Which mode actually loaded, e.g. "GPU + image + audio". */
  var mode: String = ""
    private set

  val isLoaded: Boolean get() = engine != null
  val canSee: Boolean get() = "image" in mode
  val canHear: Boolean get() = "audio" in mode

  /** Tokens used by the open conversation so far. */
  val tokensUsed: Int get() = runCatching { conversation?.getTokenCount() ?: 0 }.getOrDefault(0)

  /**
   * Loads the model on a background thread. Tries the fastest setup first and
   * falls back, as LiteRT-LM's Android guide asks: GPU+media -> CPU+media -> CPU text-only.
   */
  suspend fun load(): String = lock.withLock { withContext(Dispatchers.IO) { loadLocked() } }

  private fun loadLocked(): String {
    engine?.let { return mode }
    require(modelFile.exists()) { "Model file not found at ${modelFile.path}" }
    val cache = context.cacheDir.path
    val attempts = listOf(
      "GPU + image + audio" to EngineConfig(
        modelPath = modelFile.path, backend = Backend.GPU(), visionBackend = Backend.GPU(),
        audioBackend = Backend.CPU(), maxNumTokens = maxTokens, cacheDir = cache,
      ),
      "CPU + image + audio" to EngineConfig(
        modelPath = modelFile.path, backend = Backend.CPU(), visionBackend = Backend.CPU(),
        audioBackend = Backend.CPU(), maxNumTokens = maxTokens, cacheDir = cache,
      ),
      "CPU text-only" to EngineConfig(
        modelPath = modelFile.path, backend = Backend.CPU(), maxNumTokens = maxTokens, cacheDir = cache,
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
        conversation = candidate.createConversation(config(SYSTEM, emptyList()))
        Log.i(TAG, "Model loaded in ${(System.currentTimeMillis() - start) / 1000.0} s using $name")
        return name
      } catch (t: Throwable) {
        Log.w(TAG, "Load failed with $name, trying next", t)
        lastError = t
        runCatching { candidate.close() }
      }
    }
    throw IllegalStateException("Could not load model: ${lastError?.message}", lastError)
  }

  /**
   * Starts a fresh conversation that already "remembers" [history]
   * (used for New chat, opening a saved chat, regenerate and compacting).
   */
  suspend fun startChat(history: List<Message>, extraSystem: String = "") = lock.withLock {
    withContext(Dispatchers.IO) {
      val e = engine ?: return@withContext
      runCatching { conversation?.close() }
      conversation = null
      val system = if (extraSystem.isBlank()) SYSTEM else "$SYSTEM\n\n$extraSystem"
      conversation = try {
        e.createConversation(config(system, history))
      } catch (t: Throwable) {
        // History too long or broken: start empty rather than leaving no chat at all.
        Log.w(TAG, "History did not fit; starting empty", t)
        e.createConversation(config(system, emptyList()))
      }
    }
  }

  /** Sends one user turn (text plus optional pictures / voice). Returns the answer piece by piece. */
  fun send(text: String, images: List<ByteArray> = emptyList(), audio: List<ByteArray> = emptyList()): Flow<String> {
    val chat = checkNotNull(conversation) { "Model not loaded yet" }
    val parts = buildList<Content> {
      images.forEach { add(Content.ImageBytes(it)) }
      audio.forEach { add(Content.AudioBytes(it)) }
      add(Content.Text(text))
    }
    return chat.sendMessageAsync(Contents.of(parts)).map { it.toString() }.flowOn(Dispatchers.IO)
  }

  /**
   * One-off question in a throwaway conversation (used to summarise old messages).
   * The open chat is closed first: the engine holds one conversation at a time.
   */
  suspend fun oneShot(prompt: String, maxOutput: Int = 220, image: ByteArray? = null): String = lock.withLock {
    withContext(Dispatchers.IO) {
      val e = engine ?: return@withContext ""
      runCatching { conversation?.close() }
      conversation = null
      val temp = e.createConversation(
        ConversationConfig(
          systemInstruction = Contents.of(if (image != null) "You check pictures for a textbook." else "You summarise conversations briefly."),
          samplerConfig = SAMPLER,
          maxOutputToken = maxOutput,
        ),
      )
      try {
        if (image != null) temp.sendMessage(Contents.of(Content.ImageBytes(image), Content.Text(prompt))).toString()
        else temp.sendMessage(prompt).toString()
      } finally {
        runCatching { temp.close() }
      }
    }
  }

  /**
   * SPIKE: lends the raw engine to another conversation owner (ADK). Our open chat is closed first,
   * because the engine holds one conversation at a time; callers rebuild it afterwards.
   */
  suspend fun <T> withEngine(block: suspend (Engine) -> T): T? = lock.withLock {
    val e = engine ?: return@withLock null
    withContext(Dispatchers.IO) {
      runCatching { conversation?.close() }
      conversation = null
    }
    block(e)
  }

  /** "Stop" button. */
  fun stop() {
    runCatching { conversation?.cancelProcess() }
  }

  fun close() {
    runCatching { conversation?.close() }
    runCatching { engine?.close() }
    conversation = null
    engine = null
    mode = ""
  }

  private fun config(system: String, history: List<Message>) = ConversationConfig(
    systemInstruction = Contents.of(system),
    initialMessages = history,
    samplerConfig = SAMPLER,
    // Explicit ceiling: the default was cutting long Bengali answers short mid-sentence.
    maxOutputToken = 2048,
  )

  companion object {
    private const val TAG = "GemmaEngine"
    private val SAMPLER = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0)

    const val SYSTEM =
      "You are Jonaki AI, a helpful assistant running on the user's own device. Keep answers short " +
        "and clear. Never reply that you have no information or cannot browse: look it up with the " +
        "tools; if they fail or there is no internet, answer from your own knowledge and say it may " +
        "be out of date. Do the task with sensible defaults. Ask the user a question only when it is " +
        "truly impossible to continue."

    // The PC agent's voice line (agent.py build_system_prompt). On the phone it rides along only with
    // voice messages: in the fixed system prompt, Gemma started typed answers with "I hear you!".
    const val VOICE_RULE = "You can see images and hear audio. An attached audio clip is the user speaking " +
      "to you: reply to what they said, and never claim you cannot hear."
  }
}
