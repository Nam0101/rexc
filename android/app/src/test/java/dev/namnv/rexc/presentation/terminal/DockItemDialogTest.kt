package dev.namnv.rexc.presentation.terminal

import dev.namnv.rexc.data.DockItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DockItemDialogTest {
  @Test
  fun keyLabel_marksModifiersAndUppercasesSingleLetters() {
    assertEquals("^C", keyLabel("key_c", ctrl = true, alt = false, shift = false))
    assertEquals("⌥⇧tab", keyLabel("tab", ctrl = false, alt = true, shift = true))
    assertEquals("page_up", keyLabel("page_up", ctrl = false, alt = false, shift = false))
  }

  @Test
  fun summary_describesWhatEachCapDoes() {
    assertEquals("key · ^D", DockItem.Key("key_d", "eof", ctrl = true).summary())
    assertEquals("snippet · ls⏎-la⏎", DockItem.Text("ls", "ls\n-la", enter = true).summary())
  }

  @Test
  fun keyNames_offerNamedKeysFunctionKeysAndLetters() {
    assertTrue(keyNames.containsAll(listOf("escape", "page_down", "f12", "key_a", "key_z")))
  }
}
