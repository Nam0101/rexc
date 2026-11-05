package dev.namnv.rexc.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.namnv.rexc.core.ui.theme.Colors
import dev.namnv.rexc.core.ui.theme.MonoFamily
import dev.namnv.rexc.core.ui.theme.Radius
import dev.namnv.rexc.core.ui.theme.Space
import dev.namnv.rexc.data.FONT_FIRA
import dev.namnv.rexc.data.FONT_JETBRAINS
import dev.namnv.rexc.data.FONT_SYSTEM
import dev.namnv.rexc.data.MAX_FONT_SIZE
import dev.namnv.rexc.data.MIN_FONT_SIZE
import dev.namnv.rexc.data.OpenMode
import dev.namnv.rexc.data.ThemeMode

private const val REPO_URL = "https://github.com/Nam0101/rexc"
private const val SIZE_STEP = 0.5f

// Whole and half points between the ends: 7, 7.5 ... 28 has 41 stops inside the range.
private const val SIZE_STOPS = ((MAX_FONT_SIZE - MIN_FONT_SIZE) / SIZE_STEP).toInt() - 1

private val fonts = listOf(
  FONT_JETBRAINS to "JetBrains Mono",
  FONT_FIRA to "Fira Code",
  FONT_SYSTEM to "System monospace",
)

private val themes = listOf(
  Triple(ThemeMode.SYSTEM, "System", "Follow the phone's light or dark setting"),
  Triple(ThemeMode.LIGHT, "Light", null),
  Triple(ThemeMode.DARK, "Dark", null),
  Triple(ThemeMode.TERMINAL, "Terminal", "Flat black everywhere, like the grid"),
)

/** App preferences, grouped the way you reach for them. Stateless. */
@Composable
fun SettingsScreen(
  state: SettingsUiState,
  onEvent: (SettingsUiEvent) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = Colors.bg,
    contentWindowInsets = WindowInsets(0),
  ) { pad ->
    Column(
      Modifier.fillMaxSize().padding(pad).statusBarsPadding().navigationBarsPadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = Space.l),
      verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = Space.xs)) {
        IconButton(onClick = onBack) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Colors.ink)
        }
        Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Colors.ink)
      }
      AppearanceSection(state, onEvent)
      TerminalSection(state, onEvent)
      FeedbackSection(state, onEvent)
      AboutSection(state.version)
    }
  }
}

@Composable
private fun AppearanceSection(state: SettingsUiState, onEvent: (SettingsUiEvent) -> Unit) {
  val s = state.settings
  Section("Appearance", Icons.Default.Palette) {
    Text("Theme", fontSize = 13.sp, color = Colors.muted, modifier = Modifier.padding(start = Space.l, top = Space.m))
    themes.forEach { (mode, label, detail) ->
      RadioRow(label, detail, selected = s.theme == mode) { onEvent(SettingsUiEvent.ThemeChanged(mode)) }
    }
    Divider()
    val dynamicOn = s.dynamicColor && state.dynamicColorSupported && s.theme != ThemeMode.TERMINAL
    SwitchRow(
      title = "Dynamic color",
      detail = when {
        !state.dynamicColorSupported -> "Needs Android 12 or newer"
        s.theme == ThemeMode.TERMINAL -> "Off in the Terminal theme"
        else -> "Take the accent and surfaces from your wallpaper"
      },
      checked = dynamicOn,
      enabled = state.dynamicColorSupported && s.theme != ThemeMode.TERMINAL,
      onChange = { onEvent(SettingsUiEvent.DynamicColorChanged(it)) },
    )
    Divider()
    Text("Font", fontSize = 13.sp, color = Colors.muted, modifier = Modifier.padding(start = Space.l, top = Space.m))
    fonts.forEach { (key, label) ->
      RadioRow(label, detail = null, selected = s.font == key) { onEvent(SettingsUiEvent.FontChanged(key)) }
    }
    FontSizeRow(s.fontSize, previewFamily(s.font)) { onEvent(SettingsUiEvent.FontSizeChanged(it)) }
  }
}

/** Fira Code has no bundled file yet, so its preview shows the default mono. */
private fun previewFamily(key: String): FontFamily = if (key == FONT_SYSTEM) FontFamily.Monospace else MonoFamily

@Composable
private fun FontSizeRow(size: Float, family: FontFamily, onCommit: (Float) -> Unit) {
  var live by remember(size) { mutableFloatStateOf(size) }
  Column(Modifier.padding(horizontal = Space.l).padding(bottom = Space.m)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Size", color = Colors.ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
      Text("$live pt", color = Colors.muted, fontSize = 13.sp, fontFamily = MonoFamily)
    }
    Slider(
      value = live,
      onValueChange = { live = (it / SIZE_STEP).toInt() * SIZE_STEP },
      onValueChangeFinished = { onCommit(live) },
      valueRange = MIN_FONT_SIZE..MAX_FONT_SIZE,
      steps = SIZE_STOPS,
    )
    Text(
      "\$ ls -la\ndrwxr-xr-x  rexc  src\n-rw-r--r--  rexc  Cargo.toml",
      fontFamily = family,
      fontSize = live.sp,
      color = Colors.ink,
      modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.chip)).background(Colors.bg).padding(Space.m),
    )
  }
}

@Composable
private fun TerminalSection(state: SettingsUiState, onEvent: (SettingsUiEvent) -> Unit) {
  val s = state.settings
  Section("Terminal", Icons.Default.Terminal) {
    RadioRow(
      label = "Fit this screen",
      detail = "Opening a pane resizes it to your phone, like taking the keyboard.",
      selected = s.openMode == OpenMode.FIT,
    ) { onEvent(SettingsUiEvent.OpenModeChanged(OpenMode.FIT)) }
    RadioRow(
      label = "Mirror the desktop",
      detail = "Watch at the desktop's size and leave its layout alone. Take over from the pane's menu.",
      selected = s.openMode == OpenMode.MIRROR,
    ) { onEvent(SettingsUiEvent.OpenModeChanged(OpenMode.MIRROR)) }
    Divider()
    SwitchRow(
      title = "Local echo",
      detail = "Show what you type before the server confirms it.",
      checked = s.localEcho,
      onChange = { onEvent(SettingsUiEvent.LocalEchoChanged(it)) },
    )
    SwitchRow(
      title = "Keep screen on",
      detail = "Stop the display from sleeping while a terminal is open.",
      checked = s.keepScreenOn,
      onChange = { onEvent(SettingsUiEvent.KeepScreenOnChanged(it)) },
    )
    SwitchRow(
      title = "Confirm before closing a pane",
      detail = "Ask first, so a stray tap does not kill a running program.",
      checked = s.confirmClosePane,
      onChange = { onEvent(SettingsUiEvent.ConfirmClosePaneChanged(it)) },
    )
  }
}

@Composable
private fun FeedbackSection(state: SettingsUiState, onEvent: (SettingsUiEvent) -> Unit) {
  Section("Feedback", Icons.Default.Vibration) {
    SwitchRow(
      title = "Haptics",
      detail = "A short tick on keys, selection and long presses.",
      checked = state.settings.haptics,
      onChange = { onEvent(SettingsUiEvent.HapticsChanged(it)) },
    )
  }
}

@Composable
private fun AboutSection(version: String) {
  val uri = LocalUriHandler.current
  Section("About", Icons.Default.Info) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m)) {
      Text("Version", color = Colors.ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
      Text(version, color = Colors.muted, fontSize = 16.sp, fontFamily = MonoFamily)
    }
    Divider()
    Column(Modifier.fillMaxWidth().clickable { uri.openUri(REPO_URL) }.padding(Space.l)) {
      Text("Source code", color = Colors.accent, fontSize = 16.sp)
      Text("github.com/Nam0101/rexc", color = Colors.muted, fontSize = 12.sp)
    }
  }
}

/** A titled group: an icon and a small heading over one rounded surface of rows. */
@Composable
private fun Section(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
  Column {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(start = Space.xs, bottom = Space.s),
    ) {
      Icon(icon, contentDescription = null, tint = Colors.muted, modifier = Modifier.size(16.dp))
      Text(
        title,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = Colors.muted,
        modifier = Modifier.padding(start = Space.s),
      )
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Colors.surface), content = content)
  }
}

@Composable
private fun Divider() {
  HorizontalDivider(color = Colors.line, modifier = Modifier.padding(horizontal = Space.l))
}

@Composable
private fun SwitchRow(
  title: String,
  detail: String,
  checked: Boolean,
  onChange: (Boolean) -> Unit,
  enabled: Boolean = true,
) {
  Row(
    Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }
      .padding(horizontal = Space.l, vertical = Space.m),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f).padding(end = Space.m)) {
      Text(title, color = if (enabled) Colors.ink else Colors.muted, fontSize = 16.sp)
      Text(detail, color = Colors.muted, fontSize = 12.sp)
    }
    Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
  }
}

@Composable
private fun RadioRow(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Space.l, vertical = Space.s),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = null)
    Column(Modifier.padding(start = Space.m)) {
      Text(label, color = Colors.ink, fontSize = 16.sp)
      if (detail != null) Text(detail, color = Colors.muted, fontSize = 12.sp)
    }
  }
}
