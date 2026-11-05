package dev.namnv.rexc.presentation.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.namnv.rexc.core.ui.theme.Colors
import dev.namnv.rexc.core.ui.theme.MonoFamily
import dev.namnv.rexc.core.ui.theme.Radius
import dev.namnv.rexc.core.ui.theme.Space
import dev.namnv.rexc.navigation.Terminal
import dev.namnv.rexc.presentation.home.StateDot
import uniffi.rexc_ffi.Block
import uniffi.rexc_ffi.Host
import uniffi.rexc_ffi.HostStatus
import uniffi.rexc_ffi.ProgramState
import uniffi.rexc_ffi.Session

private val SWITCHER_HEIGHT = 520.dp
private const val SELECTED_TINT = 0.16f

/** One row of the switcher; the list is flat so the lazy column only composes what is on screen. */
private sealed interface SwitcherRow {
  val key: String

  data class HostHeader(val host: Host) : SwitcherRow {
    override val key get() = "h:${host.hostId}"
  }

  data class SessionHeader(val session: Session) : SwitcherRow {
    override val key get() = "s:${session.sessionId}"
  }

  data class Pane(val host: Host, val session: Session, val block: Block, val tab: String?) : SwitcherRow {
    override val key get() = "b:${block.blockId}"
  }
}

private fun List<Host>.toRows(): List<SwitcherRow> = flatMap { host ->
  buildList {
    add(SwitcherRow.HostHeader(host))
    for (session in host.sessions) {
      add(SwitcherRow.SessionHeader(session))
      for (window in session.windows) {
        val tab = window.displayName.takeIf { session.windows.size > 1 }
        window.blocks.forEach { add(SwitcherRow.Pane(host, session, it, tab)) }
      }
    }
  }
}

/** Every host, session and pane; previews load only for the rows in view and are dropped with the sheet. */
@Composable
internal fun SwitcherSheet(
  state: TerminalUiState,
  onEvent: (TerminalUiEvent) -> Unit,
  close: () -> Unit,
  viewModel: PaletteViewModel,
  modifier: Modifier = Modifier,
) {
  val hosts by viewModel.hosts.collectAsStateWithLifecycle()
  val previews = viewModel.previews.collectAsState()
  val rows = remember(hosts) { hosts.toRows() }
  LaunchedEffect(Unit) { viewModel.clearPreviews() }
  LazyColumn(modifier.heightIn(max = SWITCHER_HEIGHT).padding(horizontal = Space.l)) {
    items(rows.size, key = { rows[it].key }) { i ->
      when (val row = rows[i]) {
        is SwitcherRow.HostHeader -> HostHeader(row.host)
        is SwitcherRow.SessionHeader -> Text(
          row.session.label.ifBlank { "Session" },
          color = Colors.ink,
          fontSize = 15.sp,
          modifier = Modifier.padding(top = Space.s, start = Space.s),
        )
        is SwitcherRow.Pane -> PaneRow(row, state.currentBlockId, previews, viewModel) {
          close()
          onEvent(TerminalUiEvent.Open(Terminal(row.host.hostId, row.session.sessionId, row.block.blockId)))
        }
      }
    }
  }
}

@Composable
private fun HostHeader(host: Host) {
  Row(Modifier.padding(top = Space.m), verticalAlignment = Alignment.CenterVertically) {
    Box(
      Modifier.size(6.dp).clip(CircleShape)
        .background(if (host.status == HostStatus.CONNECTED) Colors.ok else Colors.amber),
    )
    Spacer(Modifier.width(6.dp))
    Text(host.name, color = Colors.muted, fontSize = 12.sp, maxLines = 1)
  }
}

@Composable
private fun PaneRow(
  row: SwitcherRow.Pane,
  currentBlockId: String?,
  previews: State<Map<String, String>>,
  viewModel: PaletteViewModel,
  onClick: () -> Unit,
) {
  val block = row.block
  val live = row.host.status == HostStatus.CONNECTED
  LaunchedEffect(block.blockId, live) {
    if (live) viewModel.loadPreview(row.host.hostId, row.session.sessionId, block.blockId)
  }
  val preview by remember(block.blockId) { derivedStateOf { previews.value[block.blockId].orEmpty() } }
  Column(
    Modifier.fillMaxWidth().padding(start = Space.s, top = 2.dp).clip(RoundedCornerShape(Radius.chip))
      .background(if (block.blockId == currentBlockId) Colors.accent.copy(alpha = SELECTED_TINT) else Colors.surface)
      .clickable(onClick = onClick).padding(Space.s),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      StateDot(block.state ?: ProgramState.IDLE, 8.dp)
      Spacer(Modifier.width(Space.s))
      Text(
        block.paneName(),
        color = Colors.ink,
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      row.tab?.let { Text("  $it", color = Colors.muted, fontSize = 12.sp, maxLines = 1) }
    }
    if (preview.isNotEmpty()) {
      Text(
        preview,
        color = Colors.muted,
        fontFamily = MonoFamily,
        fontSize = 11.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
