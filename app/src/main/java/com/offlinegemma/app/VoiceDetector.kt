package com.offlinegemma.app

import kotlin.math.max
import kotlin.math.sqrt

/**
 * "Is someone talking?" — the sound rules of talk mode, kept free of Android so they can be tested.
 * Same numbers as chat.html onTalkAudio / finishUtterance:
 * threshold = max(0.012, room noise x 3); about 0.35 s kept from before speech starts;
 * the clip ends after 1.2 s of quiet or at 30 s; clips with under 0.3 s of voice are ignored;
 * talking over the reply (louder than threshold x 3 for 0.35 s) interrupts it.
 */
class VoiceDetector(private val rate: Int = 16000, private val preFrames: Int = 11) {

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
  private val pre = ArrayDeque<ShortArray>()
  private val rec = mutableListOf<ShortArray>()
  private var startAt = 0L
  private var quietAt = 0L
  private var loudSince = 0L
  private var voicedMs = 0.0

  fun reset() {
    floor = 0.004; echo = 0.0
    pre.clear(); rec.clear(); loudSince = 0
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
        if (now - quietAt > 1200 || now - startAt > 30_000) {
          val chunks = rec.toList()
          rec.clear()
          if (voicedMs < 300) Event.Discard else Event.Finish(chunks)
        } else Event.None
      }
      Mode.SPEAKING, Mode.THINKING -> {
        val speaking = mode == Mode.SPEAKING
        // On the loudspeaker the phone hears its own voice: the bar also sits above that echo.
        val bar = if (speaking && !headphones) max(th * 3, echo * 3.5) else th * 3
        // The first 0.6 s of each reply only learns how loud the echo is.
        val learning = speaking && speakingFor < 600
        if (speaking && (learning || r <= bar)) echo = echo * 0.9 + r * 0.1
        if (speaking && !learning && r > bar) {
          if (loudSince == 0L) loudSince = now
          if (now - loudSince > 350) {
            loudSince = 0
            rec.clear(); rec.addAll(pre); rec.add(x); pre.clear()
            startAt = now; quietAt = now; voicedMs = 350.0
            Event.BargeIn
          } else Event.None
        } else {
          loudSince = 0
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
    fun rms(x: ShortArray): Double {
      if (x.isEmpty()) return 0.0
      var s = 0.0
      for (v in x) { val f = v / 32768.0; s += f * f }
      return sqrt(s / x.size)
    }
  }
}
