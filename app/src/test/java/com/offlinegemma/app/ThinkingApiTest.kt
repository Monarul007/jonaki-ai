package com.offlinegemma.app

import org.junit.Test

/**
 * Compile-only guard: the pinned LiteRT-LM (0.19.1) must expose the thinking switch, or the app
 * silently loses reasoning. Nothing is instantiated here on purpose — those classes are built for
 * Java 21 and cannot be loaded by the JVM 17 unit-test runtime, but they compile fine and Android
 * dexes them for the device.
 */
class ThinkingApiTest {
  @Test fun theThinkingSwitchIsOnTheConversationConfig() {
    // If ThinkingConfig / Channel ever disappear from the pinned build, this file stops compiling.
    val check: Any = ::buildThinkingConversationConfig
    assert(check is kotlin.Function0<*>)
  }
}

/** Mirrors what GemmaEngine.config() does when thinking is on. */
private fun buildThinkingConversationConfig() =
  com.google.ai.edge.litertlm.ConversationConfig(
    channels = listOf(
      com.google.ai.edge.litertlm.Channel(
        channelName = "thought",
        start = "<|channel>thought\n",
        end = "<|channel|>",
      ),
    ),
    thinkingConfig = com.google.ai.edge.litertlm.ThinkingConfig(
      enableThinking = true,
      thinkingTokenBudget = 512,
    ),
    maxOutputToken = 3072,
  )
