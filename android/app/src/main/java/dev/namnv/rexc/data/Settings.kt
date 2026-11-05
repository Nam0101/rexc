package dev.namnv.rexc.data

import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** How a pane opens: FIT takes the pane over at this screen's size, MIRROR watches at the desktop's size. */
enum class OpenMode { FIT, MIRROR }

/** TERMINAL dresses the whole app like the terminal: dark and flat, no dynamic color. */
enum class ThemeMode { SYSTEM, DARK, LIGHT, TERMINAL }

/** Everything the Settings screen edits. Font keys: "jetbrains", "fira", "system". */
@Immutable
data class Settings(
  val font: String = FONT_JETBRAINS,
  val fontSize: Float = DEFAULT_FONT_SIZE,
  val localEcho: Boolean = true,
  val haptics: Boolean = true,
  val keepScreenOn: Boolean = true,
  val openMode: OpenMode = OpenMode.FIT,
  val theme: ThemeMode = ThemeMode.SYSTEM,
  val dynamicColor: Boolean = false,
  val confirmClosePane: Boolean = true,
  /** Ask for the fingerprint or screen lock on launch and after 30 s away. */
  val appLock: Boolean = false,
  /** A built-in terminal theme by name; null draws each server's own colors. A host can override it. */
  val terminalTheme: String? = null,
)

const val FONT_JETBRAINS = "jetbrains"
const val FONT_FIRA = "fira"
const val FONT_SYSTEM = "system"
const val MIN_FONT_SIZE = 7f
const val MAX_FONT_SIZE = 28f
private const val DEFAULT_FONT_SIZE = 12.5f

private val FONT = stringPreferencesKey("font")
private val FONT_SIZE = floatPreferencesKey("font_size")
private val LOCAL_ECHO = booleanPreferencesKey("local_echo")
private val HAPTICS = booleanPreferencesKey("haptics")
private val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
private val OPEN_MODE = stringPreferencesKey("open_mode")
private val THEME = stringPreferencesKey("theme")
private val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
private val CONFIRM_CLOSE_PANE = booleanPreferencesKey("confirm_close_pane")
private val APP_LOCK = booleanPreferencesKey("app_lock")
private val TERMINAL_THEME = stringPreferencesKey("terminal_theme")

/** Missing or unreadable values fall back to the defaults, so a new field never breaks old installs. */
fun Preferences.toSettings(): Settings {
  val d = Settings()
  return Settings(
    font = this[FONT] ?: d.font,
    fontSize = (this[FONT_SIZE] ?: d.fontSize).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE),
    localEcho = this[LOCAL_ECHO] ?: d.localEcho,
    haptics = this[HAPTICS] ?: d.haptics,
    keepScreenOn = this[KEEP_SCREEN_ON] ?: d.keepScreenOn,
    openMode = enumOrNull<OpenMode>(this[OPEN_MODE]) ?: d.openMode,
    theme = enumOrNull<ThemeMode>(this[THEME]) ?: d.theme,
    dynamicColor = this[DYNAMIC_COLOR] ?: d.dynamicColor,
    confirmClosePane = this[CONFIRM_CLOSE_PANE] ?: d.confirmClosePane,
    appLock = this[APP_LOCK] ?: d.appLock,
    terminalTheme = this[TERMINAL_THEME] ?: d.terminalTheme,
  )
}

fun MutablePreferences.write(s: Settings) {
  this[FONT] = s.font
  this[FONT_SIZE] = s.fontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
  this[LOCAL_ECHO] = s.localEcho
  this[HAPTICS] = s.haptics
  this[KEEP_SCREEN_ON] = s.keepScreenOn
  this[OPEN_MODE] = s.openMode.name
  this[THEME] = s.theme.name
  this[DYNAMIC_COLOR] = s.dynamicColor
  this[CONFIRM_CLOSE_PANE] = s.confirmClosePane
  this[APP_LOCK] = s.appLock
  if (s.terminalTheme == null) remove(TERMINAL_THEME) else this[TERMINAL_THEME] = s.terminalTheme
}

private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? = enumValues<E>().firstOrNull { it.name == name }
