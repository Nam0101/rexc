package dev.namnv.rexc.presentation.settings

import androidx.compose.runtime.Immutable
import dev.namnv.rexc.data.OpenMode
import dev.namnv.rexc.data.Settings
import dev.namnv.rexc.data.ThemeMode
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import uniffi.rexc_ffi.TerminalTheme

/** UI state owned by [SettingsViewModel] and rendered by [SettingsScreen]. */
@Immutable
data class SettingsUiState(
  val settings: Settings = Settings(),
  val version: String = "",
  /** Material You needs Android 12; below that the switch is shown but off-limits. */
  val dynamicColorSupported: Boolean = true,
  /** The built-in terminal themes, for the "Terminal colors" picker. */
  val terminalThemes: ImmutableList<TerminalTheme> = persistentListOf(),
)

sealed interface SettingsUiEvent {
  data class ThemeChanged(val mode: ThemeMode) : SettingsUiEvent
  data class DynamicColorChanged(val on: Boolean) : SettingsUiEvent
  data class FontChanged(val key: String) : SettingsUiEvent
  data class FontSizeChanged(val size: Float) : SettingsUiEvent
  data class OpenModeChanged(val mode: OpenMode) : SettingsUiEvent
  data class LocalEchoChanged(val on: Boolean) : SettingsUiEvent
  data class KeepScreenOnChanged(val on: Boolean) : SettingsUiEvent
  data class ConfirmClosePaneChanged(val on: Boolean) : SettingsUiEvent
  data class HapticsChanged(val on: Boolean) : SettingsUiEvent

  /** A built-in theme name, or null for the server's colors. */
  data class TerminalThemeChanged(val name: String?) : SettingsUiEvent

  /** Sent only after the owner authenticated; the route asks first. */
  data class AppLockChanged(val on: Boolean) : SettingsUiEvent
}

/** The settings after one edit. */
fun Settings.reduce(event: SettingsUiEvent): Settings = when (event) {
  is SettingsUiEvent.ThemeChanged -> copy(theme = event.mode)
  is SettingsUiEvent.DynamicColorChanged -> copy(dynamicColor = event.on)
  is SettingsUiEvent.FontChanged -> copy(font = event.key)
  is SettingsUiEvent.FontSizeChanged -> copy(fontSize = event.size)
  is SettingsUiEvent.OpenModeChanged -> copy(openMode = event.mode)
  is SettingsUiEvent.LocalEchoChanged -> copy(localEcho = event.on)
  is SettingsUiEvent.KeepScreenOnChanged -> copy(keepScreenOn = event.on)
  is SettingsUiEvent.ConfirmClosePaneChanged -> copy(confirmClosePane = event.on)
  is SettingsUiEvent.HapticsChanged -> copy(haptics = event.on)
  is SettingsUiEvent.AppLockChanged -> copy(appLock = event.on)
  is SettingsUiEvent.TerminalThemeChanged -> copy(terminalTheme = event.name)
}
