package dev.namnv.rexc.presentation.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
import dev.namnv.rexc.core.ui.theme.Space

private const val DIM = 0.55f

/** Find in the scrollback: the text, "n / m", and steps toward older and newer matches. */
@Composable
fun SearchBar(search: SearchUi, ink: Color, onEvent: (TerminalUiEvent) -> Unit, modifier: Modifier = Modifier) {
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  Row(
    modifier.fillMaxWidth().padding(start = Space.l),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Space.xs),
  ) {
    Box(Modifier.weight(1f)) {
      BasicTextField(
        value = search.query,
        onValueChange = { onEvent(TerminalUiEvent.SearchQuery(it)) },
        singleLine = true,
        textStyle = TextStyle(color = ink, fontSize = 15.sp),
        cursorBrush = SolidColor(ink),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onEvent(TerminalUiEvent.SearchNext(-1)) }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
      )
      if (search.query.isEmpty()) Text("Find in scrollback", color = ink.copy(alpha = DIM), fontSize = 15.sp)
    }
    Text(search.status(), color = ink.copy(alpha = DIM), fontSize = 12.sp)
    IconButton(onClick = { onEvent(TerminalUiEvent.SearchNext(-1)) }, enabled = search.count > 0) {
      Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Older match", tint = ink)
    }
    IconButton(onClick = { onEvent(TerminalUiEvent.SearchNext(1)) }, enabled = search.count > 0) {
      Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Newer match", tint = ink)
    }
    IconButton(onClick = { onEvent(TerminalUiEvent.CloseSearch) }) {
      Icon(Icons.Default.Close, contentDescription = "Close search", tint = ink)
    }
  }
}

/** "3 / 12" for the current match, "No matches" for a query that found none, nothing before one. */
fun SearchUi.status(): String = when {
  query.isEmpty() -> ""
  count == 0 -> "No matches"
  else -> "${(current ?: (count - 1)) + 1} / $count"
}
