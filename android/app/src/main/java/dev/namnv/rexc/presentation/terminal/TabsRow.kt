package dev.namnv.rexc.presentation.terminal

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.namnv.rexc.core.ui.theme.Colors
import dev.namnv.rexc.core.ui.theme.Space
import dev.namnv.rexc.presentation.home.StateDot
import uniffi.rexc_ffi.ProgramState

/**
 * The session's tabs. They share the width while they fit, then scroll at a fixed
 * width; a long press picks one up to drop it elsewhere (the server keeps the order).
 */
@Composable
internal fun TabsRow(state: TerminalUiState, ink: Color, onEvent: (TerminalUiEvent) -> Unit) {
  val drag = remember { TabDrag() }
  val haptics = LocalHapticFeedback.current
  val ids = state.tabs.map { it.id }
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val gap = Space.s
    val share = (maxWidth - Space.m * 2 - NEW_TAB_WIDTH - gap * state.tabs.size) / state.tabs.size.coerceAtLeast(1)
    val scrolls = share < MIN_TAB_WIDTH
    val tabWidth = if (scrolls) SCROLL_TAB_WIDTH else minOf(share, MAX_TAB_WIDTH)
    val gapPx = with(LocalDensity.current) { gap.toPx() }
    val target = drag.tabId?.let { drag.targetIndex(ids) }
    Row(
      Modifier.then(if (scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
        .padding(horizontal = Space.m, vertical = Space.xs),
      horizontalArrangement = Arrangement.spacedBy(gap),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      state.tabs.forEachIndexed { index, tab ->
        val from = ids.indexOf(drag.tabId)
        TabChip(
          tab = tab,
          selected = tab.id == state.currentTabId,
          ink = ink,
          dragging = drag.tabId == tab.id,
          shift = if (drag.tabId == tab.id) drag.offset else drag.shiftFor(index, from, target, gapPx),
          modifier = Modifier.width(tabWidth)
            .onGloballyPositioned { drag.bounds[tab.id] = it.positionInParent().x to it.size.width.toFloat() }
            .tabDrag(drag, tab.id, ids, haptics, onEvent),
          onClick = { onEvent(TerminalUiEvent.SelectTab(tab.id)) },
        )
      }
      Chip(selected = false, ink = ink, onClick = { onEvent(TerminalUiEvent.NewTab) }) {
        Icon(Icons.Default.Add, contentDescription = "New tab", tint = ink, modifier = Modifier.size(16.dp))
      }
    }
  }
}

@Composable
private fun TabChip(
  tab: TabUi,
  selected: Boolean,
  ink: Color,
  dragging: Boolean,
  shift: Float,
  modifier: Modifier,
  onClick: () -> Unit,
) {
  val settled by animateFloatAsState(if (dragging) 0f else shift, label = "tabShift")
  Chip(
    selected = selected,
    ink = ink,
    onClick = onClick,
    modifier = modifier.zIndex(if (dragging) 1f else 0f).graphicsLayer {
      translationX = if (dragging) shift else settled
      val s = if (dragging) LIFT_SCALE else 1f
      scaleX = s
      scaleY = s
      shadowElevation = if (dragging) LIFT_ELEVATION else 0f
    },
  ) {
    tab.state?.takeIf { it != ProgramState.IDLE }?.let {
      StateDot(it, 7.dp)
      Spacer(Modifier.width(6.dp))
    }
    Text(
      tab.name,
      fontSize = 13.sp,
      fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center,
      color = if (selected) Colors.onAccent else ink,
      modifier = Modifier.weight(1f),
    )
  }
}

/** Long-press lifts the tab; releasing drops it where its centre landed. */
private fun Modifier.tabDrag(
  drag: TabDrag,
  tabId: String,
  ids: List<String>,
  haptics: HapticFeedback,
  onEvent: (TerminalUiEvent) -> Unit,
) = pointerInput(tabId, ids) {
  detectDragGesturesAfterLongPress(
    onDragStart = {
      haptics.performHapticFeedback(HapticFeedbackType.LongPress)
      drag.tabId = tabId
      drag.offset = 0f
    },
    onDrag = { change, amount ->
      change.consume()
      drag.offset += amount.x
    },
    onDragEnd = {
      val to = drag.targetIndex(ids)
      drag.tabId = null
      if (to != ids.indexOf(tabId)) onEvent(TerminalUiEvent.MoveTab(tabId, to))
    },
    onDragCancel = { drag.tabId = null },
  )
}

/** Where the lifted tab is, in the row's coordinates. */
@Stable
private class TabDrag {
  var tabId by mutableStateOf<String?>(null)
  var offset by mutableFloatStateOf(0f)

  /** Each tab's left edge and width, as last laid out. */
  val bounds = HashMap<String, Pair<Float, Float>>()

  /** The index the lifted tab would take: how many other tabs its centre has passed. */
  fun targetIndex(ids: List<String>): Int {
    val id = tabId ?: return -1
    val centre = bounds[id]?.let { (left, width) -> left + width / 2 + offset }
    return if (centre == null) {
      ids.indexOf(id)
    } else {
      ids.count { other -> other != id && bounds[other]?.let { (l, w) -> l + w / 2 < centre } == true }
    }
  }

  /** How far the tab at [index] steps aside while the lifted one (at [from]) hovers over [target]. */
  fun shiftFor(index: Int, from: Int, target: Int?, gapPx: Float): Float {
    val step = (tabId?.let { bounds[it]?.second } ?: 0f) + gapPx
    return when {
      target == null || from < 0 -> 0f
      index in (from + 1)..target -> -step
      index in target..<from -> step
      else -> 0f
    }
  }
}

private val NEW_TAB_WIDTH = 44.dp
private val MIN_TAB_WIDTH = 72.dp
private val MAX_TAB_WIDTH = 180.dp
private val SCROLL_TAB_WIDTH = 128.dp
private const val LIFT_SCALE = 1.04f
private const val LIFT_ELEVATION = 12f
