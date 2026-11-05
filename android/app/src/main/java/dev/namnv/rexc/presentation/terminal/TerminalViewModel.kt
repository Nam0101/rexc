package dev.namnv.rexc.presentation.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.namnv.rexc.base.EventChannel
import dev.namnv.rexc.base.HasEventFlow
import dev.namnv.rexc.core.dispatcher.AppCoroutineDispatchers
import dev.namnv.rexc.core.dispatcher.WHILE_UI_SUBSCRIBED
import dev.namnv.rexc.data.EngineRepository
import dev.namnv.rexc.data.OpenMode
import dev.namnv.rexc.data.SettingsRepository
import dev.namnv.rexc.data.TerminalController
import dev.namnv.rexc.navigation.Terminal
import dev.namnv.rexc.presentation.share.PendingShare
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.rexc_ffi.Event
import uniffi.rexc_ffi.Frame
import uniffi.rexc_ffi.Host
import uniffi.rexc_ffi.HostStatus
import uniffi.rexc_ffi.Rexc
import uniffi.rexc_ffi.RexcException
import uniffi.rexc_ffi.TerminalInfo

private const val SEARCH_DEBOUNCE_MS = 150L

/** How long a shared text waits for its pane's snapshot before it is dropped. */
private const val SHARE_WAIT_MS = 15_000L

/** The palette entry that opens the find bar; the id dock-palette registered. */
private const val REQUEST_FIND = "find"

/**
 * One session on screen: which tab and pane are shown, the open terminal for
 * that pane, and the layout actions (tabs, splits, ownership).
 */
@HiltViewModel(assistedFactory = TerminalViewModel.Factory::class)
class TerminalViewModel @AssistedInject constructor(
  @Assisted private val key: Terminal,
  private val repo: EngineRepository,
  private val settings: SettingsRepository,
  private val dispatchers: AppCoroutineDispatchers,
  private val shares: PendingShare,
) : ViewModel(),
  HasEventFlow<TerminalEffect> {
  @AssistedFactory
  interface Factory {
    fun create(key: Terminal): TerminalViewModel
  }

  private data class Opened(val controller: TerminalController, val blockId: String, val info: TerminalInfo?)

  private val selection = MutableStateFlow(Selection(blockId = key.blockId))
  private val opened = MutableStateFlow<Opened?>(null)

  /** Tab order shown while a move is in flight, until the server's order agrees. */
  private val tabOrder = MutableStateFlow<List<String>?>(null)
  private val effects = EventChannel<TerminalEffect>("TerminalEffect")
  override val eventFlow: Flow<TerminalEffect> = effects.eventFlow

  private val host: Flow<Host?> = repo.host(key.hostId)
  private val session = host.map { h -> h?.sessions?.firstOrNull { it.sessionId == key.sessionId } }
  private val resolved =
    combine(session, selection) { s, sel -> s?.let { resolveSelection(it, sel) } ?: Resolved(null, null) }

  private val search = MutableStateFlow<SearchUi?>(null)
  private var searchJob: Job? = null

  private val layout =
    combine(host, session, resolved, opened, tabOrder) { h, s, r, op, order ->
      val current = op?.takeIf { it.blockId == r.block?.blockId }
      TerminalUiState(
        hostName = h?.name.orEmpty(),
        hostStatus = h?.status ?: HostStatus.OFFLINE,
        sessionLabel = s?.label.orEmpty(),
        tabs = s?.windows?.inOrder(order)?.toTabs() ?: persistentListOf(),
        currentTabId = r.tab?.windowId,
        panes = r.tab?.blocks?.map { it.toPane() }?.toImmutableList() ?: persistentListOf(),
        currentBlockId = r.block?.blockId,
        terminal = current?.controller,
        info = current?.info?.toUi(r.block?.ownerLabel),
        gone = h != null && h.status == HostStatus.CONNECTED && s == null && h.sessions.isNotEmpty(),
        paneOrdinal = s?.paneOrdinal(r.block?.blockId) ?: 0,
        hostId = key.hostId,
        sessionId = key.sessionId,
      )
    }

  val uiState: StateFlow<TerminalUiState> = combine(layout, search) { state, find -> state.copy(search = find) }
    .stateIn(viewModelScope, WHILE_UI_SUBSCRIBED, TerminalUiState(hostId = key.hostId, sessionId = key.sessionId))

  /**
   * Frames of the current terminal, built off the main thread: one per display frame at most, and
   * only for rows that changed. Nothing is built while nobody collects (the canvas is off screen).
   */
  val frames: Flow<Frame> = opened.flatMapLatest { op ->
    if (op == null) {
      emptyFlow()
    } else {
      val cache = RowCache()
      val gate = VsyncGate(dispatchers.mainImmediate)
      val updates = repo.terminalEvents(op.controller.id).filterIsInstance<Event.Terminal>().map { }
      merge(flowOf(Unit), updates).conflate()
        .map {
          gate.await()
          RenderStats.timedBuild { cache.next(op.controller::frameSince) }
        }
        .filterNotNull()
        .filter { it.changed }
        .flowOn(dispatchers.default)
    }
  }

  /** Bells rung in the current pane (the engine already drops bursts). Collected only while on screen. */
  val bells: Flow<Unit> = opened.flatMapLatest { op ->
    if (op == null) {
      emptyFlow()
    } else {
      repo.events.filterIsInstance<Event.Bell>().filter { it.terminalId == op.controller.id }.map { }
    }
  }

  init {
    viewModelScope.launch {
      resolved.map { it.block?.blockId }.distinctUntilChanged().collect { switchTo(it) }
    }
    viewModelScope.launch {
      opened.flatMapLatest { op ->
        if (op == null) emptyFlow() else repo.terminalEvents(op.controller.id).filterIsInstance<Event.TerminalState>()
      }.collect {
        opened.update { op -> op?.copy(info = op.controller.info()) }
      }
    }
    viewModelScope.launch {
      shares.pending.filter { it?.target == key }.collectLatest { pasteShare() }
    }
    viewModelScope.launch {
      repo.events.filterIsInstance<Event.OpenUrl>().collect { effects.trySend(TerminalEffect.OpenUrl(it.url)) }
    }
    viewModelScope.launch {
      // The server caught up with the optimistic order (or the tabs changed under it): show its own again.
      session.collect { s ->
        val ids = s?.windows?.map { it.windowId } ?: return@collect
        tabOrder.update { order -> order?.takeIf { it != ids && it.size == ids.size && it.containsAll(ids) } }
      }
    }
  }

  /**
   * Text shared to this terminal: its pane shown once, then typed in once that pane has its snapshot
   * (bracketed when the program asked, else on one line), never with Enter. Dropped with a message
   * when the pane is gone or doesn't come up in time.
   */
  private suspend fun pasteShare() {
    val blockId = key.blockId
    if (blockId != null) {
      val s = session.first()
      if (s != null && s.windows.none { w -> w.blocks.any { it.blockId == blockId } }) {
        dropShare("That pane is gone — nothing was pasted")
        return
      }
      selection.value = Selection(blockId = blockId)
    }
    val op = withTimeoutOrNull(SHARE_WAIT_MS) {
      opened.filterNotNull().first { (blockId == null || it.blockId == blockId) && it.info?.hasSnapshot == true }
    }
    if (op == null) {
      dropShare("Couldn't reach the pane — nothing was pasted")
    } else {
      shares.take(key)?.let(op.controller::insert)
    }
  }

  private fun dropShare(message: String) {
    if (shares.take(key) != null) effects.trySend(TerminalEffect.Message(message))
  }

  private suspend fun switchTo(blockId: String?) {
    opened.value?.controller?.close()
    opened.value = null
    closeSearch(null)
    if (blockId == null) return
    try {
      val take = settings.settings.value.openMode == OpenMode.FIT
      val id = repo.call { openTerminal(key.hostId, key.sessionId, blockId, take) }
      val controller = repo.terminal(id)
      opened.value = Opened(controller, blockId, controller.info())
      repo.call { setLastPosition(key.hostId, key.sessionId, selection.value.tabId, blockId) }
    } catch (e: RexcException) {
      effects.trySend(TerminalEffect.Message(e.message ?: "Could not open the terminal"))
    }
  }

  fun onEvent(event: TerminalUiEvent) {
    val state = uiState.value
    when (event) {
      is TerminalUiEvent.SelectTab -> selection.value = Selection(tabId = event.tabId)
      is TerminalUiEvent.SelectPane -> selection.update { it.copy(blockId = event.blockId) }
      is TerminalUiEvent.SwipePane -> viewModelScope.launch {
        val s = repo.hosts.value.firstOrNull {
          it.hostId == key.hostId
        }?.sessions?.firstOrNull { it.sessionId == key.sessionId } ?: return@launch
        val next = neighbourPane(s, state.currentBlockId, event.delta) ?: return@launch
        selection.value = Selection(blockId = next.blockId)
      }
      is TerminalUiEvent.TabEvent -> onTabEvent(event, state)
      is TerminalUiEvent.SheetEvent -> onSheetEvent(event)
      is TerminalUiEvent.Split -> state.currentBlockId?.let { block ->
        call {
          val blockId = split(key.hostId, key.sessionId, block, event.vertical, null)
          selection.value = Selection(blockId = blockId)
        }
      }
      TerminalUiEvent.ClosePane -> state.currentBlockId?.let { block ->
        call { closeBlock(key.hostId, key.sessionId, block) }
      }
      TerminalUiEvent.TakeOver -> state.terminal?.takeOver()
      TerminalUiEvent.Release -> state.terminal?.release()
      TerminalUiEvent.Clear -> state.currentBlockId?.let { block ->
        call { clearTerminal(key.hostId, key.sessionId, block) }
      }
      is TerminalUiEvent.OpenUrl -> effects.trySend(TerminalEffect.OpenUrl(event.url))
      is TerminalUiEvent.SearchEvent -> onSearchEvent(event, state.terminal)
    }
  }

  private fun onSheetEvent(event: TerminalUiEvent.SheetEvent) {
    when (event) {
      is TerminalUiEvent.Open -> {
        val target = event.terminal
        if (target.hostId == key.hostId && target.sessionId == key.sessionId) {
          selection.value = Selection(blockId = target.blockId)
        } else {
          effects.trySend(TerminalEffect.OpenTerminal(target))
        }
      }
      is TerminalUiEvent.CloseBlock -> call { closeBlock(key.hostId, key.sessionId, event.blockId) }
      is TerminalUiEvent.FocusOnDesktop -> call { focusBlock(key.hostId, key.sessionId, event.blockId) }
      is TerminalUiEvent.Request ->
        if (event.id == REQUEST_FIND) {
          search.update {
            it ?: SearchUi()
          }
        } else {
          effects.trySend(TerminalEffect.Request(event.id))
        }
    }
  }

  private fun onSearchEvent(event: TerminalUiEvent.SearchEvent, terminal: TerminalController?) {
    when (event) {
      TerminalUiEvent.OpenSearch -> search.update { it ?: SearchUi() }
      is TerminalUiEvent.SearchQuery -> {
        search.update { SearchUi(event.query, it?.count ?: 0, it?.current) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
          delay(SEARCH_DEBOUNCE_MS)
          val found = withContext(dispatchers.default) {
            terminal?.let { it.search(event.query) to it.searchNext(0) } ?: (0 to null)
          }
          search.update { if (it?.query == event.query) SearchUi(event.query, found.first, found.second) else it }
        }
      }
      is TerminalUiEvent.SearchNext -> viewModelScope.launch {
        val at = withContext(dispatchers.default) { terminal?.searchNext(event.delta) }
        search.update { it?.copy(current = at) }
      }
      TerminalUiEvent.CloseSearch -> closeSearch(terminal)
    }
  }

  private fun closeSearch(terminal: TerminalController?) {
    searchJob?.cancel()
    search.value = null
    terminal?.searchClear()
  }

  private fun onTabEvent(event: TerminalUiEvent.TabEvent, state: TerminalUiState) {
    when (event) {
      TerminalUiEvent.NewTab -> call {
        val blockId = newWindow(key.hostId, key.sessionId, null)
        selection.value = Selection(blockId = blockId)
      }
      TerminalUiEvent.CloseTab -> state.currentTabId?.let { tab ->
        call { closeWindow(key.hostId, key.sessionId, tab) }
      }
      is TerminalUiEvent.MoveTab -> moveTab(state.tabs.map { it.id }, event.tabId, event.toIndex)
      is TerminalUiEvent.RenameTab -> state.currentTabId?.let { tab ->
        call { renameWindow(key.hostId, key.sessionId, tab, event.name) }
      }
      is TerminalUiEvent.RenameSession -> call { renameSession(key.hostId, key.sessionId, event.name) }
    }
  }

  /** Shows the new order at once and asks the server; a refusal puts the tabs back. */
  private fun moveTab(ids: List<String>, tabId: String, toIndex: Int) {
    val order = ids.moved(tabId, toIndex)
    if (order == ids) return
    tabOrder.value = order
    val before = order.getOrNull(order.indexOf(tabId) + 1)
    viewModelScope.launch {
      try {
        repo.call { moveWindow(key.hostId, key.sessionId, tabId, before) }
      } catch (e: RexcException) {
        tabOrder.value = null
        effects.trySend(TerminalEffect.Message(e.message ?: "Could not move the tab"))
      }
    }
  }

  private fun call(block: Rexc.() -> Unit) {
    viewModelScope.launch {
      try {
        repo.call(block)
      } catch (e: RexcException) {
        effects.trySend(TerminalEffect.Message(e.message ?: "The server refused"))
      }
    }
  }

  override fun onCleared() {
    opened.value?.controller?.close()
  }
}
