package dev.namnv.rexc.presentation.share

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.namnv.rexc.core.ui.theme.Colors
import dev.namnv.rexc.core.ui.theme.MonoFamily
import dev.namnv.rexc.core.ui.theme.Radius
import dev.namnv.rexc.core.ui.theme.Space
import dev.namnv.rexc.navigation.Terminal
import dev.namnv.rexc.presentation.common.AgentBadge
import dev.namnv.rexc.presentation.home.STALE_ALPHA
import dev.namnv.rexc.presentation.home.StateDot
import dev.namnv.rexc.presentation.home.hostColor
import dev.namnv.rexc.presentation.home.hostLabel
import dev.namnv.rexc.presentation.home.toUi
import dev.namnv.rexc.presentation.terminal.paneName
import uniffi.rexc_ffi.Block
import uniffi.rexc_ffi.Host
import uniffi.rexc_ffi.ProgramState
import uniffi.rexc_ffi.Session

private val SHARE_HEIGHT = 520.dp
private const val PREVIEW_LINES = 3

/** One row of the picker, flat so the lazy column composes only what is on screen. */
private sealed interface ShareRow {
  val key: String

  data class HostHeader(val host: Host) : ShareRow {
    override val key get() = "h:${host.hostId}"
  }

  data class SessionRow(val host: Host, val session: Session) : ShareRow {
    override val key get() = "s:${host.hostId}/${session.sessionId}"
  }

  data class PaneRow(val host: Host, val session: Session, val block: Block, val tab: String?) : ShareRow {
    override val key get() = "b:${host.hostId}/${block.blockId}"
  }
}

private fun List<Host>.toRows(): List<ShareRow> = flatMap { host ->
  buildList {
    add(ShareRow.HostHeader(host))
    for (session in host.sessions) {
      add(ShareRow.SessionRow(host, session))
      for (window in session.windows) {
        val tab = window.displayName.takeIf { session.windows.size > 1 }
        window.blocks.forEach { add(ShareRow.PaneRow(host, session, it, tab)) }
      }
    }
  }
}

/**
 * Where shared text goes: every host (cached and stale ones too), its sessions and their panes.
 * A session opens its focused pane; a pane opens itself. Dismissing discards the text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(text: String, hosts: List<Host>, onPick: (Terminal) -> Unit, onDismiss: () -> Unit) {
  val rows = remember(hosts) { hosts.toRows() }
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Colors.surface,
  ) {
    LazyColumn(Modifier.heightIn(max = SHARE_HEIGHT).navigationBarsPadding().padding(horizontal = Space.l)) {
      item(key = "title") { Header(text) }
      if (rows.isEmpty()) {
        item(key = "empty") { Text("Add a host first.", color = Colors.muted, modifier = Modifier.padding(Space.m)) }
      }
      items(rows.size, key = { rows[it].key }) { i ->
        when (val row = rows[i]) {
          is ShareRow.HostHeader -> HostHeader(row.host)
          is ShareRow.SessionRow -> SessionItem(row) { onPick(Terminal(row.host.hostId, row.session.sessionId)) }
          is ShareRow.PaneRow -> PaneItem(row) {
            onPick(Terminal(row.host.hostId, row.session.sessionId, row.block.blockId))
          }
        }
      }
    }
  }
}

@Composable
private fun Header(text: String) {
  Column {
    Text("Paste into a terminal", color = Colors.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Text(
      text,
      color = Colors.muted,
      fontFamily = MonoFamily,
      fontSize = 12.sp,
      maxLines = PREVIEW_LINES,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.fillMaxWidth().padding(vertical = Space.s).clip(RoundedCornerShape(Radius.chip))
        .background(Colors.bg).padding(Space.s),
    )
  }
}

@Composable
private fun HostHeader(host: Host) {
  Row(
    Modifier.padding(top = Space.m).alpha(if (host.stale) STALE_ALPHA else 1f),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(6.dp).clip(CircleShape).background(hostColor(host.status)))
    Spacer(Modifier.width(6.dp))
    Text(
      "${host.name} · ${hostLabel(host.status, host.sessions.size)}",
      color = Colors.muted,
      fontSize = 12.sp,
      maxLines = 1,
    )
  }
}

@Composable
private fun SessionItem(row: ShareRow.SessionRow, onClick: () -> Unit) {
  val session = remember(row.session) { row.session.toUi() }
  Row(
    Modifier.fillMaxWidth().padding(top = Space.s).clip(RoundedCornerShape(Radius.chip)).clickable(onClick = onClick)
      .padding(Space.s).alpha(if (row.host.stale) STALE_ALPHA else 1f),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    AgentBadge(session.app, session.state, 22.dp)
    Spacer(Modifier.width(Space.s))
    Text(
      session.label.ifBlank { "Session" },
      color = Colors.ink,
      fontSize = 15.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun PaneItem(row: ShareRow.PaneRow, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().padding(start = Space.l, top = 2.dp).clip(RoundedCornerShape(Radius.chip))
      .background(Colors.bg).clickable(onClick = onClick).padding(Space.s)
      .alpha(if (row.host.stale) STALE_ALPHA else 1f),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    StateDot(row.block.state ?: ProgramState.IDLE, 8.dp)
    Spacer(Modifier.width(Space.s))
    Text(
      row.block.paneName(),
      color = Colors.ink,
      fontSize = 14.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f, fill = false),
    )
    row.tab?.let { Text("  $it", color = Colors.muted, fontSize = 12.sp, maxLines = 1) }
  }
}
