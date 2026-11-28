package com.offlinegemma.app

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * A short "heard you" tone, used instead of a spoken line.
 *
 * Talk mode has a long silent stretch: the planner and search alone can take 8 s, and the model can
 * take another 19 s before its first word. Some acknowledgement is needed, but a *spoken* one was
 * wrong twice over — it played a canned phrase on every single turn, and "Let me check that" claims
 * something is being verified when often nothing is. A tone confirms the turn was received without
 * claiming anything, and cannot wear out with repetition the way a fixed sentence does.
 */
object Earcon {
  private const val TAG = "Earcon"

  /** Soft two-beat acknowledgement. Volume is low on purpose: this is a cue, not a notification. */
  fun heard() {
    var tone: ToneGenerator? = null
    try {
      tone = ToneGenerator(AudioManager.STREAM_MUSIC, 35)
      // TONE_PROP_ACK is the platform's own "got it" beep — no asset to ship, no licence question.
      tone.startTone(ToneGenerator.TONE_PROP_ACK, 120)
    } catch (e: Exception) {
      // A phone with no tone resources is not a reason to fail a turn; staying silent is fine.
      Log.w(TAG, "no acknowledgement tone available", e)
    } finally {
      // The tone plays on its own thread; releasing immediately can cut it off, so give it its
      // duration. This runs off the main thread (it is called from the answer job).
      tone?.let {
        Thread {
          Thread.sleep(400)
          runCatching { it.release() }
        }.start()
      }
    }
  }
}
