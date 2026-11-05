package dev.namnv.rexc.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One cap on the dock. */
@Immutable
@Serializable
sealed interface DockItem {
  /** What the cap says. */
  val label: String

  /** A named key (`escape`, `page_up`...) sent with the given modifiers. */
  @Serializable
  @SerialName("key")
  data class Key(
    val name: String,
    override val label: String,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
  ) : DockItem

  /** A snippet: [text] typed into the pane, followed by Enter when [enter]. */
  @Serializable
  @SerialName("text")
  data class Text(override val label: String, val text: String, val enter: Boolean = false) : DockItem

  /** An app action, one of [DockAction]. */
  @Serializable
  @SerialName("action")
  data class Action(val id: String) : DockItem {
    override val label: String get() = DockAction.of(id)?.title ?: id
  }
}

/** What an [DockItem.Action] cap can do; the dock draws the cap, the screen runs the rest. */
enum class DockAction(val id: String, val title: String) {
  CTRL("ctrl", "Ctrl (hold for chords)"),
  ALT("alt", "Alt"),
  SHIFT("shift", "Shift"),
  ARROWS("arrows", "Arrow keys"),
  PASTE("paste", "Paste"),
  KEYBOARD("keyboard", "Keyboard toggle"),
  PALETTE("palette", "Command palette"),
  SWITCHER("switcher", "Quick switcher"),
  MAP("map", "Pane map"),
  ;

  companion object {
    fun of(id: String): DockAction? = entries.firstOrNull { it.id == id }
  }
}

/** The dock's keys, in order. Persisted as JSON by [DockPrefs]. */
@Immutable
@Serializable
data class DockLayout(val items: List<DockItem>) {
  fun encode(): String = json.encodeToString(DockLayout.serializer(), this)

  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    /** The saved layout, or null when [raw] is missing or no longer parses. */
    fun decode(raw: String?): DockLayout? =
      raw?.let { runCatching { json.decodeFromString(DockLayout.serializer(), it) }.getOrNull() }

    private fun key(name: String, label: String) = DockItem.Key(name, label)
    private fun text(c: String) = DockItem.Text(c, c)
    private fun action(a: DockAction) = DockItem.Action(a.id)

    /** What the dock was before it could be edited. */
    val Default = DockLayout(
      listOf(
        key("escape", "esc"),
        key("tab", "tab"),
        action(DockAction.CTRL),
        action(DockAction.ALT),
        action(DockAction.SHIFT),
        action(DockAction.ARROWS),
        text("-"),
        text("|"),
        text("/"),
        text("~"),
        text("`"),
        key("home", "home"),
        key("end", "end"),
        key("page_up", "pgup"),
        key("page_down", "pgdn"),
        action(DockAction.PASTE),
        action(DockAction.KEYBOARD),
        action(DockAction.PALETTE),
      ),
    )
  }

  /** The items after moving the one at [from] to [to]. */
  fun moved(from: Int, to: Int): DockLayout {
    if (from !in items.indices) return this
    val list = items.toMutableList()
    val item = list.removeAt(from)
    list.add(to.coerceIn(0, list.size), item)
    return copy(items = list)
  }
}
