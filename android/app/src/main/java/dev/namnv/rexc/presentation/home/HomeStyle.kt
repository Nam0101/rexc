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
import uniffi.rexc_ffi.HostStatus
import uniffi.rexc_ffi.ProgramState

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
