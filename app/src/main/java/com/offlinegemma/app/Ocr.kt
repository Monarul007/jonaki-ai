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
  /**
   * Developer test only: when set, every picture "reads" as this text, so the model can be tested
   * on a perfectly read page (separates reading mistakes from answering mistakes).
   */
  @Volatile var overrideText: String? = null

  suspend fun readText(context: Context, path: String): String = lock.withLock {
    overrideText?.let { return@withLock numberFormulaLines(it.trim()) }
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
        val plain = api.utF8Text?.trim().orEmpty()
        val text = runCatching { latinFromMlKit(context, api, bitmap) }
          .onFailure { android.util.Log.w("Ocr", "Latin re-read failed", it) }
          .getOrNull()?.takeIf { it.isNotBlank() } ?: plain
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
   * Tesseract's mixed Bengali+English pass misreads Latin formulas on a Bengali page ("CuCl2" came
   * out as "00002", "CO2" as "0০02"). Each script goes to the reader built for it: Bengali words stay
   * Tesseract's, and every word that is not purely Bengali is replaced by what ML Kit's on-device
   * Latin model read at the same place on the page. A rule about scripts, never a list of words.
   * Line breaks follow Tesseract's layout.
   */
  private fun latinFromMlKit(context: Context, mixed: TessBaseAPI, bitmap: Bitmap): String {
    val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
      com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS,
    )
    val latin = try {
      com.google.android.gms.tasks.Tasks.await(
        recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)),
      )
    } finally {
      recognizer.close()
    }
    val elements = latin.textBlocks.flatMap { b -> b.lines.flatMap { l -> l.elements } }
      .filter { e -> e.boundingBox != null }
    android.util.Log.i("Ocr", "ML Kit Latin pass:\n" + latin.text)
    val word = TessBaseAPI.PageIteratorLevel.RIL_WORD
    val it = mixed.resultIterator ?: return ""
    val out = StringBuilder()
    var swapped = 0
    try {
      it.begin()
      do {
        val w = it.getUTF8Text(word) ?: continue
        if (it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE) && out.isNotEmpty()) out.append("\n")
        else if (out.isNotEmpty()) out.append(' ')
        var best = w
        // A purely Bengali word (Bengali letters/digits and punctuation) is Tesseract's job.
        val pureBengali = w.none { c -> c.isLetterOrDigit() && c !in 'ঀ'..'৿' }
        val r = it.getBoundingRect(word)
        // ML Kit words whose centre lies inside this word's box (the two readers split differently).
        val match = elements.filter { e -> r.contains(e.boundingBox!!.centerX(), e.boundingBox!!.centerY()) }
        val e = match.joinToString(" ") { m -> m.text }
        // Symbols (→, +, =) belong to no script; the Latin reader reads them, so its reading wins.
        // Only where Tesseract saw no Bengali letter (a lone "৯" there is a misread arrow; "(ক)" is not).
        val symbolsOnly = e.isNotBlank() && e.none { c -> c.isLetterOrDigit() } &&
          w.none { c -> c.isLetter() && c in 'ঀ'..'৿' }
        val hasLatinLetter = w.any { c -> c.isLetter() && c.code < 0x250 }
        if (hasLatinLetter && e.isBlank()) {
          // Tesseract saw Latin letters where the Latin reader saw no text at all: it is a Bengali
          // word misread as Latin ("কেন" -> "CHA"). Read that spot again in Bengali only.
          val bn = bengaliWord(context, bitmap, r)
          if (bn.isNotBlank() && bn.any { c -> c in 'ঀ'..'৿' }) { best = bn; swapped++ }
          android.util.Log.d("Ocr", "word '$w' has no Latin text -> bengali '$bn'")
        } else if (!pureBengali || symbolsOnly) {
          if (e.isNotBlank() && e != w && e.none { c -> c in 'ঀ'..'৿' }) { best = e; swapped++ }
          android.util.Log.d("Ocr", "word '$w' vs latin '$e' -> '$best'")
        }
        out.append(best)
      } while (it.next(word))
    } finally {
      it.delete()
    }
    android.util.Log.i("Ocr", "re-read: $swapped words replaced")
    return numberFormulaLines(out.toString().trim())
  }

  /**
   * The printed (i), (ii), (iii) in front of a page's formula lines are tiny and misread by every
   * reader tried (ours and banglaocr.com both turned "(ii)" into "(i)"), and the model then picked
   * the wrong "second reaction". The app knows the layout, so it numbers those lines itself, top to
   * bottom, and drops the misread printed marker.
   *
   * A formula line is decided by script, not by words: at least 3 of its tokens and at least 40% of
   * them carry Latin letters, ASCII digits or only symbols (an equation's "+ O2 → CO2"), unlike a
   * Bengali sentence. The printed marker is the line's first token when it is short, ends with ")"
   * and holds no Bengali letter.
   */
  internal fun numberFormulaLines(text: String): String {
    var n = 0
    return text.lines().joinToString("\n") { line ->
      val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
      val marker = tokens.firstOrNull()?.takeIf { t ->
        t.length <= 6 && t.endsWith(")") && t.none { c -> c.isLetter() && c in 'ঀ'..'৿' }
      }
      val body = if (marker != null) tokens.drop(1) else tokens
      val formulaish = body.count { t ->
        t.any { c -> (c.isLetter() && c.code < 0x250) || c in '0'..'9' } ||
          t.none { c -> c.isLetterOrDigit() }
      }
      if (body.size >= 3 && formulaish >= 3 && formulaish * 10 >= body.size * 4) {
        n++
        "[Formula line $n] " + body.joinToString(" ")
      } else {
        line
      }
    }
  }

  private var bengali: TessBaseAPI? = null

  /** One word box read by the Bengali-only model. */
  private fun bengaliWord(context: Context, bitmap: Bitmap, r: android.graphics.Rect): String {
    val api = bengali ?: TessBaseAPI().also { fresh ->
      if (!fresh.init(dataDir(context).absolutePath, "ben")) { fresh.recycle(); return "" }
      fresh.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_WORD)
      bengali = fresh
    }
    val pad = (r.height() / 4).coerceAtLeast(4)
    val left = (r.left - pad).coerceAtLeast(0)
    val top = (r.top - pad).coerceAtLeast(0)
    val right = (r.right + pad).coerceAtMost(bitmap.width)
    val bottom = (r.bottom + pad).coerceAtMost(bitmap.height)
    val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    return try {
      api.clear()
      api.setImage(crop)
      api.utF8Text?.trim().orEmpty()
    } finally {
      crop.recycle()
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
    return clean(withMargin(scaled))
  }

  /**
   * A white border around the picture. Tesseract skips text touching the edge of the image
   * (a cropped screenshot lost its whole first line).
   */
  private fun withMargin(src: Bitmap): Bitmap {
    val m = (minOf(src.width, src.height) / 8).coerceIn(24, 120)
    val out = Bitmap.createBitmap(src.width + 2 * m, src.height + 2 * m, Bitmap.Config.ARGB_8888)
    Canvas(out).apply {
      drawColor(android.graphics.Color.WHITE)
      drawBitmap(src, m.toFloat(), m.toFloat(), null)
    }
    src.recycle()
    return out
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
