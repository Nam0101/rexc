package dev.namnv.rexc.presentation.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import uniffi.rexc_ffi.Frame
import uniffi.rexc_ffi.Line
import uniffi.rexc_ffi.Run

class FrameSourceTest {
  private fun run(text: String) = Run(
    col = 0u,
    cells = text.length.toUShort(),
    text = text,
    widths = byteArrayOf(),
    fg = 0xFFFFFFFFu,
    bg = null,
    underlineColor = null,
    style = 0u,
  )

  private fun line(text: String) = Line(runs = listOf(run(text)), dirty = true)

  /** A row the engine left out because the caller already has it. */
  private val unchanged = Line(runs = emptyList(), dirty = false)

  private fun frame(rev: ULong, vararg lines: Line) = Frame(
    cols = 10u, rows = lines.size.toUShort(), background = 0xFF000000u, foreground = 0xFFFFFFFFu, cursorColor = null,
    cursor = null, lines = lines.toList(), scrollTotal = 0u, scrollOffset = 0u, scrollLen = 0u,
    alternateScreen = false, mouseTracking = false, dark = true, changed = true, rev = rev, prediction = null,
    highlights = emptyList(),
  )

  @Test
  fun rowCache_asksForOnlyWhatChangedSinceTheLastFrameItHolds() {
    val cache = RowCache()
    val asked = mutableListOf<ULong>()
    val first = cache.next { since ->
      asked += since
      frame(3u, line("one"), line("two"))
    }
    assertEquals(listOf(0uL), asked)
    val second = cache.next { since ->
      asked += since
      frame(5u, unchanged, line("TWO"))
    }
    assertEquals(listOf(0uL, 3uL), asked)
    assertSame(first!!.lines[0], second!!.lines[0], "the unchanged row is the one already held")
    assertEquals("TWO", second.lines[1].runs[0].text)
    assertEquals(5uL, second.rev)
  }

  @Test
  fun rowCache_startsOverFromAFullFrameWhenItsRowsDisagreeWithTheEngine() {
    val cache = RowCache()
    cache.next { frame(1u, line("a")) }
    val asked = mutableListOf<ULong>()
    val frame = cache.next { since ->
      asked += since
      if (since == 0uL) frame(9u, line("x"), line("y")) else frame(9u, unchanged, unchanged)
    }
    assertEquals(listOf(1uL, 0uL), asked)
    assertEquals(listOf("x", "y"), frame!!.lines.map { it.runs[0].text })
  }

  @Test
  fun rowCache_passesThroughAFailedRead() {
    assertNull(RowCache().next { null })
  }

  @Test
  fun searchStatus_readsAsNOfM() {
    assertEquals("", SearchUi().status())
    assertEquals("No matches", SearchUi("x", 0, null).status())
    assertEquals("3 / 12", SearchUi("x", 12, 2).status())
    assertEquals("12 / 12", SearchUi("x", 12, null).status(), "the bottom match is current until stepped")
  }

  @Test
  fun terminalFont_fallsBackToTheDefault() {
    assertEquals(TerminalFont.FIRA_CODE, TerminalFont.fromKey("fira"))
    assertEquals(TerminalFont.JETBRAINS_MONO, TerminalFont.fromKey("papyrus"))
    assertEquals(TerminalFont.JETBRAINS_MONO, TerminalFont.fromKey(null))
  }
}
