package dev.namnv.rexc.presentation.settings

import dev.namnv.rexc.data.OpenMode
import dev.namnv.rexc.data.Settings
import dev.namnv.rexc.data.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsStateTest {
  @Test
  fun reduce_changesOnlyTheFieldTheEventNames() {
    val base = Settings()
    assertEquals(base.copy(theme = ThemeMode.DARK), base.reduce(SettingsUiEvent.ThemeChanged(ThemeMode.DARK)))
    assertEquals(base.copy(openMode = OpenMode.MIRROR), base.reduce(SettingsUiEvent.OpenModeChanged(OpenMode.MIRROR)))
    assertEquals(base.copy(fontSize = 16f), base.reduce(SettingsUiEvent.FontSizeChanged(16f)))
    assertEquals(base.copy(haptics = false), base.reduce(SettingsUiEvent.HapticsChanged(false)))
    assertEquals(base.copy(font = "system"), base.reduce(SettingsUiEvent.FontChanged("system")))
  }
}
