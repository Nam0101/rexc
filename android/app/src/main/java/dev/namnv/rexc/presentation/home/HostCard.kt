package dev.namnv.rexc.presentation.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
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
import dev.namnv.rexc.core.ui.theme.Motion
import dev.namnv.rexc.core.ui.theme.Radius
import dev.namnv.rexc.core.ui.theme.Space
import dev.namnv.rexc.navigation.Terminal
import dev.namnv.rexc.presentation.common.AgentBadge
import dev.namnv.rexc.presentation.common.LiveDot
import dev.namnv.rexc.presentation.common.MenuEntry
import dev.namnv.rexc.presentation.common.pressScale
import dev.namnv.rexc.presentation.common.sharedBoundsFor
import uniffi.rexc_ffi.HostStatus
import uniffi.rexc_ffi.ProgramState

private const val COLLAPSED_CHEVRON_DEGREES = -90f
private const val ICON_MORPH_SCALE = 0.6f
private val badgeSize = 28.dp

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
    // Unfolds from under the header; folding is quicker, as leaving should be.
    AnimatedVisibility(
      visible = !host.collapsed,
      enter = expandVertically(Motion.enter(), expandFrom = Alignment.Top) + fadeIn(Motion.enter()),
      exit = shrinkVertically(Motion.leave(Motion.STANDARD), shrinkTowards = Alignment.Top) + fadeOut(Motion.leave()),
    ) {
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
  val chevron by animateFloatAsState(
    if (host.collapsed) COLLAPSED_CHEVRON_DEGREES else 0f,
    animationSpec = Motion.settle(),
    label = "chevron",
  )
  val dot by animateColorAsState(hostColor(host.status), Motion.move(), label = "hostDot")
  Row(
    Modifier.fillMaxWidth().clickable { onEvent(HomeUiEvent.ToggleCollapsed(host.id)) }.then(dragHandle)
      .padding(start = Space.l, end = Space.xs, top = Space.s, bottom = Space.s),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
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
    host.state?.takeIf { host.collapsed && it != ProgramState.IDLE }?.let { LiveDot(it, 10.dp) }
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
    Modifier.fillMaxWidth().pressScale().clickable(
      enabled = enabled,
      onClick = onClick,
    ).padding(horizontal = Space.l, vertical = Space.m),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // The plus shrinks away and the spinner grows in its place while the session is made.
    AnimatedContent(targetState = busy, transitionSpec = { morph() }, label = "newSession") { spinning ->
      if (spinning) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Colors.accent)
      } else {
        Icon(Icons.Default.Add, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
      }
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

/**
 * One line per session: who runs in it (badge with its state), the label, and what the
 * program is doing (or how many tabs it has). A new activity line slides up over the old.
 */
@Composable
private fun SessionRow(session: SessionUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Row(
    modifier.fillMaxWidth().pressScale().background(Colors.surface).clickable(onClick = onClick)
      .padding(horizontal = Space.l, vertical = Space.m),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    AgentBadge(session.app, session.state, badgeSize)
    Spacer(Modifier.width(Space.m))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(session.label, color = Colors.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      AnimatedContent(
        targetState = session.activity ?: "${session.tabCount} tab${if (session.tabCount == 1) "" else "s"}",
        transitionSpec = { tickerTape() },
        contentAlignment = Alignment.CenterStart,
        label = "activity",
      ) { line ->
        Text(line, color = Colors.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    Spacer(Modifier.width(Space.s))
    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Colors.line, modifier = Modifier.size(18.dp))
  }
}

/** A line of text replaced like a ticker: the new one rises in as the old one drifts up and out. */
private fun tickerTape() =
  (slideInVertically(Motion.enter(Motion.STANDARD)) { it / 2 } + fadeIn(Motion.enter(Motion.STANDARD)))
    .togetherWith(slideOutVertically(Motion.leave()) { -it / 2 } + fadeOut(Motion.leave()))

/** One small icon turning into another: shrink and fade out, grow and fade in. */
private fun morph() =
  (scaleIn(Motion.enter(Motion.STANDARD), ICON_MORPH_SCALE) + fadeIn(Motion.enter(Motion.STANDARD)))
    .togetherWith(scaleOut(Motion.leave(), ICON_MORPH_SCALE) + fadeOut(Motion.leave()))
