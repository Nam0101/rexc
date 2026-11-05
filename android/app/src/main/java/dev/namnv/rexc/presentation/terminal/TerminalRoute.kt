package dev.namnv.rexc.presentation.terminal

import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.namnv.rexc.navigation.Terminal
import dev.namnv.rexc.presentation.common.CollectSideEffect

/** Stateful host for one session's terminal. */
@Composable
fun TerminalRoute(key: Terminal, onBack: () -> Unit, modifier: Modifier = Modifier) {
  val viewModel: TerminalViewModel =
    hiltViewModel<TerminalViewModel, TerminalViewModel.Factory>(key = key.toString()) { it.create(key) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val snackbar = remember { SnackbarHostState() }
  val input = remember { TerminalInput(context) }
  LaunchedEffect(state.terminal) { input.terminal = state.terminal }
  LaunchedEffect(state.gone) { if (state.gone) onBack() }
  viewModel.eventFlow.CollectSideEffect { effect ->
    when (effect) {
      is TerminalEffect.Message -> snackbar.showSnackbar(effect.text)
      is TerminalEffect.OpenUrl -> runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, effect.url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }
    }
  }
  TerminalScreen(
    state = state,
    input = input,
    frames = viewModel.frames,
    snackbar = snackbar,
    onEvent = viewModel::onEvent,
    onBack = onBack,
    modifier = modifier,
  )
}
