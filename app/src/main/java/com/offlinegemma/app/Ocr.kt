package com.offlinegemma.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reads printed text from a picture with Tesseract, fully on the phone.
 *
 * The model's picture support (vision) needs a working GPU/media backend. When that is not
 * available — CPU-only phones and the x86_64 emulator — the attached photo used to be dropped
 * silently. This gives the chat something to answer from: the text on the page, in Bengali
 * and English. ML Kit and PaddleOCR do not support the Bengali script; Tesseract does.
 */
object Ocr {
  /** Both scripts are read in one pass; the language files ship in assets/tessdata. */
  private const val LANGUAGES = "ben+eng"
  private const val MAX_CHARS = 6000
  private const val TARGET_LONG_SIDE = 2000f

  private val lock = Mutex()
  private var engine: TessBaseAPI? = null

  /** True once the language files are on the phone (they are copied from assets on first use). */
  fun ready(context: Context): Boolean = File(tessDir(context), "ben.traineddata").exists()

  private fun dataDir(context: Context) = File(context.filesDir, "tesseract")
  private fun tessDir(context: Context) = File(dataDir(context), "tessdata")

  /** Copies the trained data out of the APK once. Tesseract needs a real file path. */
  private fun prepare(context: Context): Boolean {
    val dir = tessDir(context).apply { mkdirs() }
    for (name in listOf("ben.traineddata", "eng.traineddata")) {
      val out = File(dir, name)
      if (out.exists() && out.length() > 0) continue
      val ok = runCatching {
        context.assets.open("tessdata/$name").use { input ->
          out.outputStream().use { output -> input.copyTo(output) }
        }
      }.isSuccess
      if (!ok) {
        out.delete()
        android.util.Log.w("Ocr", "Could not copy $name from assets")
        return false
      }
    }
    return true
  }

  /** The text in the picture at [path] (a JPEG in the chat folder), or "" when none was found. */
  suspend fun readText(context: Context, path: String): String = lock.withLock {
    withContext(Dispatchers.IO) {
      runCatching {
        if (!prepare(context)) return@runCatching ""
        val bitmap = decodeForOcr(File(path)) ?: return@runCatching ""
        val api = engine ?: TessBaseAPI().also { fresh ->
          if (!fresh.init(dataDir(context).absolutePath, LANGUAGES)) {
            android.util.Log.w("Ocr", "Tesseract init failed for $LANGUAGES")
            fresh.recycle()
            return@runCatching ""
          }
          // A whole page has headings, lists and sections; automatic page segmentation reads
          // that better than assuming one uniform block of text.
          fresh.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
          engine = fresh
        }
        api.clear()
        api.setImage(bitmap)
        val text = api.utF8Text?.trim().orEmpty()
        bitmap.recycle()
        android.util.Log.i("Ocr", "Read ${text.length} chars from ${File(path).name}")
        text.take(MAX_CHARS)
      }.getOrElse {
        android.util.Log.w("Ocr", "OCR failed", it)
        ""
      }
    }
  }

  /**
   * Small photos are enlarged: Tesseract wants the smallest letters around 16–24 pixels.
   * The chat copy is at most 1024 px, so textbook pages need a 1.5–4× upscale.
   */
  private fun decodeForOcr(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options()) ?: return null
    val longest = maxOf(raw.width, raw.height)
    val scale = (TARGET_LONG_SIDE / longest).coerceIn(1f, 4f)
    val scaled = if (scale <= 1.05f) raw else Bitmap.createScaledBitmap(
      raw,
      (raw.width * scale).toInt().coerceAtLeast(1),
      (raw.height * scale).toInt().coerceAtLeast(1),
      true,
    ).also { if (it !== raw) raw.recycle() }
    return clean(scaled)
  }

  /** Grey and a little more contrast: Tesseract's binarizer reads an even page better. */
  private fun clean(src: Bitmap): Bitmap {
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val matrix = ColorMatrix().apply { setSaturation(0f) }
    matrix.postConcat(
      ColorMatrix(
        floatArrayOf(
          1.25f, 0f, 0f, 0f, -32f,
          0f, 1.25f, 0f, 0f, -32f,
          0f, 0f, 1.25f, 0f, -32f,
          0f, 0f, 0f, 1f, 0f,
        ),
      ),
    )
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
      colorFilter = ColorMatrixColorFilter(matrix)
    }
    Canvas(out).drawBitmap(src, 0f, 0f, paint)
    if (out !== src) src.recycle()
    return out
  }
}
