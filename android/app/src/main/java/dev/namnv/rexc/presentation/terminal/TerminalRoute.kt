package dev.namnv.rexc.presentation.terminal

import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.namnv.rexc.navigation.Terminal
import dev.namnv.rexc.presentation.common.CollectSideEffect
import dev.namnv.rexc.presentation.common.LocalSettings

/** Stateful host for one session's terminal. */
@Composable
fun TerminalRoute(
  key: Terminal,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  onOpen: (Terminal) -> Unit = {},
  onRequest: (String) -> Unit = {},
  font: TerminalFont = TerminalFont.JETBRAINS_MONO,
  fontSize: Float = DEFAULT_FONT_SP,
  onFontSizeChanged: (Float) -> Unit = {},
  localEcho: Boolean = true,
) {
  val viewModel: TerminalViewModel =
    hiltViewModel<TerminalViewModel, TerminalViewModel.Factory>(key = key.toString()) { it.create(key) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val snackbar = remember { SnackbarHostState() }
  val input = remember { TerminalInput(context) }
  var askToOpen by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(state.terminal) { input.terminal = state.terminal }
  LaunchedEffect(state.gone) { if (state.gone) onBack() }
  KeepScreenOn(LocalSettings.current.keepScreenOn)
  // The engine posts no system alerts for the pane on screen (it knows when the app is away).
  DisposableEffect(state.terminal) {
    val terminal = state.terminal
    terminal?.setVisible(true)
    onDispose { terminal?.setVisible(false) }
  }
  val haptics = LocalHapticFeedback.current
  val hapticsOn = LocalSettings.current.haptics
  viewModel.bells.CollectSideEffect {
    if (hapticsOn) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
  }
  viewModel.eventFlow.CollectSideEffect { effect ->
    when (effect) {
      is TerminalEffect.Message -> snackbar.showSnackbar(effect.text)
      is TerminalEffect.OpenTerminal -> onOpen(effect.terminal)
      is TerminalEffect.Request -> onRequest(effect.id)
      is TerminalEffect.OpenUrl -> when (linkKind(effect.url)) {
        LinkKind.WEB -> context.openLink(Intent.ACTION_VIEW, effect.url)
        LinkKind.MAIL -> context.openLink(Intent.ACTION_SENDTO, effect.url)
        LinkKind.OTHER -> askToOpen = effect.url
        null -> Unit
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
    canvas = CanvasSettings(font, fontSize, onFontSizeChanged, localEcho),
  )
  askToOpen?.let { url ->
    OpenLinkDialog(url, onOpen = { context.openLink(Intent.ACTION_VIEW, url) }, onDismiss = { askToOpen = null })
  }
}

private fun Context.openLink(action: String, url: String) {
  runCatching { startActivity(Intent(action, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
