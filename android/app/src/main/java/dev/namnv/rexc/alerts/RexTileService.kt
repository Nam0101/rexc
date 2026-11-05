package dev.namnv.rexc.alerts

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.namnv.rexc.MainActivity
import dev.namnv.rexc.data.EngineRepository
import dev.namnv.rexc.presentation.home.needsYou
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import uniffi.rexc_ffi.InboxItem

/** Quick Settings tile: how many programs need you, the same count as Home's "Needs you". A tap opens Home. */
class RexTileService : TileService() {
  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface Engine {
    fun engine(): EngineRepository
  }

  private val scope: CoroutineScope = MainScope()
  private var listening: Job? = null

  override fun onStartListening() {
    super.onStartListening()
    val engine = EntryPointAccessors.fromApplication(applicationContext, Engine::class.java).engine()
    listening?.cancel()
    listening = scope.launch { engine.inbox.collect { show(needsYouCount(it)) } }
  }

  override fun onStopListening() {
    listening?.cancel()
    listening = null
    super.onStopListening()
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  @SuppressLint("StartActivityAndCollapseDeprecated")
  override fun onClick() {
    super.onClick()
    val intent = Intent(this, MainActivity::class.java)
      .putExtra(MainActivity.EXTRA_HOME, true)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startActivityAndCollapse(
        PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
      )
    } else {
      @Suppress("DEPRECATION")
      startActivityAndCollapse(intent)
    }
  }

  private fun show(count: Int) {
    val tile = qsTile ?: return
    tile.state = if (count > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      tile.subtitle = when (count) {
        0 -> "All quiet"
        1 -> "1 needs you"
        else -> "$count need you"
      }
    }
    tile.updateTile()
  }

  companion object {
    @Volatile
    private var lastCount = -1

    private fun needsYouCount(items: List<InboxItem>): Int = items.count { it.state.needsYou() }

    /** The inbox changed: when the count moved, ask Android to let the tile redraw even while it is not on screen. */
    fun sync(context: Context, inbox: List<InboxItem>) {
      val count = needsYouCount(inbox)
      if (count == lastCount) return
      lastCount = count
      requestListeningState(context, ComponentName(context, RexTileService::class.java))
    }
  }
}
