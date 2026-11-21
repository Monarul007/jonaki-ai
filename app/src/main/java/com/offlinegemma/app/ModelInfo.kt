package com.offlinegemma.app

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import java.io.File

/** The one model this app uses. Pinned to an exact Hugging Face commit so it never changes. */
object ModelInfo {
  const val NAME = "Gemma 4 E2B"
  const val FILE_NAME = "gemma-4-E2B-it.litertlm"
  private const val REPO = "litert-community/gemma-4-E2B-it-litert-lm"
  private const val COMMIT = "2e12bfd869b2962984f97a1f2a26807316d534cc"
  const val URL = "https://huggingface.co/$REPO/resolve/$COMMIT/$FILE_NAME"
  const val SIZE_BYTES = 2_588_147_712L
  const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

  /** Extra room kept free on top of the model (engine cache, other apps). */
  const val SPARE_BYTES = 1_000_000_000L

  /** Gallery asks for 8 GB phones. Phones sold as "8 GB" report a bit less, so warn below 7 GB. */
  const val MIN_RAM_BYTES = 7_000_000_000L

  fun modelsDir(context: Context) = File(context.filesDir, "models").apply { mkdirs() }
  fun modelFile(context: Context) = File(modelsDir(context), FILE_NAME)
  fun partFile(context: Context) = File(modelsDir(context), "$FILE_NAME.part")

  fun isDownloaded(context: Context) = modelFile(context).length() == SIZE_BYTES

  fun freeBytes(context: Context): Long = StatFs(modelsDir(context).path).availableBytes

  /** Bytes still needed on disk to finish (counts what a paused download already saved). */
  fun bytesNeeded(context: Context): Long =
    (SIZE_BYTES - partFile(context).length()).coerceAtLeast(0) + SPARE_BYTES

  fun hasEnoughStorage(context: Context) = freeBytes(context) >= bytesNeeded(context)

  fun totalRamBytes(context: Context): Long {
    val info = ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
    return info.totalMem
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
