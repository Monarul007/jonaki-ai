package com.offlinegemma.app

import android.app.Application
import android.content.ComponentCallbacks2

/** App-wide objects. They outlive screens, so rotation or switching apps never reloads the model. */
class App : Application() {
  val prefs by lazy { Prefs(this) }
  val store by lazy { ChatStore(this) }
  val gemma by lazy { GemmaEngine(this, prefs) }
  val speaker by lazy { Speaker(this, prefs) }
  val chat by lazy { ChatController(this, gemma, store, prefs) }
  val talk by lazy { TalkController(this, chat, speaker) }

  /**
   * The phone is running out of memory while we are in the background:
   * free the model (about 2 GB). It loads again when the user comes back.
   */
  @Deprecated("Deprecated in Java")
  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    @Suppress("DEPRECATION")
    if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) chat.unloadModel()
  }
}
