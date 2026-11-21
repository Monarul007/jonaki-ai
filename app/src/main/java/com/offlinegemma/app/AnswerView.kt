package com.offlinegemma.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinegemma.app.ui.Brand
import com.offlinegemma.app.ui.LocalExtraColors

fun copyToClipboard(context: Context, text: String) {
  val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  cm.setPrimaryClip(ClipData.newPlainText("Gemma", text))
  // Android 13+ shows its own "Copied" bubble.
  if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

/** One reply from Gemma: avatar, formatted text, tools, demos, actions and follow-ups. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Answer(
  line: ChatLine,
  newest: Boolean,
  canAct: Boolean,
  speaker: Speaker,
  onDemoError: (String) -> Unit,
  onFix: () -> Unit,
  onRegenerate: () -> Unit,
  onFollowup: (String) -> Unit,
  onButton: (String, String) -> Unit,
  onCheckFacts: () -> Unit,
) {
  val context = LocalContext.current
  Row(Modifier.fillMaxWidth()) {
    Avatar(thinking = !line.done)
    Spacer(Modifier.width(10.dp))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      if (line.tools.isNotEmpty()) ToolChips(line.tools)
      if (line.text.isEmpty() && !line.done) {
        Shimmer()
      } else {
        AnswerBody(line, onDemoError, onFix, canAct)
      }
      line.error?.let {
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
          Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
        }
      }
      line.runOutput?.let { RunOutput(it) }
      if (line.sources.isNotEmpty()) Sources(line.sources)
      if (line.done) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          SmallAction(Icons.Rounded.ContentCopy, "Copy reply") { copyToClipboard(context, Markdown.plain(line.text)) }
          if (newest) SmallAction(Icons.Rounded.Refresh, "Regenerate reply", enabled = canAct, onClick = onRegenerate)
          val reading = speaker.readingId == line.id
          SmallAction(
            if (reading) Icons.Rounded.StopCircle else Icons.AutoMirrored.Rounded.VolumeUp,
            if (reading) "Stop reading" else "Read aloud",
          ) { speaker.readAloud(line.id, line.text) }
          line.seconds?.let { secs ->
            Text(
              "%.1fs · %d words".format(secs, line.text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(start = 6.dp),
            )
          }
        }
        if (newest && canAct && Tutor.wantsFollowups(line.text)) {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            for (q in Tutor.nextQuestions(line.text)) {
              SuggestionChip(onClick = { onFollowup(q) }, label = { Text(q, style = MaterialTheme.typography.bodyMedium) })
            }
            for ((label, prompt) in Tutor.BUTTONS) {
              AssistChip(
                onClick = { onButton(label, prompt) },
                label = { Text(label) },
                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                border = null,
              )
            }
            AssistChip(
              onClick = onCheckFacts,
              label = { Text(Tutor.CHECK_FACTS) },
              colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
              border = null,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun Avatar(thinking: Boolean) {
  val t = rememberInfiniteTransition(label = "avatar")
  val a by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
  Box(
    Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
    contentAlignment = Alignment.Center,
  ) {
    androidx.compose.foundation.Canvas(Modifier.size(18.dp).alpha(if (thinking) a else 1f)) {
      sparkle(Brand.aurora)
    }
  }
}

/** Three soft bars that shimmer while Gemma is thinking (before the first word). */
@Composable
private fun Shimmer() {
  val t = rememberInfiniteTransition(label = "shimmer")
  val x by t.animateFloat(-300f, 900f, infiniteRepeatable(tween(1300)), label = "x")
  val base = MaterialTheme.colorScheme.surfaceContainerHigh
  val shine = MaterialTheme.colorScheme.surfaceContainerHighest
  val brush = Brush.linearGradient(listOf(base, shine, base), start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x + 300f, 0f))
  Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    for (w in listOf(1f, 0.85f, 0.55f)) {
      Box(Modifier.fillMaxWidth(w).height(12.dp).clip(RoundedCornerShape(6.dp)).background(brush))
    }
    Text("Thinking…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
  IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
    Icon(icon, label, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolChips(tools: List<ToolUse>) {
  val extra = LocalExtraColors.current
  FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    for (t in tools) {
      val (bg, fg) = when (t.ok) {
        null -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
        true -> extra.okContainer to extra.ok
        false -> extra.badContainer to extra.bad
      }
      Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
          when (t.ok) {
            null -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = fg)
            true -> Icon(Icons.Rounded.Check, "worked", Modifier.size(14.dp))
            false -> Icon(Icons.Rounded.Close, "failed", Modifier.size(14.dp))
          }
          Spacer(Modifier.width(6.dp))
          Text(
            toolLabel(t),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 240.dp),
          )
        }
      }
    }
  }
}

private fun toolLabel(t: ToolUse) = when (t.name) {
  "web_search" -> "Searched: ${t.detail}"
  "web_fetch" -> "Read: ${t.detail.removePrefix("https://").removePrefix("www.")}"
  "run_js" -> if (t.ok == false) "Code test failed" else "Ran code"
  "run_python" -> if (t.ok == false) "Python failed" else "Ran Python"
  "read_document" -> "Read: ${t.detail}"
  else -> t.name
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Sources(sources: List<Source>) {
  val uri = LocalUriHandler.current
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("Sources", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      for (s in sources) {
        AssistChip(
          onClick = { runCatching { uri.openUri(s.url) } },
          label = { Text(s.title.ifBlank { s.url }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) },
          leadingIcon = { Icon(Icons.Rounded.Language, null, Modifier.size(16.dp)) },
        )
      }
    }
  }
}

@Composable
private fun RunOutput(text: String) {
  Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
    Column(Modifier.padding(12.dp)) {
      Text("Output", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
  }
}

/** The reply's Markdown, drawn block by block. */
@Composable
private fun AnswerBody(line: ChatLine, onDemoError: (String) -> Unit, onFix: () -> Unit, canAct: Boolean) {
  val blocks = remember(line.text) { Markdown.parse(line.text) }
  val scheme = MaterialTheme.colorScheme
  val colors = Markdown.InlineColors(scheme.onSurface, scheme.surfaceContainerHigh, scheme.primary, scheme.tertiary)
  val body = MaterialTheme.typography.bodyLarge
  for (b in blocks) {
    when (b) {
      is Markdown.Block.Para -> Text(Markdown.inline(b.text, colors), style = body)
      is Markdown.Block.Heading -> Text(
        Markdown.inline(b.text, colors),
        style = if (b.level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
      )
      is Markdown.Block.Item -> Row {
        Text(if (b.ordered) "${b.number}." else "•", style = body, color = scheme.primary, modifier = Modifier.width(if (b.ordered) 26.dp else 18.dp))
        Text(Markdown.inline(b.text, colors), style = body)
      }
      is Markdown.Block.Quote -> Row(Modifier.fillMaxWidth()) {
        Box(Modifier.width(3.dp).height(24.dp).background(scheme.primary.copy(alpha = 0.5f)))
        Text(Markdown.inline(b.text, colors), style = body, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
      }
      is Markdown.Block.Code -> CodeBlock(b.lang, b.body)
      is Markdown.Block.Math -> MathBlock(b.tex, colors)
      is Markdown.Block.Table -> TableBlock(b, colors)
      Markdown.Block.Rule -> androidx.compose.material3.HorizontalDivider(color = scheme.outlineVariant)
      is Markdown.Block.Svg -> Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)) { DemoView(b.source, onError = {}) }
      is Markdown.Block.Demo -> when {
        !b.closed -> Pending("⚙️ Building an interactive demo… (${b.html.lines().size} lines so far)")
        !line.done -> Pending("⚙️ Demo ready — it appears when the answer finishes.")
        else -> {
          Surface(shape = MaterialTheme.shapes.medium, color = androidx.compose.ui.graphics.Color.White, shadowElevation = 1.dp) {
            Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)) { DemoView(b.html, onError = onDemoError) }
          }
          line.demoError?.let { err ->
            Text(
              "⚠ The demo hit an error: $err" + if (line.autoFixing) " — asking Gemma for a fix…" else "",
              color = scheme.error,
              style = MaterialTheme.typography.bodySmall,
            )
            if (!line.autoFixing) {
              OutlinedButton(onClick = onFix, enabled = canAct) {
                Icon(Icons.Rounded.Build, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Fix this demo")
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun Pending(text: String) {
  Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
      CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
      Spacer(Modifier.width(12.dp))
      Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
  }
}

@Composable
private fun CodeBlock(lang: String, code: String) {
  val context = LocalContext.current
  Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
    Column {
      Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(lang.ifEmpty { "code" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        IconButton(onClick = { copyToClipboard(context, code) }, modifier = Modifier.size(44.dp)) {
          Icon(Icons.Rounded.ContentCopy, "Copy code", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      Text(
        code,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        softWrap = false,
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
      )
    }
  }
}

@Composable
private fun MathBlock(tex: String, colors: Markdown.InlineColors) {
  Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Text(
      Markdown.inline("$$" + tex + "$$", colors),
      Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
      style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
    )
  }
}

@Composable
private fun TableBlock(t: Markdown.Block.Table, colors: Markdown.InlineColors) {
  val scheme = MaterialTheme.colorScheme
  val cols = maxOf(t.head.size, t.rows.maxOfOrNull { it.size } ?: 0)
  Surface(shape = MaterialTheme.shapes.small, color = scheme.surfaceContainerLow) {
    // Same width for every cell in a column, so rows line up; wide tables scroll sideways inside the box.
    val cellW = if (cols <= 2) 160.dp else 130.dp
    Column(Modifier.horizontalScroll(rememberScrollState())) {
      Row(Modifier.background(scheme.surfaceContainerHigh)) {
        for (c in 0 until cols) {
          Text(
            Markdown.inline(t.head.getOrElse(c) { "" }, colors),
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(cellW).padding(horizontal = 12.dp, vertical = 8.dp),
          )
        }
      }
      for (r in t.rows) {
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
          for (c in 0 until cols) {
            Text(
              Markdown.inline(r.getOrElse(c) { "" }, colors),
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.width(cellW).padding(horizontal = 12.dp, vertical = 8.dp),
            )
          }
        }
      }
    }
  }
}
