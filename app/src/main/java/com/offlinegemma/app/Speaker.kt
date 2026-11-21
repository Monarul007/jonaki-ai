package com.offlinegemma.app

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Markdown -> words worth saying: no code, no URLs, no symbols, no NEXT: line (same as chat.html speakable). */
fun speakable(text: String): String {
  var s = text
  val fences = Regex("```").findAll(s).count()
  if (fences % 2 == 1) s = s.substring(0, s.lastIndexOf("```")) // unfinished code block: stop there
  s = s.replace(Regex("```[\\s\\S]*?```"), " ")
  s = s.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
  s = s.replace(Regex("https?://\\S+"), "")
  s = s.replace(Regex("""(?im)^\s*[*_]*\s*NEXT\s*[*_]*\s*:.*$"""), "")
  s = s.replace(Regex("`([^`]*)`"), "$1")
  s = s.replace(Regex("(?m)^\\s*[#>|-]+\\s*"), "").replace(Regex("[*_~#|$\\\\]"), "")
  return s
}

/**
 * Reads replies out loud with the phone's text-to-speech, sentence by sentence
 * while the answer is still streaming in.
 */
class Speaker(context: Context, private val prefs: Prefs) {
  private var ready = false
  private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
    ready = status == TextToSpeech.SUCCESS
    if (ready) applyVoice()
  }
  private val queued = AtomicInteger(0)
  private var spoken = 0
  private var ended = true
  private var counter = 0

  /** True while words are being said (or queued). */
  var speaking by mutableStateOf(false); private set
  var onStart: (() -> Unit)? = null
  var onIdle: (() -> Unit)? = null

  /** Which reply is being read (so its 🔊 button can show "stop"). */
  var readingId by mutableStateOf<Long?>(null); private set

  init {
    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(id: String?) { android.os.Handler(android.os.Looper.getMainLooper()).post { onStart?.invoke() } }
      override fun onDone(id: String?) = finished()
      @Deprecated("Deprecated in Java")
      override fun onError(id: String?) = finished()
      override fun onStop(id: String?, interrupted: Boolean) = finished()
    })
  }

  private fun finished() {
    if (queued.decrementAndGet() <= 0) {
      queued.set(0)
      android.os.Handler(android.os.Looper.getMainLooper()).post { checkIdle() }
    }
  }

  fun voices(): List<Voice> = runCatching {
    tts.voices.orEmpty().filter { !it.isNetworkConnectionRequired }.sortedWith(compareBy({ it.locale.displayName }, { it.name }))
  }.getOrDefault(emptyList())

  fun applyVoice() {
    if (!ready) return
    val wanted = prefs.voice?.let { name -> voices().firstOrNull { it.name == name } }
    if (wanted != null) tts.voice = wanted else tts.language = Locale.getDefault()
  }

  /** Start a new streamed reply. */
  fun start(id: Long? = null) {
    tts.stop()
    queued.set(0)
    spoken = 0
    ended = false
    speaking = true
    readingId = id
  }

  /** Speak each finished sentence of [text] that has not been said yet. */
  fun feed(text: String, final: Boolean = false) {
    if (ended) return
    val clean = speakable(text)
    var upto = clean.length
    if (!final) {
      upto = spoken
      Regex("[.!?।…](\\s|$)|\\n").findAll(clean).forEach { if (it.range.first + 1 > spoken) upto = it.range.first + 1 }
    }
    if (upto <= spoken) return
    val chunk = clean.substring(spoken, upto).replace(Regex("\\s+"), " ").trim()
    spoken = upto
    if (chunk.isNotEmpty()) say(chunk)
  }

  private fun say(chunk: String) {
    if (!ready) return
    tts.setSpeechRate(prefs.speechRate)
    Regex(".{1,220}(\\s|$)").findAll(chunk).map { it.value }.ifEmpty { sequenceOf(chunk) }.forEach { p ->
      queued.incrementAndGet()
      tts.speak(p, TextToSpeech.QUEUE_ADD, Bundle(), "u${counter++}")
    }
  }

  /** The reply is complete ([text]) or abandoned (null). */
  fun end(text: String?) {
    if (ended) return
    if (text != null) feed(text, final = true)
    ended = true
    checkIdle()
  }

  private fun checkIdle() {
    if (ended && queued.get() <= 0 && speaking) {
      speaking = false
      readingId = null
      onIdle?.invoke()
    }
  }

  /** Read one finished reply; tapping again stops it. */
  fun readAloud(id: Long, text: String) {
    if (speaking && readingId == id) { stop(); return }
    start(id)
    end(text)
  }

  fun stop() {
    ended = true
    queued.set(0)
    tts.stop()
    speaking = false
    readingId = null
  }
}
