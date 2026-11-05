package dev.namnv.rexc.presentation.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.namnv.rexc.core.dispatcher.WHILE_UI_SUBSCRIBED
import dev.namnv.rexc.data.DockPrefs
import dev.namnv.rexc.data.EngineRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.rexc_ffi.Host
import uniffi.rexc_ffi.RexcException

private const val PREVIEW_LINES = 2u

/** What the terminal's sheets read from the server: its actions, every pane's preview, the recent picks. */
@HiltViewModel(assistedFactory = PaletteViewModel.Factory::class)
class PaletteViewModel @AssistedInject constructor(
  @Assisted("host") private val hostId: String,
  @Assisted("session") private val sessionId: String,
  private val repo: EngineRepository,
  private val prefs: DockPrefs,
) : ViewModel() {
  @AssistedFactory
  interface Factory {
    fun create(@Assisted("host") hostId: String, @Assisted("session") sessionId: String): PaletteViewModel
  }

  val hosts: StateFlow<ImmutableList<Host>> = repo.hosts
  val recents: StateFlow<List<String>> = prefs.recents.stateIn(viewModelScope, WHILE_UI_SUBSCRIBED, emptyList())

  private val _serverActions = MutableStateFlow<ImmutableList<PaletteEntry>>(persistentListOf())
  val serverActions: StateFlow<ImmutableList<PaletteEntry>> = _serverActions

  /** Why the last server action failed, until dismissed. */
  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error

  /** Two-line previews by block id. A pane that could not be read is cached as blank. */
  private val _previews = MutableStateFlow<Map<String, String>>(emptyMap())
  val previews: StateFlow<Map<String, String>> = _previews

  fun loadActions() {
    viewModelScope.launch {
      try {
        _serverActions.value = repo.call { actions(hostId) }.map {
          PaletteEntry("rex:${it.name}", it.title, it.description, it.category, it.keywords)
        }.toImmutableList()
      } catch (e: RexcException) {
        repo.log("server actions", e)
      }
    }
  }

  /** Runs a server action (an entry id from [serverActions]) on [blockId]; [onDone] after the server accepts. */
  fun runServerAction(entryId: String, blockId: String?, onDone: () -> Unit) {
    viewModelScope.launch {
      try {
        repo.call { runAction(hostId, sessionId, blockId, entryId.removePrefix("rex:")) }
        prefs.pushRecent(entryId)
        onDone()
      } catch (e: RexcException) {
        _error.value = e.message ?: "The server refused"
      }
    }
  }

  fun remember(entryId: String) {
    viewModelScope.launch { prefs.pushRecent(entryId) }
  }

  fun dismissError() {
    _error.value = null
  }

  /** Reads one pane's preview unless cached; cancelled with the row that asked. */
  suspend fun loadPreview(hostId: String, sessionId: String, blockId: String) {
    if (blockId in _previews.value) return
    val text = try {
      repo.call { blockPreview(hostId, sessionId, blockId, PREVIEW_LINES) }
    } catch (e: RexcException) {
      repo.log("preview $blockId", e)
      ""
    }
    _previews.update { it + (blockId to text) }
  }

  fun clearPreviews() {
    _previews.value = emptyMap()
  }
}
