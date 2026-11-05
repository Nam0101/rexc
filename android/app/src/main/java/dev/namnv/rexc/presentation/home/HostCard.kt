package dev.namnv.rexc.presentation.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.namnv.rexc.core.ui.theme.Colors
import dev.namnv.rexc.core.ui.theme.MonoFamily
import dev.namnv.rexc.core.ui.theme.Radius
import dev.namnv.rexc.core.ui.theme.Space
import dev.namnv.rexc.navigation.Terminal
import dev.namnv.rexc.presentation.common.MenuEntry
import dev.namnv.rexc.presentation.common.sharedBoundsFor
import uniffi.rexc_ffi.HostStatus
import uniffi.rexc_ffi.ProgramState

private const val COLLAPSED_CHEVRON_DEGREES = -90f
private val chipMaxWidth = 140.dp

/**
 * One server: header (tap folds it, long press drags it), its sessions, and a way to start another.
 * [dragHandle] carries the long-press gesture the list owns.
 */
@Composable
internal fun HostCard(
  host: HostUi,
  busy: Boolean,
  onEvent: (HomeUiEvent) -> Unit,
  onSessionDialog: (SessionDialog) -> Unit,
  dragHandle: Modifier,
  modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Colors.surface)) {
    HostHeader(host, onEvent, dragHandle)
    AnimatedVisibility(visible = !host.collapsed) {
      Column {
        HostBody(host, onEvent, onSessionDialog)
        if (host.status == HostStatus.CONNECTED) {
          NewSessionRow(enabled = !busy, busy = busy) { onEvent(HomeUiEvent.NewSession(host.id)) }
        }
      }
    }
  }
}

@Composable
private fun HostHeader(host: HostUi, onEvent: (HomeUiEvent) -> Unit, dragHandle: Modifier) {
  val chevron by animateFloatAsState(if (host.collapsed) COLLAPSED_CHEVRON_DEGREES else 0f, label = "chevron")
  Row(
    Modifier.fillMaxWidth().clickable { onEvent(HomeUiEvent.ToggleCollapsed(host.id)) }.then(dragHandle)
      .padding(start = Space.l, end = Space.xs, top = Space.s, bottom = Space.s),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(hostColor(host.status)))
    Spacer(Modifier.width(Space.s))
    Column(Modifier.weight(1f)) {
      Text(host.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Colors.ink, maxLines = 1)
      val failed = host.error != null && host.status != HostStatus.CONNECTED
      Text(
        host.error.takeIf { failed } ?: hostLabel(host.status, host.sessions.size),
        fontSize = 12.sp,
        color = if (failed) Colors.danger else Colors.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    host.state?.takeIf { host.collapsed && it != ProgramState.IDLE }?.let { StateDot(it, 10.dp) }
    Icon(
      Icons.Default.ExpandMore,
      contentDescription = if (host.collapsed) "Expand" else "Collapse",
      tint = Colors.muted,
      modifier = Modifier.padding(horizontal = Space.s).rotate(chevron),
    )
    HostMenu(host, onEvent)
  }
}

@Composable
private fun HostBody(host: HostUi, onEvent: (HomeUiEvent) -> Unit, onSessionDialog: (SessionDialog) -> Unit) {
  if (host.sessions.isEmpty()) {
    EmptyHostRow(host, onConnect = { onEvent(HomeUiEvent.Connect(host.id)) })
    return
  }
  host.sessions.forEach { session ->
    SwipeableSessionRow(
      session = session,
      sharedKey = Terminal(host.id, session.id).sharedKey,
      onOpen = { onEvent(HomeUiEvent.OpenSession(host.id, session.id)) },
      onClose = { onSessionDialog(SessionDialog.Close(host.id, session)) },
      onRename = { onSessionDialog(SessionDialog.Rename(host.id, session)) },
    )
  }
}

/** What a server with nothing to list says: offline, still connecting, or simply no sessions yet. */
@Composable
private fun EmptyHostRow(host: HostUi, onConnect: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    when (host.status) {
      HostStatus.OFFLINE -> {
        Icon(Icons.Default.CloudOff, contentDescription = null, tint = Colors.muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.m))
        Text("Not connected", color = Colors.muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onConnect) { Text("Connect", color = Colors.accent) }
      }
      HostStatus.CONNECTING, HostStatus.RECONNECTING -> {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Colors.accent)
        Spacer(Modifier.width(Space.m))
        Text(hostLabel(host.status, 0), color = Colors.muted, fontSize = 14.sp)
      }
      HostStatus.CONNECTED -> Text("No sessions yet", color = Colors.muted, fontSize = 14.sp)
    }
  }
}

@Composable
private fun HostMenu(host: HostUi, onEvent: (HomeUiEvent) -> Unit) {
  var menu by remember { mutableStateOf(false) }
  val pick = { event: HomeUiEvent ->
    menu = false
    onEvent(event)
  }
  Box {
    IconButton(onClick = {
      menu = true
    }) { Icon(Icons.Default.MoreVert, contentDescription = "Server menu", tint = Colors.muted) }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
      if (host.status == HostStatus.OFFLINE) {
        MenuEntry("Connect") { pick(HomeUiEvent.Connect(host.id)) }
      } else {
        MenuEntry("Disconnect") { pick(HomeUiEvent.Disconnect(host.id)) }
      }
      MenuEntry("Edit") { pick(HomeUiEvent.EditHostClicked(host.id)) }
      MenuEntry("Remove", color = Colors.danger) { pick(HomeUiEvent.RemoveHost(host.id)) }
    }
  }
}

@Composable
private fun NewSessionRow(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
  val tint = if (enabled) Colors.accent else Colors.muted
  Row(
    Modifier.fillMaxWidth().clickable(
      enabled = enabled,
      onClick = onClick,
    ).padding(horizontal = Space.l, vertical = Space.m),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (busy) {
      CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Colors.accent)
    } else {
      Icon(Icons.Default.Add, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
    Spacer(Modifier.width(Space.s))
    Text("New session", color = tint, fontSize = 15.sp)
  }
}

/** Swipe right to rename, left to close; both only ask, the row always snaps back. */
@Composable
private fun SwipeableSessionRow(
  session: SessionUi,
  sharedKey: String,
  onOpen: () -> Unit,
  onClose: () -> Unit,
  onRename: () -> Unit,
) {
  val close by rememberUpdatedState(onClose)
  val rename by rememberUpdatedState(onRename)
  val swipe = rememberSwipeToDismissBoxState()
  // Only ask: once the swipe settles on a side, open the dialog and put the row back.
  LaunchedEffect(swipe.currentValue) {
    when (swipe.currentValue) {
      SwipeToDismissBoxValue.StartToEnd -> rename()
      SwipeToDismissBoxValue.EndToStart -> close()
      SwipeToDismissBoxValue.Settled -> return@LaunchedEffect
    }
    swipe.reset()
  }
  SwipeToDismissBox(
    state = swipe,
    backgroundContent = { SwipeBackground(swipe.dismissDirection) },
  ) {
    SessionRow(session, onOpen, Modifier.sharedBoundsFor(sharedKey))
  }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue) {
  val (color, icon, align) = when (direction) {
    SwipeToDismissBoxValue.StartToEnd -> Triple(Colors.accent, Icons.Default.Edit, Alignment.CenterStart)
    SwipeToDismissBoxValue.EndToStart -> Triple(Colors.danger, Icons.Default.Delete, Alignment.CenterEnd)
    SwipeToDismissBoxValue.Settled -> Triple(Colors.surface, null, Alignment.Center)
  }
  Box(Modifier.fillMaxSize().background(color).padding(horizontal = Space.xl), contentAlignment = align) {
    icon?.let { Icon(it, contentDescription = null, tint = Colors.onAccent) }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionRow(session: SessionUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Row(
    modifier.fillMaxWidth().background(Colors.surface).clickable(onClick = onClick)
      .padding(horizontal = Space.l, vertical = Space.m),
    verticalAlignment = Alignment.Top,
  ) {
    Box(
      Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Colors.badge),
      contentAlignment = Alignment.Center,
    ) {
      Text(">_", fontFamily = MonoFamily, fontWeight = FontWeight.Bold, color = Colors.badgeInk, fontSize = 13.sp)
    }
    Spacer(Modifier.width(Space.m))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          session.label,
          color = Colors.ink,
          fontSize = 16.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(Space.s))
        Text("${session.tabCount} tab${if (session.tabCount == 1) "" else "s"}", color = Colors.muted, fontSize = 12.sp)
      }
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
      ) {
        session.tabs.forEach { TabChipView(it) }
        if (session.hiddenTabs > 0) TabChipView(TabChip("more", "+${session.hiddenTabs}", null))
      }
    }
  }
}

@Composable
private fun TabChipView(tab: TabChip) {
  Row(
    Modifier.clip(
      RoundedCornerShape(Radius.chip),
    ).background(Colors.key).padding(horizontal = Space.s, vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    tab.state?.let {
      StateDot(it, 6.dp)
      Spacer(Modifier.width(5.dp))
    }
    Text(
      tab.name,
      color = Colors.ink,
      fontSize = 12.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.widthIn(max = chipMaxWidth),
    )
  }
}
