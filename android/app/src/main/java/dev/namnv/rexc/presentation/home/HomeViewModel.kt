package dev.namnv.rexc.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.namnv.rexc.base.EventChannel
import dev.namnv.rexc.base.HasEventFlow
import dev.namnv.rexc.core.dispatcher.WHILE_UI_SUBSCRIBED
import dev.namnv.rexc.data.EngineRepository
import dev.namnv.rexc.navigation.Terminal
import javax.inject.Inject
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.rexc_ffi.InboxItem
import uniffi.rexc_ffi.ProgramState
import uniffi.rexc_ffi.RexcException

private const val PREVIEW_LINES = 4u
private const val REFRESH_SPINNER_MS = 700L

/** Owns the home list: hosts, their sessions, the inbox, and the add/edit host sheet. */
@HiltViewModel
class HomeViewModel @Inject constructor(private val repo: EngineRepository) :
  ViewModel(),
  HasEventFlow<HomeEffect> {
  private data class Local(
    val editor: HostEditor? = null,
    val busyHostId: String? = null,
    val refreshing: Boolean = false,
    /** Host cards the user folded. */
    val collapsed: Set<String> = emptySet(),
    /** Block ids with a quick reply in flight. */
    val sending: Set<String> = emptySet(),
    /** The battery hint was waved away this session. */
    val batteryHintDismissed: Boolean = false,
  )

  private data class Preview(val stamp: String, val text: String?)

  private val local = MutableStateFlow(Local())

  /** Screen previews by block id, each tied to the status it was fetched for. */
  private val previews = MutableStateFlow<Map<String, Preview>>(emptyMap())
  private val effects = EventChannel<HomeEffect>("HomeEffect")
  override val eventFlow: Flow<HomeEffect> = effects.eventFlow

  val uiState: StateFlow<HomeUiState> = combine(
    repo.hosts,
    repo.inbox,
    local,
    previews,
    repo.needsBatteryExemption,
  ) { hosts, inbox, l, p, battery ->
    HomeUiState(
      hosts = hosts.map { it.toUi(collapsed = it.hostId in l.collapsed, inbox = inbox) }.toImmutableList(),
      inbox = inbox.filter { it.state.needsYou() }.map {
        it.toUi().copy(preview = p[it.blockId]?.text, sending = it.blockId in l.sending)
      }.toImmutableList(),
      editor = l.editor,
      busyHostId = l.busyHostId,
      refreshing = l.refreshing,
      batteryHint = battery && !l.batteryHintDismissed,
    )
  }.stateIn(viewModelScope, WHILE_UI_SUBSCRIBED, HomeUiState())

  init {
    viewModelScope.launch { repo.inbox.collect { items -> refreshPreviews(items) } }
  }

  /** Fetches a preview for every blocked program whose status moved on; drops the ones that left. */
  private fun refreshPreviews(all: List<InboxItem>) {
    val items = all.filter { it.state == ProgramState.BLOCKED }
    val wanted = items.associateBy { it.blockId }
    previews.update { old -> old.filterKeys { it in wanted } }
    for (item in items) {
      val stamp = item.previewStamp()
      if (previews.value[item.blockId]?.stamp == stamp) continue
      previews.update { it + (item.blockId to Preview(stamp, it[item.blockId]?.text)) }
      viewModelScope.launch {
        val text = try {
          repo.call { blockPreview(item.hostId, item.sessionId, item.blockId, PREVIEW_LINES) }
        } catch (e: RexcException) {
          repo.log("preview ${item.blockId}", e)
          null
        }
        previews.update { p ->
          if (p[item.blockId]?.stamp ==
            stamp
          ) {
            p + (item.blockId to Preview(stamp, text ?: p[item.blockId]?.text))
          } else {
            p
          }
        }
      }
    }
  }

  fun onEvent(event: HomeUiEvent) {
    when (event) {
      HomeUiEvent.AddHostClicked, is HomeUiEvent.EditHostClicked, is HomeUiEvent.EditorChanged,
      HomeUiEvent.SaveHost, HomeUiEvent.DismissEditor, HomeUiEvent.AllowBattery, HomeUiEvent.DismissBatteryHint,
      -> onSecondaryEvent(event)
      is HomeUiEvent.RemoveHost -> run { repo.call { removeHost(event.hostId) } }
      is HomeUiEvent.Connect -> run { repo.call { connect(event.hostId) } }
      is HomeUiEvent.Disconnect -> run { repo.call { disconnect(event.hostId) } }
      is HomeUiEvent.OpenSession -> effects.trySend(HomeEffect.OpenTerminal(Terminal(event.hostId, event.sessionId)))
      is HomeUiEvent.OpenInbox -> effects.trySend(
        HomeEffect.OpenTerminal(Terminal(event.item.hostId, event.item.sessionId, event.item.blockId)),
      )
      is HomeUiEvent.NewSession -> newSession(event.hostId)
      is HomeUiEvent.QuickReply -> quickReply(event.item, event.text)
      is HomeUiEvent.ToggleCollapsed -> toggleCollapsed(event.hostId)
      is HomeUiEvent.MoveHost -> run { repo.call { moveHost(event.hostId, event.beforeHostId) } }
      HomeUiEvent.Refresh -> refresh()
      is HomeUiEvent.CloseSession -> run { repo.call { destroySession(event.hostId, event.sessionId) } }
      is HomeUiEvent.RenameSession -> renameSession(event)
    }
  }

  /** The host editor sheet and the battery hint: small flows off the main list. */
  private fun onSecondaryEvent(event: HomeUiEvent) {
    when (event) {
      HomeUiEvent.AllowBattery -> effects.trySend(HomeEffect.RequestBatteryExemption)
      HomeUiEvent.DismissBatteryHint -> local.update { it.copy(batteryHintDismissed = true) }
      HomeUiEvent.AddHostClicked -> local.update { it.copy(editor = HostEditor(null, "", "")) }
      is HomeUiEvent.EditHostClicked -> viewModelScope.launch {
        val record = repo.call { hosts().firstOrNull { it.hostId == event.hostId } } ?: return@launch
        local.update {
          it.copy(editor = HostEditor(record.hostId, record.name, record.address, record.backgroundAlerts))
        }
      }
      is HomeUiEvent.EditorChanged -> local.update {
        it.copy(
          editor = it.editor?.copy(
            name = event.name,
            address = event.address,
            backgroundAlerts = event.backgroundAlerts,
            error = null,
          ),
        )
      }
      HomeUiEvent.SaveHost -> saveHost()
      HomeUiEvent.DismissEditor -> local.update { it.copy(editor = null) }
      else -> Unit
    }
  }

  private fun toggleCollapsed(hostId: String) = local.update {
    it.copy(collapsed = if (hostId in it.collapsed) it.collapsed - hostId else it.collapsed + hostId)
  }

  private fun renameSession(event: HomeUiEvent.RenameSession) {
    val label = event.label.trim().ifEmpty { return }
    run { repo.call { renameSession(event.hostId, event.sessionId, label) } }
  }

  /** Pull-to-refresh: ask every host to (re)connect; the spinner just confirms the request went out. */
  private fun refresh() {
    if (local.value.refreshing) return
    local.update { it.copy(refreshing = true) }
    viewModelScope.launch {
      repo.call { connectAll() }
      delay(REFRESH_SPINNER_MS)
      local.update { it.copy(refreshing = false) }
    }
  }

  private fun quickReply(item: InboxUi, text: String) {
    if (item.blockId in local.value.sending) return
    local.update { it.copy(sending = it.sending + item.blockId) }
    viewModelScope.launch {
      try {
        repo.call { quickReply(item.hostId, item.sessionId, item.blockId, text) }
      } catch (e: RexcException) {
        effects.trySend(HomeEffect.Message(e.message ?: "Could not send"))
      } finally {
        local.update { it.copy(sending = it.sending - item.blockId) }
      }
    }
  }

  private fun saveHost() {
    val editor = local.value.editor ?: return
    val name = editor.name.trim().ifEmpty {
      editor.address.trim().substringAfter("://").substringBefore('/').substringBefore(':')
    }
    if (editor.address.isBlank()) {
      local.update { it.copy(editor = editor.copy(error = "Enter the server address")) }
      return
    }
    local.update { it.copy(editor = editor.copy(saving = true, error = null)) }
    viewModelScope.launch {
      try {
        val id = repo.call {
          if (editor.hostId == null) {
            addHost(name, editor.address.trim())
          } else {
            updateHost(editor.hostId, name, editor.address.trim(), editor.backgroundAlerts)
            editor.hostId
          }
        }
        repo.call { connect(id) }
        local.update { it.copy(editor = null) }
      } catch (e: RexcException) {
        local.update { it.copy(editor = editor.copy(saving = false, error = e.message ?: "Could not save")) }
      }
    }
  }

  private fun newSession(hostId: String) {
    if (local.value.busyHostId != null) return
    local.update { it.copy(busyHostId = hostId) }
    viewModelScope.launch {
      try {
        val sessionId = repo.call { newSession(hostId, null) }
        effects.trySend(HomeEffect.OpenTerminal(Terminal(hostId, sessionId)))
      } catch (e: RexcException) {
        effects.trySend(HomeEffect.Message(e.message ?: "Could not create a session"))
      } finally {
        local.update { it.copy(busyHostId = null) }
      }
    }
  }

  private fun run(block: suspend () -> Unit) {
    viewModelScope.launch {
      try {
        block()
      } catch (e: RexcException) {
        effects.trySend(HomeEffect.Message(e.message ?: "Something went wrong"))
      }
    }
  }
}
