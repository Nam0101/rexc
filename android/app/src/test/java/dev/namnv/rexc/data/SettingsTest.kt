package dev.namnv.rexc.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsTest {
  @Test
  fun toSettings_emptyPreferencesGiveTheDefaults() {
    assertEquals(Settings(), mutablePreferencesOf().toSettings())
  }

  @Test
  fun write_thenRead_roundTripsEveryField() {
    val custom = Settings(
      font = FONT_FIRA,
      fontSize = 20f,
      localEcho = false,
      haptics = false,
      keepScreenOn = false,
      openMode = OpenMode.MIRROR,
      theme = ThemeMode.TERMINAL,
      dynamicColor = false,
      confirmClosePane = false,
    )
    val prefs = mutablePreferencesOf().apply { write(custom) }
    assertEquals(custom, prefs.toSettings())
  }

  @Test
  fun toSettings_unknownEnumNamesFallBackToTheDefaults() {
    val prefs = mutablePreferencesOf(stringPreferencesKey("theme") to "NEON", stringPreferencesKey("open_mode") to "x")
    assertEquals(Settings(), prefs.toSettings())
  }

  @Test
  fun write_clampsTheFontSizeToTheSliderRange() {
    val tooBig = mutablePreferencesOf().apply { write(Settings(fontSize = 99f)) }
    val tooSmall = mutablePreferencesOf().apply { write(Settings(fontSize = 1f)) }
    assertEquals(MAX_FONT_SIZE, tooBig.toSettings().fontSize)
    assertEquals(MIN_FONT_SIZE, tooSmall.toSettings().fontSize)
  }
}
