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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offlinegemma.app.ui.Brand
import kotlin.math.cos
import kotlin.math.sin

private fun label(s: TalkController.State) = when (s) {
  TalkController.State.OFF -> ""
  TalkController.State.LISTENING -> "Listening…"
  TalkController.State.RECORDING -> "Hearing you…"
  TalkController.State.THINKING -> "Thinking…"
  TalkController.State.SPEAKING -> "Speaking…"
}

private fun hint(s: TalkController.State) = when (s) {
  TalkController.State.LISTENING -> "Just start talking"
  TalkController.State.RECORDING -> "Pause when you're done"
  TalkController.State.THINKING -> "Gemma is working on a reply"
  TalkController.State.SPEAKING -> "Talk over Gemma to interrupt"
  TalkController.State.OFF -> ""
}

/** Full-screen voice chat: an animated aurora orb, what's happening, and the reply as it's spoken. */
@Composable
fun TalkScreen(talk: TalkController, onClose: () -> Unit) {
  val view = LocalView.current
  DisposableEffect(Unit) {
    view.keepScreenOn = true
    onDispose { view.keepScreenOn = false }
  }
  val bg = Brush.verticalGradient(listOf(Color(0xFF0B0D1A), Color(0xFF141838), Color(0xFF0B2A2C)))
  Box(Modifier.fillMaxSize().background(bg).safeDrawingPadding()) {
    Column(
      Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Talk with Gemma", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.Headphones, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        Text(" Best with headphones", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
      }
      Spacer(Modifier.weight(1f))
      Orb(talk.state, talk.level, Modifier.size(260.dp))
      Spacer(Modifier.height(36.dp))
      AnimatedContent(talk.state, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "label") { s ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            label(s),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
          )
          Text(hint(s), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f))
        }
      }
      Spacer(Modifier.height(20.dp))
      Text(
        talk.error ?: talk.caption,
        style = MaterialTheme.typography.bodyLarge,
        color = if (talk.error != null) Color(0xFFFFB4AB) else Color.White.copy(alpha = 0.8f),
        textAlign = TextAlign.Center,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.heightIn(min = 96.dp),
      )
      Spacer(Modifier.weight(1f))
      FilledTonalIconButton(
        onClick = onClose,
        modifier = Modifier.size(72.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.White.copy(alpha = 0.14f), contentColor = Color.White),
      ) { Icon(Icons.Rounded.Close, "End voice chat", Modifier.size(32.dp)) }
      Text("End", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
    }
  }
}

/**
 * The talk orb. Breathes while listening, swells with your voice while recording,
 * orbits while thinking, and sends out soft rings while speaking.
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
  Canvas(modifier.semantics { contentDescription = "Voice activity: ${label(state)}" }) {
    val r0 = size.minDimension / 2 * 0.62f
    val scale = when (state) {
      TalkController.State.RECORDING -> 1f + voice * 0.35f
      TalkController.State.LISTENING -> breathe
      else -> 1f
    }
    val r = r0 * scale
    // Outer glow.
    drawCircle(Brush.radialGradient(listOf(b.copy(alpha = 0.45f), Color.Transparent), center = center, radius = r * 1.6f), radius = r * 1.6f)
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
      Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), center = Offset(center.x - r * 0.3f, center.y - r * 0.35f), radius = r * 0.9f),
      radius = r,
    )
    // Thinking: three little moons circling.
    if (state == TalkController.State.THINKING) {
      for (k in 0..2) {
        val ang = Math.toRadians((orbit + k * 120).toDouble())
        val p = Offset(center.x + (r * 1.25f) * cos(ang).toFloat(), center.y + (r * 1.25f) * sin(ang).toFloat())
        drawCircle(Color.White.copy(alpha = 0.85f - k * 0.2f), radius = 6.dp.toPx(), center = p)
      }
    }
  }
}
