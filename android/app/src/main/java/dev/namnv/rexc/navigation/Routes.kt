package dev.namnv.rexc.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

// `data object`/`data class` keys give Navigation 3 a stable `contentKey` (their `toString()`).

@Serializable
data object Home : NavKey

/** One session's terminal; [blockId] picks the pane to open, else the focused one. */
@Serializable
data class Terminal(val hostId: String, val sessionId: String, val blockId: String? = null) : NavKey
