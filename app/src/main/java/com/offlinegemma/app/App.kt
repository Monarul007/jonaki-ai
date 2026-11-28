package com.offlinegemma.app

import android.app.Application

/** App-wide objects. They outlive screens, so rotation or switching apps never reloads the model. */
class App : Application() {
  override fun onCreate() {
    super.onCreate()
    // Before anything reads the pack: a first install has no study files until this copies them.
    Knowledge.installBundled(this)
    Exams.installBundled(this)
  }

  val prefs by lazy { Prefs(this) }
  val store by lazy { ChatStore(this) }
  val gemma by lazy { GemmaEngine(this, prefs) }
  val speaker by lazy { Speaker(this, prefs) }
  val chat by lazy { ChatController(this, gemma, store, prefs) }
  val talk by lazy { TalkController(this, chat, speaker) }
  val examStore by lazy { ExamStore(this) }
  val exam by lazy { ExamController(this, examStore) }

  // The model is deliberately not freed on pause, stop or memory trims: OPPO and Redmi phones
  // call onTrimMemory when the camera or a picker opens, and unloading made the app come back
  // to the "Waking up" screen with no photo. It is only freed when the system actually kills
  // the process, or when the user deletes the model in Settings.
}
