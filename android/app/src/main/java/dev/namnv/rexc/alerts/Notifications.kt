package dev.namnv.rexc.alerts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import androidx.core.content.ContextCompat
import dev.namnv.rexc.MainActivity
import dev.namnv.rexc.R
import uniffi.rexc_ffi.Alert
import uniffi.rexc_ffi.AlertKind
import uniffi.rexc_ffi.HostRecord

/** System notifications for programs that need you, finished, or failed. */
object Notifications {
  private const val NEEDS_YOU = "needs_you"
  private const val UPDATES = "updates"
  private const val FINISHED = "finished"
  private const val WATCHING = "watching"
  private const val SENT_TIMEOUT_MS = 4_000L

  fun ensureChannels(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
      NotificationChannel(NEEDS_YOU, "Needs you", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "A program is waiting for your answer"
      },
    )
    nm.createNotificationChannel(
      NotificationChannel(UPDATES, "Failed and other updates", NotificationManager.IMPORTANCE_DEFAULT),
    )
    // Finishing is good news, not an interruption: it shows up without sound or a heads-up.
    nm.createNotificationChannel(
      NotificationChannel(FINISHED, "Finished", NotificationManager.IMPORTANCE_LOW),
    )
    nm.createNotificationChannel(
      NotificationChannel(WATCHING, "Watching in the background", NotificationManager.IMPORTANCE_MIN).apply {
        description = "Keeps your servers connected while the app is away so they can alert you"
        setShowBadge(false)
      },
    )
  }

  /** The quiet, ongoing notice behind [AlertService]; its Stop action turns background alerts off. */
  fun watching(context: Context, hosts: List<HostRecord>, stop: PendingIntent): Notification {
    val open = PendingIntent.getActivity(
      context,
      0,
      Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_IMMUTABLE,
    )
    return Notification.Builder(context, WATCHING)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle("Watching for alerts")
      .setContentText(hosts.joinToString { it.name })
      .setOngoing(true)
      .setContentIntent(open)
      .addAction(
        Notification.Action.Builder(
          Icon.createWithResource(context, R.drawable.ic_notification),
          "Stop",
          stop,
        ).build(),
      )
      .setCategory(Notification.CATEGORY_SERVICE)
      .build()
  }

  fun show(context: Context, alert: Alert) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      return
    }
    val blocked = alert.kind is AlertKind.Blocked
    val intent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
      putExtra("host_id", alert.hostId)
      putExtra("session_id", alert.sessionId)
      putExtra("block_id", alert.blockId)
    }
    val id = alert.blockId.hashCode()
    val pi = PendingIntent.getActivity(
      context,
      id,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val where = listOf(alert.hostName, alert.sessionLabel, alert.windowLabel).filter {
      it.isNotBlank()
    }.joinToString(" › ")
    val n = Notification.Builder(context, channelFor(alert.kind))
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(alert.title)
      .setContentText(alert.body.ifBlank { where })
      .setSubText(where)
      .setStyle(
        Notification.BigTextStyle().bigText(
          listOf(alert.body, where).filter {
            it.isNotBlank()
          }.joinToString("\n"),
        ),
      )
      .setContentIntent(pi)
      .setAutoCancel(true)
      .setCategory(if (blocked) Notification.CATEGORY_CALL else Notification.CATEGORY_STATUS)
      .setGroup(alert.hostId)
      .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
      .apply { if (blocked) addActions(context, alert, id) }
      .build()
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.notify(id, n)
    nm.notify(alert.hostId.hashCode(), groupSummary(context, alert))
  }

  private fun channelFor(kind: AlertKind): String = when (kind) {
    is AlertKind.Blocked -> NEEDS_YOU
    AlertKind.Done -> FINISHED
    AlertKind.Error, AlertKind.Notification -> UPDATES
  }

  /** One bundle per host; its own sound is off so only the alerts inside it make noise. */
  private fun groupSummary(context: Context, alert: Alert): Notification = Notification.Builder(context, UPDATES)
    .setSmallIcon(R.drawable.ic_notification)
    .setContentTitle(alert.hostName)
    .setGroup(alert.hostId)
    .setGroupSummary(true)
    .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
    .setAutoCancel(true)
    .build()

  /** Reply is the open question; the quick buttons answer a permission prompt in one tap. */
  private fun Notification.Builder.addActions(context: Context, alert: Alert, id: Int) {
    val (rare, common) = quickActionsFor(alert.kind).partition { it.raw }
    // Android shows three actions: Reply stays among them and the rarely needed Esc goes last.
    common.forEach { addAction(quickAction(context, alert, id, it)) }
    addAction(replyAction(context, alert, id))
    rare.forEach { addAction(quickAction(context, alert, id, it)) }
  }

  /** Inline text that [ReplyReceiver] types into the pane, followed by Enter. */
  private fun quickAction(context: Context, alert: Alert, id: Int, action: QuickAction): Notification.Action {
    val intent = Intent(context, ReplyReceiver::class.java).apply {
      putExtra(MainActivity.EXTRA_HOST, alert.hostId)
      putExtra(MainActivity.EXTRA_SESSION, alert.sessionId)
      putExtra(MainActivity.EXTRA_BLOCK, alert.blockId)
      putExtra(ReplyReceiver.EXTRA_ID, id)
      putExtra(ReplyReceiver.EXTRA_TEXT, action.text)
      putExtra(ReplyReceiver.EXTRA_RAW, action.raw)
      putExtra(ReplyReceiver.EXTRA_LABEL, action.label)
    }
    // Extras do not tell intents apart, so the request code has to.
    val pi = PendingIntent.getBroadcast(
      context,
      id * QuickAction.entries.size + action.ordinal,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notification), action.label, pi)
      .build()
  }

  private fun replyAction(context: Context, alert: Alert, id: Int): Notification.Action {
    val intent = Intent(context, ReplyReceiver::class.java).apply {
      putExtra(MainActivity.EXTRA_HOST, alert.hostId)
      putExtra(MainActivity.EXTRA_SESSION, alert.sessionId)
      putExtra(MainActivity.EXTRA_BLOCK, alert.blockId)
      putExtra(ReplyReceiver.EXTRA_ID, id)
    }
    // Mutable: the system fills the typed text into the intent.
    val pi = PendingIntent.getBroadcast(
      context,
      id,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
    return Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notification), "Reply", pi)
      .addRemoteInput(RemoteInput.Builder(ReplyReceiver.KEY_REPLY).setLabel("Type into ${alert.title}").build())
      .setAllowGeneratedReplies(false)
      .build()
  }

  /** Replaces a replied-to notification: Android keeps the reply field spinning until it changes. */
  fun replied(context: Context, id: Int, text: String, error: String?) {
    val n = Notification.Builder(context, UPDATES)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(if (error == null) "Sent" else "Couldn't send")
      .setContentText(if (error == null) text else "$text — $error")
      .setTimeoutAfter(if (error == null) SENT_TIMEOUT_MS else 0)
      .setOnlyAlertOnce(true)
      .setAutoCancel(true)
      .build()
    context.getSystemService(NotificationManager::class.java).notify(id, n)
  }

  /** Dismisses the alerts (not the watch notice) when the app comes back on screen. */
  fun clearAlerts(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.activeNotifications.filter { it.notification.channelId != WATCHING }.forEach { nm.cancel(it.id) }
  }
}
