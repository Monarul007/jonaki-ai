package com.offlinegemma.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

/**
 * App settings. Saved on the phone, and observable by the screens
 * (changing one redraws what uses it).
 */
class Prefs(context: Context) {
  private val ctxt = context.applicationContext
  private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
  private val bigPhone = ModelInfo.totalRamBytes(context) >= ModelInfo.DEFAULT.recommendedRamBytes

  private fun bool(key: String, def: Boolean) = mutableStateOf(sp.getBoolean(key, def))

  private val _wifiOnly = bool("wifi_only", true)
  var wifiOnly: Boolean
    get() = _wifiOnly.value
    set(v) { _wifiOnly.value = v; sp.edit().putBoolean("wifi_only", v).apply() }

  /** Interactive demos in tutor answers. Off by default on small phones (they are slow). */
  private val _demos = bool("demos", bigPhone)
  var demos: Boolean
    get() = _demos.value
    set(v) { _demos.value = v; sp.edit().putBoolean("demos", v).apply() }

  /** Agent mode: Gemma may use tools (web search, run code). */
  private val _agent = bool("agent", true)
  var agent: Boolean
    get() = _agent.value
    set(v) { _agent.value = v; sp.edit().putBoolean("agent", v).apply() }

  /**
   * Let the model reason step by step before answering a question with numbers in it.
   * Off by default: on this phone reasoning decodes at a few tokens a second.
   */
  private val _thinking = bool("thinking", false)
  var thinking: Boolean
    get() = _thinking.value
    set(v) { _thinking.value = v; sp.edit().putBoolean("thinking", v).apply() }

  /** Web search tool and "Check facts". The only thing that ever sends data off the phone. */
  private val _webSearch = bool("web_search", true)
  var webSearch: Boolean
    get() = _webSearch.value
    set(v) { _webSearch.value = v; sp.edit().putBoolean("web_search", v).apply() }

  private val _theme = mutableStateOf(runCatching { ThemeChoice.valueOf(sp.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeChoice.SYSTEM))
  var theme: ThemeChoice
    get() = _theme.value
    set(v) { _theme.value = v; sp.edit().putString("theme", v.name).apply() }

  /** Use the colours of the phone's wallpaper (Material You) instead of the app's own. */
  private val _dynamicColor = bool("dynamic_color", false)
  var dynamicColor: Boolean
    get() = _dynamicColor.value
    set(v) { _dynamicColor.value = v; sp.edit().putBoolean("dynamic_color", v).apply() }

  private val _speechRate = mutableFloatStateOf(sp.getFloat("speech_rate", 1.0f))
  var speechRate: Float
    get() = _speechRate.floatValue
    set(v) { _speechRate.floatValue = v; sp.edit().putFloat("speech_rate", v).apply() }

  /** Text-to-speech voice name; null = the phone's default voice. */
  private val _voice = mutableStateOf(sp.getString("voice", null))
  var voice: String?
    get() = _voice.value
    set(v) { _voice.value = v; sp.edit().putString("voice", v).apply() }

  /** Answer language: true = Gemma always answers in Bengali (বাংলা), false = English / the user's language. */
  private val _bengali = bool("answer_bengali", false)
  var answerBengali: Boolean
    get() = _bengali.value
    set(v) { _bengali.value = v; sp.edit().putBoolean("answer_bengali", v).apply() }

  /** Welcome screen seen. */
  private val _onboarded = bool("onboarded", false)
  var onboarded: Boolean
    get() = _onboarded.value
    set(v) { _onboarded.value = v; sp.edit().putBoolean("onboarded", v).apply() }

  /** The load mode that worked last time, tried first next time. */
  var lastMode: String?
    get() = sp.getString("last_mode", null)
    set(v) = sp.edit().putString("last_mode", v).apply()

  /**
   * Which model to run. Saved by name so an unknown value (a model removed from the app) falls back
   * to the default instead of leaving the app with nothing to load.
   */
  private val _model = mutableStateOf(
    runCatching { ChatModel.valueOf(sp.getString("chat_model", null) ?: "") }.getOrDefault(ModelInfo.DEFAULT),
  )
  var model: ChatModel
    get() = _model.value
    set(v) { _model.value = v; sp.edit().putString("chat_model", v.name).apply() }

  /**
   * The model to run right now: the chosen one if its file is on the phone **and this phone can hold
   * it**. A file alone is not enough — a model left on the phone by an earlier build may be too large
   * to run here, and only the phone can settle that at load time.
   */
  fun usableModel(): ChatModel {
    if (ModelInfo.isUsable(ctxt, model)) return model
    ModelInfo.installed(ctxt).firstOrNull()?.let { here ->
      model = here
      return here
    }
    return model
  }
}
