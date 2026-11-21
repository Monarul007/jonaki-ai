package com.offlinegemma.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Something to show full screen: a figure, or a picture / PDF / document / voice clip from a message. */
data class ViewItem(
  val path: String,
  val name: String,
  val title: String,
  val caption: AnnotatedString? = null,
  /** A web page for "Open original" (figures); attachments open the file in another app instead. */
  val page: String? = null,
)

private enum class Kind { IMAGE, PDF, AUDIO, TEXT }

private fun kindOf(name: String): Kind {
  val ext = name.substringAfterLast('.', "").lowercase()
  return when {
    ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp") -> Kind.IMAGE
    ext == "pdf" -> Kind.PDF
    ext in setOf("wav", "mp3", "ogg", "m4a", "flac", "webm", "aac") -> Kind.AUDIO
    else -> Kind.TEXT
  }
}

/** Only the app's own files may be opened (chats and figures live in its private folder). */
private fun ownFile(context: Context, path: String): File? {
  val f = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
  return if (f.exists() && f.path.startsWith(context.filesDir.canonicalPath)) f else null
}

private fun mimeOf(name: String) = when (kindOf(name)) {
  Kind.IMAGE -> "image/*"
  Kind.PDF -> "application/pdf"
  Kind.AUDIO -> "audio/*"
  Kind.TEXT -> when (name.substringAfterLast('.', "").lowercase()) {
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    else -> "text/plain"
  }
}

/** Full-screen, dark viewer. Back gesture or ✕ closes it. */
@Composable
fun Viewer(item: ViewItem, onClose: () -> Unit) {
  val context = LocalContext.current
  val uri = LocalUriHandler.current
  val file = remember(item.path) { ownFile(context, item.path) }
  Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0C10))) {
      // Top bar
      Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(item.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
      }
      Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (file == null) {
          Text("This file is no longer on the phone.", color = Color.White.copy(alpha = 0.7f))
        } else when (kindOf(item.name.ifBlank { file.name }).let { if (it == Kind.TEXT && kindOf(file.name) != Kind.TEXT) kindOf(file.name) else it }) {
          Kind.IMAGE -> ZoomImage(file)
          Kind.PDF -> PdfPages(file)
          Kind.AUDIO -> AudioPlayer(file)
          Kind.TEXT -> TextSheet(context, file, item.name)
        }
      }
      // Caption + actions
      Column(Modifier.fillMaxWidth().background(Color(0xFF14161D)).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        item.caption?.let {
          // Long captions scroll, so the source and licence line is always reachable.
          Text(it, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(bottom = 8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = {
            if (item.page != null) runCatching { uri.openUri(item.page) }
            else if (file != null) openWith(context, file, item.name)
          }, enabled = item.page != null || file != null) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = Color.White); Spacer(Modifier.width(6.dp)); Text("Open original", color = Color.White)
          }
          TextButton(onClick = { if (file != null) share(context, file, item.name) }, enabled = file != null) {
            Icon(Icons.Rounded.Share, null, tint = Color.White); Spacer(Modifier.width(6.dp)); Text("Share", color = Color.White)
          }
        }
      }
    }
  }
}

private fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

private fun openWith(context: Context, file: File, name: String) {
  val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(context, file), mimeOf(name.ifBlank { file.name })).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  runCatching { context.startActivity(Intent.createChooser(i, "Open with")) }
}

private fun share(context: Context, file: File, name: String) {
  val i = Intent(Intent.ACTION_SEND).setType(mimeOf(name.ifBlank { file.name }))
    .putExtra(Intent.EXTRA_STREAM, uriFor(context, file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  runCatching { context.startActivity(Intent.createChooser(i, "Share or save")) }
}

/** Pinch to zoom, drag to pan, double-tap to zoom in or back out. */
@Composable
private fun Zoomable(content: @Composable () -> Unit) {
  var scale by remember { mutableFloatStateOf(1f) }
  var offset by remember { mutableStateOf(Offset.Zero) }
  Box(
    Modifier.fillMaxSize()
      .clipToBounds() // a zoomed picture stays inside its area
      .pointerInput(Unit) {
        detectTapGestures(onDoubleTap = { p ->
          if (scale > 1.05f) { scale = 1f; offset = Offset.Zero } else { scale = 2.5f; offset = (Offset(size.width / 2f, size.height / 2f) - p) * 1.5f }
        })
      }
      .pointerInput(Unit) {
        // Pinch always zooms; one finger pans only when zoomed in, so it can still turn PDF pages.
        awaitEachGesture {
          awaitFirstDown(requireUnconsumed = false)
          do {
            val event = awaitPointerEvent()
            if (event.changes.size > 1 || scale > 1f) {
              scale = (scale * event.calculateZoom()).coerceIn(1f, 8f)
              offset = if (scale == 1f) Offset.Zero else offset + event.calculatePan()
              event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
          } while (event.changes.any { it.pressed })
        }
      }
    ,
    contentAlignment = Alignment.Center,
  ) {
    Box(
      Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
      contentAlignment = Alignment.Center,
    ) { content() }
  }
}

@Composable
private fun ZoomImage(file: File) {
  val bmp = remember(file) { runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull() }
  if (bmp == null) { Text("Can't show this picture.", color = Color.White); return }
  Zoomable { Image(bmp, contentDescription = file.name, modifier = Modifier.fillMaxSize().padding(8.dp)) }
}

@Composable
private fun PdfPages(file: File) {
  val renderer = remember(file) {
    runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull()
  }
  DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }
  if (renderer == null) { Text("Can't open this PDF.", color = Color.White); return }
  val pager = rememberPagerState { renderer.pageCount }
  Box {
    HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
      val page by produceState<Bitmap?>(null, i) {
        value = withContext(Dispatchers.IO) {
          synchronized(renderer) {
            renderer.openPage(i).use { p ->
              val w = 1400
              val h = (w.toFloat() * p.height / p.width).toInt()
              Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                b.eraseColor(android.graphics.Color.WHITE)
                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
              }
            }
          }
        }
      }
      val b = page
      if (b == null) CircularProgressIndicator(Modifier.size(32.dp))
      else Zoomable { Image(b.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxSize().padding(8.dp)) }
    }
    Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(50), modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
      Text("Page ${pager.currentPage + 1} of ${renderer.pageCount}", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }
  }
}

@Composable
private fun TextSheet(context: Context, file: File, name: String) {
  val text by produceState<String?>(null, file) {
    value = withContext(Dispatchers.IO) {
      runCatching { Attachments.extractText(context, file, name.ifBlank { file.name }, null) }.getOrElse { "Can't read this file: ${it.message}" }
    }
  }
  val t = text
  if (t == null) { CircularProgressIndicator(Modifier.size(32.dp)); return }
  Surface(color = Color(0xFF181A22), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxSize().padding(12.dp)) {
    Text(
      t.ifBlank { "(no readable text in this file)" },
      color = Color(0xFFE6E6EE),
      fontFamily = FontFamily.Monospace,
      fontSize = 13.sp,
      lineHeight = 19.sp,
      softWrap = false,
      modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp),
    )
  }
}

@Composable
private fun AudioPlayer(file: File) {
  val player = remember(file) { runCatching { MediaPlayer().apply { setDataSource(file.path); prepare() } }.getOrNull() }
  var playing by remember { mutableStateOf(false) }
  var pos by remember { mutableFloatStateOf(0f) }
  DisposableEffect(player) { onDispose { runCatching { player?.release() } } }
  if (player == null) { Text("Can't play this clip.", color = Color.White); return }
  LaunchedEffect(playing) {
    while (playing) {
      pos = player.currentPosition.toFloat() / player.duration.coerceAtLeast(1)
      if (!player.isPlaying) playing = false
      delay(200)
    }
  }
  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
    FilledIconButton(onClick = {
      if (playing) player.pause() else player.start()
      playing = !playing
    }, modifier = Modifier.size(80.dp)) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(40.dp)) }
    Spacer(Modifier.size(24.dp))
    Slider(value = pos, onValueChange = { pos = it; player.seekTo((it * player.duration).toInt()) })
    Text("%.1f s".format(player.duration / 1000f), color = Color.White.copy(alpha = 0.7f))
  }
}
