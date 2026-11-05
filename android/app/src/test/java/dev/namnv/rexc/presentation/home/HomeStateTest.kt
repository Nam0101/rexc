package dev.namnv.rexc.presentation.home

import kotlin.test.Test
import kotlin.test.assertEquals
import uniffi.rexc_ffi.Block
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
  fun sessionToUi_summarisesRunningProgramsAndMostUrgentState() {
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
    assertEquals("claude · codex", ui.summary)
  }

  @Test
  fun sessionToUi_idleSessionShowsTheActiveTabName() {
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
    assertEquals("~/src", session.toUi().summary)
    assertEquals(ProgramState.IDLE, session.toUi().state)
  }
}
