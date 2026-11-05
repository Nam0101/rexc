package dev.namnv.rexc.presentation.common

import androidx.compose.runtime.staticCompositionLocalOf
import dev.namnv.rexc.data.Settings

/** The user's settings, provided once at the root so any screen can read them. */
val LocalSettings = staticCompositionLocalOf { Settings() }
