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
  // Sources are cited as bare [1], [2], [3] so the reader can match them to the list. Spoken aloud
  // they become "three" — a web answer came back with a [3] after almost every line, so the audio was
  // a poem punctuated by the number three. A link [text](url) keeps its text; a bare number is dropped.
  s = s.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
  s = s.replace(Regex("\\[\\s*\\d{1,3}\\s*\\]"), "")
  s = s.replace(Regex("https?://\\S+"), "")
  s = s.replace(Regex("""(?im)^\s*[*_]*\s*NEXT\s*[*_]*\s*:.*$"""), "")
  s = s.replace(Tutor.FIG_INLINE, " ")
  s = s.replace(Regex("`([^`]*)`"), "$1")
  s = s.replace(Regex("(?m)^\\s*[#>|-]+\\s*"), "").replace(Regex("[*_~#|$\\\\]"), "")
  return s
}

private val BENGALI = Regex("[\u0980-\u09FF]")

fun hasBengali(text: String) = BENGALI.containsMatchIn(text)

/**
 * Splits text for speaking (same rules as the PC app): by sentence, then neighbouring sentences in
 * the same language are joined up to ~220 characters. When nothing is playing yet, a long first piece
 * is cut at its first comma so the first words come out sooner.
 */
fun pieces(text: String, nothingPlaying: Boolean): List<String> {
  val sentences = text.split(Regex("(?<=[.!?।…])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    .flatMap { s -> if (s.length <= 220) listOf(s) else Regex(".{1,220}(\\s|$)").findAll(s).map { it.value.trim() }.filter { it.isNotEmpty() }.toList() }
  val out = mutableListOf<String>()
  for (s in sentences) {
    val last = out.lastOrNull()
    if (last != null && hasBengali(last) == hasBengali(s) && last.length + 1 + s.length <= 220) out[out.lastIndex] = "$last $s"
    else out.add(s)
  }
  if (nothingPlaying && out.isNotEmpty() && out[0].length > 90) {
    val cut = out[0].indexOf(", ")
    if (cut in 1 until 90) {
      val first = out[0]
      out[0] = first.substring(0, cut + 1)
      out.add(1, first.substring(cut + 2))
    }
  }
  return out
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
      override fun onStart(id: String?) {
        if (firstAt > 0) {
          // Report the voice requested for THIS utterance. `current` is whatever was set last, which is
          // not the same thing and hid a Bengali answer being spoken by an English voice.
          val used = id?.let { voiceOf.remove(it) } ?: "?"
          android.util.Log.i("Speaker", "First sound after ${android.os.SystemClock.elapsedRealtime() - firstAt} ms (asked ${used}, engine ${tts.voice?.name})")
          firstAt = 0
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post { onStart?.invoke() }
      }
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

  /** Offline voices whose data is on the phone (no internet needed, nothing to download). */
  fun voices(): List<Voice> = runCatching {
    tts.voices.orEmpty().filter { v ->
      !v.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty()
    }.sortedWith(compareBy({ it.locale.displayName }, { -it.quality }, { it.name }))
  }.getOrDefault(emptyList())

  /** The best-sounding installed voice for a language: highest quality, then quickest to start. */
  fun bestVoice(language: String): Voice? =
    voices().filter { it.locale.language == language }.maxWithOrNull(compareBy<Voice>({ it.quality }, { -it.latency }))

  /** The voice the user picked, or null for "Automatic". */
  private fun chosen(): Voice? = prefs.voice?.let { name -> voices().firstOrNull { it.name == name } }

  private val appLanguage get() = Locale.getDefault().language.takeIf { it != "bn" } ?: "en"

  /**
   * Which voice says this piece. Automatic: Bengali letters -> the best Bengali voice,
   * anything else -> the chosen voice (or the best voice for the phone's language).
   */
  fun voiceFor(text: String): Voice? {
    val pick = chosen()
    return if (hasBengali(text)) {
      val bn = pick?.takeIf { it.locale.language == "bn" } ?: bestVoice("bn")
      // Bengali script read by an English voice is not "slightly wrong", it is unintelligible — and
      // the fallback here used to hand back `pick` (often English) with no sign that anything was off.
      // Report which voice is about to read Bengali text so this can never be a silent failure.
      android.util.Log.i(
        "Speaker",
        if (bn != null) "Bengali voice: ${bn.name} (${bn.locale})" else "NO Bengali voice installed",
      )
      bn ?: pick
    } else {
      pick?.takeIf { it.locale.language != "bn" } ?: bestVoice(appLanguage) ?: bestVoice("en")
    }
  }

  /** Is a Bengali voice installed? If not, the picker offers to install one. */
  fun hasBengaliVoice() = bestVoice("bn") != null

  fun applyVoice() {
    if (!ready) return
    voiceFor("a")?.let { tts.voice = it } ?: run { tts.language = Locale.getDefault() }
  }

  /** Start a new streamed reply. */
  fun start(id: Long? = null) {
    tts.stop()
    queued.set(0)
    spoken = 0
    ended = false
    speaking = true
    readingId = id
    // No warm-up speak() here on purpose. The acknowledgement tone already wakes the audio path, and
    // queuing a silent utterance would be *worse than nothing*: it joins the same queue as the answer,
    // so the first real sentence would wait behind it.
  }

  /** Speak each finished sentence of [text] that has not been said yet. */
  fun feed(text: String, final: Boolean = false) {
    if (ended) return
    val clean = speakable(text)
    var upto = clean.length
    if (!final) {
      upto = spoken
      Regex("[.!?।…](\\s|$)|\\n").findAll(clean).forEach { if (it.range.first + 1 > spoken) upto = it.range.first + 1 }
      // Nothing spoken yet and no sentence has finished: a model can emit a long opening clause before
      // it reaches a full stop, and the screen fills with readable words while talk mode is still
      // silent — which is exactly the "text appears, then the voice starts" gap. Start on the first
      // clause instead of the first sentence, but only for the opening piece and only at a real break:
      // a comma or ~90 characters ending on a word boundary.
      if (upto <= spoken && spoken == 0) {
        val head = clean.take(140)
        val stop = listOf(", ", "; ", "। ", " — ")
          .map { head.indexOf(it) }
          .filter { it in 20 until 140 }
          .minOrNull()
          ?: if (clean.length >= 90) clean.lastIndexOf(' ', 90).takeIf { it > 20 } else null
        if (stop != null) upto = stop + 1
      }
    }
    if (upto <= spoken) return
    val chunk = clean.substring(spoken, upto).replace(Regex("\\s+"), " ").trim()
    spoken = upto
    if (chunk.isNotEmpty()) say(chunk)
  }

  private var current: Voice? = null
  /** When the first piece of a reply was handed over (to measure how fast speech starts). */
  @Volatile private var firstAt = 0L
  /** Voice actually requested for each queued utterance, so onStart can report the truth. */
  private val voiceOf = java.util.concurrent.ConcurrentHashMap<String, String>()

  private fun say(chunk: String) {
    if (!ready) return
    tts.setSpeechRate(prefs.speechRate)
    if (queued.get() == 0) firstAt = android.os.SystemClock.elapsedRealtime()
    for (p in pieces(chunk, nothingPlaying = queued.get() == 0)) {
      val v = voiceFor(p)
      // setVoice() returns an error code and used to be called as a plain assignment, so a rejected
      // Bengali voice fell back to the engine default (en-US) silently — which is why Bengali text was
      // read by an English voice and came out as noise rather than words.
      if (v != null && v != current) {
        val ok = tts.setVoice(v)
        current = v
        if (ok != TextToSpeech.SUCCESS) {
          android.util.Log.w("Speaker", "setVoice(${v.name}) rejected ($ok); falling back to its language")
          // At least make the language right: an engine with a partial Bengali pack can still say the
          // script if it is told the locale, even when the full voice is not usable.
          tts.language = v.locale
        }
      }
      val id = "u${counter++}"
      voiceOf[id] = v?.name ?: "default"
      queued.incrementAndGet()
      // Played strictly in order (QUEUE_ADD); each piece uses the voice set just before it.
      tts.speak(p, TextToSpeech.QUEUE_ADD, Bundle(), id)
      // What the engine is actually given, after speakable(). This is the only place that proves the
      // cleaning worked: a unit test shows the function is right, not that its output reaches the TTS.
      android.util.Log.i("Speaker", "say[${v?.locale ?: "default"}]: ${p.replace("\n", " ").take(160)}")
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
