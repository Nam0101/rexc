package dev.namnv.rexc.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import dev.namnv.rexc.core.ui.theme.Colors
import dev.namnv.rexc.core.ui.theme.Space
import dev.namnv.rexc.presentation.common.TerminalColorsRow
import kotlinx.collections.immutable.ImmutableList
import uniffi.rexc_ffi.TerminalTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostEditorSheet(editor: HostEditor, themes: ImmutableList<TerminalTheme>, onEvent: (HomeUiEvent) -> Unit) {
  ModalBottomSheet(onDismissRequest = { onEvent(HomeUiEvent.DismissEditor) }, containerColor = Colors.surface) {
    Column(Modifier.padding(horizontal = Space.xl).padding(bottom = Space.xl)) {
      Text(
        if (editor.hostId == null) "Add server" else "Edit server",
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = Colors.ink,
      )
      Spacer(Modifier.height(Space.l))
      AddressField(editor, onEvent)
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
      Spacer(Modifier.height(Space.s))
      TerminalColorsRow(
        themes = themes,
        selected = editor.theme,
        unsetLabel = "Same as app",
        onSelect = { onEvent(HomeUiEvent.EditorThemeChanged(it)) },
        contentPadding = PaddingValues(vertical = Space.s),
      )
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

/** The address, typed or scanned from the QR code that `rexc qr` prints on the desktop. */
@Composable
private fun AddressField(editor: HostEditor, onEvent: (HomeUiEvent) -> Unit) {
  val context = LocalContext.current
  var scanError by remember { mutableStateOf<String?>(null) }
  val error = editor.error ?: scanError
  OutlinedTextField(
    value = editor.address,
    onValueChange = {
      scanError = null
      onEvent(HomeUiEvent.EditorChanged(editor.name, it, editor.backgroundAlerts))
    },
    label = { Text("Address") },
    placeholder = { Text("name.tailxxxx.ts.net or 100.64.0.1:7777") },
    supportingText = { Text(error ?: "Tailnet name, host:port, or an https:// URL. Or scan the code from `rexc qr`.") },
    isError = error != null,
    singleLine = true,
    keyboardOptions = KeyboardOptions(
      keyboardType = KeyboardType.Uri,
      capitalization = KeyboardCapitalization.None,
    ),
    trailingIcon = {
      IconButton(onClick = {
        scanHostQr(
          context,
          onResult = { qr ->
            scanError = null
            onEvent(HomeUiEvent.EditorChanged(qr.name.ifEmpty { editor.name }, qr.address, editor.backgroundAlerts))
          },
          onError = { scanError = it },
        )
      }) { Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR code", tint = Colors.accent) }
    },
    modifier = Modifier.fillMaxWidth(),
  )
}
