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
  /** Microphone loudness 0..1, for the animated orb. */
  var level by mutableFloatStateOf(0f); private set
  /** Shown under the orb: what Gemma is saying. */
  var caption by mutableStateOf(""); private set
  var error by mutableStateOf<String?>(null); private set

  private val main = Handler(Looper.getMainLooper())
  @Volatile private var running = false
  private var thread: Thread? = null

  // Sound detection, same numbers as the PC: threshold = max(0.012, noise floor x 3).
  private var floor = 0.004
  /** How loud our own voice comes back through the microphone while speaking (loudspeaker echo). */
  private var echo = 0.0
  private val pre = ArrayDeque<ShortArray>()
  private val rec = mutableListOf<ShortArray>()
  private var startAt = 0L
  private var quietAt = 0L
  private var loudSince = 0L
  private var voicedMs = 0.0
  private var answerId: Long? = null
  /** Headphones on: no loudspeaker echo, so interrupting needs no extra margin. */
  private var loudspeakerSafe = false

  // Timing, to compare with the PC (full loop ~15 s, interruption ~1 s).
  private var spokeEndAt = 0L

  init {
    chat.onAnswerStart = { id, voice ->
      if (running && voice) {
        answerId = id
        caption = ""
        speaker.start(id)
      }
    }
    chat.onAnswerText = { id, text ->
      if (running && id == answerId) {
        speaker.feed(text)
        caption = speakable(text).trim().takeLast(220)
      }
    }
    chat.onAnswerDone = { line ->
      if (running && line.id == answerId) {
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

  @SuppressLint("MissingPermission") // the screen asks for the microphone first
  fun start() {
    if (running) return
    if (!chat.gemma.canHear) {
      error = "Talk mode needs Gemma's hearing, which didn't load on this phone."
      return
    }
    error = null
    val rate = RATE
    val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val ar = try {
      // VOICE_COMMUNICATION turns on the phone's own echo cancelling where it has one.
      AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, FRAME * 8))
    } catch (t: Throwable) {
      error = "The microphone is not available."
      return
    }
    if (ar.state != AudioRecord.STATE_INITIALIZED) {
      error = "The microphone is not available."
      ar.release()
      return
    }
    val aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(ar.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
    val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(ar.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
    Log.i(TAG, "Talk start: echo canceller=${aec != null}, noise suppressor=${ns != null}")

    running = true
    loudspeakerSafe = headphones()
    floor = 0.004
    echo = 0.0
    pre.clear(); rec.clear()
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

  fun stop() {
    running = false
    thread = null
    speaker.stop()
    answerId = null
    caption = ""
    level = 0f
    moveTo(State.OFF)
  }

  private fun rms(x: ShortArray): Double {
    var s = 0.0
    for (v in x) { val f = v / 32768.0; s += f * f }
    return sqrt(s / x.size)
  }

  /** Called for every ~32 ms of microphone sound (same logic as chat.html onTalkAudio). */
  private fun onFrame(x: ShortArray) {
    val r = rms(x)
    val now = SystemClock.elapsedRealtime()
    val ms = x.size * 1000.0 / RATE
    val th = max(0.012, floor * 3)
    main.post { level = (r / 0.08).coerceIn(0.0, 1.0).toFloat() }

    when (st) {
      State.LISTENING -> {
        pre.addLast(x); while (pre.size > PRE_FRAMES) pre.removeFirst()
        if (r > th) {
          rec.clear(); rec.addAll(pre); pre.clear()
          startAt = now; quietAt = now; voicedMs = 0.0
          moveTo(State.RECORDING)
        } else {
          floor = floor * 0.97 + r * 0.03 // follow the room's background noise
        }
      }
      State.RECORDING -> {
        rec.add(x)
        if (r > th) { quietAt = now; voicedMs += ms }
        if (now - quietAt > 1200 || now - startAt > 30_000) finishUtterance()
      }
      State.SPEAKING, State.THINKING -> {
        // Talking over the reply interrupts it. On the loudspeaker the phone hears its own voice,
        // so the bar is set above that echo (learned while speaking) as well as above 3x the threshold.
        val speakingNow = st == State.SPEAKING
        val bar = if (speakingNow && !loudspeakerSafe) max(th * 3, echo * 3.5) else th * 3
        // The first 0.6 s of each reply only learns how loud the echo is.
        val learning = speakingNow && now - speakingSince < 600
        if (speakingNow && (learning || r <= bar)) echo = echo * 0.9 + r * 0.1
        if (speakingNow && !learning && r > bar) {
          if (loudSince == 0L) loudSince = now
          if (now - loudSince > 350) {
            Log.i(TAG, "Barge-in: rms=%.3f bar=%.3f echo=%.3f".format(r, bar, echo))
            loudSince = 0
            main.post {
              speaker.stop()
              if (chat.busy) chat.stop()
            }
            rec.clear(); rec.addAll(pre); rec.add(x); pre.clear()
            startAt = now; quietAt = now; voicedMs = 350.0
            moveTo(State.RECORDING)
          }
        } else {
          loudSince = 0
          pre.addLast(x); while (pre.size > PRE_FRAMES) pre.removeFirst()
        }
      }
      State.OFF -> {}
    }
  }

  private fun finishUtterance() {
    val chunks = rec.toList()
    rec.clear()
    if (voicedMs < 300) { moveTo(State.LISTENING); return } // a cough or a click
    moveTo(State.THINKING)
    spokeEndAt = SystemClock.elapsedRealtime()
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
