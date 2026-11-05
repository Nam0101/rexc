package dev.namnv.rexc.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Stateful host for the settings screen. */
@Composable
fun SettingsRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
  val viewModel: SettingsViewModel = hiltViewModel()
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  SettingsScreen(state = state, onEvent = viewModel::onEvent, onBack = onBack, modifier = modifier)
}
