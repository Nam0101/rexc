package dev.namnv.rexc.base

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber

/** A ViewModel that emits one-shot effects (navigation, messages) next to its state. */
interface HasEventFlow<out E> {
  val eventFlow: Flow<E>
}

/** Buffered one-shot effects: delivered once, kept while the UI is away. */
class EventChannel<E>(logTag: String = "EventChannel") : HasEventFlow<E> {
  private val tag = logTag.take(MAX_TAG)
  private val channel = Channel<E>(capacity = Channel.BUFFERED)

  override val eventFlow: Flow<E> = channel.receiveAsFlow()

  fun trySend(event: E): Boolean {
    val result = channel.trySend(event)
    if (!result.isSuccess) Timber.tag(tag).w("Dropped event (buffer full): %s", event)
    return result.isSuccess
  }

  private companion object {
    const val MAX_TAG = 23
  }
}
