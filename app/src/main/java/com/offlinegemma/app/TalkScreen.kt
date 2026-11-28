package com.offlinegemma.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinegemma.app.ui.Brand
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private fun label(s: TalkController.State, waking: Boolean) = when {
  waking -> "Waking up..."
  s == TalkController.State.OFF -> "Ready when you are"
  s == TalkController.State.LISTENING -> "Listening"
  s == TalkController.State.RECORDING -> "Hearing you"
  s == TalkController.State.THINKING -> "Thinking"
  else -> "Speaking"
}

private fun hint(s: TalkController.State, waking: Boolean) = when {
  waking -> "The model is loading — this takes a few seconds."
  s == TalkController.State.OFF -> "Tap end when you're finished."
  s == TalkController.State.LISTENING -> "Speak naturally — pause when you're done."
  s == TalkController.State.RECORDING -> "I'll send it as soon as you pause."
  s == TalkController.State.THINKING -> "Working on a reply · tap the orb to stop."
  else -> "Tap the orb to interrupt."
}

/** Full-screen voice chat: an aurora orb, a clear status, and the reply as it is spoken. */
@Composable
fun TalkScreen(talk: TalkController, onClose: () -> Unit) {
  val view = LocalView.current
  DisposableEffect(Unit) {
    view.keepScreenOn = true
    onDispose { view.keepScreenOn = false }
  }
  val bg = Brush.verticalGradient(
    listOf(Color(0xFF06081A), Color(0xFF101638), Color(0xFF07292C)),
  )
  Box(Modifier.fillMaxSize().background(bg).safeDrawingPadding()) {
    Stars()
    // Two soft aurora glows, dithered by the Canvas gradient so no contour lines show.
    Canvas(Modifier.fillMaxSize()) {
      drawCircle(
        Brush.radialGradient(
          listOf(Brand.Violet.copy(alpha = 0.22f), Brand.Violet.copy(alpha = 0f)),
          center = Offset(size.width * 0.85f, size.height * 0.08f),
          radius = size.width * 0.9f,
        ),
        radius = size.width * 0.9f,
      )
      drawCircle(
        Brush.radialGradient(
          listOf(Brand.Teal.copy(alpha = 0.14f), Brand.Teal.copy(alpha = 0f)),
          center = Offset(size.width * 0.1f, size.height * 0.82f),
          radius = size.width * 0.8f,
        ),
        radius = size.width * 0.8f,
      )
    }

    Column(
      Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(onClick = onClose, label = "End voice chat") {
          Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            "JONAKI",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.sp),
            color = Color.White.copy(alpha = 0.5f),
          )
          Text("Voice", style = MaterialTheme.typography.titleLarge, color = Color.White)
        }
        OnDeviceChip()
      }

      Spacer(Modifier.height(10.dp))

      Box(Modifier.fillMaxWidth().height(216.dp), contentAlignment = Alignment.Center) {
        Orb(
          talk.state,
          talk.level,
          Modifier.size(186.dp).clip(CircleShape).clickable(
            enabled = talk.state == TalkController.State.SPEAKING ||
              talk.state == TalkController.State.THINKING,
            onClickLabel = "Interrupt Jonaki AI",
            role = Role.Button,
          ) { talk.interrupt() },
        )
      }

      val statusKey = if (talk.waking) "WAKING" else talk.state.name
      AnimatedContent(
        targetState = statusKey,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
        label = "status",
      ) { key ->
        val waking = key == "WAKING"
        val s = if (waking) TalkController.State.OFF else TalkController.State.valueOf(key)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            label(s, waking),
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
          )
          Text(
            hint(s, waking),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.58f),
            textAlign = TextAlign.Center,
          )
        }
      }

      Spacer(Modifier.height(12.dp))

      if (!talk.onHeadphones) {
        Row(
          Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            Icons.Rounded.Headphones,
            null,
            tint = Color.White.copy(alpha = 0.42f),
            modifier = Modifier.size(14.dp),
          )
          Spacer(Modifier.width(6.dp))
          Text(
            "Headphones keep the reply out of the microphone.",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.42f),
          )
        }
      }

      Spacer(Modifier.height(10.dp))

      ReplyCard(talk, Modifier.weight(1f))

      Spacer(Modifier.height(12.dp))

      Surface(
        onClick = onClose,
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.10f),
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
      ) {
        Row(
          Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.Rounded.Close, null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(10.dp))
          Text("End conversation", style = MaterialTheme.typography.titleSmall)
        }
      }
    }
  }
}

@Composable
private fun ReplyCard(talk: TalkController, modifier: Modifier) {
  val scroll = rememberScrollState()
  LaunchedEffect(talk.transcript) {
    if (scroll.value >= scroll.maxValue - 32) scroll.scrollTo(scroll.maxValue)
  }
  Surface(
    shape = RoundedCornerShape(26.dp),
    color = Color.White.copy(alpha = 0.06f),
    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)),
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          Modifier.size(7.dp).clip(CircleShape)
            .background(if (talk.state == TalkController.State.SPEAKING) Brand.Teal else Brand.Violet),
        )
        Spacer(Modifier.width(8.dp))
        Text(
          "Jonaki",
          style = MaterialTheme.typography.labelLarge,
          color = Color.White.copy(alpha = 0.75f),
          fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        if (talk.state == TalkController.State.SPEAKING) {
          Icon(
            Icons.Rounded.RecordVoiceOver,
            null,
            tint = Brand.Teal,
            modifier = Modifier.size(16.dp),
          )
        }
      }
      Spacer(Modifier.height(10.dp))
      Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
          talk.error?.let { message ->
            Text(
              message,
              style = MaterialTheme.typography.bodyMedium,
              color = Color(0xFFFFB4AB),
            )
            Spacer(Modifier.height(10.dp))
          }
          val working = talk.state == TalkController.State.THINKING && talk.transcript.isBlank()
          if (working) {
            // Nothing to read yet, so show the work instead of a dead placeholder. This is the same
            // list the chat screen puts behind "Worked through it", but voice has no panel to open:
            // the card is the only place a waiting reader can look.
            WorkSteps(talk.steps, talk.doing)
          } else {
            Text(
              talk.transcript.ifBlank {
                if (talk.waking) "One moment while I wake up." else "Ask me anything — I'm listening."
              },
              style = MaterialTheme.typography.bodyLarge,
              color = Color.White.copy(alpha = if (talk.transcript.isBlank()) 0.45f else 0.86f),
            )
          }
        }
      }
      // Rate what was just said. Voice replies are the likeliest to be wrong (the question came in
      // through ASR), and this is the only way to mark one — the same verdict the chat screen stores.
      // Placed AFTER the text, where it belongs: a verdict on a reply cannot come before the reply.
      talk.answerLine?.let { line ->
        if (line.rating != null || talk.state != TalkController.State.THINKING) {
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            IconButton(
              onClick = { talk.rateCurrent(Rating.UP) },
              modifier = Modifier.size(40.dp),
              enabled = talk.state != TalkController.State.THINKING,
            ) {
              Icon(
                Icons.Rounded.ThumbUp, "This answer was helpful", Modifier.size(17.dp),
                tint = if (line.rating == Rating.UP) Brand.Teal else Color.White.copy(alpha = 0.45f),
              )
            }
            IconButton(
              onClick = { talk.rateCurrent(Rating.DOWN) },
              modifier = Modifier.size(40.dp),
              enabled = talk.state != TalkController.State.THINKING,
            ) {
              Icon(
                Icons.Rounded.ThumbDown, "This answer needs improvement", Modifier.size(17.dp),
                tint = if (line.rating == Rating.DOWN) Color(0xFFFFB4AB) else Color.White.copy(alpha = 0.45f),
              )
            }
            Spacer(Modifier.weight(1f))
          }
        }
      }
    }
  }
}

/**
 * The steps behind a spoken answer, worded the way the work actually happens:
 *
 *   ✓ Checked your study files · 2 passages
 *   ✓ Decided: search for "…"
 *   ✓ Searched: …
 *   ● Reading quora.com…              (still running)
 *
 * Grey and small, so the answer still owns the card once it starts. Ticks turn to a spinner while
 * something is happening and a cross if it failed — the same marks the chat panel uses.
 */
@Composable
private fun WorkSteps(steps: List<ToolUse>, doing: String?) {
  val faint = Color.White.copy(alpha = 0.62f)
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (steps.isEmpty() && doing == null) {
      Text(
        "Thinking it through…",
        style = MaterialTheme.typography.bodyLarge,
        color = Color.White.copy(alpha = 0.45f),
      )
      return@Column
    }
    // Oldest first, but only the last few: this is orientation, not a log.
    for (t in steps.takeLast(6)) {
      Row(verticalAlignment = Alignment.Top) {
        StepGlyph(t.ok, faint)
        Spacer(Modifier.width(8.dp))
        Column {
          Text(spokenStepLabel(t), style = MaterialTheme.typography.labelMedium, color = faint)
          val detail = stepDetail(t)
          if (detail.isNotBlank()) {
            Text(
              detail,
              style = MaterialTheme.typography.labelSmall,
              color = faint.copy(alpha = 0.6f),
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
    // The live activity, when there is one: the step list only shows what has already finished.
    doing?.let {
      Row(verticalAlignment = Alignment.Top) {
        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Brand.Teal)
        Spacer(Modifier.width(8.dp))
        Text(it, style = MaterialTheme.typography.labelMedium, color = Brand.Teal.copy(alpha = 0.9f))
      }
    }
  }
}

@Composable
private fun StepGlyph(ok: Boolean?, color: Color) {
  when (ok) {
    null -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = color)
    true -> Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = Brand.Teal)
    false -> Icon(Icons.Rounded.Close, null, Modifier.size(14.dp), tint = Color(0xFFFFB4AB))
  }
}

/** A step in words a person would use, not the tool's internal name. */
private fun spokenStepLabel(t: ToolUse): String = when (t.name) {
  "knowledge" -> "Checked your study files"
  "plan" -> "Decided: ${t.detail}"
  "ocr" -> if (t.ok == false) "Couldn't read the picture" else "Read the picture"
  "web_search" -> "Searched the web"
  "web_fetch" -> "Read a page"
  "calculate" -> if (t.ok == true) "Calculated it" else "Working out the numbers"
  "run_js" -> if (t.ok == false) "Code test failed" else "Ran the code"
  else -> t.name.replace('_', ' ')
}

/** The short proof under a step: what was searched, which page, how many passages. */
private fun stepDetail(t: ToolUse): String = when (t.name) {
  "knowledge" -> t.detail
  "web_search" -> t.detail
  "web_fetch" -> t.detail.removePrefix("https://").removePrefix("www.")
  "calculate" -> if (t.ok == true) "${t.detail} = ${t.result.take(40)}" else t.detail
  "ocr" -> ""
  else -> ""
}

@Composable
private fun RoundButton(onClick: () -> Unit, label: String, content: @Composable () -> Unit) {
  Surface(
    onClick = onClick,
    shape = CircleShape,
    color = Color.White.copy(alpha = 0.08f),
    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
    modifier = Modifier.size(44.dp).semantics { contentDescription = label },
  ) {
    Box(contentAlignment = Alignment.Center) { content() }
  }
}

@Composable
private fun OnDeviceChip() {
  Surface(
    shape = RoundedCornerShape(50),
    color = Color.White.copy(alpha = 0.07f),
    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
  ) {
    Row(
      Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(Modifier.size(6.dp).clip(CircleShape).background(Brand.Teal))
      Spacer(Modifier.width(6.dp))
      Text("On-device", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
    }
  }
}

/** A quiet starfield, so the voice screen belongs to the same night sky as Home. */
@Composable
private fun Stars() {
  val dots = remember {
    val r = Random(11)
    List(26) { Triple(r.nextFloat(), r.nextFloat() * 0.62f, 0.8f + r.nextFloat() * 1.6f) }
  }
  Canvas(Modifier.fillMaxSize()) {
    dots.forEach { (x, y, rad) ->
      drawCircle(Color.White.copy(alpha = 0.18f), radius = rad.dp.toPx(), center = Offset(size.width * x, size.height * y))
    }
  }
}

/**
 * The talk orb. Breathes while listening, swells with your voice while recording, orbits while
 * thinking, and sends out soft rings while speaking.
 */
@Composable
private fun Orb(state: TalkController.State, level: Float, modifier: Modifier) {
  val t = rememberInfiniteTransition(label = "orb")
  val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "spin")
  val breathe by t.animateFloat(0.92f, 1.04f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "breathe")
  val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "ring")
  val orbit by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "orbit")
  val voice by animateFloatAsState(if (state == TalkController.State.RECORDING) level else 0f, spring(stiffness = 400f), label = "voice")

  val (a, b, c) = when (state) {
    TalkController.State.RECORDING -> Triple(Brand.Violet, Color(0xFFB06AF8), Brand.Indigo)
    TalkController.State.SPEAKING -> Triple(Brand.Indigo, Brand.Violet, Brand.Teal)
    TalkController.State.THINKING -> Triple(Color(0xFF3A4BC4), Brand.Indigo, Color(0xFF2A7F9E))
    else -> Triple(Brand.Teal, Color(0xFF3FD3C0), Brand.Indigo)
  }
  Canvas(modifier.semantics { contentDescription = "Voice activity: ${label(state, false)}" }) {
    val r0 = size.minDimension / 2 * 0.60f
    val scale = when (state) {
      TalkController.State.RECORDING -> 1f + voice * 0.30f
      TalkController.State.LISTENING -> breathe
      else -> 1f
    }
    val r = r0 * scale

    // Outer glow.
    drawCircle(
      Brush.radialGradient(listOf(b.copy(alpha = 0.42f), b.copy(alpha = 0f)), center = center, radius = r * 1.7f),
      radius = r * 1.7f,
    )
    // Microphone level: three soft rings that follow the voice.
    if (state == TalkController.State.RECORDING) {
      for (k in 0..2) {
        val p = (voice * 0.6f + k * 0.18f)
        drawCircle(
          c.copy(alpha = (1f - p) * 0.45f),
          radius = r * (1.02f + p * 0.75f),
          style = Stroke(width = 2.dp.toPx()),
        )
      }
    }
    // Speaking: rings flowing outward.
    if (state == TalkController.State.SPEAKING) {
      for (k in 0..2) {
        val p = (ring + k / 3f) % 1f
        drawCircle(c.copy(alpha = (1f - p) * 0.5f), radius = r * (1f + p * 0.6f), style = Stroke(width = 3.dp.toPx()))
      }
    }
    // The orb: a turning sweep of aurora colours with a soft highlight.
    rotate(spin) {
      drawCircle(Brush.sweepGradient(listOf(a, b, c, a), center = center), radius = r)
    }
    drawCircle(
      Brush.radialGradient(
        listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0f)),
        center = Offset(center.x - r * 0.3f, center.y - r * 0.35f),
        radius = r * 0.9f,
      ),
      radius = r,
    )
    // Thinking: three little moons circling.
    if (state == TalkController.State.THINKING) {
      for (k in 0..2) {
        val ang = (orbit + k * 120) * 0.017453292f
        val p = Offset(center.x + (r * 1.25f) * cos(ang), center.y + (r * 1.25f) * sin(ang))
        drawCircle(Color.White.copy(alpha = 0.85f - k * 0.2f), radius = 6.dp.toPx(), center = p)
      }
    }
  }
}