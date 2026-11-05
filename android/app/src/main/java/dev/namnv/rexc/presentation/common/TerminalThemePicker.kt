package dev.namnv.rexc.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.namnv.rexc.core.ui.theme.Colors
import dev.namnv.rexc.core.ui.theme.Space
import uniffi.rexc_ffi.TerminalTheme

/** ANSI red through cyan: enough of a palette to tell themes apart at a glance. */
private const val SWATCH_FIRST = 1
private const val SWATCH_LAST = 6

/**
 * "Terminal colors": the chosen theme and its swatch; a tap opens the list.
 * [unsetLabel] names the null choice, which comes first ("From server", "Same as app").
 */
@Composable
fun TerminalColorsRow(
  themes: List<TerminalTheme>,
  selected: String?,
  unsetLabel: String,
  onSelect: (String?) -> Unit,
  contentPadding: PaddingValues = PaddingValues(),
) {
  var open by remember { mutableStateOf(false) }
  val theme = themes.firstOrNull { it.name == selected }
  Row(
    Modifier.fillMaxWidth().clickable { open = true }.padding(contentPadding),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f).padding(end = Space.m)) {
      Text("Terminal colors", color = Colors.ink, fontSize = 16.sp)
      Text(theme?.name ?: unsetLabel, color = Colors.muted, fontSize = 12.sp)
    }
    if (theme != null) ThemeSwatch(theme)
  }
  if (open) {
    TerminalThemeDialog(themes, selected = theme?.name, unsetLabel, onDismiss = { open = false }) {
      open = false
      onSelect(it)
    }
  }
}

@Composable
private fun TerminalThemeDialog(
  themes: List<TerminalTheme>,
  selected: String?,
  unsetLabel: String,
  onDismiss: () -> Unit,
  onSelect: (String?) -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Colors.surface,
    title = { Text("Terminal colors") },
    text = {
      LazyColumn(Modifier.heightIn(max = 420.dp)) {
        item { ThemeOption(unsetLabel, theme = null, selected = selected == null) { onSelect(null) } }
        items(themes, key = { it.name }) { t ->
          ThemeOption(t.name, theme = t, selected = selected == t.name) { onSelect(t.name) }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
private fun ThemeOption(label: String, theme: TerminalTheme?, selected: Boolean, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick)
      .padding(vertical = Space.s),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = null)
    Text(label, color = Colors.ink, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(horizontal = Space.m))
    if (theme != null) ThemeSwatch(theme)
  }
}

/** The theme's background with a few of its ANSI colors on it. Decorative: the name says which theme. */
@Composable
private fun ThemeSwatch(theme: TerminalTheme) {
  val shape = RoundedCornerShape(6.dp)
  Row(
    Modifier.clip(shape).background(Color(theme.background.toInt())).border(1.dp, Colors.line, shape)
      .padding(horizontal = 6.dp, vertical = 5.dp),
    horizontalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    for (i in SWATCH_FIRST..SWATCH_LAST) {
      val c = theme.palette.getOrNull(i) ?: continue
      Box(Modifier.size(8.dp).clip(CircleShape).background(Color(c.toInt())))
    }
  }
}
