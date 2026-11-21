package com.offlinegemma.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.offlinegemma.app.ui.Brand

/** The four-point sparkle from the app icon, filling the box. */
fun DrawScope.sparkle(brush: Brush, scale: Float = 1f) {
  val w = size.minDimension * scale
  val c = Offset(size.width / 2, size.height / 2)
  val r = w / 2
  val k = r * 0.2f // waist of the star
  val p = Path().apply {
    moveTo(c.x, c.y - r)
    lineTo(c.x + k, c.y - k)
    lineTo(c.x + r, c.y)
    lineTo(c.x + k, c.y + k)
    lineTo(c.x, c.y + r)
    lineTo(c.x - k, c.y + k)
    lineTo(c.x - r, c.y)
    lineTo(c.x - k, c.y - k)
    close()
  }
  drawPath(p, brush)
}

/** App logo: the sparkle with a slow, soft aurora glow behind it. */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier, animate: Boolean = true) {
  val t = rememberInfiniteTransition(label = "brand")
  val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "spin")
  val pulse by t.animateFloat(0.9f, 1.05f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "pulse")
  Canvas(modifier.size(size).semantics { contentDescription = "Jonaki logo, a firefly spark" }) {
    val glow = Brush.radialGradient(
      listOf(Brand.Violet.copy(alpha = 0.35f), Brand.Teal.copy(alpha = 0.12f), Color.Transparent),
      center = center, radius = this.size.minDimension / 2,
    )
    drawCircle(glow, radius = this.size.minDimension / 2 * (if (animate) pulse else 1f))
    rotate(if (animate) spin * 0.05f else 0f) {
      sparkle(Brush.linearGradient(listOf(Brand.Indigo, Brand.Violet, Brand.Teal), start = Offset.Zero, end = Offset(this.size.width, this.size.height)), 0.62f)
    }
  }
}

/** Icon in a soft round tile + title + description. */
@Composable
fun FeatureRow(icon: ImageVector, title: String, body: String, tint: Color = MaterialTheme.colorScheme.primary) {
  Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Box(
      Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)),
      contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = tint) }
    Column(Modifier.padding(top = 2.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/** A rounded, softly tinted card. */
@Composable
fun SoftCard(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceContainerLow, content: @Composable () -> Unit) {
  Surface(modifier, shape = MaterialTheme.shapes.large, color = color, content = content)
}
