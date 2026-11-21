package com.offlinegemma.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinegemma.app.ThemeChoice

/**
 * Brand: "night sky with an aurora". Deep indigo ink, a violet glow and a teal spark.
 * Used for the logo, the talk orb and the main buttons.
 */
object Brand {
  val Indigo = Color(0xFF4C5BD4)
  val Violet = Color(0xFF7A5AF8)
  val Teal = Color(0xFF12B5A0)
  val Ink = Color(0xFF1B1F3B)
  val aurora = Brush.linearGradient(listOf(Indigo, Violet, Teal))
}

/** Extra colours Material 3 does not have: tool chips that worked or failed. */
data class ExtraColors(val ok: Color, val okContainer: Color, val bad: Color, val badContainer: Color, val userBubble: Color, val onUserBubble: Color)

val LocalExtraColors = staticCompositionLocalOf {
  ExtraColors(Color.Green, Color.Green, Color.Red, Color.Red, Color.Blue, Color.White)
}

private val LightColors = lightColorScheme(
  primary = Color(0xFF4351C9),
  onPrimary = Color.White,
  primaryContainer = Color(0xFFE0E3FF),
  onPrimaryContainer = Color(0xFF0E1563),
  secondary = Color(0xFF5B5D72),
  onSecondary = Color.White,
  secondaryContainer = Color(0xFFE1E0F9),
  onSecondaryContainer = Color(0xFF181A2C),
  tertiary = Color(0xFF00897A),
  onTertiary = Color.White,
  tertiaryContainer = Color(0xFFB4F1E4),
  onTertiaryContainer = Color(0xFF00201B),
  background = Color(0xFFF7F7FB),
  onBackground = Color(0xFF1A1B23),
  surface = Color(0xFFF7F7FB),
  onSurface = Color(0xFF1A1B23),
  surfaceVariant = Color(0xFFE3E2EC),
  onSurfaceVariant = Color(0xFF464652),
  surfaceContainerLowest = Color.White,
  surfaceContainerLow = Color(0xFFF1F1F7),
  surfaceContainer = Color(0xFFEBEBF2),
  surfaceContainerHigh = Color(0xFFE5E5ED),
  surfaceContainerHighest = Color(0xFFDFDFE8),
  outline = Color(0xFF767684),
  outlineVariant = Color(0xFFC7C6D4),
  error = Color(0xFFBA1A1A),
  errorContainer = Color(0xFFFFDAD6),
  onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
  primary = Color(0xFFBCC2FF),
  onPrimary = Color(0xFF1A2478),
  primaryContainer = Color(0xFF333FAF),
  onPrimaryContainer = Color(0xFFE0E3FF),
  secondary = Color(0xFFC5C4DD),
  onSecondary = Color(0xFF2D2F42),
  secondaryContainer = Color(0xFF2E3046),
  onSecondaryContainer = Color(0xFFE1E0F9),
  tertiary = Color(0xFF6FDCC6),
  onTertiary = Color(0xFF00382F),
  tertiaryContainer = Color(0xFF005045),
  onTertiaryContainer = Color(0xFFB4F1E4),
  background = Color(0xFF0E1016),
  onBackground = Color(0xFFE4E1EC),
  surface = Color(0xFF0E1016),
  onSurface = Color(0xFFE4E1EC),
  surfaceVariant = Color(0xFF2A2B36),
  onSurfaceVariant = Color(0xFFC7C6D4),
  surfaceContainerLowest = Color(0xFF090B10),
  surfaceContainerLow = Color(0xFF15171F),
  surfaceContainer = Color(0xFF1A1C25),
  surfaceContainerHigh = Color(0xFF22242E),
  surfaceContainerHighest = Color(0xFF2B2D38),
  outline = Color(0xFF91909E),
  outlineVariant = Color(0xFF464652),
  error = Color(0xFFFFB4AB),
  errorContainer = Color(0xFF93000A),
  onErrorContainer = Color(0xFFFFDAD6),
)

private val AppTypography = Typography().let { t ->
  t.copy(
    displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    // Chat text: a touch bigger and airier than the default, for long answers.
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    bodyMedium = t.bodyMedium.copy(lineHeight = 21.sp),
    labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
  )
}

private val AppShapes = Shapes(
  extraSmall = RoundedCornerShape(6.dp),
  small = RoundedCornerShape(10.dp),
  medium = RoundedCornerShape(16.dp),
  large = RoundedCornerShape(24.dp),
  extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun AppTheme(theme: ThemeChoice, dynamicColor: Boolean, content: @Composable () -> Unit) {
  val dark = when (theme) {
    ThemeChoice.SYSTEM -> isSystemInDarkTheme()
    ThemeChoice.LIGHT -> false
    ThemeChoice.DARK -> true
  }
  val context = LocalContext.current
  val colors: ColorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    dark -> DarkColors
    else -> LightColors
  }
  val extra = if (dark) {
    ExtraColors(Color(0xFF7BDCA0), Color(0xFF0F3A22), Color(0xFFFFB4AB), Color(0xFF4A1512), colors.primaryContainer, colors.onPrimaryContainer)
  } else {
    ExtraColors(Color(0xFF1B7A43), Color(0xFFD5F5E0), Color(0xFFB3261E), Color(0xFFFCE4E1), colors.primary, colors.onPrimary)
  }
  androidx.compose.runtime.CompositionLocalProvider(LocalExtraColors provides extra) {
    MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
  }
}
