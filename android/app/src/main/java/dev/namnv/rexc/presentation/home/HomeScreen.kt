package dev.namnv.rexc.presentation.home

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.namnv.rexc.core.ui.theme.Colors
import dev.namnv.rexc.core.ui.theme.MonoFamily
import dev.namnv.rexc.core.ui.theme.Radius
import dev.namnv.rexc.core.ui.theme.Space
import dev.namnv.rexc.presentation.common.LocalSettings
import kotlinx.coroutines.delay

/** How long the dragged order stays on screen waiting for the engine to confirm it. */
private const val SETTLE_MS = 1_000L
private const val DRAG_SCALE = 1.02f
private const val DRAG_SHADOW_PX = 24f
private const val DRAG_Z = 1f

/** The home list: what needs you, then every host with its sessions. Stateless. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
  state: HomeUiState,
  snackbar: SnackbarHostState,
  onEvent: (HomeUiEvent) -> Unit,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var dialog by remember { mutableStateOf<SessionDialog?>(null) }
  val listState = rememberLazyListState()
  val haptics = LocalHapticFeedback.current
  val hapticsOn = LocalSettings.current.haptics
  val reorder = remember(listState) {
    HostReorderState(listState) { id, before ->
      if (hapticsOn) haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
      onEvent(HomeUiEvent.MoveHost(id, before))
    }
  }
  val hosts = arrange(state.hosts, reorder.order)
  val engineIds = state.hosts.map { it.id }
  val currentHosts by rememberUpdatedState(hosts)
  LaunchedEffect(reorder.order, engineIds, reorder.dragging) {
    if (reorder.dragging == null && reorder.order != null) {
      if (reorder.order != engineIds) delay(SETTLE_MS)
      reorder.settle()
    }
  }
  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = Colors.bg,
    snackbarHost = { SnackbarHost(snackbar) },
    contentWindowInsets = WindowInsets(0),
  ) { padding ->
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    PullToRefreshBox(
      isRefreshing = state.refreshing,
      onRefresh = { onEvent(HomeUiEvent.Refresh) },
      modifier = Modifier.fillMaxSize().padding(padding),
    ) {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.l, end = Space.l, top = top + Space.s, bottom = bottom + Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.m),
      ) {
        item(key = "header") { Header(onAdd = { onEvent(HomeUiEvent.AddHostClicked) }, onOpenSettings) }
        batteryItem(state.batteryHint, onEvent)
        if (state.inbox.isNotEmpty()) {
          item(key = "inbox-title") { SectionTitle("Inbox") }
          items(state.inbox, key = { "${it.hostId}/${it.blockId}" }) { item -> InboxCard(item, onEvent) }
        }
        if (hosts.isEmpty()) {
          item(key = "empty") { EmptyState { onEvent(HomeUiEvent.AddHostClicked) } }
        }
        items(hosts, key = { it.id }) { host ->
          DraggableHostCard(host, state.busyHostId == host.id, reorder, { currentHosts.map { it.id } }, onEvent) {
            dialog = it
          }
        }
      }
    }
  }
  state.editor?.let { editor -> HostEditorSheet(editor, onEvent) }
  SessionDialogs(dialog, onDismiss = { dialog = null }, onEvent = onEvent)
}

/** The battery-optimisation nudge, only while background alerts could still be frozen. */
private fun LazyListScope.batteryItem(visible: Boolean, onEvent: (HomeUiEvent) -> Unit) {
  if (!visible) return
  item(key = "battery") {
    BatteryHint(
      visible = true,
      onAllow = { onEvent(HomeUiEvent.AllowBattery) },
      onDismiss = { onEvent(HomeUiEvent.DismissBatteryHint) },
    )
  }
}

@Composable
private fun LazyItemScope.DraggableHostCard(
  host: HostUi,
  busy: Boolean,
  reorder: HostReorderState,
  currentIds: () -> List<String>,
  onEvent: (HomeUiEvent) -> Unit,
  onSessionDialog: (SessionDialog) -> Unit,
) {
  val dragging = reorder.dragging == host.id
  val lifted = if (dragging) {
    Modifier.zIndex(DRAG_Z).graphicsLayer {
      translationY = reorder.offsetOf(host.id)
      scaleX = DRAG_SCALE
      scaleY = DRAG_SCALE
      shadowElevation = DRAG_SHADOW_PX
      shape = RoundedCornerShape(Radius.card)
    }
  } else {
    Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = tween())
  }
  val handle = Modifier.pointerInput(host.id) {
    detectDragGesturesAfterLongPress(
      onDragStart = { reorder.start(host.id, currentIds()) },
      onDrag = { change, delta ->
        change.consume()
        reorder.drag(delta.y)
      },
      onDragEnd = reorder::end,
      onDragCancel = reorder::end,
    )
  }
  HostCard(host, busy, onEvent, onSessionDialog, dragHandle = handle, modifier = lifted)
}

@Composable
private fun Header(onAdd: () -> Unit, onOpenSettings: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Text("rexc", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Colors.ink, modifier = Modifier.weight(1f))
    IconButton(onClick = onOpenSettings) {
      Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Colors.muted)
    }
    IconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Add server", tint = Colors.accent) }
  }
}

@Composable
private fun SectionTitle(text: String) {
  Text(
    text,
    fontSize = 13.sp,
    fontWeight = FontWeight.SemiBold,
    color = Colors.muted,
    modifier = Modifier.padding(top = Space.s),
  )
}

/** First run: a terminal prompt badge, what to type, and the one button that matters. */
@Composable
private fun EmptyState(onAdd: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Colors.surface)
      .padding(Space.xl),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      Modifier.size(56.dp).clip(RoundedCornerShape(Radius.chip)).background(Colors.badge),
      contentAlignment = Alignment.Center,
    ) {
      Text(">_", fontFamily = MonoFamily, fontWeight = FontWeight.Bold, color = Colors.badgeInk, fontSize = 22.sp)
    }
    Spacer(Modifier.height(Space.l))
    Text("No servers yet", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Colors.ink)
    Spacer(Modifier.height(Space.s))
    Text(
      "Add your Rex server by its tailnet name (name.tailxxxx.ts.net), " +
        "a 100.x address with a port, or a full https:// URL.",
      fontSize = 14.sp,
      color = Colors.muted,
      lineHeight = 20.sp,
      textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(Space.l))
    Button(onClick = onAdd) { Text("Add server") }
  }
}
