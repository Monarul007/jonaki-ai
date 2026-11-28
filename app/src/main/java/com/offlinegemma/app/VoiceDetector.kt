package com.offlinegemma.app

import kotlin.math.max
import kotlin.math.sqrt

/**
 * "Is someone talking?" — the sound rules of talk mode, kept free of Android so they can be tested.
 * threshold = max(0.012, room noise x 3); about 0.35 s kept from before speech starts; a turn ends
 * after a short stretch of quiet, and clips with under 0.5 s of voice are ignored.
 * Barge-in detection exists, but talk mode disables it: only a tap on the orb stops a reply.
 */
class VoiceDetector(
  private val rate: Int = 16000,
  private val preFrames: Int = 11,
  /**
   * Quiet that ends the turn once a full sentence is clearly under way.
   *
   * Someone waiting through 2.5 s of silence after every question reads as "it did not hear me", so
   * this is short — 900 ms is the usual conversational end-of-turn window.
   */
  private val endSilenceMs: Int = 900,
  /**
   * Quiet that ends the turn when only a word or two has been said.
   *
   * Measured failure, and the reason this is not one fixed number: "রবীন্দ্রনাথ ঠাকুরের সোনার তরী
   * কবিতাটি পড়ে শোনাও" (read me Tagore's Sonar Tori) was cut after "রবীন্দ্রনাথ ঠাকুরের". The 900 ms
   * window expired in the breath before "সোনার তরী", the fragment was submitted as its own question,
   * and the rest arrived as a second turn — the user got an answer about the wrong thing, and the
   * model transcribed a clip that had been sliced mid-phrase.
   *
   * A short burst is far more likely to be the start of a sentence than a whole question, so it earns
   * a longer grace period. Once enough has been said the window tightens back to 900 ms, so ordinary
   * questions still end promptly.
   */
  private val patientSilenceMs: Int = 1800,
  /** Voiced time below which an utterance counts as "only a word or two", so the longer window applies. */
  private val shortUtteranceMs: Double = 1500.0,
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
    data class Finish(val chunks: List<ShortArray>, val lastLoudMs: Long = 0) : Event()
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
        val window = if (voicedMs < shortUtteranceMs) patientSilenceMs else endSilenceMs
        if (now - quietAt > window || now - startAt > 30_000) {
          val chunks = rec.toList()
          rec.clear()
          // quietAt is the *last* frame that was loud, so this is the real end of speech — not the
          // moment the window elapsed, which is `window` later. Anything timed "from the end of
          // speech" has to use this or it silently includes the whole silence window.
          if (voicedMs < 500) Event.Discard else Event.Finish(chunks, quietAt)
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
