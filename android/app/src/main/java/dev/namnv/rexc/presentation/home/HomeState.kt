package dev.namnv.rexc.presentation.home

import androidx.compose.runtime.Immutable
import dev.namnv.rexc.navigation.Terminal
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import uniffi.rexc_ffi.Host
import uniffi.rexc_ffi.HostStatus
import uniffi.rexc_ffi.InboxItem
import uniffi.rexc_ffi.ProgramState
import uniffi.rexc_ffi.Session

/** UI state owned by [HomeViewModel] and rendered by [HomeScreen]. */
@Immutable
data class HomeUiState(
  val hosts: ImmutableList<HostUi> = persistentListOf(),
  /** Programs that need you, failed, or are working: blocked first. */
  val inbox: ImmutableList<InboxUi> = persistentListOf(),
  val editor: HostEditor? = null,
  /** A host whose "new session" call is in flight. */
  val busyHostId: String? = null,
  /** Pull-to-refresh is reconnecting every host. */
  val refreshing: Boolean = false,
)

@Immutable
data class HostUi(
  val id: String,
  val name: String,
  val address: String,
  val status: HostStatus,
  val error: String?,
  val sessions: ImmutableList<SessionUi>,
  val collapsed: Boolean = false,
  /** The most urgent program state across all sessions, shown when the card is folded. */
  val state: ProgramState? = null,
)

@Immutable
data class SessionUi(
  val id: String,
  val label: String,
  val tabCount: Int,
  /** The most urgent program state across the session's panes. */
  val state: ProgramState?,
  /** One chip per tab, most urgent first, capped at [MAX_TAB_CHIPS]. */
  val tabs: ImmutableList<TabChip>,
  /** Tabs that did not get a chip. */
  val hiddenTabs: Int,
)

/** A tab as a chip: its name and the most urgent state among its panes. */
@Immutable
data class TabChip(val id: String, val name: String, val state: ProgramState?)

@Immutable
data class InboxUi(
  val hostId: String,
  val sessionId: String,
  val blockId: String,
  val app: String,
  val state: ProgramState,
  val kind: String?,
  val title: String?,
  val message: String?,
  val where: String,
  /** The pane's last screen lines, fetched lazily; null until known. */
  val preview: String? = null,
  /** A quick reply to this pane is in flight. */
  val sending: Boolean = false,
)

/** The add/edit host sheet. [hostId] is null for a new host. */
@Immutable
data class HostEditor(
  val hostId: String?,
  val name: String,
  val address: String,
  val backgroundAlerts: Boolean = false,
  val error: String? = null,
  val saving: Boolean = false,
)

sealed interface HomeUiEvent {
  data object AddHostClicked : HomeUiEvent
  data class EditHostClicked(val hostId: String) : HomeUiEvent
  data class EditorChanged(val name: String, val address: String, val backgroundAlerts: Boolean) : HomeUiEvent
  data object SaveHost : HomeUiEvent
  data object DismissEditor : HomeUiEvent
  data class RemoveHost(val hostId: String) : HomeUiEvent
  data class Connect(val hostId: String) : HomeUiEvent
  data class Disconnect(val hostId: String) : HomeUiEvent
  data class OpenSession(val hostId: String, val sessionId: String) : HomeUiEvent
  data class OpenInbox(val item: InboxUi) : HomeUiEvent

  /** Types [text] and Enter into the item's pane without opening it. */
  data class QuickReply(val item: InboxUi, val text: String) : HomeUiEvent
  data class NewSession(val hostId: String) : HomeUiEvent
  data class ToggleCollapsed(val hostId: String) : HomeUiEvent

  /** Drops [hostId] just before [beforeHostId], or last when null. */
  data class MoveHost(val hostId: String, val beforeHostId: String?) : HomeUiEvent
  data object Refresh : HomeUiEvent
  data class CloseSession(val hostId: String, val sessionId: String) : HomeUiEvent
  data class RenameSession(val hostId: String, val sessionId: String, val label: String) : HomeUiEvent
}

sealed interface HomeEffect {
  data class OpenTerminal(val target: Terminal) : HomeEffect
  data class Message(val text: String) : HomeEffect
}

private val urgency =
  listOf(ProgramState.BLOCKED, ProgramState.ERROR, ProgramState.WORKING, ProgramState.DONE, ProgramState.IDLE)

const val MAX_TAB_CHIPS = 6

/** Lower is more urgent; no state at all comes last. */
private fun rank(state: ProgramState?): Int = urgency.indexOf(state).let { if (it < 0) urgency.size else it }

private fun Iterable<ProgramState?>.mostUrgent(): ProgramState? = filterNotNull().minByOrNull(::rank)

fun Host.toUi(collapsed: Boolean = false): HostUi {
  val ui = sessions.map { it.toUi() }
  return HostUi(
    id = hostId,
    name = name,
    address = address,
    status = status,
    error = error,
    sessions = ui.toImmutableList(),
    collapsed = collapsed,
    state = ui.map { it.state }.mostUrgent(),
  )
}

fun Session.toUi(): SessionUi {
  val chips = windows.map { w -> TabChip(w.windowId, w.displayName, w.blocks.map { it.state }.mostUrgent()) }
    .sortedBy { rank(it.state) }
  return SessionUi(
    id = sessionId,
    label = label,
    tabCount = windows.size,
    state = chips.map { it.state }.mostUrgent(),
    tabs = chips.take(MAX_TAB_CHIPS).toImmutableList(),
    hiddenTabs = (chips.size - MAX_TAB_CHIPS).coerceAtLeast(0),
  )
}

/** What a preview was fetched for; a new status or message means the screen moved on. */
fun InboxItem.previewStamp(): String = "$state/$updatedAt/${message.orEmpty()}"

fun InboxItem.toUi(): InboxUi = InboxUi(
  hostId = hostId,
  sessionId = sessionId,
  blockId = blockId,
  app = app,
  state = state,
  kind = kind,
  title = title,
  message = message,
  where = listOf(hostName, sessionLabel, windowLabel).filter { it.isNotBlank() }.joinToString(" › "),
)
