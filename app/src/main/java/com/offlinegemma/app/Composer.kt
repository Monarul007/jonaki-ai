package com.offlinegemma.app

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

/** The bottom of the chat: attachment previews, the message box, attach, talk and send buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Composer(
  chat: ChatController,
  text: String,
  onText: (String) -> Unit,
  onSend: () -> Unit,
  onTalk: () -> Unit,
  sheetOpen: Boolean,
  onSheet: (Boolean) -> Unit,
) {
  val context = LocalContext.current
  var cameraUri by remember { mutableStateOf<Uri?>(null) }

  val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
    uris.forEach { chat.addImage(it) }
  }
  val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
    if (ok) cameraUri?.let { chat.addImage(it) }
  }
  val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
    uris.forEach { chat.addFile(it) }
  }

  if (sheetOpen) {
    ModalBottomSheet(onDismissRequest = { onSheet(false) }) {
      Column(Modifier.padding(bottom = 24.dp)) {
        Text("Add to your message", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
        SheetItem(Icons.Rounded.PhotoCamera, "Camera", "Take a photo") {
          onSheet(false)
          val f = File(context.cacheDir, "camera").apply { mkdirs() }.let { File(it, "photo_${System.currentTimeMillis()}.jpg") }
          val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
          cameraUri = uri
          camera.launch(uri)
        }
        SheetItem(Icons.Rounded.PhotoLibrary, "Photos", "Pick pictures or screenshots") {
          onSheet(false)
          photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        SheetItem(Icons.Rounded.Description, "Files", "PDF, Word, PowerPoint, Excel, CSV, text, code") {
          onSheet(false)
          files.launch(Attachments.DOC_MIME_TYPES)
        }
        val clipImage = remember { clipboardImage(context) }
        SheetItem(
          Icons.Rounded.ContentPaste,
          "Paste screenshot",
          if (clipImage != null) "Use the picture you copied" else "Copy a picture first, then come back",
          enabled = clipImage != null,
        ) {
          onSheet(false)
          clipImage?.let { chat.addImage(it) }
        }
      }
    }
  }

  Surface(color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
      AnimatedVisibility(chat.draft.isNotEmpty() || chat.importing) {
        LazyRow(
          Modifier.fillMaxWidth().padding(top = 8.dp),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(chat.draft, key = { it.path }) { a -> DraftChip(a, onRemove = { chat.removeDraft(a) }) }
          if (chat.importing) item { Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
        }
      }
      Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
      ) {
        IconButton(onClick = { onSheet(true) }, modifier = Modifier.size(56.dp), enabled = !chat.busy) {
          Icon(Icons.Rounded.Add, "Add photo or file")
        }
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f)) {
          TextField(
            value = text,
            onValueChange = onText,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            placeholder = { Text(if (chat.ready) "Ask Gemma anything" else "Gemma is waking up…") },
            maxLines = 5,
            colors = TextFieldDefaults.colors(
              focusedContainerColor = Color.Transparent,
              unfocusedContainerColor = Color.Transparent,
              disabledContainerColor = Color.Transparent,
              focusedIndicatorColor = Color.Transparent,
              unfocusedIndicatorColor = Color.Transparent,
              disabledIndicatorColor = Color.Transparent,
            ),
            textStyle = MaterialTheme.typography.bodyLarge,
          )
        }
        Spacer(Modifier.size(8.dp))
        when {
          chat.busy -> FilledIconButton(
            onClick = { chat.stop() },
            modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
          ) { Icon(Icons.Rounded.Stop, "Stop the answer") }
          text.isBlank() && chat.draft.isEmpty() -> FilledTonalIconButton(
            onClick = onTalk,
            enabled = chat.ready,
            modifier = Modifier.size(56.dp),
          ) { Icon(Icons.Rounded.GraphicEq, "Talk mode: speak with Gemma") }
          else -> FilledIconButton(
            onClick = onSend,
            enabled = chat.ready && !chat.importing,
            modifier = Modifier.size(56.dp),
          ) { Icon(Icons.AutoMirrored.Rounded.Send, "Send") }
        }
      }
    }
  }
}

@Composable
private fun SheetItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, enabled: Boolean = true, onClick: () -> Unit) {
  ListItem(
    headlineContent = { Text(title) },
    supportingContent = { Text(body) },
    leadingContent = {
      Box(Modifier.size(44.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape, modifier = Modifier.size(44.dp)) {
          Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
      }
    },
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    modifier = Modifier
      .padding(horizontal = 8.dp)
      .clip(MaterialTheme.shapes.medium)
      .clickable(enabled = enabled, onClick = onClick)
      .alpha(if (enabled) 1f else 0.45f),
    tonalElevation = 0.dp,
  )
}

private fun clipboardImage(context: Context): Uri? = runCatching {
  val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  val clip = cm.primaryClip ?: return null
  val desc = clip.description
  (0 until clip.itemCount).firstNotNullOfOrNull { i ->
    clip.getItemAt(i).uri?.takeIf { uri ->
      (0 until desc.mimeTypeCount).any { desc.getMimeType(it).startsWith("image/") } ||
        context.contentResolver.getType(uri)?.startsWith("image/") == true
    }
  }
}.getOrNull()

/** A picture thumbnail or a file chip waiting to be sent, with an × to remove it. */
@Composable
private fun DraftChip(a: Attachment, onRemove: () -> Unit) {
  Box {
    AttachmentView(a, small = true)
    Surface(
      onClick = onRemove,
      shape = CircleShape,
      color = MaterialTheme.colorScheme.inverseSurface,
      contentColor = MaterialTheme.colorScheme.inverseOnSurface,
      modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(22.dp),
    ) { Icon(Icons.Rounded.Close, "Remove ${a.name}", Modifier.padding(3.dp)) }
  }
}

/** Shows one attachment: a picture thumbnail, or a chip with the file name. */
@Composable
fun AttachmentView(a: Attachment, small: Boolean = false) {
  when (a.kind) {
    Attachment.Kind.IMAGE -> {
      val bmp = remember(a.path) {
        runCatching {
          val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
          android.graphics.BitmapFactory.decodeFile(a.path, opts)?.asImageBitmap()
        }.getOrNull()
      }
      if (bmp != null) {
        Image(
          bmp,
          contentDescription = "Picture: ${a.name}",
          contentScale = ContentScale.Crop,
          modifier = Modifier.size(if (small) 64.dp else 160.dp).clip(MaterialTheme.shapes.medium),
        )
      }
    }
    else -> Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
      Row(Modifier.heightIn(min = if (small) 64.dp else 48.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (a.kind == Attachment.Kind.AUDIO) Icons.Rounded.Mic else Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Spacer(Modifier.size(8.dp))
        Column {
          Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(max = 180.dp))
          if (a.kind == Attachment.Kind.DOC) {
            Text("${a.chars} characters", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
          }
        }
      }
    }
  }
}
