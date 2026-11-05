package dev.namnv.rexc

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import dev.namnv.rexc.alerts.Notifications
import dev.namnv.rexc.data.EngineRepository
import javax.inject.Inject
import timber.log.Timber

@HiltAndroidApp
class RexcApplication : Application() {
  @Inject lateinit var engine: EngineRepository

  override fun onCreate() {
    super.onCreate()
    if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
    Notifications.ensureChannels(this)
    ProcessLifecycleOwner.get().lifecycle.addObserver(
      object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
          engine.setBackground(false)
          engine.syncAlertService(force = true)
          Notifications.clearAlerts(this@RexcApplication)
        }

        override fun onStop(owner: LifecycleOwner) {
          engine.setBackground(true)
        }
      },
    )
  }
}
