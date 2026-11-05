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
 * The notification's inline Reply: types the text and Enter into the pane
 * through the engine, which connects for just this call when the app is frozen.
 */
class ReplyReceiver : BroadcastReceiver() {
  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface Engine {
    fun engine(): EngineRepository
  }

  private data class Reply(val host: String, val session: String, val block: String, val text: String, val id: Int)

  override fun onReceive(context: Context, intent: Intent) {
    val reply = replyIn(intent) ?: return
    val engine = EntryPointAccessors.fromApplication(context, Engine::class.java).engine()
    val pending = goAsync()
    scope.launch {
      val error = try {
        engine.call { quickReply(reply.host, reply.session, reply.block, reply.text) }
        null
      } catch (e: RexcException) {
        Timber.w(e, "Reply from notification failed")
        e.message ?: "the server refused"
      }
      Notifications.replied(context, reply.id, reply.text, error)
      pending.finish()
    }
  }

  private fun replyIn(intent: Intent): Reply? {
    val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
    val ids = listOf(MainActivity.EXTRA_HOST, MainActivity.EXTRA_SESSION, MainActivity.EXTRA_BLOCK)
      .map { intent.getStringExtra(it) ?: return null }
    return text?.let { Reply(ids[0], ids[1], ids[2], it, intent.getIntExtra(EXTRA_ID, 0)) }
  }

  companion object {
    const val KEY_REPLY = "reply"
    const val EXTRA_ID = "notification_id"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }
}
