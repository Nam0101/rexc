package dev.namnv.rexc.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.namnv.rexc.R
import dev.namnv.rexc.core.ui.theme.Colors
import dev.namnv.rexc.core.ui.theme.Space

/** Drawn instead of the app while it is locked, so nothing of it shows behind. */
@Composable
fun LockCover(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier.fillMaxSize().background(Colors.bg).systemBarsPadding(),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(Icons.Default.Lock, contentDescription = null, tint = Colors.muted, modifier = Modifier.size(40.dp))
    Text(
      stringResource(R.string.app_name),
      color = Colors.ink,
      fontSize = 24.sp,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(top = Space.m),
    )
    Button(onClick = onUnlock, modifier = Modifier.padding(top = Space.l)) { Text("Unlock") }
  }
}
