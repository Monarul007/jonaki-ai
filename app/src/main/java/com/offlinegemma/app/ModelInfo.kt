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
   * Context window to load with on a phone of this app's minimum RAM. Smaller on the larger file:
   * weights plus KV cache must fit in the process, and E4B's weights are about 1 GB bigger.
   */
  val maxTokens: Int,
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
  ),
}

/** The model file the app runs, and the numbers that describe it. */
object ModelInfo {
  /** The model used when the user has not chosen another one. */
  val DEFAULT = ChatModel.E2B

  const val NAME = "Gemma 4 E2B"
  const val FILE_NAME = "gemma-4-E2B-it.litertlm"
  const val URL = ""
  const val SIZE_BYTES = 2_588_147_712L
  const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

  /** Extra room kept free on top of a model (engine cache, other apps). */
  const val SPARE_BYTES = 1_000_000_000L

  /** Gallery asks for 8 GB phones. Phones sold as "8 GB" report a bit less, so warn below 7 GB. */
  const val MIN_RAM_BYTES = 7_000_000_000L

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

  fun hasEnoughStorage(context: Context) = freeBytes(context) >= bytesNeeded(context)

  fun totalRamBytes(context: Context): Long {
    val info = ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
    return info.totalMem
  }

  /** Which models are on this phone right now, for Settings. */
  fun installed(context: Context): List<ChatModel> = ChatModel.entries.filter { isDownloaded(context, it) }

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
