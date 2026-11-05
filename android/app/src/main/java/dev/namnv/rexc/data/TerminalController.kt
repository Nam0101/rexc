package dev.namnv.rexc.data

import androidx.compose.runtime.Stable
import timber.log.Timber
import uniffi.rexc_ffi.Frame
import uniffi.rexc_ffi.KeyMods
import uniffi.rexc_ffi.Rexc
import uniffi.rexc_ffi.RexcException
import uniffi.rexc_ffi.TerminalInfo

/**
 * One open terminal, for the screen that draws it. Every call is cheap and
 * non-blocking in the engine (frames are read from memory; input is queued),
 * so these are safe from the main thread during a gesture.
 */
@Suppress("TooManyFunctions") // a facade: one method per engine call
@Stable
class TerminalController(private val engine: Rexc, val id: ULong) {
  fun frame(): Frame? = guard { engine.terminalFrame(id) }

  /** The frame with only the rows changed after [since] (a previous frame's `rev`); the rest come back empty. */
  fun frameSince(since: ULong): Frame? = guard { engine.terminalFrameSince(id, since) }
  fun info(): TerminalInfo? = guard { engine.terminalInfo(id) }

  fun setViewport(cols: Int, rows: Int, cellW: Int, cellH: Int) {
    guard { engine.terminalSetViewport(id, cols.toUShort(), rows.toUShort(), cellW.toUInt(), cellH.toUInt()) }
  }

  fun sendText(text: String) {
    guard { engine.terminalSendText(id, text) }
  }

  fun sendKey(key: String, mods: KeyMods): Boolean = guard { engine.terminalSendKey(id, key, mods) } ?: false

  fun paste(text: String) {
    guard { engine.terminalPaste(id, text) }
  }

  /** Types [text] in and never presses Enter: bracketed when the program asked, else on one line. */
  fun insert(text: String) {
    guard { engine.terminalInsert(id, text) }
  }

  fun scroll(deltaRows: Int, col: Int, row: Int) {
    guard { engine.terminalScroll(id, deltaRows, col.toUShort(), row.toUShort()) }
  }

  /** A click at a cell for a mouse-tracking program; false when nothing was sent. */
  fun click(col: Int, row: Int, right: Boolean): Boolean =
    guard { engine.terminalClick(id, col.toUShort(), row.toUShort(), right) } ?: false

  fun scrollToBottom() {
    guard { engine.terminalScrollToBottom(id) }
  }

  fun select(fromCol: Int, fromRow: Int, toCol: Int, toRow: Int): Boolean = guard {
    engine.terminalSelect(id, fromCol.toUShort(), fromRow.toUShort(), toCol.toUShort(), toRow.toUShort())
  } ?: false

  fun selectWord(col: Int, row: Int): Boolean = guard {
    engine.terminalSelectWord(id, col.toUShort(), row.toUShort())
  } ?: false

  fun clearSelection() {
    guard { engine.terminalClearSelection(id) }
  }

  fun selectionText(): String? = guard { engine.terminalSelectionText(id) }
  fun allText(): String = guard { engine.terminalAllText(id) }.orEmpty()
  fun linkAt(col: Int, row: Int): String? = guard { engine.terminalLinkAt(id, col.toUShort(), row.toUShort()) }

  /** Show typed text ahead of the server's echo on slow links (never on fast ones). */
  fun setLocalEcho(on: Boolean) {
    guard { engine.terminalSetLocalEcho(id, on) }
  }

  /** Match count for [query] in the scrollback; the match nearest the bottom becomes current. */
  fun search(query: String): Int = guard { engine.terminalSearch(id, query) }?.toInt() ?: 0

  /** Move the current match by [delta] (positive goes down, wraps) and scroll to it; its index, if any. */
  fun searchNext(delta: Int): Int? = guard { engine.terminalSearchNext(id, delta) }?.toInt()

  fun searchClear() {
    guard { engine.terminalSearchClear(id) }
  }

  fun takeOver() {
    guard { engine.terminalTakeOver(id) }
  }

  fun release() {
    guard { engine.terminalRelease(id) }
  }

  /** This terminal is on screen now, or no longer is: its block raises no alerts while it is. */
  fun setVisible(visible: Boolean) = engine.setTerminalVisible(id, visible)

  fun close() = engine.closeTerminal(id)

  private inline fun <T> guard(block: () -> T): T? = try {
    block()
  } catch (e: RexcException) {
    Timber.d(e, "terminal %d", id.toLong())
    null
  }
}
