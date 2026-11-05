package dev.namnv.rexc.core.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeTest {
  @Test
  fun readableOn_keepsThePreferredColorWhenItContrastsEnough() {
    assertEquals(Color.White, readableOn(Color(0xFF0A62E8), Color.White))
  }

  @Test
  fun readableOn_switchesToBlackOnALightAccent() {
    val lightAccent = Color(0xFFFFE082)
    assertEquals(Color.Black, readableOn(lightAccent, Color.White))
  }

  @Test
  fun readableOn_switchesToWhiteOnADarkAccentWhenThePreferredInkIsDark() {
    assertEquals(Color.White, readableOn(Color(0xFF1B1F3B), Color(0xFF222222)))
  }

  @Test
  fun contrast_isSymmetricAndSpansOneToTwentyOne() {
    assertEquals(contrast(Color.Black, Color.White), contrast(Color.White, Color.Black))
    assertTrue(contrast(Color.Black, Color.White) in 20.9f..21.1f)
    assertEquals(1f, contrast(Color.Gray, Color.Gray))
  }
}
