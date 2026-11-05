package dev.namnv.rexc.presentation.share

import dev.namnv.rexc.navigation.Terminal
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Most text one share pastes, in UTF-8 bytes. */
const val MAX_SHARE_BYTES = 64 * 1024

// UTF-8 continuation bytes look like 10xxxxxx.
private const val UTF8_TOP_BITS = 0xC0
private const val UTF8_CONTINUATION = 0x80

/**
 * What another app shared, ready to paste: the subject on its own line above the text when it adds
 * something, trailing line breaks dropped (an unbracketed paste would turn them into Enter), cut at
 * [MAX_SHARE_BYTES] on a character boundary. Null when nothing is left.
 */
fun sharedText(text: String?, subject: String?): String? {
  val body = text.orEmpty()
  val full = if (subject.isNullOrBlank() || subject == body) body else "$subject\n$body"
  val bytes = full.trimEnd('\n', '\r').encodeToByteArray()
  var end = minOf(bytes.size, MAX_SHARE_BYTES)
  // Back off a continuation byte so the cut never splits a character.
  while (end < bytes.size && end > 0 && bytes[end].toInt() and UTF8_TOP_BITS == UTF8_CONTINUATION) end--
  return bytes.decodeToString(0, end).takeIf { it.isNotBlank() }
}

/** Shared text waiting for the terminal it was sent to; that terminal pastes it once its pane opens. */
@Singleton
class PendingShare @Inject constructor() {
  data class Share(val target: Terminal, val text: String)

  private val _pending = MutableStateFlow<Share?>(null)
  val pending: StateFlow<Share?> = _pending.asStateFlow()

  fun send(target: Terminal, text: String) {
    _pending.value = Share(target, text)
  }

  /** The text for [target], at most once. */
  fun take(target: Terminal): String? {
    val share = _pending.value?.takeIf { it.target == target } ?: return null
    return share.text.takeIf { _pending.compareAndSet(share, null) }
  }
}
