package dev.namnv.rexc.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DockLayoutTest {
  private val custom = DockLayout(
    listOf(
      DockItem.Key("key_c", "^C", ctrl = true),
      DockItem.Text("gs", "git status", enter = true),
      DockItem.Action("palette"),
    ),
  )

  @Test
  fun encode_thenDecode_roundTripsEveryKindOfItem() {
    assertEquals(custom, DockLayout.decode(custom.encode()))
    assertEquals(DockLayout.Default, DockLayout.decode(DockLayout.Default.encode()))
  }

  @Test
  fun decode_returnsNullForMissingOrBrokenData() {
    assertNull(DockLayout.decode(null))
    assertNull(DockLayout.decode("{not json"))
    assertNull(DockLayout.decode("""{"items":[{"t":"nope"}]}"""))
  }

  @Test
  fun default_holdsTheOriginalKeysAndThePalette() {
    val labels = DockLayout.Default.items.map { it.label }
    assertTrue(labels.containsAll(listOf("esc", "tab", "home", "end", "pgup", "pgdn", "-", "|")))
    assertTrue(DockLayout.Default.items.contains(DockItem.Action("palette")))
  }

  @Test
  fun moved_reordersAndClampsTheTarget() {
    val moved = custom.moved(0, 2).items
    assertEquals(listOf("gs", "palette", "^C"), moved.map { (it as? DockItem.Action)?.id ?: it.label })
    assertEquals(custom.moved(2, 0).items.first(), DockItem.Action("palette"))
    assertEquals(custom.moved(0, 99).items.last(), custom.items.first())
    assertEquals(custom, custom.moved(7, 0))
  }

  @Test
  fun pushed_putsTheNewestFirstWithoutDuplicatesAndKeepsTen() {
    assertEquals(listOf("b", "a", "c"), listOf("a", "b", "c").pushed("b"))
    val many = (1..10).map { "x$it" }
    assertEquals(listOf("new") + many.take(9), many.pushed("new"))
  }
}
