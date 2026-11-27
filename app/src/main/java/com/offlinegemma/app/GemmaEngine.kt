package com.offlinegemma.app

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Channel
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingMode
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Holds the loaded Gemma model (app-wide, so turning the phone never reloads it)
 * and the one open conversation.
 */
class GemmaEngine(private val context: Context, private val prefs: Prefs) {

  /** The file and window of whichever model the user chose. Read at load time, not cached. */
  val model: ChatModel get() = prefs.model
  val modelFile: File get() = ModelInfo.file(context, model)

  /**
   * How many tokens (word pieces) the model can hold. The larger model carries a smaller window:
   * its weights are ~1 GB bigger, and weights plus KV cache must fit in the same process. A phone
   * below the recommended RAM halves the E2B window as before.
   */
  val maxTokens: Int
    get() = model.maxTokens.let { if (ModelInfo.totalRamBytes(context) >= ModelInfo.MIN_RAM_BYTES) it else it / 2 }

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
    // MTP speculative decoding: the model drafts several tokens per step and verifies them, which is
    // the biggest lever on decode speed. ADAPTIVE, not ON, so it turns itself off if this GPU turns
    // out to decode slower with it (LiteRT-LM measures; backends without adaptive behave like ON).
    val mtp = SpeculativeDecodingConfig(mode = SpeculativeDecodingMode.ADAPTIVE)
    val attempts = listOf(
      "GPU + image + audio" to EngineConfig(
        modelPath = modelFile.path, backend = Backend.GPU(), visionBackend = Backend.GPU(),
        audioBackend = Backend.CPU(), maxNumTokens = maxTokens, cacheDir = cache,
        speculativeDecodingConfig = mtp,
      ),
      "CPU + image + audio" to EngineConfig(
        modelPath = modelFile.path, backend = Backend.CPU(), visionBackend = Backend.CPU(),
        audioBackend = Backend.CPU(), maxNumTokens = maxTokens, cacheDir = cache,
        speculativeDecodingConfig = mtp,
      ),
      "CPU text-only" to EngineConfig(
        modelPath = modelFile.path, backend = Backend.CPU(), maxNumTokens = maxTokens, cacheDir = cache,
        speculativeDecodingConfig = mtp,
      ),
    )
    // Phones under the recommended memory: the audio part plus an image answer did not fit (a 4 GB
    // phone was closed for LOW_MEMORY while answering about a photo). There, keep pictures and leave
    // audio out; voice still works through the phone's own speech recognition.
    val lowRam = ModelInfo.totalRamBytes(context) < ModelInfo.MIN_RAM_BYTES
    val tried = if (lowRam) {
      listOf(
        "GPU + image" to EngineConfig(
          modelPath = modelFile.path, backend = Backend.GPU(), visionBackend = Backend.GPU(),
          maxNumTokens = maxTokens, cacheDir = cache, speculativeDecodingConfig = mtp,
        ),
      ) + attempts.filter { "audio" !in it.first }
    } else attempts
    // Try the mode that worked last time first, so a phone doesn't re-try failing modes every launch.
    val ordered = tried.sortedByDescending { it.first == prefs.lastMode }
    var lastError: Throwable? = null
    for ((name, config) in ordered) {
      val candidate = Engine(config)
      try {
        val start = System.currentTimeMillis()
        candidate.initialize()
        engine = candidate
        mode = name
        prefs.lastMode = name
        // Prefill the system prompt once at open instead of re-reading it on every turn, and keep
        // reasoning out of the KV cache so thinking does not eat the context window.
        @OptIn(ExperimentalApi::class)
        run { ExperimentalFlags.filterChannelContentFromKvCache = true }
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
  suspend fun startChat(
    history: List<Message>,
    extraSystem: String = "",
    think: Boolean = false,
    numeric: Boolean = false,
  ) = lock.withLock {
    withContext(Dispatchers.IO) {
      val e = engine ?: return@withContext
      runCatching { conversation?.close() }
      conversation = null
      thinking = think
      Log.i(TAG, "conversation: thinking=$think budget=${if (think) THINKING_BUDGET else 0} numeric=$numeric")
      val system = if (extraSystem.isBlank()) SYSTEM else "$SYSTEM\n\n$extraSystem"
      conversation = try {
        e.createConversation(config(system, history, think, numeric))
      } catch (t: Throwable) {
        // History too long or broken: start empty rather than leaving no chat at all.
        Log.w(TAG, "History did not fit; starting empty", t)
        e.createConversation(config(system, emptyList(), think, numeric))
      }
    }
  }

  /** True while the model is allowed to reason before answering (see [startChat]). */
  @Volatile private var thinking = false

  /** The reasoning of the current answer, when thinking is on. Never shown as answer text. */
  val lastThought = StringBuilder()

  /** Called with reasoning pieces as they arrive, for an optional "Thinking…" indicator. */
  var onThought: ((String) -> Unit)? = null

  /** Sends one user turn (text plus optional pictures / voice). Returns the answer piece by piece. */
  fun send(
    text: String,
    images: List<ByteArray> = emptyList(),
    audio: List<ByteArray> = emptyList(),
    think: Boolean = thinking,
  ): Flow<String> {
    val chat = checkNotNull(conversation) { "Model not loaded yet" }
    val parts = buildList<Content> {
      images.forEach { add(Content.ImageBytes(it)) }
      audio.forEach { add(Content.AudioBytes(it)) }
      add(Content.Text(text))
    }
    lastThought.clear()
    val filter = if (think) ReasoningFilter() else null
    // Thinking is switched per message, so the conversation does not have to be rebuilt (and its
    // whole history re-read) just to reason about one question.
    return chat.sendMessageAsync(
      Contents.of(parts),
      maxOutputToken = if (think) MAX_OUTPUT_THINKING else MAX_OUTPUT,
      // Only sent when reasoning is actually wanted. Passing a config with enableThinking=false on
      // a conversation that declares a `thought` channel made the whole reply arrive on the channel
      // with an empty text body, so the answer rendered as nothing at all.
      thinkingConfig = if (think) {
        ThinkingConfig(enableThinking = true, thinkingTokenBudget = THINKING_BUDGET)
      } else null,
    )
      // Reasoning arrives in its own channel; keep it out of the visible answer.
      .onEach { msg ->
        if (think) runCatching {
          msg.channels[THOUGHT_CHANNEL]?.takeIf { it.isNotBlank() }?.let { piece ->
            lastThought.append(piece)
            Log.i(TAG, "thought +${piece.length} chars (total ${lastThought.length})")
            onThought?.invoke(piece)
          }
        }
      }
      // The message text carries the reasoning AND the answer: "…<channel|>answer". Everything
      // before the marker is reasoning, so nothing is shown until the answer actually starts.
      .map { msg -> filter?.feed(msg.toString()) ?: msg.toString() }
      .filter { it.isNotEmpty() }
      .flowOn(Dispatchers.IO)
  }

  /**
   * Holds back the reasoning half of a thinking reply. The stream is "reasoning<channel|>answer",
   * delivered in pieces, so this emits only the answer and only once it has begun.
   */
  private class ReasoningFilter {
    private val buf = StringBuilder()
    private var cut = 0
    private var emitted = 0

    fun feed(piece: String): String {
      buf.append(piece)
      val marker = END_MARKER
      val i = buf.lastIndexOf(marker)
      if (i >= 0) cut = maxOf(cut, i + marker.length)
      if (cut == 0 || buf.length <= emitted) return ""
      val from = maxOf(emitted, cut)
      if (from >= buf.length) { emitted = buf.length; return "" }
      emitted = buf.length
      return buf.substring(from)
    }
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

  private fun config(system: String, history: List<Message>, think: Boolean = false, numeric: Boolean = false) = ConversationConfig(
    systemInstruction = Contents.of(system),
    initialMessages = history,
    samplerConfig = if (numeric) SAMPLER_PRECISE else SAMPLER,
    // Reasoning goes to its own channel, so it can never leak into the answer or the TTS.
    //
    // Declaring this always looked harmless (a place to put reasoning that thinking was supposed to
    // switch off) and it is not: with `enableThinking = false` the model still wrote to the channel,
    // so reasoning was paid for and hidden. Getting rid of the channel for normal answers made the
    // reasoning spill into the visible text instead — worse to read. So the channel stays, and the
    // rule is now "if this model reasons, let thinking be on and show it": see ChatController.
    channels = listOf(Channel(THOUGHT_CHANNEL, "<|channel>thought\n", "<|channel|>")),
    thinkingConfig = ThinkingConfig(
      enableThinking = think,
      // A budget keeps a hard sum from thinking forever on a phone that decodes ~5 tokens/s.
      thinkingTokenBudget = if (think) THINKING_BUDGET else 0,
    ),
    // Explicit ceiling: the default was cutting long Bengali answers short mid-sentence.
    // With thinking on, the reasoning tokens come out of this same allowance.
    maxOutputToken = if (think) MAX_OUTPUT_THINKING else MAX_OUTPUT,
  )

  companion object {
    private const val TAG = "GemmaEngine"
    /**
     * Sampling for normal answers. temperature 1.0 / topK 64 was too hot for a 4B model writing
     * Bengali: it slipped whole words and even inverted a negation ("সবুজ অঙ্গসমূহে বাদে সংঘটিত
     * হয়" — "occurs except in green organs"), and the planner invented "সালোকসংশ্লেষণারক্ষ" for
     * সালোকসংশ্লেষণ. 0.7 keeps the answer varied but makes it copy words it actually knows.
     */
    private val SAMPLER = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.7)

    /**
     * Sampling for arithmetic. At temperature 1.0 the model duplicated a digit inside its own working
     * ("৯৮০০" written as "৯৮৮০০") and then computed from the typo. A low temperature makes it copy
     * the numbers it was given instead of re-rolling them.
     */
    private val SAMPLER_PRECISE = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.4)

    /** The channel the chat template writes reasoning to. */
    const val THOUGHT_CHANNEL = "thought"
    private const val END_MARKER = "<channel|>"

    /** Reasoning allowance per answer when thinking is on (it is slow: ~5 tokens/s on this phone). */
    const val THINKING_BUDGET = 256

    /** Output ceilings. Reasoning tokens come out of the same budget, so thinking needs more room. */
    const val MAX_OUTPUT = 2048
    const val MAX_OUTPUT_THINKING = 2048

    const val SYSTEM =
      "You are Jonaki AI, an offline assistant created and developed by Monarul Islam " +
        "(GitHub: Monarul007), an AI enthusiast whose website is https://monarul.pro.bd. You run " +
        "entirely on the user's phone using Google's Gemma model through LiteRT-LM. " +
        "Answer questions about yourself ONLY when the user actually asks who you are, who made " +
        "you, which model you use, or who the developer is; never mention your creator or your " +
        "website unprompted, and never open an ordinary answer with your name or origin. " +
        "Never claim to be Gemini, ChatGPT, Claude or a Google assistant; Google provides the " +
        "underlying Gemma model, but this app is not made by Google. " +
        "Keep answers short and clear. Never reply that you have no information or cannot browse: " +
        "look it up with the tools; if they fail or there is no internet, answer from your own " +
        "knowledge and say it may be out of date. Do the task with sensible defaults. Ask the user " +
        "a question only when it is truly impossible to continue."

    // The PC agent's voice line (agent.py build_system_prompt). On the phone it rides along only with
    // voice messages: in the fixed system prompt, Gemma started typed answers with "I hear you!".
    const val VOICE_RULE = "You can see images and hear audio. An attached audio clip is the user speaking " +
      "to you: reply to what they said, and never claim you cannot hear."
  }
}
