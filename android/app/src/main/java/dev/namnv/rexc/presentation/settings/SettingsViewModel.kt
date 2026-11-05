package dev.namnv.rexc.presentation.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.namnv.rexc.BuildConfig
import dev.namnv.rexc.core.dispatcher.WHILE_UI_SUBSCRIBED
import dev.namnv.rexc.data.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Edits the user's preferences; every change is written straight through to the store. */
@HiltViewModel
class SettingsViewModel @Inject constructor(private val repo: SettingsRepository) : ViewModel() {
  private val static = SettingsUiState(
    version = BuildConfig.VERSION_NAME,
    dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
  )

  val uiState: StateFlow<SettingsUiState> = repo.settings.map { static.copy(settings = it) }
    .stateIn(viewModelScope, WHILE_UI_SUBSCRIBED, static.copy(settings = repo.settings.value))

  fun onEvent(event: SettingsUiEvent) {
    viewModelScope.launch { repo.update { it.reduce(event) } }
  }
}
