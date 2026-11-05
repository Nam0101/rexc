package dev.namnv.rexc.presentation.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaletteFilterTest {
  private val entries = listOf(
    PaletteEntry("app:split_right", "Split right", keywords = "pane vertical"),
    PaletteEntry("app:split_down", "Split down", keywords = "pane horizontal"),
    PaletteEntry("app:new_tab", "New tab", category = "Tabs"),
    PaletteEntry("rex:dev_layout", "Open Dev Layout", "Opens the editor and a build pane", category = "Actions"),
  )

  private fun ids(query: String, recents: List<String> = emptyList()) =
    filterEntries(entries, query, recents).map { it.id }

  @Test
  fun emptyQuery_keepsOrderWhenThereAreNoRecents() {
    assertEquals(entries.map { it.id }, ids(""))
  }

  @Test
  fun emptyQuery_putsRecentsFirstInRecencyOrder() {
    assertEquals(listOf("app:new_tab", "rex:dev_layout"), ids("", listOf("app:new_tab", "rex:dev_layout")).take(2))
  }

  @Test
  fun query_matchesScatteredLettersInOrder() {
    assertEquals(listOf("app:split_right"), ids("sprt"))
  }

  @Test
  fun query_needsEveryWordToMatch() {
    assertEquals(listOf("app:split_down"), ids("split down"))
    assertEquals(emptyList(), ids("split zebra"))
  }

  @Test
  fun query_searchesKeywordsCategoryAndDescription() {
    assertEquals(listOf("app:split_right"), ids("vertical"))
    assertEquals(listOf("app:new_tab"), ids("tabs"))
    assertEquals(listOf("rex:dev_layout"), ids("editor"))
  }

  @Test
  fun query_ranksTitleWordStartAboveScatteredMatch() {
    val ranked = filterEntries(
      listOf(
        PaletteEntry("a", "Close tab"),
        PaletteEntry("b", "Split new tab"),
        PaletteEntry("c", "Tab 3 of the big list"),
      ),
      "tab",
      emptyList(),
    )
    assertEquals("c", ranked.first().id)
  }

  @Test
  fun fuzzyScore_rejectsMissingLettersAndWrongOrder() {
    assertNull(fuzzyScore("xyz", "split"))
    assertNull(fuzzyScore("ts", "split"))
    assertNotNull(fuzzyScore("spt", "split"))
  }

  @Test
  fun fuzzyScore_prefersSubstringToScatteredLetters() {
    assertTrue(checkNotNull(fuzzyScore("spl", "split")) > checkNotNull(fuzzyScore("spt", "split")))
  }
}
