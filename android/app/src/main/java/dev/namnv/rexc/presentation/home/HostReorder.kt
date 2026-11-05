package dev.namnv.rexc.presentation.home

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** [order] with [id] moved to index [to]; unchanged when it is unknown. */
fun moveId(order: List<String>, id: String, to: Int): List<String> {
  val from = order.indexOf(id)
  if (from < 0 || from == to) return order
  return order.toMutableList().apply { add(to.coerceIn(0, size - 1), removeAt(from)) }
}

/** The id [id] now sits in front of in [order], null when it is last: what the engine's move call needs. */
fun beforeIdOf(order: List<String>, id: String): String? = order.getOrNull(order.indexOf(id) + 1)

/** [hosts] arranged by [order]; hosts missing from it keep their place at the end. */
fun arrange(hosts: List<HostUi>, order: List<String>?): List<HostUi> {
  if (order == null) return hosts
  val byId = hosts.associateBy { it.id }
  return order.mapNotNull(byId::get) + hosts.filter { it.id !in order }
}

/**
 * Long-press drag of one host card inside the home list. [order] is the arrangement shown while
 * dragging and until the engine confirms it; the dragged card follows the finger through a
 * translation that makes up for every swap, so it never jumps.
 */
@Stable
class HostReorderState(private val list: LazyListState, private val onDrop: (id: String, before: String?) -> Unit) {
  var order by mutableStateOf<List<String>?>(null)
    private set
  var dragging by mutableStateOf<String?>(null)
    private set
  private var travelled by mutableFloatStateOf(0f)
  private var grabbedAt = 0

  /** Pixels to push the dragged card from where the list currently lays it out. */
  fun offsetOf(id: String): Float = if (id != dragging) 0f else grabbedAt + travelled - layoutOffset(id)

  fun start(id: String, current: List<String>) {
    val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return
    dragging = id
    order = current
    grabbedAt = info.offset
    travelled = 0f
  }

  fun drag(delta: Float) {
    val id = dragging ?: return
    travelled += delta
    order = order?.let { current -> swapTarget(id, current)?.let { moveId(current, id, it) } ?: current }
  }

  /** The index to swap the dragged card into once the finger passes a neighbour's middle, else null. */
  private fun swapTarget(id: String, order: List<String>): Int? {
    val items = list.layoutInfo.visibleItemsInfo
    val me = items.firstOrNull { it.key == id } ?: return null
    // Passing a neighbour's edge instead would flip back and forth when the heights differ.
    val centre = grabbedAt + travelled + me.size / 2f
    val over = items.firstOrNull { (it.key as? String) in order && it.key != id && it.crossedBy(centre, me.offset) }
    return over?.let { order.indexOf(it.key) }
  }

  /** Lets go; [order] stays until [settle] sees the engine agree. */
  fun end() {
    val id = dragging ?: return
    dragging = null
    order?.let { onDrop(id, beforeIdOf(it, id)) }
  }

  /** The engine's order caught up (or never will): stop overriding it. */
  fun settle() {
    if (dragging == null) order = null
  }

  private fun layoutOffset(id: String): Int = list.layoutInfo.visibleItemsInfo.firstOrNull {
    it.key == id
  }?.offset ?: grabbedAt

  private fun LazyListItemInfo.crossedBy(centre: Float, dragged: Int): Boolean {
    val middle = offset + size / 2f
    return if (offset > dragged) centre > middle else centre < middle
  }
}
