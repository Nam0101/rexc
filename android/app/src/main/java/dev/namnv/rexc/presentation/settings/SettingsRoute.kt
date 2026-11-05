package dev.namnv.rexc.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.namnv.rexc.lock.DeviceAuth

/** Stateful host for the settings screen. [authenticate] asks for the owner; the app lock changes only after it. */
@Composable
fun SettingsRoute(
  onBack: () -> Unit,
  authenticate: (onResult: (Boolean) -> Unit) -> Unit,
  modifier: Modifier = Modifier,
) {
  val viewModel: SettingsViewModel = hiltViewModel()
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  // Re-checked on every return, so a screen lock set in Android's settings shows at once.
  var lockAvailable by remember { mutableStateOf(DeviceAuth.available(context)) }
  LifecycleResumeEffect(Unit) {
    lockAvailable = DeviceAuth.available(context)
    onPauseOrDispose {}
  }
  SettingsScreen(
    state = state,
    onEvent = { event ->
      when {
        event !is SettingsUiEvent.AppLockChanged -> viewModel.onEvent(event)
        // Nothing to ask for: only switching a stuck lock off is allowed.
        !lockAvailable -> if (!event.on) viewModel.onEvent(event)
        else -> authenticate { ok -> if (ok) viewModel.onEvent(event) }
      }
    },
    onBack = onBack,
    modifier = modifier,
    lockAvailable = lockAvailable,
  )
}
