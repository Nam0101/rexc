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
  /** Programs that need you or failed: blocked first. Working and done ones show on their session row. */
  val inbox: ImmutableList<InboxUi> = persistentListOf(),
  val editor: HostEditor? = null,
  /** A host whose "new session" call is in flight. */
  val busyHostId: String? = null,
  /** Pull-to-refresh is reconnecting every host. */
  val refreshing: Boolean = false,
  /** Background alerts are on but Android may still freeze the app: offer the battery exemption. */
  val batteryHint: Boolean = false,
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
  /** What the most urgent program in the session is doing ("claude-code · adding hosts"), if any. */
  val activity: String? = null,
)

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

  /** Open the system's battery-optimisation prompt for the app. */
  data object AllowBattery : HomeUiEvent
  data object DismissBatteryHint : HomeUiEvent
}

sealed interface HomeEffect {
  data class OpenTerminal(val target: Terminal) : HomeEffect
  data class Message(val text: String) : HomeEffect
  data object RequestBatteryExemption : HomeEffect
}

private val urgency =
  listOf(ProgramState.BLOCKED, ProgramState.ERROR, ProgramState.WORKING, ProgramState.DONE, ProgramState.IDLE)

/** Lower is more urgent; no state at all comes last. */
private fun rank(state: ProgramState?): Int = urgency.indexOf(state).let { if (it < 0) urgency.size else it }

private fun Iterable<ProgramState?>.mostUrgent(): ProgramState? = filterNotNull().minByOrNull(::rank)

/** States the home list surfaces in its own section; the rest only colour a session row. */
fun ProgramState.needsYou(): Boolean = this == ProgramState.BLOCKED || this == ProgramState.ERROR

/** [inbox] lends each session the line of its most urgent program. */
fun Host.toUi(collapsed: Boolean = false, inbox: List<InboxItem> = emptyList()): HostUi {
  val ui = sessions.map { s -> s.toUi(inbox.filter { it.sessionId == s.sessionId }.minByOrNull { rank(it.state) }) }
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

fun Session.toUi(program: InboxItem? = null): SessionUi = SessionUi(
  id = sessionId,
  label = label,
  tabCount = windows.size,
  state = windows.flatMap { w -> w.blocks.map { it.state } }.mostUrgent(),
  activity = program?.let { p ->
    val detail = p.message?.takeIf { it.isNotBlank() } ?: p.title?.takeIf { it.isNotBlank() }
    listOfNotNull(p.app, detail).joinToString(" · ")
  },
)

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
