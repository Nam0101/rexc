package dev.namnv.rexc.presentation.home

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import dev.namnv.rexc.core.ui.theme.Colors

/** A swipe on a session row opens one of these; the row snaps back and the dialog decides. */
internal sealed interface SessionDialog {
  val hostId: String
  val session: SessionUi

  data class Close(override val hostId: String, override val session: SessionUi) : SessionDialog
  data class Rename(override val hostId: String, override val session: SessionUi) : SessionDialog
}

@Composable
internal fun SessionDialogs(dialog: SessionDialog?, onDismiss: () -> Unit, onEvent: (HomeUiEvent) -> Unit) {
  when (dialog) {
    null -> Unit
    is SessionDialog.Close -> CloseSessionDialog(dialog, onDismiss, onEvent)
    is SessionDialog.Rename -> RenameSessionDialog(dialog, onDismiss, onEvent)
  }
}

@Composable
private fun CloseSessionDialog(dialog: SessionDialog.Close, onDismiss: () -> Unit, onEvent: (HomeUiEvent) -> Unit) {
  val tabs = dialog.session.tabCount
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Colors.surface,
    title = { Text("Close \"${dialog.session.label}\"?") },
    text = {
      Text("Its $tabs tab${if (tabs == 1) "" else "s"} and every program running in them will end.")
    },
    confirmButton = {
      TextButton(onClick = {
        onEvent(HomeUiEvent.CloseSession(dialog.hostId, dialog.session.id))
        onDismiss()
      }) { Text("Close session", color = Colors.danger) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
private fun RenameSessionDialog(dialog: SessionDialog.Rename, onDismiss: () -> Unit, onEvent: (HomeUiEvent) -> Unit) {
  var label by remember(dialog) { mutableStateOf(dialog.session.label) }
  val save = {
    onEvent(HomeUiEvent.RenameSession(dialog.hostId, dialog.session.id, label))
    onDismiss()
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Colors.surface,
    title = { Text("Rename session") },
    text = {
      OutlinedTextField(
        value = label,
        onValueChange = { label = it },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save() }),
      )
    },
    confirmButton = { TextButton(onClick = save, enabled = label.isNotBlank()) { Text("Save") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
