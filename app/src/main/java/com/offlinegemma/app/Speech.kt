package com.offlinegemma.app

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.getEndpointConfig
import java.io.File

/**
 * Bengali + English speech recognition, on the phone.
 *
 * Why this exists: the audio was being handed to the chat model with "write down what this says",
 * which is not what a 2B chat model is for. Measured on this phone, 3 of 5 Bengali clips came back
 * Romanised ("Tariq Rahman Bangladesh-er koto tomo prodhantromntri"), and that text is what the
 * study files are searched with — so the one word carrying the question ("কত তম", how-many-th) was
 * lost and the search matched "প্রথম" (first) instead.
 *
 * sherpa-onnx is a real ASR runtime: zipformer2 transducer, ~94 MB, measured at RTF 0.04 on its own
 * sample (25× faster than real time), and it emits Bengali script.
 */
object Speech {
  private const val TAG = "Speech"

  /** Files shipped (or downloaded) for the recogniser. */
  private const val ENCODER = "encoder.onnx"
  private const val DECODER = "decoder.onnx"
  private const val JOINER = "joiner.onnx"
  private const val TOKENS = "tokens.txt"

  /** ~94 MB of model; not in git (see .gitignore), downloaded once like the chat model. */
  const val SIZE_BYTES = 94_119_939L

  private var recognizer: OnlineRecognizer? = null

  fun dir(context: Context) = File(context.filesDir, "asr").apply { mkdirs() }

  fun isInstalled(context: Context): Boolean = listOf(ENCODER, DECODER, JOINER, TOKENS)
    .all { File(dir(context), it).length() > 0 }

  /** The recogniser, built once and kept: building it reads 94 MB of ONNX. */
  @Synchronized
  private fun engine(context: Context): OnlineRecognizer? {
    recognizer?.let { return it }
    if (!isInstalled(context)) return null
    val d = dir(context)
    return runCatching {
      OnlineRecognizer(
        // NULL on purpose: the model files live in the app's own directory, and sherpa refuses an
        // absolute path while an AssetManager is set — it aborts the process with
        // "Load '.../decoder.onnx' failed". (k2-fsa issue #2562.)
        assetManager = null,
        config = OnlineRecognizerConfig(
          featConfig = getFeatureConfig(sampleRate = 16000, featureDim = 80),
          modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
              encoder = File(d, ENCODER).path,
              decoder = File(d, DECODER).path,
              joiner = File(d, JOINER).path,
            ),
            tokens = File(d, TOKENS).path,
            // Told explicitly: the model card says zipformer2, and the runtime cannot guess it from
            // a file name that is just "encoder.onnx".
            modelType = "zipformer2",
            numThreads = 2,
            provider = "cpu",
          ),
          // Endpointing off: the whole clip is decoded in one pass here, so the trailing-silence
          // rules would only cut it short.
          endpointConfig = getEndpointConfig(),
          enableEndpoint = false,
          decodingMethod = "greedy_search",
        ),
      ).also { recognizer = it }
    }.onFailure { Log.e(TAG, "recogniser could not be built", it) }.getOrNull()
  }

  /**
   * What [wav] says, as text. Empty when the model is not installed or nothing was decoded, which
   * leaves the caller free to fall back to the chat model.
   *
   * [wav] must be 16-bit mono PCM (the app already records at 16 kHz): that is what the recogniser
   * accepts, and the same clip is kept for playback.
   */
  fun transcribe(context: Context, wav: ByteArray): String {
    val rec = engine(context) ?: return ""
    val samples = pcm16(wav) ?: return ""
    if (samples.isEmpty()) return ""
    val startedAt = System.currentTimeMillis()
    return runCatching {
      val stream = rec.createStream("")
      stream.acceptWaveform(samples, sampleRate = 16000)
      stream.inputFinished()
      while (rec.isReady(stream)) rec.decode(stream)
      // getResult returns a result object; the words are its `text`.
      val text = rec.getResult(stream).text.trim()
      stream.release()
      Log.i(TAG, "asr '${text.take(80)}' in ${System.currentTimeMillis() - startedAt} ms")
      text
    }.onFailure { Log.e(TAG, "decode failed", it) }.getOrDefault("")
  }

  /**
   * The 16-bit samples inside a WAV file, or null when the header says it is not PCM16 mono.
   * The recorders here always write that format, so a mismatch means something unexpected rather
   * than a case worth converting.
   */
  private fun pcm16(wav: ByteArray): FloatArray? {
    if (wav.size < 44) return null
    // "RIFF"…"WAVE"
    if (wav[0] != 'R'.code.toByte() || wav[8] != 'W'.code.toByte()) return null
    val channels = (wav[22].toInt() and 0xFF) or (wav[23].toInt() shl 8)
    val bits = (wav[34].toInt() and 0xFF) or (wav[35].toInt() shl 8)
    if (channels != 1 || bits != 16) return null
    val data = findDataChunk(wav) ?: return null
    val count = (wav.size - data) / 2
    if (count <= 0) return null
    return FloatArray(count) { i ->
      val lo = wav[data + i * 2].toInt() and 0xFF
      val hi = wav[data + i * 2 + 1].toInt()
      ((lo or (hi shl 8)).toShort().toInt() / 32768f)
    }
  }

  /** The offset of the "data" chunk's payload, walking the chunk headers rather than assuming 44. */
  private fun findDataChunk(wav: ByteArray): Int? {
    var at = 12
    while (at + 8 <= wav.size) {
      val id = String(wav, at, 4, Charsets.US_ASCII)
      val size = (wav[at + 4].toInt() and 0xFF) or (wav[at + 5].toInt() and 0xFF shl 8) or
        (wav[at + 6].toInt() and 0xFF shl 16) or (wav[at + 7].toInt() and 0xFF shl 24)
      if (id == "data") return at + 8
      at += 8 + size + (size and 1) // chunks are word-aligned
    }
    return null
  }
}
