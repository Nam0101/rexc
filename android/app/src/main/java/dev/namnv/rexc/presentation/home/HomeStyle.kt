package dev.namnv.rexc.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.namnv.rexc.core.ui.theme.Colors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import uniffi.rexc_ffi.HostStatus
import uniffi.rexc_ffi.ProgramState

/** Rows and cards drawn from a host's stale state: still readable, plainly not live. */
internal const val STALE_ALPHA = 0.6f

@Composable
fun StateDot(state: ProgramState, size: androidx.compose.ui.unit.Dp = 8.dp) {
  Box(Modifier.size(size).clip(CircleShape).background(stateColor(state)))
}

@Composable
fun stateColor(state: ProgramState): Color = when (state) {
  ProgramState.BLOCKED -> Colors.amber
  ProgramState.ERROR -> Colors.danger
  ProgramState.WORKING -> Colors.accent
  ProgramState.DONE -> Colors.ok
  ProgramState.IDLE -> Colors.muted
}

fun stateLabel(state: ProgramState, kind: String?): String = when (state) {
  ProgramState.BLOCKED -> when (kind) {
    "permission" -> "wants permission"
    "question" -> "has a question"
    "auth" -> "needs sign-in"
    else -> "waiting for you"
  }
  ProgramState.ERROR -> "failed"
  ProgramState.WORKING -> "working"
  ProgramState.DONE -> "done"
  ProgramState.IDLE -> ""
}

@Composable
internal fun hostColor(status: HostStatus): Color = when (status) {
  HostStatus.CONNECTED -> Colors.ok
  HostStatus.CONNECTING, HostStatus.RECONNECTING -> Colors.amber
  HostStatus.OFFLINE -> Colors.line
}

internal fun hostLabel(status: HostStatus, sessions: Int): String = when (status) {
  HostStatus.CONNECTED -> if (sessions == 0) "Connected · no sessions" else "Connected"
  HostStatus.CONNECTING -> "Connecting…"
  HostStatus.RECONNECTING -> "Reconnecting…"
  HostStatus.OFFLINE -> "Offline"
}

/** When stale state was last live: "14:32" today, else "Nov 4, 14:32"; 12 or 24 hour as the device is set. */
internal fun asOfLabel(
  asOfMs: Long,
  nowMs: Long,
  is24Hour: Boolean,
  zone: ZoneId = ZoneId.systemDefault(),
  locale: Locale = Locale.getDefault(),
): String {
  val time = Instant.ofEpochMilli(asOfMs).atZone(zone)
  val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
  val clock = if (is24Hour) "HH:mm" else "h:mm a"
  val pattern = if (time.toLocalDate() == today) clock else "MMM d, $clock"
  return "as of ${DateTimeFormatter.ofPattern(pattern, locale).format(time)}"
}
