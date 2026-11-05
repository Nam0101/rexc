package dev.namnv.rexc.presentation.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeysTest {
  @Test
  fun fromChar_mapsLettersDigitsAndPunctuationToW3cCodes() {
    assertEquals("key_c", Keys.fromChar('c'))
    assertEquals("key_c", Keys.fromChar('C'))
    assertEquals("digit_1", Keys.fromChar('1'))
    assertEquals("bracket_left", Keys.fromChar('{'))
    assertEquals("space", Keys.fromChar(' '))
    assertNull(Keys.fromChar('é'))
  }

  @Test
  fun isShifted_recognisesUppercaseAndShiftedSymbols() {
    assertTrue(Keys.isShifted('A'))
    assertTrue(Keys.isShifted('{'))
    assertTrue(Keys.isShifted('!'))
    assertFalse(Keys.isShifted('a'))
    assertFalse(Keys.isShifted('['))
  }
}
