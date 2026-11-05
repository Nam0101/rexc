package dev.namnv.rexc.presentation.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.collections.immutable.persistentListOf
import uniffi.rexc_ffi.Block
import uniffi.rexc_ffi.HostStatus
import uniffi.rexc_ffi.ProgramState
import uniffi.rexc_ffi.Session
import uniffi.rexc_ffi.Window

class HomeStateTest {
  private fun block(id: String, app: String?, state: ProgramState?) = Block(
    blockId = id, flavor = "shell", owner = null, ownerLabel = null, ownedByMe = false,
    x = 0.0, y = 0.0, w = 1.0, h = 1.0, title = null, displayTitle = null, pwd = null, pwdShort = null,
    process = "zsh", app = app, state = state, program = null, exitCode = null,
  )

  @Test
  fun sessionToUi_listsTabsMostUrgentFirstAndKeepsTheMostUrgentState() {
    val session = Session(
      sessionId = "s",
      label = "work",
      activeWindowId = "w1",
      loaded = true,
      busy = true,
      windows = listOf(
        Window("w1", "Window 1", "claude", null, null, true, listOf(block("b1", "claude", ProgramState.WORKING))),
        Window(
          "w2",
          "Window 2",
          "codex",
          null,
          null,
          false,
          listOf(block("b2", "codex", ProgramState.BLOCKED), block("b3", null, null)),
        ),
      ),
    )
    val ui = session.toUi()
    assertEquals(2, ui.tabCount)
    assertEquals(ProgramState.BLOCKED, ui.state)
    assertEquals(listOf("codex", "claude"), ui.tabs.map { it.name })
    assertEquals(listOf(ProgramState.BLOCKED, ProgramState.WORKING), ui.tabs.map { it.state })
  }

  @Test
  fun sessionToUi_idleSessionStillListsItsTab() {
    val session = Session(
      sessionId = "s",
      label = "work",
      activeWindowId = "w1",
      loaded = true,
      busy = false,
      windows = listOf(
        Window("w1", "Window 1", "~/src", "b1", null, false, listOf(block("b1", null, ProgramState.IDLE))),
      ),
    )
    assertEquals(listOf("~/src"), session.toUi().tabs.map { it.name })
    assertEquals(ProgramState.IDLE, session.toUi().state)
  }

  @Test
  fun sessionToUi_capsTabChipsAndCountsTheRest() {
    val windows = (1..MAX_TAB_CHIPS + 3).map { i ->
      Window("w$i", "Window $i", "tab $i", null, null, false, listOf(block("b$i", null, ProgramState.IDLE)))
    }
    val ui = Session("s", "work", "w1", true, false, windows).toUi()
    assertEquals(MAX_TAB_CHIPS, ui.tabs.size)
    assertEquals(3, ui.hiddenTabs)
    assertEquals(MAX_TAB_CHIPS + 3, ui.tabCount)
  }

  @Test
  fun sessionToUi_tabWithoutAnyStateSortsLast() {
    val windows = listOf(
      Window("w1", "Window 1", "plain", null, null, false, listOf(block("b1", null, null))),
      Window("w2", "Window 2", "done", null, null, false, listOf(block("b2", null, ProgramState.DONE))),
    )
    val ui = Session("s", "work", "w1", true, false, windows).toUi()
    assertEquals(listOf("done", "plain"), ui.tabs.map { it.name })
    assertEquals(ProgramState.DONE, ui.state)
  }

  private fun hostUi(id: String) = HostUi(id, id, "", HostStatus.CONNECTED, null, persistentListOf())

  @Test
  fun moveId_putsTheIdAtTheTargetIndex() {
    assertEquals(listOf("c", "a", "b"), moveId(listOf("a", "b", "c"), "c", 0))
    assertEquals(listOf("b", "c", "a"), moveId(listOf("a", "b", "c"), "a", 2))
    assertEquals(listOf("a", "b"), moveId(listOf("a", "b"), "zzz", 0))
  }

  @Test
  fun beforeIdOf_isTheNextIdOrNullAtTheEnd() {
    assertEquals("b", beforeIdOf(listOf("a", "b", "c"), "a"))
    assertEquals(null, beforeIdOf(listOf("a", "b", "c"), "c"))
  }

  @Test
  fun arrange_followsTheOrderAndKeepsStrangersAtTheEnd() {
    val hosts = listOf(hostUi("a"), hostUi("b"), hostUi("c"))
    assertEquals(listOf("c", "a", "b"), arrange(hosts, listOf("c", "a")).map { it.id })
    assertEquals(hosts, arrange(hosts, null))
    assertEquals(listOf("b", "a", "c"), arrange(hosts, listOf("b", "gone", "a")).map { it.id })
  }
}
