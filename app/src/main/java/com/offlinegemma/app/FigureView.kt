package com.offlinegemma.app

import android.graphics.BitmapFactory
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.offlinegemma.app.ui.LocalExtraColors

/** "Source: <title> · licence · author" — the attribution CC BY-SA needs, with the title linked. */
fun figureSource(f: Figure, linkColor: Color) = buildAnnotatedString {
  append("Source: ")
  withLink(LinkAnnotation.Url(f.page.ifBlank { "https://commons.wikimedia.org" }, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
    append(f.title.ifBlank { "picture" })
  }
  listOf(f.license, f.credit).filter { it.isNotBlank() }.forEach { append(" · $it") }
}

/**
 * A textbook figure: the picture on a light card, "Figure N." in the brand colour, Gemma's caption,
 * a small "✓ checked" mark and the source line. Tap the picture to open it full screen.
 */
@Composable
fun FigureCard(
  query: String,
  n: Int,
  state: ChatController.FigState?,
  canFind: Boolean,
  onFind: () -> Unit,
  onOpen: (Figure) -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  val colors = Markdown.InlineColors(scheme.onSurface, scheme.surfaceContainerHigh, scheme.primary, scheme.tertiary)
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = scheme.surfaceContainerLow,
    modifier = Modifier.fillMaxWidth().animateContentSize(),
  ) {
    Column {
      when (state) {
        is ChatController.FigState.Done -> {
          val bmp = remember(state.figure.img) { runCatching { BitmapFactory.decodeFile(state.figure.img)?.asImageBitmap() }.getOrNull() }
          if (bmp != null) {
            Box(
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Color(0xFFF7F7F4)) // pictures are drawn for white paper, also in dark mode
                .clickable(onClickLabel = "Open the picture full screen", role = Role.Image) { onOpen(state.figure) }
                .padding(12.dp),
              contentAlignment = Alignment.Center,
            ) {
              Image(
                bmp,
                contentDescription = "Figure $n: ${state.figure.caption}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
              )
            }
          }
        }
        else -> Box(
          Modifier.fillMaxWidth().heightIn(min = 96.dp)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.6f))
            .padding(16.dp),
          contentAlignment = Alignment.Center,
        ) {
          when (state) {
            null -> Wait("Picture for “$query” comes when the answer is done…")
            is ChatController.FigState.Busy -> Wait(state.message)
            is ChatController.FigState.None -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text("🖼️", style = MaterialTheme.typography.headlineSmall)
              Text(state.reason.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
              if (state.retry) {
                Spacer(Modifier.size(8.dp))
                FilledTonalButton(onClick = onFind, enabled = canFind) {
                  Icon(Icons.Rounded.ImageSearch, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Find picture")
                }
              }
            }
            else -> {}
          }
        }
      }
      Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val f = (state as? ChatController.FigState.Done)?.figure
        Text(
          buildAnnotatedString {
            withStyle(SpanStyle(color = scheme.primary, fontWeight = FontWeight.Bold)) { append("Figure $n. ") }
            append(Markdown.inline(f?.caption ?: query, colors))
          },
          style = MaterialTheme.typography.bodyMedium,
        )
        if (f != null) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            if (f.checked) {
              val extra = LocalExtraColors.current
              Surface(color = extra.okContainer, contentColor = extra.ok, shape = RoundedCornerShape(50)) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Rounded.Check, null, Modifier.size(12.dp))
                  Spacer(Modifier.width(3.dp))
                  Text("checked", style = MaterialTheme.typography.labelSmall)
                }
              }
              Spacer(Modifier.width(8.dp))
            }
          }
          Text(figureSource(f, scheme.primary), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
      }
    }
  }
}

@Composable
private fun Wait(text: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    Spacer(Modifier.width(12.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
