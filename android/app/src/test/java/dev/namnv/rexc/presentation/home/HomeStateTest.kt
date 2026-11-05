package dev.namnv.rexc.presentation.home

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.collections.immutable.persistentListOf
import uniffi.rexc_ffi.Block
import uniffi.rexc_ffi.Host
import uniffi.rexc_ffi.HostStatus
import uniffi.rexc_ffi.InboxItem
import uniffi.rexc_ffi.ProgramState
import uniffi.rexc_ffi.Session
import uniffi.rexc_ffi.Window

class HomeStateTest {
  private fun block(id: String, app: String?, state: ProgramState?) = Block(
    blockId = id, flavor = "shell", owner = null, ownerLabel = null, ownedByMe = false,
    x = 0.0, y = 0.0, w = 1.0, h = 1.0, title = null, displayTitle = null, pwd = null, pwdShort = null,
    process = "zsh", app = app, state = state, program = null, exitCode = null,
  )

  private fun session(vararg windows: Window) = Session("s", "work", "w1", true, false, windows.toList())

  private fun inbox(
    sessionId: String,
    app: String,
    state: ProgramState,
    message: String?,
    title: String? = null,
    stale: Boolean = false,
  ) = InboxItem("h", "mac", sessionId, "work", "w1", "tab", "b", app, state, null, title, message, null, stale)

  @Test
  fun sessionToUi_countsTabsAndKeepsTheMostUrgentState() {
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
    assertEquals(null, ui.activity)
    assertEquals("claude", ui.app)
  }

  @Test
  fun sessionToUi_idleSessionHasNoActivity() {
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
    assertEquals(ProgramState.IDLE, session.toUi().state)
    assertEquals(1, session.toUi().tabCount)
    assertEquals(null, session.toUi().activity)
  }

  @Test
  fun sessionToUi_stateIgnoresBlocksWithoutOne() {
    val windows = listOf(
      Window("w1", "Window 1", "plain", null, null, false, listOf(block("b1", null, null))),
      Window("w2", "Window 2", "done", null, null, false, listOf(block("b2", null, ProgramState.DONE))),
    )
    assertEquals(ProgramState.DONE, Session("s", "work", "w1", true, false, windows).toUi().state)
  }

  @Test
  fun sessionToUi_activityIsTheMostUrgentProgramsAppAndMessage() {
    val s =
      session(Window("w1", "Window 1", "tab", null, null, false, listOf(block("b1", "claude", ProgramState.WORKING))))
    val working = inbox("s", "claude-code", ProgramState.WORKING, "adding hosts")
    val titled = inbox("s", "codex", ProgramState.DONE, "  ", title = "Fix tests")
    assertEquals("claude-code · adding hosts", s.toUi(working).activity)
    assertEquals("codex · Fix tests", s.toUi(titled).activity)
    assertEquals("codex", s.toUi(inbox("s", "codex", ProgramState.DONE, null)).activity)
  }

  @Test
  fun hostToUi_lendsEachSessionItsMostUrgentProgramOnly() {
    val host = Host(
      hostId = "h",
      name = "mac",
      address = "mac.ts.net",
      status = HostStatus.CONNECTED,
      clientId = null,
      error = null,
      sessions = listOf(session(Window("w1", "Window 1", "tab", null, null, false, listOf(block("b1", null, null))))),
      clients = emptyList(),
      stale = false,
      asOfMs = null,
    )
    val inbox = listOf(
      inbox("s", "claude-code", ProgramState.WORKING, "working on it"),
      inbox("s", "codex", ProgramState.BLOCKED, "needs a yes"),
      inbox("other", "codex", ProgramState.ERROR, "elsewhere"),
    )
    assertEquals("codex · needs a yes", host.toUi(inbox = inbox).sessions.single().activity)
    assertEquals("codex", host.toUi(inbox = inbox).sessions.single().app)
    assertEquals(null, host.toUi().sessions.single().activity)
    assertEquals(null, host.toUi().sessions.single().app)
  }

  @Test
  fun staleState_carriesOverToTheHostAndItsInboxItems() {
    val host = Host(
      hostId = "h",
      name = "mac",
      address = "mac.ts.net",
      status = HostStatus.OFFLINE,
      clientId = null,
      error = null,
      sessions = emptyList(),
      clients = emptyList(),
      stale = true,
      asOfMs = 1_000L,
    )
    assertEquals(true, host.toUi().stale)
    assertEquals(1_000L, host.toUi().asOfMs)
    assertEquals(true, inbox("s", "codex", ProgramState.BLOCKED, "ok?", stale = true).toUi().stale)
    assertEquals(false, inbox("s", "codex", ProgramState.BLOCKED, "ok?").toUi().stale)
  }

  @Test
  fun asOfLabel_showsTheTimeTodayAndTheDateBefore() {
    val zone = ZoneId.of("UTC")
    val at = ZonedDateTime.of(2026, 11, 5, 14, 32, 0, 0, zone).toInstant().toEpochMilli()
    val sameDay = ZonedDateTime.of(2026, 11, 5, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
    val nextDay = ZonedDateTime.of(2026, 11, 6, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
    assertEquals("as of 14:32", asOfLabel(at, sameDay, is24Hour = true, zone = zone, locale = Locale.US))
    assertEquals("as of 2:32 PM", asOfLabel(at, sameDay, is24Hour = false, zone = zone, locale = Locale.US))
    assertEquals("as of Nov 5, 14:32", asOfLabel(at, nextDay, is24Hour = true, zone = zone, locale = Locale.US))
  }

  @Test
  fun needsYou_isBlockedOrError() {
    val states = listOf(
      ProgramState.BLOCKED,
      ProgramState.ERROR,
      ProgramState.WORKING,
      ProgramState.DONE,
      ProgramState.IDLE,
    )
    assertEquals(listOf(true, true, false, false, false), states.map { it.needsYou() })
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
