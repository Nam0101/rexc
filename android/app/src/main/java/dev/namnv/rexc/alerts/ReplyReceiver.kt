package dev.namnv.rexc.alerts

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.namnv.rexc.MainActivity
import dev.namnv.rexc.data.EngineRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import uniffi.rexc_ffi.RexcException

/**
 * The notification's inline Reply and quick buttons: types the text (and Enter, unless raw)
 * into the pane through the engine, which connects for just this call when the app is frozen.
 */
class ReplyReceiver : BroadcastReceiver() {
  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface Engine {
    fun engine(): EngineRepository
  }

  private data class Reply(
    val host: String,
    val session: String,
    val block: String,
    val text: String,
    val enter: Boolean,
    val label: String,
    val id: Int,
  )

  override fun onReceive(context: Context, intent: Intent) {
    val reply = replyIn(intent) ?: return
    val engine = EntryPointAccessors.fromApplication(context, Engine::class.java).engine()
    val pending = goAsync()
    scope.launch {
      val error = try {
        engine.call { quickReply(reply.host, reply.session, reply.block, reply.text, reply.enter) }
        null
      } catch (e: RexcException) {
        Timber.w(e, "Reply from notification failed")
        e.message ?: "the server refused"
      }
      Notifications.replied(context, reply.id, reply.label, error)
      pending.finish()
    }
  }

  /** Typed text from the inline field, or a quick button's ready-made [EXTRA_TEXT]. */
  private fun replyIn(intent: Intent): Reply? {
    val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
    val text = typed ?: intent.getStringExtra(EXTRA_TEXT)
    val keys = listOf(MainActivity.EXTRA_HOST, MainActivity.EXTRA_SESSION, MainActivity.EXTRA_BLOCK)
    val ids = keys.mapNotNull { intent.getStringExtra(it) }
    if (text == null || ids.size < keys.size) return null
    return Reply(
      host = ids[0],
      session = ids[1],
      block = ids[2],
      text = text,
      enter = !intent.getBooleanExtra(EXTRA_RAW, false),
      label = intent.getStringExtra(EXTRA_LABEL) ?: text,
      id = intent.getIntExtra(EXTRA_ID, 0),
    )
  }

  companion object {
    const val KEY_REPLY = "reply"
    const val EXTRA_ID = "notification_id"
    const val EXTRA_TEXT = "text"

    /** Send [EXTRA_TEXT] without Enter (Esc). */
    const val EXTRA_RAW = "raw"
    const val EXTRA_LABEL = "label"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }
}
