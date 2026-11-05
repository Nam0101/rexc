package dev.namnv.rexc.core.dispatcher

import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted

/** Injected dispatchers, so tests can swap them for a test dispatcher. */
interface AppCoroutineDispatchers {
  val main: CoroutineDispatcher
  val mainImmediate: CoroutineDispatcher
  val io: CoroutineDispatcher
  val default: CoroutineDispatcher
}

internal class DefaultAppCoroutineDispatchers @Inject constructor() : AppCoroutineDispatchers {
  override val main: CoroutineDispatcher get() = Dispatchers.Main
  override val mainImmediate: CoroutineDispatcher get() = Dispatchers.Main.immediate
  override val io: CoroutineDispatcher get() = Dispatchers.IO
  override val default: CoroutineDispatcher get() = Dispatchers.Default
}

private const val STOP_TIMEOUT_MILLIS: Long = 5_000

/** Keeps upstream flows alive across a rotation, stops them when the UI is gone for longer. */
val WHILE_UI_SUBSCRIBED: SharingStarted = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS)
