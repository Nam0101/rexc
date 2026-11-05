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
import uniffi.rexc_ffi.Prompt
import uniffi.rexc_ffi.RexcException

/**
 * The notification's inline Reply and quick buttons: types the text (and Enter, unless raw)
 * into the pane through the engine, which connects for just this call when the app is frozen.
 * The engine types nothing when the pane no longer waits on the prompt the notification showed.
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
    val prompt: Prompt,
  )

  override fun onReceive(context: Context, intent: Intent) {
    val reply = replyIn(intent) ?: return
    val engine = EntryPointAccessors.fromApplication(context, Engine::class.java).engine()
    val pending = goAsync()
    scope.launch {
      try {
        engine.call { quickReply(reply.host, reply.session, reply.block, reply.text, reply.prompt, reply.enter) }
        Notifications.replied(context, reply.id, reply.label, null)
      } catch (e: RexcException.NotWaiting) {
        Notifications.notWaiting(context, reply.id, reply.label, e.message)
      } catch (e: RexcException) {
        Timber.w(e, "Reply from notification failed")
        Notifications.replied(context, reply.id, reply.label, e.message ?: "the server refused")
      }
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
      // Only Blocked alerts carry reply actions, so every reply answers their prompt.
      prompt = Prompt(intent.getStringExtra(EXTRA_PROMPT_KIND), intent.getStringExtra(EXTRA_PROMPT_MESSAGE)),
    )
  }

  companion object {
    const val KEY_REPLY = "reply"
    const val EXTRA_ID = "notification_id"
    const val EXTRA_TEXT = "text"

    /** Send [EXTRA_TEXT] without Enter (Esc). */
    const val EXTRA_RAW = "raw"
    const val EXTRA_LABEL = "label"

    /** The Blocked status's kind and message the notification showed: what the reply answers. */
    const val EXTRA_PROMPT_KIND = "prompt_kind"
    const val EXTRA_PROMPT_MESSAGE = "prompt_message"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }
}
