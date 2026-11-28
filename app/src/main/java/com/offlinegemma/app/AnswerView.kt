package com.offlinegemma.app

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinegemma.app.ui.Brand
import com.offlinegemma.app.ui.LocalExtraColors

fun copyToClipboard(context: Context, text: String) {
  val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  cm.setPrimaryClip(ClipData.newPlainText("Jonaki AI", text))
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
  chat: ChatController? = null,
  onOpen: (ViewItem) -> Unit = {},
  onLongPress: () -> Unit = {},
) {
  val context = LocalContext.current
  // Full-width reply (no bubble), a small "Gemma" label on top; long-press opens the actions sheet.
  Column(
    Modifier.fillMaxWidth().combinedClickable(
      interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
      indication = null,
      onClick = {},
      onLongClick = onLongPress,
      onLongClickLabel = "Message actions",
    ),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Avatar(thinking = !line.done)
      Spacer(Modifier.width(8.dp))
      Text("Jonaki AI", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (line.tools.isNotEmpty() || line.thought.isNotBlank() || (!line.done && chat?.status != null)) {
      WorkPanel(line, chat?.status)
    }
    if (line.text.isEmpty() && !line.done) {
      Shimmer(
        chat?.queuePosition(line.id)?.let { ahead ->
          if (ahead <= 0) "Waiting for its turn…" else "Waiting… $ahead ahead"
        } ?: "Thinking…",
      )
    } else {
      AnswerBody(line, onDemoError, onFix, canAct, chat, onOpen)
    }
    line.error?.let {
      Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
        Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
      }
    }
    line.runOutput?.let { RunOutput(it) }
    if (line.sources.isNotEmpty()) Sources(line.sources)
    if (line.done) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.offset(x = (-12).dp)) {
        SmallAction(Icons.Rounded.ContentCopy, "Copy reply") { copyToClipboard(context, Markdown.plain(line.text)) }
        if (newest) SmallAction(Icons.Rounded.Refresh, "Regenerate reply", enabled = canAct, onClick = onRegenerate)
        val reading = speaker.readingId == line.id
        SmallAction(
          if (reading) Icons.Rounded.StopCircle else Icons.AutoMirrored.Rounded.VolumeUp,
          if (reading) "Stop reading" else "Read aloud",
        ) { speaker.readAloud(line.id, line.text) }
        // Explain: why this answer is right and the idea behind it. On demand, because it is a whole
        // model round-trip and most answers do not need one. Once asked, the text is kept on the line
        // and simply shown, so tapping again costs nothing.
        SmallAction(
          Icons.Rounded.Lightbulb,
          if (line.explain.isBlank()) "Explain this answer" else "Explanation shown below",
          enabled = line.explain.isNotBlank() || (canAct && chat != null),
        ) { chat?.explain(line.id) }
        // Rating: the only record of which answers were actually wrong, so they can be reviewed later.
        SmallAction(
          Icons.Rounded.ThumbUp,
          "This answer was helpful",
          enabled = chat != null,
          tint = if (line.rating == Rating.UP) Brand.Teal else null,
        ) { chat?.rate(line.id, Rating.UP) }
        SmallAction(
          Icons.Rounded.ThumbDown,
          "This answer needs improvement",
          enabled = chat != null,
          tint = if (line.rating == Rating.DOWN) MaterialTheme.colorScheme.error else null,
        ) { chat?.rate(line.id, Rating.DOWN) }
        line.seconds?.let { secs ->
          Text(
            "%.1fs · %d words".format(secs, line.text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
          )
        }
      }
      // The explanation, once asked for: kept in the line so it survives a reload.
      if (line.explain.isNotBlank()) ExplainPanel(line.explain)
    }
    // Follow-ups: only on the newest reply, after it finished, in one sideways-scrolling row.
    androidx.compose.animation.AnimatedVisibility(
      visible = line.done && newest && canAct && Tutor.wantsFollowups(line.text),
      enter = androidx.compose.animation.fadeIn(tween(200)) + androidx.compose.animation.expandVertically(tween(200)),
      exit = androidx.compose.animation.fadeOut(tween(150)) + androidx.compose.animation.shrinkVertically(tween(150)),
    ) {
      FollowupRow(Tutor.nextQuestions(line.text), onFollowup, onButton, onCheckFacts)
    }
  }
}

/**
 * Suggested questions (filled) then tutor actions (outlined, with icons), in ONE row that scrolls
 * sideways and snaps. It reaches the screen edges (the chat list has 16dp side padding) and fades
 * out on the right to show there is more.
 */
@Composable
private fun FollowupRow(
  questions: List<String>,
  onFollowup: (String) -> Unit,
  onButton: (String, String) -> Unit,
  onCheckFacts: () -> Unit,
) {
  val context = LocalContext.current
  val state = androidx.compose.foundation.lazy.rememberLazyListState()
  val fadeColor = MaterialTheme.colorScheme.surface
  val bleed = 16.dp
  androidx.compose.foundation.lazy.LazyRow(
    state = state,
    flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = bleed, end = bleed + 24.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier
      .layout { m, c ->
        // Full bleed: 16dp wider on each side than the reply column.
        val extra = bleed.roundToPx() * 2
        val p = m.measure(c.copy(minWidth = c.maxWidth + extra, maxWidth = c.maxWidth + extra))
        layout(c.maxWidth, p.height) { p.place(-extra / 2, 0) }
      }
      .drawWithContent {
        drawContent()
        if (state.canScrollForward) {
          val w = 40.dp.toPx()
          drawRect(
            androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(fadeColor.copy(alpha = 0f), fadeColor), startX = size.width - w, endX = size.width),
            topLeft = androidx.compose.ui.geometry.Offset(size.width - w, 0f),
            size = androidx.compose.ui.geometry.Size(w, size.height),
          )
        }
      }
      .semantics { contentDescription = "Suggested follow-ups" },
  ) {
    items(questions.size) { i ->
      val q = questions[i]
      Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
          .heightIn(min = 44.dp)
          .widthIn(max = 260.dp)
          .clip(RoundedCornerShape(50))
          .combinedClickable(
            onClickLabel = "Ask: $q",
            onClick = { onFollowup(q) },
            onLongClick = { android.widget.Toast.makeText(context, q, android.widget.Toast.LENGTH_LONG).show() },
          ),
      ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
          Text(q, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
    }
    val actions = Tutor.BUTTONS.map { (label, prompt) -> label to { onButton(label, prompt) } } + (Tutor.CHECK_FACTS to onCheckFacts)
    items(actions.size) { i ->
      val (label, run) = actions[i]
      OutlinedButton(
        onClick = run,
        modifier = Modifier.heightIn(min = 44.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
        shape = RoundedCornerShape(50),
      ) { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
    }
  }
}

@Composable
private fun Avatar(thinking: Boolean) {
  val t = rememberInfiniteTransition(label = "avatar")
  val a by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
  Box(
    Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
    contentAlignment = Alignment.Center,
  ) {
    androidx.compose.foundation.Canvas(Modifier.size(14.dp).alpha(if (thinking) a else 1f)) {
      sparkle(Brand.aurora)
    }
  }
}

/** Three soft bars that shimmer while Gemma is thinking (before the first word). */
@Composable
private fun Shimmer(label: String = "Thinking…") {
  val t = rememberInfiniteTransition(label = "shimmer")
  val x by t.animateFloat(-300f, 900f, infiniteRepeatable(tween(1300)), label = "x")
  val base = MaterialTheme.colorScheme.surfaceContainerHigh
  val shine = MaterialTheme.colorScheme.surfaceContainerHighest
  val brush = Brush.linearGradient(listOf(base, shine, base), start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x + 300f, 0f))
  Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    for (w in listOf(1f, 0.85f, 0.55f)) {
      Box(Modifier.fillMaxWidth(w).height(12.dp).clip(RoundedCornerShape(6.dp)).background(brush))
    }
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/**
 * The explanation behind an answer: why it is right, the rule that makes it right, and the idea
 * underneath. Sits inside a quiet box so it reads as commentary on the answer, not more answer.
 *
 * The model is allowed to decline (see ChatController.explain) — "sorry, I can't explain this simply"
 * is a legitimate outcome and is shown as-is, rather than a plausible-sounding invention.
 */
@Composable
private fun ExplainPanel(text: String) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Lightbulb, null, Modifier.size(15.dp), tint = Brand.Teal)
        Spacer(Modifier.width(6.dp))
        Text(
          "Why this answer",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
        text.trim(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

@Composable
private fun SmallAction(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  enabled: Boolean = true,
  tint: androidx.compose.ui.graphics.Color? = null,
  onClick: () -> Unit,
) {
  IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
    Icon(
      icon, label, Modifier.size(19.dp),
      // A rated thumb is tinted, so the chosen verdict is visible at a glance — the same cue a video
      // site gives when you have already voted.
      tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * Every step behind an answer, in the order it happened: the decision ("search: … · calculate: …"),
 * the model's own reasoning, then each tool call with its result. Collapsed to one quiet line while
 * reading, the way a coding agent shows its work. The answer below stays the main thing.
 */
@Composable
fun WorkPanel(line: ChatLine, doing: String? = null) {
  // Open while the answer is being worked out, closed once it is done.
  //
  // It was always closed, so during a turn the reader saw one changing summary line and had to expand
  // it to see what was happening — and by then the step was already over. Watching the work is the
  // point while it runs; afterwards it is noise beside a finished answer, so it folds itself away.
  var open by remember(line.id) { mutableStateOf(!line.done) }
  LaunchedEffect(line.done) { if (line.done) open = false }
  val uri = LocalUriHandler.current
  val muted = MaterialTheme.colorScheme.onSurfaceVariant
  // A finished answer must never show a spinner: once the reply is done, nothing is still running,
  // whatever a step's `ok` was left as.
  val running = if (line.done) null else line.tools.lastOrNull { it.ok == null }
  val steps = line.tools.size + if (line.thought.isNotBlank()) 1 else 0
  Column {
    Row(
      Modifier
        .clip(RoundedCornerShape(8.dp))
        .clickable(onClickLabel = if (open) "Hide steps" else "Show steps") { open = !open }
        .heightIn(min = 36.dp)
        .padding(end = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      StepMark(running?.ok ?: if (line.tools.isNotEmpty() && line.tools.all { it.ok == false }) false else true, muted)
      Spacer(Modifier.width(8.dp))
      Text(
        when {
          running != null -> toolLabel(running)
          // While the turn is still running, the live activity is the honest label. The tool list only
          // holds steps that have FINISHED, so showing a summary of it during prep named work that was
          // already over and hid the work in progress.
          !line.done && doing != null -> doing
          line.thought.isNotBlank() -> "Worked through it · $steps steps"
          else -> toolSummary(line.tools)
        },
        style = MaterialTheme.typography.labelMedium,
        color = muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      Text(
        if (open) "hide" else "show",
        style = MaterialTheme.typography.labelSmall,
        color = muted.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = 8.dp),
      )
      Icon(
        if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
        null,
        Modifier.padding(start = 2.dp).size(18.dp),
        tint = muted,
      )
    }
    androidx.compose.animation.AnimatedVisibility(visible = open) {
      Row(Modifier.padding(start = 7.dp, top = 2.dp).height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
        Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          // The model's reasoning first when it reasoned, in the words it actually used.
          if (line.thought.isNotBlank()) {
            // `ok = null` means "still running", so passing it unconditionally left this row
            // spinning forever after the answer had already been served.
            StepRow(if (line.done) true else null, "Reasoning", line.thought.trim().take(4000), muted, null) { }
          }
          for (t in line.tools) {
            val link = t.detail.takeIf { t.name == "web_fetch" && it.startsWith("http") }
            StepRow(if (line.done) (t.ok ?: true) else t.ok, toolLabel(t), t.result.trim(), muted, t.ms) {
              if (link != null) runCatching { uri.openUri(link) }
            }
          }
        }
      }
    }
  }
}

/** One step: mark, label with its duration, and (when there is one) the detail it produced. */
@Composable
private fun StepRow(ok: Boolean?, label: String, detail: String, muted: androidx.compose.ui.graphics.Color, ms: Long? = null, onTap: () -> Unit) {
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .clickable(enabled = true, onClickLabel = "Step detail") { onTap() }
      .padding(vertical = 3.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      StepMark(ok, muted)
      Spacer(Modifier.width(8.dp))
      Text(label, style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
      // A step that took seconds is usually the one that made the answer feel slow.
      if (ms != null && ms >= 400) {
        Text(
          " · " + fmtStepTime(ms),
          style = MaterialTheme.typography.labelSmall,
          color = muted.copy(alpha = 0.6f),
        )
      }
    }
    if (detail.isNotBlank()) {
      Text(
        // Kept short: the panel is for orientation, not for reading a whole page again.
        detail.replace(Regex("\\s+"), " ").take(220),
        style = MaterialTheme.typography.bodySmall,
        color = muted.copy(alpha = 0.75f),
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 24.dp, top = 1.dp),
      )
    }
  }
}

/** "820 ms", "4.2 s", "1 min 12 s" — short enough to sit after a step label. */
internal fun fmtStepTime(ms: Long): String = when {
  ms < 1000 -> "$ms ms"
  ms < 60_000 -> "%.1f s".format(ms / 1000.0)
  else -> "${ms / 60_000} min ${(ms % 60_000) / 1000} s"
}

/** ✓ worked, ✕ failed, spinner = still running. Grey, so the answer stays the main thing. */
@Composable
private fun StepMark(ok: Boolean?, color: androidx.compose.ui.graphics.Color) {
  val extra = LocalExtraColors.current
  when (ok) {
    null -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = color)
    true -> Icon(Icons.Rounded.Check, "worked", Modifier.size(16.dp), tint = extra.ok)
    false -> Icon(Icons.Rounded.Close, "failed", Modifier.size(16.dp), tint = extra.bad)
  }
}

/** All steps in one line: "Checked your study files · searched the web · read 3 pages". */
internal fun toolSummary(tools: List<ToolUse>): String {
  val parts = mutableListOf<String>()
  fun count(name: String) = tools.count { it.name == name }
  if (count("knowledge") > 0) parts += "study files"
  if (count("ocr") > 0) parts += if (tools.any { it.name == "ocr" && it.ok == true }) "read the picture" else "couldn't read the picture"
  if (count("read_document") > 0) parts += "read the file"
  if (count("calculate") > 0) parts += "calculated"
  count("web_search").let { if (it == 1) parts += "searched the web" else if (it > 1) parts += "searched $it times" }
  val pages = tools.count { it.name == "web_fetch" && it.ok == true }
  if (pages > 0) parts += if (pages == 1) "read 1 page" else "read $pages pages"
  else if (count("web_fetch") > 0) parts += "couldn't open the pages"
  if (count("run_js") + count("run_python") > 0) parts += "ran the code"
  val known = setOf("plan", "knowledge", "ocr", "read_document", "calculate", "web_search", "web_fetch", "run_js", "run_python")
  tools.filter { it.name !in known }.map { it.name }.distinct().forEach { parts += it }
  return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
}

private fun toolLabel(t: ToolUse) = when (t.name) {
  "plan" -> "Decided: ${t.detail}"
  "web_search" -> "Searched: ${t.detail}"
  "web_fetch" -> "Read: ${t.detail.removePrefix("https://").removePrefix("www.")}"
  "ocr" -> if (t.ok == false) "Couldn't read the picture" else "Read the picture"
  "run_js" -> if (t.ok == false) "Code test failed" else "Ran code"
  "calculate" -> if (t.ok == true) "Calculated: ${t.detail} = ${t.result}" else "Calculated: ${t.detail}"
  "run_python" -> if (t.ok == false) "Python failed" else "Ran Python"
  "read_document" -> "Read: ${t.detail}"
  "knowledge" -> "Checked your study files: ${t.detail}"
  else -> t.name
}

/** The answer's sources in ONE sideways-scrolling row, numbered like its citations [1], [2]. */
@Composable
private fun Sources(sources: List<Source>) {
  val uri = LocalUriHandler.current
  androidx.compose.foundation.lazy.LazyRow(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    modifier = Modifier.semantics { contentDescription = "Sources" },
  ) {
    items(sources.size) { i ->
      val s = sources[i]
      val host = runCatching { java.net.URI(s.url).host?.removePrefix("www.") }.getOrNull()
      AssistChip(
        onClick = { runCatching { uri.openUri(s.url) } },
        label = {
          Text(
            "${i + 1}  " + (host ?: s.title.ifBlank { s.url }),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
          )
        },
        leadingIcon = { Icon(Icons.Rounded.Language, null, Modifier.size(14.dp)) },
      )
    }
  }
}

@Composable
private fun RunOutput(text: String) {
  Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
    Column(Modifier.padding(12.dp)) {
      val head = text.substringBefore('\n')
      Text(head, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(text.substringAfter('\n', ""), fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
  }
}

/** The reply's Markdown, drawn block by block. */
@Composable
private fun AnswerBody(line: ChatLine, onDemoError: (String) -> Unit, onFix: () -> Unit, canAct: Boolean, chat: ChatController?, onOpen: (ViewItem) -> Unit) {
  // Citations [1], [2] become links to the sources listed under the answer.
  val blocks = remember(line.text, line.sources) { Markdown.parse(Tools.linkCitations(line.text, line.sources)) }
  val scheme = MaterialTheme.colorScheme
  val colors = Markdown.InlineColors(scheme.onSurface, scheme.surfaceContainerHigh, scheme.primary, scheme.tertiary)
  val body = MaterialTheme.typography.bodyLarge
  // The answer itself, set apart from the steps above it by a hairline and a slightly larger
  // type: with a work panel open, the reader must never wonder where the answer begins.
  if (line.thought.isNotBlank() || line.tools.isNotEmpty()) {
    androidx.compose.material3.HorizontalDivider(
      color = scheme.outlineVariant.copy(alpha = 0.5f),
      modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
    )
  }
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
      is Markdown.Block.Chem -> ChemBlock(b.text, colors)
      is Markdown.Block.Table -> TableBlock(b, colors)
      Markdown.Block.Rule -> androidx.compose.material3.HorizontalDivider(color = scheme.outlineVariant)
      is Markdown.Block.Figure -> {
        val k = "${chat?.chatId}|${line.id}|${b.n}"
        val live = chat?.figStates?.get(k)
        val saved = remember(k, line.done, line.text) { chat?.figState(line, b.n, b.query) }
        val st = live ?: saved
        FigureCard(
          query = b.query,
          n = b.n,
          state = st,
          canFind = canAct,
          onFind = { chat?.findPicture(line.id, b.n) },
          onOpen = { f ->
            onOpen(
              ViewItem(
                path = f.img,
                name = f.img.substringAfterLast('/'),
                title = "Figure ${b.n} · ${f.title}",
                caption = androidx.compose.ui.text.buildAnnotatedString {
                  append("Figure ${b.n}. ")
                  append(Markdown.inline(f.caption, colors))
                  append("\n")
                  append(figureSource(f, androidx.compose.ui.graphics.Color(0xFF9FA8FF)))
                },
                page = f.page,
              ),
            )
          },
        )
      }
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
              "⚠ The demo hit an error: $err" + if (line.autoFixing) " — asking Jonaki AI for a fix…" else "",
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
  Surface(
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Text(
      Markdown.inline("$$" + tex + "$$", colors),
      Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
      textAlign = TextAlign.Center,
      style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, fontSize = 20.sp, lineHeight = 28.sp),
    )
  }
}

/** A reaction on its own card: real arrows, real subscripts, a quiet colour accent. */
@Composable
private fun ChemBlock(raw: String, colors: Markdown.InlineColors) {
  val scheme = MaterialTheme.colorScheme
  Surface(
    shape = MaterialTheme.shapes.medium,
    color = scheme.surfaceContainerLow,
    border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(Modifier.width(3.dp).height(30.dp).background(scheme.primary, RoundedCornerShape(2.dp)))
      Spacer(Modifier.width(12.dp))
      Text(
        Markdown.inline(Markdown.chem(raw), colors),
        modifier = Modifier.weight(1f),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, fontSize = 20.sp, lineHeight = 30.sp),
      )
    }
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
