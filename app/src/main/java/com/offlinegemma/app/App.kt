package com.offlinegemma.app

import android.app.Application

/** Keeps one loaded model for the whole app, so rotating the screen doesn't load it twice. */
class App : Application() {
  val gemma by lazy { GemmaEngine(this) }
}
