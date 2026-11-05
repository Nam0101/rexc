package dev.namnv.rexc.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow

/** Collects one-shot effects while the host lifecycle is at least [minActiveState]. */
@Composable
fun <T> Flow<T>.CollectSideEffect(
  minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
  onEffect: suspend (T) -> Unit,
) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val currentOnEffect = rememberUpdatedState(onEffect)
  LaunchedEffect(this, lifecycle, minActiveState) {
    lifecycle.repeatOnLifecycle(minActiveState) {
      collect { currentOnEffect.value(it) }
    }
  }
}
