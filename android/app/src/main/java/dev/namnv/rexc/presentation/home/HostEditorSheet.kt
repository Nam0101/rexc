package dev.namnv.rexc.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import dev.namnv.rexc.core.ui.theme.Colors
import dev.namnv.rexc.core.ui.theme.Space

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostEditorSheet(editor: HostEditor, onEvent: (HomeUiEvent) -> Unit) {
  ModalBottomSheet(onDismissRequest = { onEvent(HomeUiEvent.DismissEditor) }, containerColor = Colors.surface) {
    Column(Modifier.padding(horizontal = Space.xl).padding(bottom = Space.xl)) {
      Text(
        if (editor.hostId == null) "Add server" else "Edit server",
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = Colors.ink,
      )
      Spacer(Modifier.height(Space.l))
      OutlinedTextField(
        value = editor.address,
        onValueChange = { onEvent(HomeUiEvent.EditorChanged(editor.name, it, editor.backgroundAlerts)) },
        label = { Text("Address") },
        placeholder = { Text("name.tailxxxx.ts.net or 100.64.0.1:7777") },
        supportingText = { Text(editor.error ?: "Tailnet name, host:port, or an https:// URL") },
        isError = editor.error != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
          keyboardType = KeyboardType.Uri,
          capitalization = KeyboardCapitalization.None,
        ),
        modifier = Modifier.fillMaxWidth(),
      )
      Spacer(Modifier.height(Space.s))
      OutlinedTextField(
        value = editor.name,
        onValueChange = { onEvent(HomeUiEvent.EditorChanged(it, editor.address, editor.backgroundAlerts)) },
        label = { Text("Name (optional)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      Spacer(Modifier.height(Space.m))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("Alerts in the background", color = Colors.ink, fontSize = 15.sp)
          Text(
            "Keep this server connected while the app is away, behind a quiet notification, " +
              "so a program that needs you can reach you.",
            color = Colors.muted,
            fontSize = 12.sp,
          )
        }
        Switch(checked = editor.backgroundAlerts, onCheckedChange = {
          onEvent(HomeUiEvent.EditorChanged(editor.name, editor.address, it))
        })
      }
      Spacer(Modifier.height(Space.l))
      Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { onEvent(HomeUiEvent.DismissEditor) }) { Text("Cancel") }
        Spacer(Modifier.width(Space.s))
        Button(onClick = {
          onEvent(HomeUiEvent.SaveHost)
        }, enabled = !editor.saving) { Text(if (editor.saving) "Saving…" else "Save") }
      }
    }
  }
}
