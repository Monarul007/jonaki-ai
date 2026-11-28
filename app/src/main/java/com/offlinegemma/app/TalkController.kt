package com.offlinegemma.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Hands-free voice chat: listen -> send the voice clip -> speak the reply -> listen again.
 * A port of chat.html talkStart / onTalkAudio / finishUtterance.
 */
class TalkController(private val context: Context, private val chat: ChatController, private val speaker: Speaker) {

  enum class State { OFF, LISTENING, RECORDING, THINKING, SPEAKING }

  var state by mutableStateOf(State.OFF); private set
  /** True while waiting for the model to wake before the microphone can start. */
  var waking by mutableStateOf(false); private set
  /** Microphone loudness 0..1, for the animated orb. */
  var level by mutableFloatStateOf(0f); private set
  /** The reply as it streams, for the transcript on screen. */
  var transcript by mutableStateOf(""); private set
  var error by mutableStateOf<String?>(null); private set

  private val main = Handler(Looper.getMainLooper())
  @Volatile private var running = false
  private var thread: Thread? = null

  // Sound detection, same numbers as the PC: threshold = max(0.012, noise floor x 3).
  /** How loud our own voice comes back through the microphone while speaking (loudspeaker echo). */
  private var answerId: Long? = null
  /** Invalidates a pending "wait for the model" retry when Talk is stopped. */
  private var startToken = 0
  private val vad = VoiceDetector(RATE, PRE_FRAMES)
  /** Headphones on: no loudspeaker echo (the detector's echo learning uses this). */
  private var loudspeakerSafe = false

  // Timing, to compare with the PC (full loop ~15 s, interruption ~1 s).
  private var spokeEndAt = 0L
  /** True once the real answer has started speaking, so the holding line is not said over it. */
  @Volatile private var spokeAnswer = false

  init {
    chat.onAnswerStart = { id, voice ->
      if (running && voice) {
        answerId = id
        transcript = ""
        spokeAnswer = false
        speaker.start(id)
      }
    }
    chat.onAnswerText = { id, text ->
      if (running && id == answerId) {
        spokeAnswer = true
        speaker.feed(text)
        transcript = speakable(text).trim()
      }
    }
    // Preparation (transcribe -> plan -> search -> first token) takes seconds before any answer text
    // exists, and talk mode was silent for all of it. Acknowledge as soon as the turn is submitted —
    // do NOT delay this with postDelayed.
    //
    // Measured: a 2000 ms postDelayed was delivered at 3470 ms after the last word, because the main
    // thread is busy with the model and a posted callback only runs when it next gets the looper. A
    // delay here is therefore not a promise about wall-clock time. Firing immediately means the
    // acknowledgement lands when it genuinely can — ~1.2 s after the last syllable (900 ms end-of-turn
    // window + ASR) — and the real answer flushes it the moment it starts speaking.
    chat.onPreparing = { _ ->
      // `running` alone is the wrong test: while a spoken turn is being prepared, talk mode may still
      // be waking the audio part, so `waking` counts too. Outside a talk session nothing is said.
      if ((running || waking) && !spokeAnswer) {
        Log.i(TAG, "Holding line requested ${SystemClock.elapsedRealtime() - spokeEndAt} ms after last word")
        speaker.hold(if (onHeadphones) "One moment." else "Let me check that.")
      }
    }
    chat.onAnswerDone = { line ->
      if (running && line.id == answerId) {
        transcript = speakable(line.text).trim()
        speaker.end(line.text)
        answerId = null
        if (!speaker.speaking && st != State.RECORDING) moveTo(State.LISTENING)
      }
    }
    speaker.onStart = {
      if (running && st != State.RECORDING) {
        if (st == State.THINKING) Log.i(TAG, "Timing: speech end -> first words spoken ${SystemClock.elapsedRealtime() - spokeEndAt} ms")
        moveTo(State.SPEAKING)
      }
    }
    // The acknowledgement is heard, then the answer takes a while to arrive. The screen must keep
    // saying "thinking" in that gap: the holding line used to be reported as speech starting, so the
    // UI said "Speaking" for the whole silent search with nothing coming out of the speaker.
    speaker.onHold = {
      if (running && st == State.THINKING) Log.i(TAG, "Acknowledged; still thinking")
    }
    speaker.onIdle = { if (running && (st == State.THINKING || st == State.SPEAKING) && !chat.busy) moveTo(State.LISTENING) }
  }

  /** The state the microphone thread works with (the screen's copy follows it). */
  @Volatile private var st = State.OFF
  private var speakingSince = 0L

  private fun moveTo(s: State) {
    if (s == State.SPEAKING && st != State.SPEAKING) speakingSince = SystemClock.elapsedRealtime()
    st = s
    if (Looper.myLooper() == Looper.getMainLooper()) state = s else main.post { state = s }
  }

  /** Is the sound coming out of headphones (no loudspeaker echo)? */
  private fun headphones(): Boolean {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
      it.type in setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET,
      )
    }
  }

  /** True when the reply goes to headphones, where the microphone will not hear it. */
  val onHeadphones: Boolean get() = headphones()

  @SuppressLint("MissingPermission") // the screen asks for the microphone first
  fun start() {
    if (running || waking) return
    if (chat.gemma.canHear) { beginListening(); return }
    if (chat.loadError != null) {
      error = "Jonaki AI's model did not load. Open Settings and try again."
      return
    }
    // The model takes ~30-40 s to wake after a cold start. Tapping Talk in that window used to show
    // "hearing didn't load", which reads like a permanent fault. Wait for it instead.
    error = null
    waking = true
    val token = ++startToken
    fun attempt(n: Int) {
      main.postDelayed({
        if (token != startToken) return@postDelayed
        when {
          chat.gemma.canHear -> { waking = false; beginListening() }
          n >= 120 -> {
            waking = false
            error = "Jonaki AI's hearing did not load on this phone. Talk needs the audio model."
          }
          else -> attempt(n + 1)
        }
      }, 500)
    }
    attempt(0)
  }

  @SuppressLint("MissingPermission")
  private fun beginListening() {
    if (running) return
    error = null
    val rate = RATE
    val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val ar = try {
      // VOICE_RECOGNITION, not VOICE_COMMUNICATION: on Xiaomi phones the call-style echo
      // cancelling mutes the microphone completely while the phone speaks, so you could never
      // interrupt. Our own echo filter (VoiceDetector) keeps Gemma from hearing itself instead.
      AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, FRAME * 8))
    } catch (t: Throwable) {
      error = "The microphone is not available."
      return
    }
    if (ar.state != AudioRecord.STATE_INITIALIZED) {
      error = "The microphone is not available."
      ar.release()
      return
    }
    // The platform echo canceller is off for the same reason (it gates the microphone on some phones).
    val aec: AcousticEchoCanceler? = null
    val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(ar.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
    Log.i(TAG, "Talk start: echo canceller=${aec != null}, noise suppressor=${ns != null}")

    running = true
    loudspeakerSafe = headphones()
    transcript = ""
    // Only the orb may interrupt a reply; the microphone never stops the answer.
    vad.bargeInEnabled = false
    vad.reset()
    moveTo(if (chat.busy) State.THINKING else State.LISTENING)
    thread = Thread({
      val buf = ShortArray(FRAME)
      try {
        ar.startRecording()
        while (running) {
          val n = ar.read(buf, 0, FRAME)
          if (n <= 0) continue
          onFrame(buf.copyOf(n))
        }
      } catch (t: Throwable) {
        Log.w(TAG, "Recording stopped", t)
      } finally {
        runCatching { ar.stop() }
        ar.release()
        aec?.release()
        ns?.release()
      }
    }, "talk-mic").apply { start() }
  }

  /** Only an orb tap can interrupt a reply; the microphone never stops it. */
  /** Tap to interrupt: stop speaking (and the answer), then listen. */
  fun interrupt() {
    if (!running || (st != State.SPEAKING && st != State.THINKING)) return
    Log.i(TAG, "Interrupted by tap")
    speaker.stop()
    if (chat.busy) chat.stop()
    answerId = null
    vad.reset()
    moveTo(State.LISTENING)
  }

  fun stop() {
    running = false
    startToken++
    waking = false
    thread = null
    speaker.stop()
    answerId = null
    transcript = ""
    level = 0f
    moveTo(State.OFF)
  }

  /** Called for every ~32 ms of microphone sound (same logic as chat.html onTalkAudio). */
  private fun onFrame(x: ShortArray) {
    val r = VoiceDetector.rms(x)
    val now = SystemClock.elapsedRealtime()
    main.post { level = (r / 0.08).coerceIn(0.0, 1.0).toFloat() }
    val mode = when (st) {
      State.LISTENING -> VoiceDetector.Mode.LISTENING
      State.RECORDING -> VoiceDetector.Mode.RECORDING
      State.SPEAKING -> VoiceDetector.Mode.SPEAKING
      State.THINKING -> VoiceDetector.Mode.THINKING
      State.OFF -> return
    }
    val e = vad.feed(x, now, mode, now - speakingSince, loudspeakerSafe)
    when (e) {
      VoiceDetector.Event.Start -> moveTo(State.RECORDING)
      VoiceDetector.Event.Discard -> moveTo(State.LISTENING)
      is VoiceDetector.Event.Finish -> finishUtterance(e.chunks, e.lastLoudMs)
      VoiceDetector.Event.BargeIn -> {
        // Only a tap on the orb may interrupt a reply; ignore speech picked up over it.
      }
      VoiceDetector.Event.None -> {}
    }
  }

  private fun finishUtterance(chunks: List<ShortArray>, lastLoudMs: Long) {
    moveTo(State.THINKING)
    // The real end of speech, from the detector: the moment the last loud frame was heard, not the
    // moment the end-of-turn window elapsed. Logging against the wrong reference made a 900 ms window
    // look like part of the deliberate wait, and hid the fact that the acknowledgement never played.
    spokeEndAt = lastLoudMs
    val wav = encodeWav(chunks)
    main.post {
      val dir = chat.store.filesDir(chat.chatId)
      val f = File(dir, "voice_${UUID.randomUUID().toString().take(8)}.wav").apply { writeBytes(wav) }
      val att = Attachment(Attachment.Kind.AUDIO, "Voice message", f.path)
      sendWhenFree(att, 0)
    }
  }

  /** A reply may still be finishing after an interruption; wait for it (up to 5 s, like the PC). */
  private fun sendWhenFree(att: Attachment, tries: Int) {
    if (!running) return
    if (chat.busy && tries < 50) { main.postDelayed({ sendWhenFree(att, tries + 1) }, 100); return }
    chat.sendTyped("", voice = true, extra = listOf(att))
  }

  companion object {
    private const val TAG = "Talk"
    const val RATE = 16000
    private const val FRAME = 512 // 32 ms
    private const val PRE_FRAMES = 11 // ~0.35 s kept from before speech starts

    /** 16 kHz mono 16-bit WAV (LiteRT-LM reads WAV directly). */
    fun encodeWav(chunks: List<ShortArray>): ByteArray {
      val samples = chunks.sumOf { it.size }
      val data = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
      chunks.forEach { c -> c.forEach { data.putShort(it) } }
      val out = ByteArrayOutputStream(44 + samples * 2)
      val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
      h.put("RIFF".toByteArray()); h.putInt(36 + samples * 2); h.put("WAVE".toByteArray())
      h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
      h.putInt(RATE); h.putInt(RATE * 2); h.putShort(2); h.putShort(16)
      h.put("data".toByteArray()); h.putInt(samples * 2)
      out.write(h.array()); out.write(data.array())
      return out.toByteArray()
    }
  }
}
