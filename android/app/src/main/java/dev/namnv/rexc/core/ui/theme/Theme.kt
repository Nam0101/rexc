package dev.namnv.rexc.core.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.namnv.rexc.R

/**
 * One design system in two appearances: grouped paper by day, near-black at
 * night. One blue for anything you can act on; green, amber and red only for state.
 */
private data class Palette(
  val dark: Boolean,
  val bg: Color,
  val surface: Color,
  val raised: Color,
  val key: Color,
  val keyOn: Color,
  val line: Color,
  val ink: Color,
  val muted: Color,
  val accent: Color,
  val onAccent: Color,
  val ok: Color,
  val amber: Color,
  val danger: Color,
  val badge: Color,
  val badgeInk: Color,
  val scrim: Color,
)

private val Light = Palette(
  dark = false,
  bg = Color(0xFFF2F2F7),
  surface = Color(0xFFFFFFFF),
  raised = Color(0xFFFFFFFF),
  key = Color(0xFFE9E9EF),
  keyOn = Color(0xFFDCE7FB),
  line = Color(0xFFD9D9DE),
  ink = Color(0xFF1C1C1E),
  muted = Color(0xFF63636A),
  accent = Color(0xFF0A62E8),
  onAccent = Color(0xFFFFFFFF),
  ok = Color(0xFF137A3D),
  amber = Color(0xFF9A6300),
  danger = Color(0xFFC7162B),
  badge = Color(0xFF1C1C1E),
  badgeInk = Color(0xFF30D158),
  scrim = Color(0x40000000),
)

private val Dark = Palette(
  dark = true,
  bg = Color(0xFF0A0A0B),
  surface = Color(0xFF161618),
  raised = Color(0xFF242427),
  key = Color(0xFF232326),
  keyOn = Color(0xFF12345C),
  line = Color(0xFF2C2C2E),
  ink = Color(0xFFF2F2F7),
  muted = Color(0xFF98989F),
  accent = Color(0xFF0A84FF),
  onAccent = Color(0xFFFFFFFF),
  ok = Color(0xFF30D158),
  amber = Color(0xFFFFD60A),
  danger = Color(0xFFFF453A),
  badge = Color(0xFF2C2C2E),
  badgeInk = Color(0xFF30D158),
  scrim = Color(0x99000000),
)

/** The palette in use, read through state so colors recompose on a light/dark switch. */
object Colors {
  private var palette by mutableStateOf(Light)
  val bg: Color get() = palette.bg
  val surface: Color get() = palette.surface
  val raised: Color get() = palette.raised
  val key: Color get() = palette.key
  val keyOn: Color get() = palette.keyOn
  val line: Color get() = palette.line
  val ink: Color get() = palette.ink
  val muted: Color get() = palette.muted
  val accent: Color get() = palette.accent
  val onAccent: Color get() = palette.onAccent
  val ok: Color get() = palette.ok
  val amber: Color get() = palette.amber
  val danger: Color get() = palette.danger
  val badge: Color get() = palette.badge
  val badgeInk: Color get() = palette.badgeInk
  val scrim: Color get() = palette.scrim
  val isDark: Boolean get() = palette.dark

  internal fun use(dark: Boolean) {
    val next = if (dark) Dark else Light
    if (palette != next) palette = next
  }
}

object Space {
  val xs = 4.dp
  val s = 8.dp
  val m = 12.dp
  val l = 16.dp
  val xl = 20.dp
}

object Radius {
  val chip = 12.dp
  val card = 16.dp
  val dock = 22.dp
}

val MonoFamily = FontFamily(
  Font(R.font.jetbrains_mono_regular),
  Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

@Composable
fun RexcTheme(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  val p = if (dark) Dark else Light
  val scheme = if (dark) {
    darkColorScheme(
      primary = p.accent, onPrimary = p.onAccent, background = p.bg, onBackground = p.ink,
      surface = p.surface, onSurface = p.ink, onSurfaceVariant = p.muted, outline = p.line,
      outlineVariant = p.line, surfaceContainer = p.raised, surfaceContainerHigh = p.raised,
      surfaceContainerHighest = p.key, surfaceVariant = p.key, error = p.danger, scrim = p.scrim,
    )
  } else {
    lightColorScheme(
      primary = p.accent, onPrimary = p.onAccent, background = p.bg, onBackground = p.ink,
      surface = p.surface, onSurface = p.ink, onSurfaceVariant = p.muted, outline = p.line,
      outlineVariant = p.line, surfaceContainer = p.raised, surfaceContainerHigh = p.raised,
      surfaceContainerHighest = p.key, surfaceVariant = p.key, error = p.danger, scrim = p.scrim,
    )
  }
  val view = LocalView.current
  SideEffect {
    Colors.use(dark)
    val window = view.context.findActivity()?.window ?: return@SideEffect
    WindowCompat.getInsetsController(window, view).apply {
      isAppearanceLightStatusBars = !dark
      isAppearanceLightNavigationBars = !dark
    }
  }
  MaterialTheme(colorScheme = scheme, content = content)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}
