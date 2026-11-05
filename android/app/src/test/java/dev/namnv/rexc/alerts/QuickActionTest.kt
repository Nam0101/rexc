package dev.namnv.rexc.alerts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import uniffi.rexc_ffi.AlertKind

class QuickActionTest {
  @Test
  fun permission_prompts_get_yes_no_and_a_raw_escape() {
    val actions = quickActionsFor(AlertKind.Blocked("permission", "Allow Bash?"))
    assertEquals(listOf("Yes", "No", "Esc"), actions.map { it.label })
    assertEquals(listOf("y", "n", "\u001b"), actions.map { it.text })
    assertEquals(listOf(QuickAction.Esc), actions.filter { it.raw })
  }

  @Test
  fun questions_and_other_alerts_get_no_quick_actions() {
    assertTrue(quickActionsFor(AlertKind.Blocked("question", null)).isEmpty())
    assertTrue(quickActionsFor(AlertKind.Blocked(null, null)).isEmpty())
    assertTrue(quickActionsFor(AlertKind.Done).isEmpty())
  }
}
