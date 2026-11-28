package com.offlinegemma.app

import android.util.Log

/**
 * A step-by-step record of what the agent loop actually did, for judging whether the workflow is
 * sound and why a particular answer went wrong.
 *
 * Written to logcat under one tag and numbered `step 1`, `step 2`, …, so a whole turn can be read in
 * order with `adb logcat -s AgentStep` and saved for offline reading. Deliberately verbose: the point
 * is to see the task, the decision, the tool call and its result, what changed in the prompt, and
 * where the turn ended — things the normal one-line "turn timing" summary cannot show.
 *
 * Every entry is prefixed with the elapsed ms since the turn began, so a slow step is obvious without
 * cross-referencing timestamps.
 */
object AgentTrace {
  const val TAG = "AgentStep"

  private var startedAt = 0L
  private var step = 0
  private var enabled = false

  /** Begin a turn. Returns false when tracing is off, and every other call becomes a no-op. */
  fun begin(task: String, voice: Boolean, model: String, think: Boolean, agent: Boolean): Boolean {
    startedAt = System.currentTimeMillis()
    step = 0
    enabled = true
    line("TASK", "voice=$voice model=$model think=$think agentMode=$agent")
    line("TASK", task.replace("\n", " ").take(600))
    return true
  }

  fun end(reason: String) {
    if (!enabled) return
    line("END", reason)
    enabled = false
  }

  /** A decision or state change: what the loop chose to do next and why. */
  fun note(what: String, detail: String = "") = line(what, detail)

  /** The prompt handed to the model this round, shortened: what the model is actually being asked. */
  fun round(n: Int, prompt: String, historySize: Int) {
    line("ROUND $n", "prompt=${prompt.length}ch history=$historySize")
    line("  sent", prompt.replace("\n", " ⏎ ").take(700))
  }

  /** What came back: the reasoning, and the visible text, kept separate as they are in the stream. */
  fun reply(n: Int, visible: String, reasoning: String, ms: Long) {
    if (!enabled) return
    line("  reply $n", "${visible.length}ch visible, ${reasoning.length}ch reasoning, ${ms}ms")
    if (reasoning.isNotBlank()) line("  reasoning", reasoning.replace("\n", " ⏎ ").take(900))
    line("  said", visible.replace("\n", " ⏎ ").take(900))
  }

  /** A tool the model asked for, and what it returned. */
  fun tool(name: String, args: Any?, ok: Boolean, result: String, ms: Long) {
    if (!enabled) return
    line("TOOL $name", "${if (ok) "ok" else "FAILED"} ${ms}ms args=$args")
    if (result.isNotBlank()) line("  result", result.replace("\n", " ⏎ ").take(900))
  }

  private fun line(kind: String, text: String) {
    if (!enabled) return
    val t = System.currentTimeMillis() - startedAt
    if (kind.startsWith("  ")) {
      // Continuation of the previous step: no new number.
      Log.i(TAG, String.format("%6d ms  %s %s", t, kind.trim(), text))
    } else {
      step++
      Log.i(TAG, String.format("%6d ms  [%02d] %-12s %s", t, step, kind, text))
    }
  }
}
