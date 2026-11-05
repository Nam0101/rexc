package dev.namnv.rexc.presentation.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import dev.namnv.rexc.core.ui.theme.Space
import dev.namnv.rexc.data.DockAction
import dev.namnv.rexc.data.DockItem

/** The three kinds of dock item, as the editor offers them. */
internal enum class ItemKind(val title: String) {
  KEY("Key"),
  TEXT("Snippet"),
  ACTION("Action"),
  ;

  companion object {
    fun of(item: DockItem): ItemKind = when (item) {
      is DockItem.Key -> KEY
      is DockItem.Text -> TEXT
      is DockItem.Action -> ACTION
    }
  }
}

/** The key names a cap can send: the named keys, F1..F12 and the letters (for ^C and friends). */
internal val keyNames: List<String> =
  listOf("escape", "tab", "enter", "backspace", "delete", "insert", "space", "home", "end", "page_up", "page_down") +
    listOf("arrow_up", "arrow_down", "arrow_left", "arrow_right") +
    (1..12).map { "f$it" } + ('a'..'z').map { "key_$it" }

/** What a key cap says when nobody typed a label: `^C`, `⌥⇧tab`... */
internal fun keyLabel(name: String, ctrl: Boolean, alt: Boolean, shift: Boolean): String {
  val base = name.removePrefix("key_")
  val prefix = (if (ctrl) "^" else "") + (if (alt) "⌥" else "") + (if (shift) "⇧" else "")
  return prefix + if (prefix.isNotEmpty() && base.length == 1) base.uppercase() else base
}

/** One line under the label in the editor: what the cap does. */
internal fun DockItem.summary(): String = when (this) {
  is DockItem.Key -> "key · " + keyLabel(name, ctrl, alt, shift)
  is DockItem.Text -> "snippet · " + text.replace("\n", "⏎") + if (enter) "⏎" else ""
  is DockItem.Action -> "action"
}

/** Add or change one item. A blank label falls back to what the item does. */
@Composable
internal fun ItemDialog(kind: ItemKind, initial: DockItem?, onSave: (DockItem) -> Unit, onDismiss: () -> Unit) {
  var label by remember { mutableStateOf(if (initial is DockItem.Action) "" else initial?.label.orEmpty()) }
  var key by remember { mutableStateOf((initial as? DockItem.Key) ?: DockItem.Key("escape", "")) }
  var snippet by remember { mutableStateOf((initial as? DockItem.Text)?.text.orEmpty()) }
  var enter by remember { mutableStateOf((initial as? DockItem.Text)?.enter ?: false) }
  var action by remember { mutableStateOf((initial as? DockItem.Action)?.id ?: DockAction.PALETTE.id) }
  val built: DockItem? = when (kind) {
    ItemKind.KEY -> key.copy(label = label.ifBlank { keyLabel(key.name, key.ctrl, key.alt, key.shift) })
    ItemKind.TEXT -> snippet.takeIf {
      it.isNotEmpty()
    }?.let { DockItem.Text(label.ifBlank { it.take(LABEL_FALLBACK) }, it, enter) }
    ItemKind.ACTION -> DockItem.Action(action)
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (initial == null) "Add ${kind.title.lowercase()}" else "Edit ${kind.title.lowercase()}") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (kind != ItemKind.ACTION) {
          OutlinedTextField(label, {
            label = it
          }, label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        when (kind) {
          ItemKind.KEY -> KeyForm(key) { key = it }
          ItemKind.TEXT -> SnippetForm(snippet, enter, { snippet = it }, { enter = it })
          ItemKind.ACTION -> ActionForm(action) { action = it }
        }
      }
    },
    confirmButton = { TextButton(onClick = { built?.let(onSave) }, enabled = built != null) { Text("Save") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

private const val LABEL_FALLBACK = 8

@Composable
private fun KeyForm(key: DockItem.Key, onChange: (DockItem.Key) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(key.name) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      keyNames.forEach { name ->
        DropdownMenuItem(text = { Text(name) }, onClick = {
          open = false
          onChange(key.copy(name = name))
        })
      }
    }
  }
  Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
    FilterChip(key.ctrl, { onChange(key.copy(ctrl = !key.ctrl)) }, label = { Text("ctrl") })
    FilterChip(key.alt, { onChange(key.copy(alt = !key.alt)) }, label = { Text("alt") })
    FilterChip(key.shift, { onChange(key.copy(shift = !key.shift)) }, label = { Text("shift") })
  }
}

@Composable
private fun SnippetForm(text: String, enter: Boolean, onText: (String) -> Unit, onEnter: (Boolean) -> Unit) {
  OutlinedTextField(text, onText, label = { Text("Text to type") }, minLines = 2, modifier = Modifier.fillMaxWidth())
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
    Switch(enter, onEnter)
    Text("Press Enter after")
  }
}

@Composable
private fun ActionForm(selected: String, onSelect: (String) -> Unit) {
  Column {
    DockAction.entries.forEach { a ->
      Row(Modifier.fillMaxWidth().clickable { onSelect(a.id) }, verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected == a.id, onClick = { onSelect(a.id) })
        Text(a.title)
      }
    }
  }
}
