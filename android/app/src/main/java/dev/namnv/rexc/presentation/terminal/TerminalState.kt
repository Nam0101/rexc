package dev.namnv.rexc.presentation.terminal

import androidx.compose.runtime.Immutable
import dev.namnv.rexc.data.TerminalController
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import uniffi.rexc_ffi.Block
import uniffi.rexc_ffi.HostStatus
import uniffi.rexc_ffi.ProgramState
import uniffi.rexc_ffi.Session
import uniffi.rexc_ffi.SizeMode
import uniffi.rexc_ffi.StreamState
import uniffi.rexc_ffi.TerminalInfo
import uniffi.rexc_ffi.Window

/** UI state owned by [TerminalViewModel] and rendered by [TerminalScreen]. */
@Immutable
data class TerminalUiState(
  val hostName: String = "",
  val hostStatus: HostStatus = HostStatus.OFFLINE,
  val sessionLabel: String = "",
  val tabs: ImmutableList<TabUi> = persistentListOf(),
  val currentTabId: String? = null,
  /** Panes of the current tab, in layout order. */
  val panes: ImmutableList<PaneUi> = persistentListOf(),
  val currentBlockId: String? = null,
  /** The open terminal for [currentBlockId], once the engine has it. */
  val terminal: TerminalController? = null,
  val info: TerminalInfoUi? = null,
  /** The session no longer exists on the server. */
  val gone: Boolean = false,
  /** Position of the current pane in reading order across tabs; drives the slide direction. */
  val paneOrdinal: Int = 0,
)

@Immutable
data class TabUi(val id: String, val name: String, val busy: Boolean, val state: ProgramState?)

@Immutable
data class PaneUi(
  val blockId: String,
  val name: String,
  val state: ProgramState?,
  val ownerLabel: String?,
  val ownedByMe: Boolean,
)

enum class StreamUi { CONNECTING, LIVE, RECONNECTING, ENDED }

@Immutable
data class TerminalInfoUi(
  val stream: StreamUi,
  val endedReason: String? = null,
  val ownedByMe: Boolean,
  /** Another client owns the size; we show it at theirs. */
  val mirror: Boolean,
  val cols: Int,
  val rows: Int,
  val ownerLabel: String?,
  val exitCode: Int?,
)

sealed interface TerminalUiEvent {
  data class SelectTab(val tabId: String) : TerminalUiEvent
  data class SelectPane(val blockId: String) : TerminalUiEvent

  /** +1 next pane, -1 previous; wraps into the next/previous tab. */
  data class SwipePane(val delta: Int) : TerminalUiEvent

  /** Tab and session housekeeping, handled together. */
  sealed interface TabEvent : TerminalUiEvent
  data object NewTab : TabEvent
  data object CloseTab : TabEvent

  /** Drop the tab at [toIndex] among the tabs (optimistic; the server confirms). */
  data class MoveTab(val tabId: String, val toIndex: Int) : TabEvent
  data class RenameTab(val name: String) : TabEvent
  data class RenameSession(val name: String) : TabEvent
  data class Split(val vertical: Boolean) : TerminalUiEvent
  data object ClosePane : TerminalUiEvent
  data object TakeOver : TerminalUiEvent
  data object Release : TerminalUiEvent
  data object Clear : TerminalUiEvent
  data class OpenUrl(val url: String) : TerminalUiEvent
}

sealed interface TerminalEffect {
  data class Message(val text: String) : TerminalEffect
  data class OpenUrl(val url: String) : TerminalEffect
}

/** What the user picked; resolved against the live session by [resolveSelection]. */
data class Selection(val tabId: String? = null, val blockId: String? = null)

data class Resolved(val tab: Window?, val block: Block?)

/**
 * The tab and pane to show: the picked ones when they still exist, else the tab
 * holding the picked pane, else the server's active tab and its focused pane.
 */
fun resolveSelection(session: Session, selection: Selection): Resolved {
  val windows = session.windows
  val tab = windows.firstOrNull { it.windowId == selection.tabId }
    ?: windows.firstOrNull { w -> w.blocks.any { it.blockId == selection.blockId } }
    ?: windows.firstOrNull { it.windowId == session.activeWindowId }
    ?: windows.firstOrNull()
  val block = tab?.blocks?.firstOrNull { it.blockId == selection.blockId }
    ?: tab?.blocks?.firstOrNull { it.blockId == tab.focusedBlockId }
    ?: tab?.blocks?.firstOrNull()
  return Resolved(tab, block)
}

/** The pane [delta] steps away in reading order across all tabs, wrapping around. */
fun neighbourPane(session: Session, blockId: String?, delta: Int): Block? {
  val all = session.windows.flatMap { it.blocks }
  if (all.isEmpty()) return null
  val index = all.indexOfFirst { it.blockId == blockId }.coerceAtLeast(0)
  return all[Math.floorMod(index + delta, all.size)]
}

fun Window.toTab(): TabUi = TabUi(
  id = windowId,
  name = displayName,
  busy = busy,
  state = blocks.mapNotNull { it.state }.minByOrNull { urgencyOf(it) },
)

fun Block.toPane(): PaneUi = PaneUi(
  blockId = blockId,
  name = displayTitle ?: app ?: process ?: pwdShort ?: "shell",
  state = state,
  ownerLabel = ownerLabel,
  ownedByMe = ownedByMe,
)

fun TerminalInfo.toUi(ownerLabel: String?): TerminalInfoUi = TerminalInfoUi(
  stream = when (stream) {
    StreamState.Connecting -> StreamUi.CONNECTING
    StreamState.Live -> StreamUi.LIVE
    StreamState.Reconnecting -> StreamUi.RECONNECTING
    is StreamState.Ended -> StreamUi.ENDED
  },
  endedReason = (stream as? StreamState.Ended)?.reason,
  ownedByMe = ownedByMe,
  mirror = mode == SizeMode.MIRROR,
  cols = cols.toInt(),
  rows = rows.toInt(),
  ownerLabel = ownerLabel,
  exitCode = exitCode,
)

/** Most urgent first: what needs the user outranks what is merely running or finished. */
private val urgencyOrder =
  listOf(ProgramState.BLOCKED, ProgramState.ERROR, ProgramState.WORKING, ProgramState.DONE, ProgramState.IDLE)

private fun urgencyOf(state: ProgramState): Int = urgencyOrder.indexOf(state)

fun List<Window>.toTabs(): ImmutableList<TabUi> = map { it.toTab() }.toImmutableList()

/** The windows in [order] when it names exactly these windows, else as the server has them. */
fun List<Window>.inOrder(order: List<String>?): List<Window> {
  if (order == null || order.size != size || !order.containsAll(map { it.windowId })) return this
  return sortedBy { order.indexOf(it.windowId) }
}

/** The window order after moving [tabId] to [toIndex]. */
fun List<String>.moved(tabId: String, toIndex: Int): List<String> {
  val from = indexOf(tabId)
  if (from < 0) return this
  return toMutableList().apply {
    removeAt(from)
    add(toIndex.coerceIn(0, size), tabId)
  }
}

/** The pane's position in reading order across all tabs, for the slide direction between panes. */
fun Session.paneOrdinal(blockId: String?): Int = windows.flatMap { it.blocks }.indexOfFirst { it.blockId == blockId }
