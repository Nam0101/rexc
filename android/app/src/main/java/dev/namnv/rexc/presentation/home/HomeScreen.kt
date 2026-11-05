package dev.namnv.rexc.presentation.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.namnv.rexc.core.ui.theme.Colors
import dev.namnv.rexc.core.ui.theme.MonoFamily
import dev.namnv.rexc.core.ui.theme.Motion
import dev.namnv.rexc.core.ui.theme.Radius
import dev.namnv.rexc.core.ui.theme.Space
import dev.namnv.rexc.presentation.common.LocalSettings
import dev.namnv.rexc.presentation.common.appear
import dev.namnv.rexc.presentation.common.pressScale
import dev.namnv.rexc.presentation.common.rememberScreenStart
import kotlinx.coroutines.delay

/** How long the dragged order stays on screen waiting for the engine to confirm it. */
private const val SETTLE_MS = 1_000L
private const val DRAG_SCALE = 1.02f
private const val DRAG_SHADOW_PX = 24f
private const val DRAG_Z = 1f
private const val CARET_PERIOD_MS = 1_060
private const val ICON_PRESS = 0.85f
private val caretSize = DpSize(11.dp, 24.dp)

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
  val screenStart = rememberScreenStart()
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
        homeItems(state, hosts, reorder, { currentHosts.map { it.id } }, screenStart, onEvent, onOpenSettings) {
          dialog = it
        }
      }
    }
  }
  state.editor?.let { editor -> HostEditorSheet(editor, state.terminalThemes, onEvent) }
  SessionDialogs(dialog, onDismiss = { dialog = null }, onEvent = onEvent)
}

/**
 * The list body: header, the battery hint, what needs you, then the hosts in [hosts] order.
 * Items rise in one after another when the screen opens; later changes slide rows into place.
 */
private fun LazyListScope.homeItems(
  state: HomeUiState,
  hosts: List<HostUi>,
  reorder: HostReorderState,
  currentIds: () -> List<String>,
  screenStart: Long,
  onEvent: (HomeUiEvent) -> Unit,
  onOpenSettings: () -> Unit,
  onSessionDialog: (SessionDialog) -> Unit,
) {
  var slot = 0
  item(key = "header") {
    Header(onAdd = { onEvent(HomeUiEvent.AddHostClicked) }, onOpenSettings, Modifier.appear(0, screenStart))
  }
  batteryItem(state.batteryHint, onEvent)
  if (state.batteryHint) slot++
  if (state.inbox.isNotEmpty()) {
    val index = ++slot
    item(key = "needs-you") { NeedsYou(state.inbox, onEvent, settleInPlace().appear(index, screenStart)) }
  }
  if (hosts.isEmpty()) {
    item(key = "empty") { EmptyState({ onEvent(HomeUiEvent.AddHostClicked) }, Modifier.appear(1, screenStart)) }
  }
  val firstHost = slot + 1
  itemsIndexed(hosts, key = { _, host -> host.id }) { i, host ->
    DraggableHostCard(
      host = host,
      busy = state.busyHostId == host.id,
      reorder = reorder,
      currentIds = currentIds,
      onEvent = onEvent,
      modifier = Modifier.appear(firstHost + i, screenStart),
      onSessionDialog = onSessionDialog,
    )
  }
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
  modifier: Modifier,
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
    settleInPlace()
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
  HostCard(host, busy, onEvent, onSessionDialog, dragHandle = handle, modifier = modifier.then(lifted))
}

/** A list item that fades in, fades out, and springs to its new place when the list around it changes. */
private fun LazyItemScope.settleInPlace(): Modifier = Modifier.animateItem(
  fadeInSpec = Motion.enter(),
  fadeOutSpec = Motion.leave(),
  placementSpec = Motion.settle(),
)

@Composable
private fun Header(onAdd: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
    Text("rexc", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Colors.ink)
    Caret()
    Spacer(Modifier.weight(1f))
    IconButton(onClick = onOpenSettings, modifier = Modifier.pressScale(ICON_PRESS)) {
      Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Colors.muted)
    }
    IconButton(onClick = onAdd, modifier = Modifier.pressScale(ICON_PRESS)) {
      Icon(Icons.Default.Add, contentDescription = "Add server", tint = Colors.accent)
    }
  }
}

/** The block cursor after the name, blinking at a terminal's pace: on half the time, off the other. */
@Composable
private fun Caret() {
  val blink by rememberInfiniteTransition(label = "caret").animateFloat(
    initialValue = 1f,
    targetValue = 0f,
    animationSpec = infiniteRepeatable(
      keyframes {
        durationMillis = CARET_PERIOD_MS
        1f at 0
        1f at CARET_PERIOD_MS / 2 - 1
        0f at CARET_PERIOD_MS / 2
        0f at CARET_PERIOD_MS - 1
      },
      RepeatMode.Restart,
    ),
    label = "caret",
  )
  Box(
    Modifier.padding(start = 3.dp, top = 5.dp).size(caretSize).graphicsLayer { alpha = blink }
      .background(Colors.accent),
  )
}

/** The programs waiting on you, grouped in one card above the hosts. */
@Composable
private fun NeedsYou(items: List<InboxUi>, onEvent: (HomeUiEvent) -> Unit, modifier: Modifier = Modifier) {
  Column(modifier) {
    SectionTitle("Needs you")
    Spacer(Modifier.height(Space.s))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Colors.surface)) {
      items.forEachIndexed { i, item ->
        if (i > 0) HorizontalDivider(color = Colors.line, modifier = Modifier.padding(horizontal = Space.l))
        InboxCard(item, onEvent)
      }
    }
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
private fun EmptyState(onAdd: () -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Colors.surface)
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
