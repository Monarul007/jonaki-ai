package com.offlinegemma.app

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * The models Jonaki AI can run. Each is pinned to an exact URL and SHA-256 so a file never changes
 * under the app, and each lives in its own file: a bigger model is downloaded alongside its
 * predecessor, never in place of it.
 */
enum class ChatModel(
  val label: String,
  val FILE_NAME: String,
  /** Shown in Settings so the trade-off is not a surprise. */
  val speed: String,
  val URL: String,
  val SIZE_BYTES: Long,
  val SHA256: String,
  /**
   * Context window to load with. Smaller on the larger file: weights plus KV cache must fit in the
   * process, and E4B's weights are about 1 GB bigger.
   */
  val maxTokens: Int,
  /**
   * Peak memory this model's process needs: weights plus KV cache. This is a **warning threshold, not
   * a gate**. Identical 7.56 GB phones disagree about E4B: on one it made the phone unusable, on a
   * Snapdragon 7s Gen 3 / Adreno 710 moto with 3.1 GB free and 5.7 GB of swap it runs steadily. Total
   * RAM cannot tell those apart, so refusing to install on it would be wrong — the app offers the
   * model and lets the load either work or fail cleanly.
   */
  val minTotalRamBytes: Long,
  /** At or above this the model runs comfortably. Between this and [minTotalRamBytes] it is slow. */
  val recommendedRamBytes: Long,
) {
  E2B(
    label = "Gemma 4 E2B",
    FILE_NAME = "gemma-4-E2B-it.litertlm",
    speed = "fastest",
    URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
      "2e12bfd869b2962984f97a1f2a26807316d534cc/gemma-4-E2B-it.litertlm",
    SIZE_BYTES = 2_588_147_712L,
    SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
    maxTokens = 8192,
    // Measured, not estimated: this model loaded in GPU + image + audio on a 4 GB phone
    // (OPPO CPH2895, Unisoc UMS9230E, Android 16), which reports about 3.7 GB in total.
    minTotalRamBytes = 3_500_000_000L,
    // 8 GB is the line where it is comfortable; phones sold as 8 GB report a little less.
    recommendedRamBytes = 7_000_000_000L,
  ),
  E4B(
    label = "Gemma 4 E4B",
    FILE_NAME = "gemma-4-E4B-it.litertlm",
    speed = "slower, better at maths and Bengali",
    URL = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/" +
      "gemma-4-E4B-it.litertlm",
    SIZE_BYTES = 3_659_530_240L,
    SHA256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
    // E4B's weights are 1 GB larger than E2B's; on a 7.5 GB phone the KV cache has to give way.
    maxTokens = 4096,
    // Measured both ways on 7.56 GB phones: unusable on one (Android's low-memory killer kept closing
    // System UI), steady on the other. The difference was free memory and swap, not total RAM, and
    // that is the device's call to make at load time — so E4B is offered, with this as the warning
    // threshold. 11 GB keeps the warning lit on every 8 GB-class phone, including the ones it runs
    // fine on, because the honest message there is "this is a big model for this phone", not "it works".
    minTotalRamBytes = 11_000_000_000L,
    recommendedRamBytes = 12_000_000_000L,
  ),
}

/** Whether a model can be installed on this phone, and if not, why. */
enum class Fit { READY, TOO_LARGE, NO_STORAGE }

/** The model file the app runs, and the numbers that describe it. */
object ModelInfo {
  /** The model used when the user has not chosen another one. */
  val DEFAULT = ChatModel.E2B

  const val NAME = "Gemma 4 E2B"

  /** Extra room kept free on top of a model (engine cache, other apps). */
  const val SPARE_BYTES = 1_000_000_000L

  fun modelsDir(context: Context) = File(context.filesDir, "models").apply { mkdirs() }

  fun file(context: Context, m: ChatModel) = File(modelsDir(context), m.FILE_NAME)
  fun partFile(context: Context, m: ChatModel) = File(modelsDir(context), "${m.FILE_NAME}.part")

  /** The default model's file, for the paths that simply mean "the model Jonaki runs". */
  fun modelFile(context: Context) = file(context, DEFAULT)
  fun partFile(context: Context) = partFile(context, DEFAULT)

  fun isDownloaded(context: Context, m: ChatModel) = file(context, m).length() == m.SIZE_BYTES
  fun isDownloaded(context: Context) = isDownloaded(context, DEFAULT)

  fun freeBytes(context: Context): Long = StatFs(modelsDir(context).path).availableBytes

  /** Bytes still needed on disk to finish [m] (counts what a paused download already saved). */
  fun bytesNeeded(context: Context, m: ChatModel): Long =
    (m.SIZE_BYTES - partFile(context, m).length()).coerceAtLeast(0) + SPARE_BYTES

  fun bytesNeeded(context: Context): Long = bytesNeeded(context, DEFAULT)

  fun totalRamBytes(context: Context): Long {
    val info = ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
    return info.totalMem
  }

  /** Available physical memory right now. A phone can ship as 8 GB yet expose far less, so this is
   *  shown as information; [lowMemory] is what decides whether to warn. */
  fun availableRamBytes(context: Context): Long {
    val info = ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
    return info.availMem
  }

  /**
   * Android's own verdict on whether the phone is under memory pressure right now.
   *
   * Not `availMem < model size`: on a healthy phone `availMem` is routinely ~1 GB of a 7.5 GB phone
   * (measured: MemTotal 7,556,364 kB, MemAvailable 1,155,840 kB), because page cache counts as used.
   * Comparing the two made every idle phone look overloaded, which is noise, not a warning.
   */
  fun lowMemory(context: Context): Boolean {
    val info = ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
    return info.lowMemory
  }

  /**
   * Can this phone install [m]? Two questions, deliberately kept apart:
   *
   * 1. Is the phone big enough at all? [ChatModel.minTotalRamBytes] is the model's peak working set,
   *    so the answer never depends on what happens to be free. This is what keeps a model that
   *    would make the phone unusable off the phone entirely.
   * 2. Is there room on disk? A paused download already holds [partBytes], so only the rest counts.
   */
  fun fit(m: ChatModel, totalRamBytes: Long, freeBytes: Long, partBytes: Long): Fit = when {
    // Room for the weights *on disk* is the only hard requirement. Total RAM is not a verdict:
    // identical 7.56 GB phones disagree about E4B, so the app lets the phone decide at load time.
    freeBytes < (m.SIZE_BYTES - partBytes).coerceAtLeast(0) + SPARE_BYTES -> Fit.NO_STORAGE
    else -> Fit.READY
  }

  fun fit(context: Context, m: ChatModel): Fit =
    fit(m, totalRamBytes(context), freeBytes(context), partFile(context, m).length())

  /**
   * Whether the phone is *comfortably* big enough for [m]. Advice, not permission: two 7.56 GB phones
   * disagree about E4B, so the app does not refuse on total RAM alone. The screen warns; the load
   * either succeeds or reports a clear error.
   */
  fun roomyEnough(context: Context, m: ChatModel): Boolean =
    roomyEnough(m, totalRamBytes(context))

  /** Pure core of [roomyEnough], so the threshold itself can be unit-tested. */
  fun roomyEnough(m: ChatModel, totalRamBytes: Long): Boolean = totalRamBytes >= m.minTotalRamBytes

  /** The models that fit on this phone's storage, best first. Nothing is hidden for being big. */
  fun installable(context: Context): List<ChatModel> =
    ChatModel.entries.filter { fit(context, it) != Fit.NO_STORAGE }

  /** Which models are on this phone right now, for Settings. */
  fun installed(context: Context): List<ChatModel> = ChatModel.entries.filter { isDownloaded(context, it) }

  /** On the phone and loadable here: a file alone does not mean the app can use it. */
  fun isUsable(context: Context, m: ChatModel): Boolean = isDownloaded(context, m)

  /** True when the app has something it can try to run, so onboarding can skip the download screen. */
  fun hasUsableModel(context: Context): Boolean = installed(context).isNotEmpty()

  /**
   * Models on the phone that are bigger than this phone is comfortable with. Kept for the download
   * screen's advice line only — never used to block anything, because that judgement is false on
   * phones that run the same model perfectly well.
   */
  fun oversizedInstalled(context: Context): List<ChatModel> =
    installed(context).filterNot { roomyEnough(context, it) }

  /** True when the phone can run [m] but it will be slow (below the comfortable line). */
  fun slow(context: Context, m: ChatModel): Boolean = totalRamBytes(context) < m.recommendedRamBytes

  /** True when the phone is short of memory right now, so a download should wait for other apps to
   *  close. Uses Android's own verdict rather than a raw number, which is almost always small. */
  fun busy(context: Context): Boolean = lowMemory(context)

  /** Removes one model's files. Other models are never touched. */
  fun delete(context: Context, m: ChatModel) {
    file(context, m).delete()
    partFile(context, m).delete()
  }

  fun deleteAll(context: Context) {
    modelFile(context).delete()
    partFile(context).delete()
  }
}

fun formatBytes(bytes: Long): String = when {
  bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1e9)
  bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1e6)
  else -> "%.0f KB".format(bytes / 1e3)
}

fun formatDuration(ms: Long): String {
  val s = ms / 1000
  return when {
    s >= 3600 -> "${s / 3600} h ${(s % 3600) / 60} min"
    s >= 60 -> "${s / 60} min ${s % 60} s"
    else -> "$s s"
  }
}
