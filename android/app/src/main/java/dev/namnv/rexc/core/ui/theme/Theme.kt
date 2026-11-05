package dev.namnv.rexc.core.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.namnv.rexc.R
import dev.namnv.rexc.data.Settings
import dev.namnv.rexc.data.ThemeMode

/**
 * One design system in two appearances: grouped paper by day, near-black at
 * night. One blue for anything you can act on; green, amber and red only for state.
 */
internal data class Palette(
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

internal val Light = Palette(
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

internal val Dark = Palette(
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

/** The terminal look for the whole app: flat black chrome, nothing but ink on it. */
private val TerminalLook = Dark.copy(
  bg = Color(0xFF000000),
  surface = Color(0xFF0D0D0F),
  raised = Color(0xFF1A1A1D),
  key = Color(0xFF1A1A1D),
  line = Color(0xFF26262A),
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

  internal fun use(next: Palette) {
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

private const val MIN_TEXT_CONTRAST = 4.5f

/** Material You mapped onto the app's palette; state colors stay fixed so green still means "ok". */
internal fun dynamicPalette(scheme: ColorScheme, base: Palette): Palette = base.copy(
  bg = if (base.dark) scheme.surfaceContainerLowest else scheme.surfaceContainerLow,
  surface = if (base.dark) scheme.surfaceContainerLow else scheme.surfaceContainerLowest,
  raised = if (base.dark) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest,
  key = scheme.surfaceContainerHighest,
  keyOn = scheme.primaryContainer,
  line = scheme.outlineVariant,
  ink = scheme.onSurface,
  muted = scheme.onSurfaceVariant,
  accent = scheme.primary,
  onAccent = readableOn(scheme.primary, scheme.onPrimary),
  danger = scheme.error,
)

/** [preferred] if it reads on [bg], else whichever of black and white contrasts more. */
internal fun readableOn(bg: Color, preferred: Color): Color {
  if (contrast(bg, preferred) >= MIN_TEXT_CONTRAST) return preferred
  return if (contrast(bg, Color.White) >= contrast(bg, Color.Black)) Color.White else Color.Black
}

private const val LUMINANCE_OFFSET = 0.05f

/** WCAG contrast ratio between two colors. */
internal fun contrast(a: Color, b: Color): Float {
  val x = a.luminance()
  val y = b.luminance()
  return (maxOf(x, y) + LUMINANCE_OFFSET) / (minOf(x, y) + LUMINANCE_OFFSET)
}

@Composable
fun RexcTheme(settings: Settings, content: @Composable () -> Unit) {
  val systemDark = isSystemInDarkTheme()
  val dark = when (settings.theme) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.DARK, ThemeMode.TERMINAL -> true
    ThemeMode.LIGHT -> false
  }
  val context = LocalContext.current
  val dynamic = settings.dynamicColor && settings.theme != ThemeMode.TERMINAL &&
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
  val palette = when {
    settings.theme == ThemeMode.TERMINAL -> TerminalLook
    dynamic -> {
      val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      dynamicPalette(scheme, if (dark) Dark else Light)
    }
    dark -> Dark
    else -> Light
  }
  val view = LocalView.current
  SideEffect {
    Colors.use(palette)
    val window = view.context.findActivity()?.window ?: return@SideEffect
    WindowCompat.getInsetsController(window, view).apply {
      isAppearanceLightStatusBars = !dark
      isAppearanceLightNavigationBars = !dark
    }
  }
  MaterialTheme(colorScheme = palette.toScheme(), content = content)
}

private fun Palette.toScheme(): ColorScheme {
  val base = if (dark) darkColorScheme() else lightColorScheme()
  return base.copy(
    primary = accent, onPrimary = onAccent, background = bg, onBackground = ink,
    surface = surface, onSurface = ink, onSurfaceVariant = muted, outline = line,
    outlineVariant = line, surfaceContainer = raised, surfaceContainerHigh = raised,
    surfaceContainerHighest = key, surfaceVariant = key, error = danger, scrim = scrim,
  )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}
