package dev.namnv.rexc.alerts

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Whether Android's battery optimisation (Doze, app standby) is off for this app, which background alerts depend on. */
object BatteryOptimization {
  fun isIgnoring(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

  /** The system's one-tap "Allow rexc to run in the background" dialog. */
  @SuppressLint("BatteryLife")
  fun requestIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
}
