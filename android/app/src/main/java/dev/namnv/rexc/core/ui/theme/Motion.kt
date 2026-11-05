package dev.namnv.rexc.core.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * One motion language for the app: snappy, with a little give. Entrances decelerate
 * on the emphasized curve, exits leave quickly, and anything a finger touches
 * settles on a spring. Three durations cover every case.
 */
object Motion {
  /** Micro feedback: a key press, a tint change. */
  const val QUICK = 150

  /** A row, chip or bar changing state or sliding into place. */
  const val STANDARD = 260

  /** A card or pane entering, a group unfolding. */
  const val SLOW = 400

  /** Material 3 emphasized decelerate: fast start, soft landing. For things that appear. */
  val emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

  /** Material 3 accelerate: for things that leave. */
  val exit = CubicBezierEasing(0.3f, 0f, 1f, 1f)

  /** Material 3 standard: for on-screen moves. */
  val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

  fun <T> enter(duration: Int = SLOW): FiniteAnimationSpec<T> = tween(duration, easing = emphasized)

  fun <T> leave(duration: Int = QUICK): FiniteAnimationSpec<T> = tween(duration, easing = exit)

  fun <T> move(duration: Int = STANDARD): FiniteAnimationSpec<T> = tween(duration, easing = standard)

  /** The spring under a finger: quick, with a hint of overshoot. */
  fun <T> touch(): FiniteAnimationSpec<T> = spring(dampingRatio = TOUCH_DAMPING, stiffness = Spring.StiffnessMedium)

  /** The spring things settle on after a change: slower, barely bouncy. */
  fun <T> settle(): FiniteAnimationSpec<T> = spring(dampingRatio = SETTLE_DAMPING, stiffness = Spring.StiffnessMediumLow)

  private const val TOUCH_DAMPING = 0.7f
  private const val SETTLE_DAMPING = 0.85f
}
