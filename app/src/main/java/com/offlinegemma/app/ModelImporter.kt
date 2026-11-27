package com.offlinegemma.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copies a model file the user already has (from a USB stick, another phone, a backup) into the
 * app, instead of downloading 2.6-3.7 GB again. The file is identified by its exact byte size and
 * only kept when its SHA-256 matches the pinned value, so a renamed or damaged file is refused.
 */
object ModelImporter {

  /** Throws with a message meant for the UI when the file is not one of our models. */
  suspend fun import(
    context: Context,
    uri: Uri,
    onProgress: (copied: Long, total: Long) -> Unit,
  ): ChatModel = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
      if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
    } ?: -1L
    val model = ChatModel.entries.firstOrNull { it.SIZE_BYTES == size }
      ?: throw IllegalArgumentException(
        if (size <= 0) "Could not read that file's size."
        else "That file is ${formatBytes(size)}. A Jonaki model is 2.59 GB (E2B) or 3.66 GB (E4B).",
      )

    val part = ModelInfo.partFile(context, model)
    part.parentFile?.mkdirs()
    val md = MessageDigest.getInstance("SHA-256")
    var copied = 0L
    resolver.openInputStream(uri)?.use { input ->
      part.outputStream().use { out ->
        val buf = ByteArray(1 shl 20)
        while (true) {
          val n = input.read(buf)
          if (n < 0) break
          out.write(buf, 0, n)
          md.update(buf, 0, n)
          copied += n
          onProgress(copied, model.SIZE_BYTES)
        }
      }
    } ?: throw IllegalStateException("Could not open that file.")

    val hash = md.digest().joinToString("") { "%02x".format(it) }
    if (hash != model.SHA256) {
      part.delete()
      throw IllegalStateException(
        "That file is damaged or a different build (its fingerprint does not match). Nothing was installed.",
      )
    }
    val dest = ModelInfo.file(context, model)
    dest.delete()
    if (!part.renameTo(dest)) throw IllegalStateException("Could not save the model file on this phone.")
    model
  }
}