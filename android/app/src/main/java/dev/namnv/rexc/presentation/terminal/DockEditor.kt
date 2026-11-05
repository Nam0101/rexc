package dev.namnv.rexc.presentation.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.namnv.rexc.core.ui.theme.Colors
import dev.namnv.rexc.core.ui.theme.MonoFamily
import dev.namnv.rexc.core.ui.theme.Space
import dev.namnv.rexc.data.DockItem
import dev.namnv.rexc.data.DockLayout
import kotlin.math.roundToInt

private val ROW_HEIGHT = 52.dp
private const val NOT_DRAGGING = -1L

/** An item in the editor with an identity of its own, so a dragged row keeps its gesture while others move. */
@Immutable
private data class Entry(val uid: Long, val item: DockItem)

/** What the item dialog is for: a new item of [kind], or the entry [uid] being changed. */
@Immutable
private data class Draft(val kind: ItemKind, val uid: Long? = null, val initial: DockItem? = null)

/** Edit the dock: drag to reorder, tap to change, add keys, snippets and actions. Every change is saved at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DockEditor(layout: DockLayout, onSave: (DockLayout) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
  val entries =
    remember {
      mutableStateListOf<Entry>().apply { layout.items.forEachIndexed { i, item -> add(Entry(i.toLong(), item)) } }
    }
  var nextUid by remember { mutableLongStateOf(entries.size.toLong()) }
  var draft by remember { mutableStateOf<Draft?>(null) }
  val save = { onSave(DockLayout(entries.map { it.item })) }
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Colors.surface,
  ) {
    Column(Modifier.imePadding().navigationBarsPadding().padding(horizontal = Space.l)) {
      Text("Dock", color = Colors.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
      Text("Drag to reorder, tap to edit.", color = Colors.muted, fontSize = 13.sp)
      Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        ReorderableRows(
          entries,
          onMoved = save,
          onEdit = { draft = Draft(ItemKind.of(it.item), it.uid, it.item) },
          onDelete = {
            entries.remove(it)
            save()
          },
        )
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        ItemKind.entries.forEach { kind -> TextButton(onClick = { draft = Draft(kind) }) { Text(kind.title) } }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = {
          onReset()
          onDismiss()
        }) { Text("Reset", color = Colors.danger) }
      }
    }
  }
  draft?.let { current ->
    ItemDialog(
      kind = current.kind,
      initial = current.initial,
      onSave = { item ->
        val at = entries.indexOfFirst { it.uid == current.uid }
        if (at >= 0) entries[at] = entries[at].copy(item = item) else entries.add(Entry(nextUid++, item))
        draft = null
        save()
      },
      onDismiss = { draft = null },
    )
  }
}

@Composable
private fun ReorderableRows(
  entries: SnapshotStateList<Entry>,
  onMoved: () -> Unit,
  onEdit: (Entry) -> Unit,
  onDelete: (Entry) -> Unit,
) {
  val rowPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
  var dragging by remember { mutableLongStateOf(NOT_DRAGGING) }
  var dragY by remember { mutableFloatStateOf(0f) }
  entries.toList().forEach { entry ->
    key(entry.uid) {
      val lifted = dragging == entry.uid
      val end = {
        dragging = NOT_DRAGGING
        dragY = 0f
        onMoved()
      }
      EditorRow(
        entry = entry,
        modifier = Modifier.zIndex(if (lifted) 1f else 0f).offset {
          IntOffset(0, if (lifted) dragY.roundToInt() else 0)
        },
        handle = Modifier.pointerInput(entry.uid) {
          detectDragGestures(
            onDragStart = {
              dragging = entry.uid
              dragY = 0f
            },
            onDragEnd = end,
            onDragCancel = end,
          ) { change, delta ->
            change.consume()
            dragY = settle(entries, entry.uid, dragY + delta.y, rowPx)
          }
        },
        onEdit = { onEdit(entry) },
        onDelete = { onDelete(entry) },
      )
    }
  }
}

/**
 * Moves [uid] past the rows that [offset] has crossed; returns the offset left over,
 * which the row still draws so it follows the finger. Past either end the surplus is dropped.
 */
private fun settle(entries: MutableList<Entry>, uid: Long, offset: Float, rowPx: Float): Float {
  val from = entries.indexOfFirst { it.uid == uid }
  val steps = (offset / rowPx).roundToInt()
  val to = (from + steps).coerceIn(0, entries.lastIndex)
  if (to == from) return if (steps == 0) offset else 0f
  entries.add(to, entries.removeAt(from))
  return offset - (to - from) * rowPx
}

@Composable
private fun EditorRow(entry: Entry, modifier: Modifier, handle: Modifier, onEdit: () -> Unit, onDelete: () -> Unit) {
  Row(
    modifier.fillMaxWidth().height(ROW_HEIGHT).background(Colors.surface).clickable(onClick = onEdit),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      Icons.Default.DragHandle,
      contentDescription = "Drag to reorder",
      tint = Colors.muted,
      modifier = handle.size(ROW_HEIGHT).padding(Space.m),
    )
    Column(Modifier.weight(1f)) {
      Text(
        entry.item.label,
        color = Colors.ink,
        fontFamily = MonoFamily,
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(entry.item.summary(), color = Colors.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    IconButton(onClick = onDelete) { Icon(Icons.Default.Close, contentDescription = "Remove", tint = Colors.muted) }
  }
}
