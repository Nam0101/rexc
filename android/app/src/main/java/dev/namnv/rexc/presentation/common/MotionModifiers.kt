package dev.namnv.rexc.presentation.common

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.namnv.rexc.core.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PRESSED_SCALE = 0.97f
private const val APPEAR_WINDOW_MS = 700L
private const val STAGGER_MS = 35L
private const val MAX_STAGGERED = 8
private val APPEAR_RISE = 14.dp

/**
 * Shrinks the content a touch while a finger is on it and springs back on release.
 * Watches the pointer without consuming it, so the click, long press or swipe
 * underneath still happens; it just feels pressed.
 */
@Composable
fun Modifier.pressScale(pressed: Float = PRESSED_SCALE): Modifier {
  val scale = remember { Animatable(1f) }
  val scope = rememberCoroutineScope()
  return pointerInput(pressed) {
    awaitEachGesture {
      awaitFirstDown(requireUnconsumed = false)
      scope.launch { scale.animateTo(pressed, spring(stiffness = Spring.StiffnessHigh)) }
      waitForUpOrCancellation()
      scope.launch { scale.animateTo(1f, Motion.touch()) }
    }
  }.graphicsLayer {
    scaleX = scale.value
    scaleY = scale.value
  }
}

/** When a screen opened; items that compose soon after it rise in, later ones (scrolled to) just appear. */
@Composable
fun rememberScreenStart(): Long = remember { SystemClock.uptimeMillis() }

/**
 * The entrance of the [index]th item of a list: a short rise and fade, staggered so the
 * list builds top-down. Only for items composed within the opening moment of [screenStart].
 */
@Composable
fun Modifier.appear(index: Int, screenStart: Long): Modifier {
  val fresh = remember { SystemClock.uptimeMillis() - screenStart < APPEAR_WINDOW_MS }
  if (!fresh) return this
  val progress = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    delay(index.coerceAtMost(MAX_STAGGERED) * STAGGER_MS)
    progress.animateTo(1f, Motion.enter())
  }
  val rise = with(LocalDensity.current) { APPEAR_RISE.toPx() }
  return graphicsLayer {
    alpha = progress.value
    translationY = (1f - progress.value) * rise
  }
}
