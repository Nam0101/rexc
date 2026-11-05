package dev.namnv.rexc.presentation.home

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.namnv.rexc.alerts.BatteryOptimization
import dev.namnv.rexc.navigation.Terminal
import dev.namnv.rexc.presentation.common.CollectSideEffect

/** Stateful host for the home list; maps effects to navigation and messages. */
@Composable
fun HomeRoute(onOpenTerminal: (Terminal) -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
  val viewModel: HomeViewModel = hiltViewModel()
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  val context = LocalContext.current
  viewModel.eventFlow.CollectSideEffect { effect ->
    when (effect) {
      is HomeEffect.OpenTerminal -> onOpenTerminal(effect.target)
      is HomeEffect.Message -> snackbar.showSnackbar(effect.text)
      HomeEffect.RequestBatteryExemption -> runCatching {
        context.startActivity(BatteryOptimization.requestIntent(context))
      }.onFailure { snackbar.showSnackbar("Open Settings › Battery and allow rexc to run unrestricted") }
    }
  }
  HomeScreen(
    state = state,
    snackbar = snackbar,
    onEvent = viewModel::onEvent,
    onOpenSettings = onOpenSettings,
    modifier = modifier,
  )
}
