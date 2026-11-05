package dev.namnv.rexc.presentation.terminal

import androidx.compose.runtime.Immutable

/** One line of the command palette. [id] is `app:<x>` for the phone's own actions and `rex:<name>` for the server's. */
@Immutable
data class PaletteEntry(
  val id: String,
  val title: String,
  val subtitle: String = "",
  val category: String = "",
  val keywords: String = "",
)

private const val EXACT = 100
private const val MAX_OFFSET = 50

/** An index that is next to no letter, so the first match never counts as adjacent. */
private const val BEFORE_START = -2
private const val WORD_START = 50
private const val SUBSEQUENCE = 10
private const val ADJACENT = 5
private const val TITLE_WEIGHT = 3

/**
 * Entries matching every word of [query], best first; recents lead an empty query.
 * A word matches as a substring (better at a word start) or as scattered letters.
 */
fun filterEntries(entries: List<PaletteEntry>, query: String, recents: List<String>): List<PaletteEntry> {
  val words = query.lowercase().split(' ').filter(String::isNotEmpty)
  if (words.isEmpty()) {
    val rank = recents.withIndex().associate { it.value to it.index }
    return entries.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
  }
  return entries.mapNotNull { e -> entryScore(e, words)?.let { e to it } }
    .sortedByDescending { it.second }
    .map { it.first }
}

private fun entryScore(entry: PaletteEntry, words: List<String>): Int? {
  val title = entry.title.lowercase()
  val rest = "${entry.category} ${entry.keywords} ${entry.subtitle}".lowercase()
  var total = 0
  for (word in words) {
    val inTitle = fuzzyScore(word, title)?.times(TITLE_WEIGHT)
    val best = maxOf(inTitle ?: 0, fuzzyScore(word, rest) ?: 0)
    if (best == 0) return null
    total += best
  }
  return total
}

/** How well [word] fits [text] (both lower case), or null when it does not. Always positive. */
internal fun fuzzyScore(word: String, text: String): Int? {
  val at = text.indexOf(word)
  return if (at >= 0) substringScore(text, at) else scatteredScore(word, text)
}

private fun substringScore(text: String, at: Int): Int =
  EXACT - at.coerceAtMost(MAX_OFFSET) + if (isWordStart(text, at)) WORD_START else 0

/** The letters of [word] found in order, not necessarily side by side; null when one is missing. */
private fun scatteredScore(word: String, text: String): Int? {
  var score = SUBSEQUENCE
  var from = 0
  var previous = BEFORE_START
  for (c in word) {
    val i = text.indexOf(c, from)
    if (i < 0) return null
    if (i == previous + 1) score += ADJACENT
    if (isWordStart(text, i)) score += ADJACENT
    previous = i
    from = i + 1
  }
  return score
}

private fun isWordStart(text: String, i: Int) = i == 0 || !text[i - 1].isLetterOrDigit()
