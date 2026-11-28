package com.offlinegemma.app

import kotlin.math.max
import kotlin.math.sqrt

/**
 * "Is someone talking?" — the sound rules of talk mode, kept free of Android so they can be tested.
 * Same numbers as chat.html onTalkAudio / finishUtterance:
 * threshold = max(0.012, room noise x 3); about 0.35 s kept from before speech starts;
 * the clip ends after 2.5 s of quiet or at 30 s; clips with under 0.5 s of voice are ignored.
 * Barge-in detection exists, but talk mode disables it: only a tap on the orb stops a reply.
 */
class VoiceDetector(
  private val rate: Int = 16000,
  private val preFrames: Int = 11,
  /**
   * How much quiet means "they finished the thought".
   *
   * This is the single biggest term in how long a spoken turn feels dead, because nothing at all
   * happens until it elapses: it was 2500 ms, and 2.5 s of silence after every question reads as "it
   * did not hear me". 900 ms is the usual end-of-turn window for conversational assistants (long
   * enough for a natural pause between clauses, short enough not to feel broken), and the 0.35 s of
   * pre-roll kept from before speech still protects the start of a word.
   */
  private val endSilenceMs: Int = 900,
  /** How far above the learned echo the user must be to interrupt (loudspeaker only). */
  var echoMargin: Double = 2.5,
  /** Talk mode turns this off: only an orb tap may interrupt a reply. */
  var bargeInEnabled: Boolean = true,
) {

  enum class Mode { LISTENING, RECORDING, SPEAKING, THINKING }

  sealed class Event {
    data object None : Event()
    /** Speech started: begin recording. */
    data object Start : Event()
    /** The user finished a sentence: send this clip. */
    data class Finish(val chunks: List<ShortArray>) : Event()
    /** Too short (a cough or a click): go back to listening. */
    data object Discard : Event()
    /** The user talked over the reply: stop speaking and record. */
    data object BargeIn : Event()
  }

  var floor = 0.004; private set
  /** How loud our own voice comes back through the microphone (loudspeaker echo). */
  var echo = 0.0; private set
  /** The interrupt bar used on the last frame (for logging). */
  var lastBar = 0.0; private set
  private val pre = ArrayDeque<ShortArray>()
  private val rec = mutableListOf<ShortArray>()
  private var startAt = 0L
  private var quietAt = 0L
  private val loud = ArrayDeque<Boolean>()
  private var voicedMs = 0.0

  fun reset() {
    floor = 0.004; echo = 0.0
    pre.clear(); rec.clear(); loud.clear()
  }

  fun threshold() = max(0.012, floor * 3)

  /**
   * One frame of microphone sound.
   * [speakingFor] = how long the reply has been spoken (ms); [headphones] = no loudspeaker echo.
   */
  fun feed(x: ShortArray, now: Long, mode: Mode, speakingFor: Long = 0, headphones: Boolean = false): Event {
    val r = rms(x)
    val ms = x.size * 1000.0 / rate
    val th = threshold()
    return when (mode) {
      Mode.LISTENING -> {
        keep(x)
        if (r > th) {
          rec.clear(); rec.addAll(pre); pre.clear()
          startAt = now; quietAt = now; voicedMs = 0.0
          Event.Start
        } else {
          floor = floor * 0.97 + r * 0.03 // follow the room's background noise
          Event.None
        }
      }
      Mode.RECORDING -> {
        rec.add(x)
        if (r > th) { quietAt = now; voicedMs += ms }
        if (now - quietAt > endSilenceMs || now - startAt > 30_000) {
          val chunks = rec.toList()
          rec.clear()
          if (voicedMs < 500) Event.Discard else Event.Finish(chunks)
        } else Event.None
      }
      Mode.SPEAKING, Mode.THINKING -> {
        val speaking = mode == Mode.SPEAKING
        // On the loudspeaker the phone hears its own voice: the bar also sits above that echo.
        val bar = if (speaking && !headphones) max(th * 3, echo * echoMargin) else th * 3
        // The first 0.6 s of each reply only learns how loud the echo is.
        val learning = speaking && speakingFor < 600
        if (speaking && (learning || r <= bar)) echo = echo * 0.9 + r * 0.1
        lastBar = bar
        if (!bargeInEnabled) return Event.None
        // Speech has small gaps between words, so count loud frames in a ~0.5 s window
        // instead of asking for 0.35 s without a single quiet frame.
        loud.addLast(speaking && !learning && r > bar)
        while (loud.size > BARGE_WINDOW) loud.removeFirst()
        if (loud.count { it } >= BARGE_FRAMES) {
          loud.clear()
          rec.clear(); rec.addAll(pre); rec.add(x); pre.clear()
          startAt = now; quietAt = now; voicedMs = 350.0
          Event.BargeIn
        } else {
          keep(x)
          Event.None
        }
      }
    }
  }

  private fun keep(x: ShortArray) {
    pre.addLast(x)
    while (pre.size > preFrames) pre.removeFirst()
  }

  companion object {
    /** ~0.5 s window of 32 ms frames, of which ~0.35 s must be loud (the PC's 350 ms). */
    const val BARGE_WINDOW = 16
    const val BARGE_FRAMES = 11

    fun rms(x: ShortArray): Double {
      if (x.isEmpty()) return 0.0
      var s = 0.0
      for (v in x) { val f = v / 32768.0; s += f * f }
      return sqrt(s / x.size)
    }
  }
}
