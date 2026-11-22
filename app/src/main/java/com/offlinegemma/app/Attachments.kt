package com.offlinegemma.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns pictures and documents the user adds into something Gemma can use.
 * Everything happens on the phone.
 */
object Attachments {
  /** Same limit as the PC app: about 5000 characters of a document go to the model. */
  const val DOC_CHARS = 5000
  /** Gemma looks at pictures at a small size; bigger ones only cost memory. */
  private const val MAX_SIDE = 1024
  /** A sharper copy for OCR only: small letters on a textbook page need the extra pixels. */
  private const val OCR_MAX_SIDE = 2400

  val DOC_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "text/*",
    "application/json",
    "application/xml",
    "application/javascript",
    "application/x-python",
    "image/*",
    "audio/*",
  )

  fun displayName(context: Context, uri: Uri): String =
    runCatching {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
      }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

  /** Copies any picture into the chat's folder as a small JPEG (plus a sharper OCR copy). */
  suspend fun importImage(context: Context, uri: Uri, dir: File, name: String = displayName(context, uri)): Attachment =
    withContext(Dispatchers.IO) {
      importFromSource(ImageDecoder.createSource(context.contentResolver, uri), dir, name)
    }

  /**
   * Decodes once at the larger OCR size, then saves the small copy the model uses and, when the
   * photo was bigger than that copy, a sharper one for OCR. This is what makes small textbook
   * text readable: the 1024 px chat copy loses it, the 2400 px copy keeps it.
   */
  private fun importFromSource(src: ImageDecoder.Source, dir: File, name: String): Attachment {
    val big = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
      decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      val w = info.size.width
      val h = info.size.height
      val scale = minOf(1f, OCR_MAX_SIDE.toFloat() / maxOf(w, h))
      if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
    }
    val longest = maxOf(big.width, big.height)
    if (longest <= MAX_SIDE) {
      val only = writeJpeg(big, dir, name, 90)
      big.recycle()
      return only
    }
    val s = MAX_SIDE.toFloat() / longest
    val small = Bitmap.createScaledBitmap(
      big,
      (big.width * s).toInt().coerceAtLeast(1),
      (big.height * s).toInt().coerceAtLeast(1),
      true,
    )
    val vision = writeJpeg(small, dir, name, 88)
    val sharp = writeJpeg(big, dir, name + "_ocr", 92)
    small.recycle()
    big.recycle()
    return vision.copy(ocrPath = sharp.path)
  }

  private fun writeJpeg(bmp: Bitmap, dir: File, name: String, quality: Int): Attachment {
    val out = File(dir, "img_${UUID.randomUUID().toString().take(8)}.jpg")
    out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    return Attachment(Attachment.Kind.IMAGE, name, out.path)
  }

  fun saveBitmap(bmp: Bitmap, dir: File, name: String): Attachment {
    val longest = maxOf(bmp.width, bmp.height)
    val kept = if (longest > MAX_SIDE) {
      val s = MAX_SIDE.toFloat() / longest
      Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
        .also { if (it !== bmp) bmp.recycle() }
    } else bmp
    val out = File(dir, "img_${UUID.randomUUID().toString().take(8)}.jpg")
    out.outputStream().use { kept.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    return Attachment(Attachment.Kind.IMAGE, name, out.path)
  }

  /** Copies a document and reads its text (PDF, Word, PowerPoint, Excel, CSV, text, code). */
  suspend fun importDoc(context: Context, uri: Uri, dir: File): Pair<Attachment, String> = withContext(Dispatchers.IO) {
    val name = displayName(context, uri)
    val out = File(dir, "doc_${UUID.randomUUID().toString().take(8)}_${name.replace(Regex("[^\\w.-]"), "_")}")
    context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
      ?: error("Could not open $name")
    val text = runCatching { extractText(context, out, name, context.contentResolver.getType(uri)) }
      .getOrElse { "(Could not read this file: ${it.message})" }
    Attachment(Attachment.Kind.DOC, name, out.path, text.length) to text
  }

  /** What the model sees for a document: a header line and the first [DOC_CHARS] characters. */
  fun docForModel(name: String, text: String): String {
    val cut = text.length > DOC_CHARS
    val head = "[file: $name" + (if (cut) "; showing the first $DOC_CHARS of ${text.length} characters" else "") + "]"
    return if (text.isBlank()) "$head\n(no readable text in this file)" else "$head\n${text.take(DOC_CHARS)}"
  }

  fun extractText(context: Context, file: File, name: String, mime: String?): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
      ext == "pdf" || mime == "application/pdf" -> pdfText(context, file)
      ext == "docx" -> zipText(file, Regex("word/document\\.xml")).let { xmlText(it.joinToString("\n"), "w:p") }
      ext == "pptx" -> zipEntries(file)
        .filterKeys { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }
        .toSortedMap(compareBy { Regex("\\d+").findAll(it).last().value.toInt() })
        .entries.mapIndexed { k, e -> "[slide ${k + 1}]\n" + xmlText(e.value, "a:p").trim() }
        .joinToString("\n")
      ext == "xlsx" -> xlsxText(file)
      else -> file.bufferedReader().use { r -> CharArray(200_000).let { buf -> String(buf, 0, r.read(buf).coerceAtLeast(0)) } }
    }.trim()
  }

  private fun pdfText(context: Context, file: File): String {
    PDFBoxResourceLoader.init(context.applicationContext)
    PDDocument.load(file).use { doc ->
      val total = doc.numberOfPages
      val stripper = PDFTextStripper()
      val parts = (1..minOf(total, 40)).map { p ->
        stripper.startPage = p
        stripper.endPage = p
        "[page $p/$total]\n" + stripper.getText(doc).trim()
      }
      val text = parts.joinToString("\n").trim()
      return if (text.replace(Regex("\\[page \\d+/\\d+]"), "").isBlank()) "(The PDF has no text layer - it is probably scanned pictures.)" else text
    }
  }

  private fun zipEntries(file: File, max: Int = 200): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    ZipInputStream(file.inputStream().buffered()).use { z ->
      while (true) {
        val e = z.nextEntry ?: break
        if (!e.isDirectory && e.name.endsWith(".xml") && out.size < max) out[e.name] = z.readBytes().toString(Charsets.UTF_8)
      }
    }
    return out
  }

  private fun zipText(file: File, name: Regex) = zipEntries(file).filterKeys { name.matches(it) }.values

  private fun unescape(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()

  /** Same idea as agent.py _xml_text: end of paragraph -> new line, then drop the tags. */
  private fun xmlText(xml: String, paraTag: String): String {
    val s = xml.replace("</$paraTag>", "\u0001").replace(Regex("<[^>]+>"), "")
    return s.split('\u0001').joinToString("\n") { unescape(it).trimEnd() }.trim()
  }

  private fun xlsxText(file: File): String {
    val all = zipEntries(file)
    val shared = all["xl/sharedStrings.xml"]?.let { ss ->
      Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(ss).map { unescape(it.groupValues[1].replace(Regex("<[^>]+>"), "")) }.toList()
    } ?: emptyList()
    val sheets = all.keys.filter { Regex("xl/worksheets/sheet\\d+\\.xml").matches(it) }.sorted().take(5)
    val out = mutableListOf<String>()
    for (n in sheets) {
      out.add("[${n.substringAfterLast('/').removeSuffix(".xml")}]")
      val xml = all.getValue(n)
      for (row in Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(xml).take(300)) {
        val cells = Regex("<c([^>]*)>(.*?)</c>", RegexOption.DOT_MATCHES_ALL).findAll(row.groupValues[1]).map { c ->
          val attrs = c.groupValues[1]
          val v = (Regex("<v>(.*?)</v>").find(c.groupValues[2]) ?: Regex("<t[^>]*>(.*?)</t>").find(c.groupValues[2]))?.groupValues?.get(1)?.let(::unescape) ?: ""
          if ("t=\"s\"" in attrs) v.toIntOrNull()?.let { shared.getOrNull(it) } ?: v else v
        }
        out.add(cells.joinToString(","))
      }
    }
    return out.joinToString("\n")
  }

  /** Copies an audio clip the user picked (LiteRT-LM reads WAV and MP3 directly). */
  suspend fun importAudio(context: Context, uri: Uri, dir: File): Attachment = withContext(Dispatchers.IO) {
    val name = displayName(context, uri)
    val out = File(dir, "audio_${UUID.randomUUID().toString().take(8)}.${name.substringAfterLast('.', "wav")}")
    context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
    Attachment(Attachment.Kind.AUDIO, name, out.path)
  }
}
