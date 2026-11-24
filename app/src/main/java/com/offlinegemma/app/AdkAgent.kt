package com.offlinegemma.app

import android.util.Log
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.litertlm.LiteRtLmModel
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.ai.edge.litertlm.Engine

/**
 * SPIKE (branch spike/adk-agent): the agent loop run by Google's Agent Development Kit instead of
 * our own `<tool_call>` text protocol. ADK hands the tools to LiteRT-LM's NATIVE tool calling, runs
 * them through [runTool] (so the chat still shows the usual tool chips and collects sources), feeds
 * the results back and stops when the model answers in text or after [MAX_LLM_CALLS] model calls.
 *
 * Only the agent loop moves here. OCR, page research, review, verification and figures stay in
 * ChatController. Turned on with the debug intent `--ez adk true`.
 */
object AdkAgent {
  private const val TAG = "AdkAgent"
  private const val MAX_LLM_CALLS = 6

  /** Debug switch; off by default so release behaviour is unchanged. */
  @Volatile var enabled = false

  /** A web tool whose real work is done by ChatController's runTool. */
  private class WebTool(
    name: String,
    description: String,
    private val params: Map<String, String>,
    private val required: List<String>,
    private val run: suspend (Tools.Call) -> String,
  ) : FunctionTool(name, description, false, emptyMap<String, Any>(), false) {
    override fun declaration() = FunctionDeclaration(
      name,
      description,
      Schema.Builder()
        .type(Type.OBJECT)
        .properties(params.mapValues { (_, d) -> Schema.Builder().type(Type.STRING).description(d).build() })
        .required(required)
        .build(),
    )

    override suspend fun execute(toolContext: ToolContext, args: Map<String, Any?>): Any {
      val call = Tools.Call(name, args.mapValues { (_, v) -> v?.toString().orEmpty() })
      Log.i(TAG, "tool $name $args")
      return mapOf("result" to run(call).take(2400))
    }
  }

  /**
   * One answer. [prompt] is the full user turn (rules, page text, research…). Streams the text as it
   * comes through [onText]; returns the final answer text.
   */
  suspend fun answer(
    engine: Engine,
    system: String,
    prompt: String,
    runTool: suspend (Tools.Call) -> String,
    onText: (String) -> Unit,
  ): String {
    val tools = listOf(
      WebTool(
        "web_search",
        "Search the web (DuckDuckGo). Returns titles, short snippets and links. Use it when you are not sure of a fact.",
        mapOf("query" to "What to search for, in English."),
        listOf("query"),
        runTool,
      ),
      WebTool(
        "web_fetch",
        "Read one web page. Returns the parts of the page that match the query.",
        mapOf("url" to "The page address.", "query" to "What you are looking for on the page."),
        listOf("url"),
        runTool,
      ),
    )
    val model = LiteRtLmModel.create(engine, "gemma-4-e2b")
    val agent = LlmAgent(
      name = "jonaki",
      model = model,
      instruction = Instruction(system),
      tools = tools,
    )
    val runner = InMemoryRunner.builder().agent(agent).appName("jonaki").build()
    val key = SessionKey("jonaki", "user", "s" + System.nanoTime())
    var streamed = ""
    var final = ""
    try {
      runner.sessionService.createSession(key, emptyMap())
      runner.runAsync(
        key.appName, key.userId, key.id,
        Content.fromText("user", prompt),
        emptyMap(),
        RunConfig(StreamingMode.SSE, MAX_LLM_CALLS),
      ).collect { e ->
        val text = e.content?.parts?.mapNotNull { it.text }?.joinToString("").orEmpty()
        val calls = e.content?.parts?.mapNotNull { it.functionCall?.name }.orEmpty()
        if (calls.isNotEmpty()) Log.i(TAG, "model asked for tools: $calls")
        if (e.errorMessage != null) Log.w(TAG, "event error ${e.errorCode}: ${e.errorMessage}")
        if (text.isEmpty()) return@collect
        if (e.partial) {
          streamed += text
          onText(streamed)
        } else {
          // The aggregated final event of a model call replaces what was streamed for it.
          final = text
          streamed = ""
          onText(final)
        }
      }
    } finally {
      runCatching { runner.close() }
      // Closes only ADK's conversation: the engine belongs to GemmaEngine (ownsEngine = false).
      runCatching { model.close() }
    }
    return final.ifBlank { streamed }
  }
}
