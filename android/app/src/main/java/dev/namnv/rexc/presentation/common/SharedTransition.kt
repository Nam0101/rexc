package dev.namnv.rexc.presentation.common

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.OverlayClip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import dev.namnv.rexc.core.ui.theme.Radius

/** Set by the nav host; null in previews and tests, where [sharedBoundsFor] does nothing. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/**
 * Morphs a list card into the screen it opens and back. Content is laid out at its final size and scaled,
 * so a terminal never resizes mid-flight.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedBoundsFor(key: String): Modifier {
  val shared = LocalSharedTransitionScope.current ?: return this
  val animated = LocalNavAnimatedContentScope.current
  return with(shared) {
    sharedBounds(
      sharedContentState = rememberSharedContentState(key),
      animatedVisibilityScope = animated,
      resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
      clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(Radius.card)),
    )
  }
}
