package dev.namnv.rexc.presentation.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.namnv.rexc.core.dispatcher.WHILE_UI_SUBSCRIBED
import dev.namnv.rexc.data.DockLayout
import dev.namnv.rexc.data.DockPrefs
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The dock's saved keys. */
@HiltViewModel
class DockViewModel @Inject constructor(private val prefs: DockPrefs) : ViewModel() {
  val layout: StateFlow<DockLayout> = prefs.layout.stateIn(viewModelScope, WHILE_UI_SUBSCRIBED, DockLayout.Default)

  fun save(layout: DockLayout) {
    viewModelScope.launch { prefs.saveLayout(layout) }
  }

  fun reset() {
    viewModelScope.launch { prefs.resetLayout() }
  }
}
