package dev.namnv.rexc.presentation.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import uniffi.rexc_ffi.Block
import uniffi.rexc_ffi.Session
import uniffi.rexc_ffi.Window

class TerminalStateTest {
  private fun block(id: String) = Block(
    blockId = id, flavor = "shell", owner = null, ownerLabel = null, ownedByMe = false,
    x = 0.0, y = 0.0, w = 1.0, h = 1.0, title = null, displayTitle = null, pwd = null, pwdShort = null,
    process = null, app = null, state = null, program = null, exitCode = null,
  )

  private fun window(id: String, focused: String?, vararg blocks: String) = Window(
    windowId = id,
    label = "Window $id",
    displayName = "Window $id",
    focusedBlockId = focused,
    zoomedBlockId = null,
    busy = false,
    blocks = blocks.map { block(it) },
  )

  private val session = Session(
    sessionId = "s",
    label = "work",
    activeWindowId = "w2",
    loaded = true,
    busy = false,
    windows = listOf(window("w1", "b1", "b1", "b2"), window("w2", "b4", "b3", "b4")),
  )

  @Test
  fun resolveSelection_fallsBackToActiveWindowAndItsFocusedPane() {
    val r = resolveSelection(session, Selection())
    assertEquals("w2", r.tab?.windowId)
    assertEquals("b4", r.block?.blockId)
  }

  @Test
  fun resolveSelection_findsTheTabHoldingAPickedPane() {
    val r = resolveSelection(session, Selection(blockId = "b2"))
    assertEquals("w1", r.tab?.windowId)
    assertEquals("b2", r.block?.blockId)
  }

  @Test
  fun resolveSelection_pickedTabWinsOverStalePane() {
    val r = resolveSelection(session, Selection(tabId = "w1", blockId = "gone"))
    assertEquals("w1", r.tab?.windowId)
    assertEquals("b1", r.block?.blockId)
  }

  @Test
  fun resolveSelection_emptySessionHasNothing() {
    val r = resolveSelection(session.copy(windows = emptyList()), Selection(blockId = "b1"))
    assertNull(r.tab)
    assertNull(r.block)
  }

  @Test
  fun neighbourPane_wrapsAcrossTabs() {
    assertEquals("b1", neighbourPane(session, "b4", 1)?.blockId)
    assertEquals("b4", neighbourPane(session, "b1", -1)?.blockId)
    assertEquals("b3", neighbourPane(session, "b2", 1)?.blockId)
  }

  @Test
  fun moved_reordersIdsAndClampsTheIndex() {
    val ids = listOf("a", "b", "c", "d")
    assertEquals(listOf("b", "c", "a", "d"), ids.moved("a", 2))
    assertEquals(listOf("d", "a", "b", "c"), ids.moved("d", 0))
    assertEquals(listOf("a", "b", "d", "c"), ids.moved("c", 9))
    assertEquals(ids, ids.moved("zz", 1))
  }

  @Test
  fun inOrder_appliesAnOrderOnlyWhenItNamesExactlyTheseWindows() {
    fun window(id: String) = Window(id, id, id, null, null, false, emptyList())
    val windows = listOf(window("a"), window("b"), window("c"))
    assertEquals(listOf("c", "a", "b"), windows.inOrder(listOf("c", "a", "b")).map { it.windowId })
    assertEquals(listOf("a", "b", "c"), windows.inOrder(listOf("c", "a")).map { it.windowId })
    assertEquals(listOf("a", "b", "c"), windows.inOrder(null).map { it.windowId })
  }
}
