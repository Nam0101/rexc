package dev.namnv.rexc.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.namnv.rexc.core.ui.theme.Colors
import dev.namnv.rexc.core.ui.theme.Radius
import dev.namnv.rexc.core.ui.theme.Space

/** A quiet nudge: Android may stop the app in the background, and with it the alerts the user asked for. */
@Composable
fun BatteryHint(visible: Boolean, onAllow: () -> Unit, onDismiss: () -> Unit) {
  if (!visible) return
  Column(
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Colors.surface)
      .padding(Space.l),
  ) {
    Text("Background alerts may stop", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Colors.ink)
    Text(
      "Android can put the app to sleep when the screen is off. Allow it to keep running so alerts still arrive.",
      fontSize = 14.sp,
      lineHeight = 20.sp,
      color = Colors.muted,
      modifier = Modifier.padding(top = Space.xs),
    )
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = Space.s),
      horizontalArrangement = Arrangement.End,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = onDismiss) { Text("Not now", color = Colors.muted) }
      TextButton(onClick = onAllow) { Text("Allow", color = Colors.accent) }
    }
  }
}
