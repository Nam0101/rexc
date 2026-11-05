package dev.namnv.rexc.presentation.terminal

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import dev.namnv.rexc.core.ui.theme.Colors

/** Asks before a pane goes away, when the "confirm before closing a pane" setting is on. */
@Composable
fun ClosePaneDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Colors.surface,
    title = { Text("Close this pane?") },
    text = { Text("Whatever is running in it stops.") },
    confirmButton = {
      TextButton(onClick = {
        onConfirm()
        onDismiss()
      }) { Text("Close", color = Colors.danger) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

/** The events that remove a pane and so deserve the confirmation. */
fun TerminalUiEvent.closesPane(): Boolean = this is TerminalUiEvent.ClosePane || this is TerminalUiEvent.CloseBlock

/** [onEvent] for everything, except that pane-closing events go to [onAsk] first when [confirm] is on. */
fun guardClose(
  confirm: Boolean,
  onEvent: (TerminalUiEvent) -> Unit,
  onAsk: (TerminalUiEvent) -> Unit,
): (TerminalUiEvent) -> Unit = { if (confirm && it.closesPane()) onAsk(it) else onEvent(it) }

/** Keeps the display on while the terminal is on screen, released when it leaves. */
@Composable
fun KeepScreenOn(enabled: Boolean) {
  val view = LocalView.current
  DisposableEffect(view, enabled) {
    view.keepScreenOn = enabled
    onDispose { view.keepScreenOn = false }
  }
}
