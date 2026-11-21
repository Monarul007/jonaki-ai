package com.offlinegemma.app

import android.app.Application

/** App-wide objects. They outlive screens, so rotation or switching apps never reloads the model. */
class App : Application() {
  val prefs by lazy { Prefs(this) }
  val store by lazy { ChatStore(this) }
  val gemma by lazy { GemmaEngine(this, prefs) }
  val speaker by lazy { Speaker(this, prefs) }
  val chat by lazy { ChatController(this, gemma, store, prefs) }
  val talk by lazy { TalkController(this, chat, speaker) }

  // The model is deliberately not freed on pause, stop or memory trims: OPPO and Redmi phones
  // call onTrimMemory when the camera or a picker opens, and unloading made the app come back
  // to the "Waking up" screen with no photo. It is only freed when the system actually kills
  // the process, or when the user deletes the model in Settings.
}
