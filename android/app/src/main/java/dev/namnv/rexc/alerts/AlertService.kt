package dev.namnv.rexc.alerts

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.namnv.rexc.data.EngineRepository
import javax.inject.Inject
import timber.log.Timber
import uniffi.rexc_ffi.HostRecord

/**
 * Holds the process open while the app is away, so the engine's connections to the
 * hosts with "alerts in the background" stay up and their alerts reach [Notifications].
 * The engine does the watching; this only keeps Android from freezing it.
 */
@AndroidEntryPoint
class AlertService : Service() {
  @Inject lateinit var engine: EngineRepository

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val watched = engine.watchedHosts()
    return when {
      intent?.action == ACTION_STOP -> {
        engine.stopWatching()
        stopSelf()
        START_NOT_STICKY
      }
      watched.isEmpty() -> {
        stopSelf()
        START_NOT_STICKY
      }
      else -> {
        // A null intent is the system bringing us back after a kill: the fresh engine has not connected yet.
        if (intent == null) engine.connectWatched()
        watch(Notifications.watching(this, watched, stopIntent()))
        START_STICKY
      }
    }
  }

  private fun watch(notification: Notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(WATCH_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
      startForeground(WATCH_ID, notification)
    }
  }

  private fun stopIntent(): PendingIntent = PendingIntent.getService(
    this,
    0,
    Intent(this, AlertService::class.java).setAction(ACTION_STOP),
    PendingIntent.FLAG_IMMUTABLE,
  )

  companion object {
    private const val WATCH_ID = 1
    private const val ACTION_STOP = "dev.namnv.rexc.alerts.STOP"

    /** Runs the service exactly while some host wants alerts in the background. Starting needs the app on screen. */
    fun sync(context: Context, watched: List<HostRecord>) {
      val intent = Intent(context, AlertService::class.java)
      if (watched.isEmpty()) {
        context.stopService(intent)
      } else {
        try {
          ContextCompat.startForegroundService(context, intent)
        } catch (e: IllegalStateException) {
          // ForegroundServiceStartNotAllowedException: we are in the background; the next foreground retries.
          Timber.w(e, "Alert service could not start")
        }
      }
    }
  }
}
