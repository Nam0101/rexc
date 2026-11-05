package dev.namnv.rexc.presentation.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.namnv.rexc.core.ui.theme.Colors
import dev.namnv.rexc.core.ui.theme.Space

private val SHEET_LIST_HEIGHT = 420.dp

/** Search over the phone's actions and the server's; the best match runs on Enter. */
@Composable
internal fun PaletteSheet(
  state: TerminalUiState,
  host: SheetHost,
  sheets: SheetsState,
  viewModel: PaletteViewModel,
  modifier: Modifier = Modifier,
) {
  val server by viewModel.serverActions.collectAsStateWithLifecycle()
  val recents by viewModel.recents.collectAsStateWithLifecycle()
  val error by viewModel.error.collectAsStateWithLifecycle()
  val local = remember(state.tabs.size, state.info?.ownedByMe, host) { appActions(state, host, sheets::open) }
  var query by remember { mutableStateOf("") }
  val shown = remember(local, server, query, recents) {
    filterEntries(local.map { it.entry } + server, query, recents)
  }
  val pick = { entry: PaletteEntry ->
    val app = local.firstOrNull { it.entry.id == entry.id }
    if (app != null) {
      viewModel.remember(entry.id)
      sheets.close()
      app.run()
    } else {
      viewModel.runServerAction(entry.id, state.currentBlockId, onDone = sheets::close)
    }
  }
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) {
    viewModel.loadActions()
    focus.requestFocus()
  }
  Column(modifier.padding(horizontal = Space.l).padding(bottom = Space.l)) {
    OutlinedTextField(
      value = query,
      onValueChange = {
        query = it
        viewModel.dismissError()
      },
      placeholder = { Text("Type a command") },
      singleLine = true,
      isError = error != null,
      supportingText = error?.let { { Text(it) } },
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
      keyboardActions = KeyboardActions(onGo = { shown.firstOrNull()?.let(pick) }),
      modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    LazyColumn(Modifier.heightIn(max = SHEET_LIST_HEIGHT), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      items(shown, key = { it.id }) { entry -> PaletteRow(entry, onClick = { pick(entry) }) }
    }
  }
}

@Composable
private fun PaletteRow(entry: PaletteEntry, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Space.s),
    horizontalArrangement = Arrangement.spacedBy(Space.m),
  ) {
    Column(Modifier.weight(1f)) {
      Text(entry.title, color = Colors.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (entry.subtitle.isNotEmpty()) {
        Text(entry.subtitle, color = Colors.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    if (entry.category.isNotEmpty()) Text(entry.category, color = Colors.muted, fontSize = 12.sp, maxLines = 1)
  }
}
