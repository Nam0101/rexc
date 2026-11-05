package dev.namnv.rexc.presentation.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.namnv.rexc.core.ui.theme.Colors
import dev.namnv.rexc.core.ui.theme.Space
import dev.namnv.rexc.presentation.common.MenuEntry
import dev.namnv.rexc.presentation.home.StateDot
import uniffi.rexc_ffi.ProgramState

private const val MAP_ASPECT = 1.6f
private val PANE_GAP = 3.dp
private const val IDLE_FILL = 0.12f
private const val CURRENT_FILL = 0.28f

/** The current tab drawn as it is split on the desktop; tap a pane to go there, long press for more. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PaneMapSheet(
  state: TerminalUiState,
  onEvent: (TerminalUiEvent) -> Unit,
  close: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val tab = state.tabs.firstOrNull { it.id == state.currentTabId }?.name.orEmpty()
  Column(
    modifier.padding(horizontal = Space.l).padding(bottom = Space.l),
    verticalArrangement = Arrangement.spacedBy(Space.m),
  ) {
    Text(tab.ifBlank { "Panes" }, color = Colors.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(MAP_ASPECT)) {
      state.panes.forEach { pane ->
        MapPane(
          pane = pane,
          current = pane.blockId == state.currentBlockId,
          mapWidth = maxWidth,
          mapHeight = maxHeight,
          onSelect = {
            close()
            onEvent(TerminalUiEvent.SelectPane(pane.blockId))
          },
          onEvent = onEvent,
          onDone = close,
        )
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MapPane(
  pane: PaneUi,
  current: Boolean,
  mapWidth: Dp,
  mapHeight: Dp,
  onSelect: () -> Unit,
  onEvent: (TerminalUiEvent) -> Unit,
  onDone: () -> Unit,
) {
  var menu by remember { mutableStateOf(false) }
  val shape = RoundedCornerShape(10.dp)
  Box(
    Modifier.offset(mapWidth * pane.x, mapHeight * pane.y)
      .size((mapWidth * pane.w - PANE_GAP).coerceAtLeast(0.dp), (mapHeight * pane.h - PANE_GAP).coerceAtLeast(0.dp))
      .clip(shape)
      .background(Colors.ink.copy(alpha = if (current) CURRENT_FILL else IDLE_FILL))
      .then(if (current) Modifier.border(2.dp, Colors.accent, shape) else Modifier)
      .combinedClickable(onClick = onSelect, onLongClick = { menu = true })
      .padding(Space.s),
  ) {
    Column {
      Row(verticalAlignment = Alignment.CenterVertically) {
        pane.state?.takeIf { it != ProgramState.IDLE }?.let {
          StateDot(it, 7.dp)
          Spacer(Modifier.width(5.dp))
        }
        Text(
          pane.name,
          color = Colors.ink,
          fontSize = 12.sp,
          fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      pane.ownerLabel?.let { Text(it, color = Colors.muted, fontSize = 10.sp, maxLines = 1) }
    }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
      MenuEntry("Focus on desktop") {
        menu = false
        onEvent(TerminalUiEvent.FocusOnDesktop(pane.blockId))
      }
      MenuEntry("Close pane", color = Colors.danger) {
        menu = false
        onDone()
        onEvent(TerminalUiEvent.CloseBlock(pane.blockId))
      }
    }
  }
}

/** The split layout at thumbnail size for the pane strip; one canvas, drawn once per layout change. */
@Composable
internal fun LayoutThumb(state: TerminalUiState, ink: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val panes = state.panes
  val currentId = state.currentBlockId
  Canvas(
    modifier.size(64.dp, 40.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(2.dp),
  ) {
    val gap = PANE_GAP.toPx() / 2
    val radius = CornerRadius(3.dp.toPx())
    panes.forEach { p ->
      val topLeft = Offset(p.x * size.width + gap / 2, p.y * size.height + gap / 2)
      val box = Size((p.w * size.width - gap).coerceAtLeast(0f), (p.h * size.height - gap).coerceAtLeast(0f))
      val color = if (p.blockId == currentId) Colors.accent else ink.copy(alpha = THUMB_INK)
      drawRoundRect(color, topLeft, box, radius)
    }
  }
}

private const val THUMB_INK = 0.3f
