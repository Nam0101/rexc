package dev.namnv.rexc.presentation.home

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.namnv.rexc.core.ui.theme.Colors
import dev.namnv.rexc.core.ui.theme.MonoFamily
import dev.namnv.rexc.core.ui.theme.Radius
import dev.namnv.rexc.core.ui.theme.Space
import dev.namnv.rexc.navigation.Terminal
import dev.namnv.rexc.presentation.common.LocalSettings
import dev.namnv.rexc.presentation.common.sharedBoundsFor
import uniffi.rexc_ffi.ProgramState

/** One program that needs you: what it asks, its last screen lines, and a reply box that needs no terminal. */
@Composable
internal fun InboxCard(item: InboxUi, onEvent: (HomeUiEvent) -> Unit) {
  val tint = stateColor(item.state)
  Column(
    modifier = Modifier.fillMaxWidth()
      .sharedBoundsFor(Terminal(item.hostId, item.sessionId, item.blockId).sharedKey)
      .clip(RoundedCornerShape(Radius.card)).background(Colors.surface)
      .clickable { onEvent(HomeUiEvent.OpenInbox(item)) }.padding(Space.l),
    verticalArrangement = Arrangement.spacedBy(Space.s),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      StateDot(item.state, size = 10.dp)
      Spacer(Modifier.width(Space.m))
      Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(item.app, fontWeight = FontWeight.SemiBold, color = Colors.ink, fontSize = 16.sp)
          Spacer(Modifier.width(Space.s))
          Text(stateLabel(item.state, item.kind), color = tint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        val detail = item.message?.takeIf { it.isNotBlank() } ?: item.title.orEmpty()
        if (detail.isNotBlank()) {
          Text(detail, color = Colors.ink, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(item.where, color = Colors.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    item.preview?.takeIf {
      it.isNotBlank()
    }?.let { ScreenPreview(it, onOpen = { onEvent(HomeUiEvent.OpenInbox(item)) }) }
    if (item.state != ProgramState.DONE) {
      ReplyRow(sending = item.sending, onSend = { onEvent(HomeUiEvent.QuickReply(item, it)) })
    }
  }
}

/** The pane's last lines, as the desktop shows them; a tap opens the pane, a long press copies them. */
@Composable
private fun ScreenPreview(text: String, onOpen: () -> Unit) {
  val context = LocalContext.current
  val haptics = LocalHapticFeedback.current
  val hapticsOn = LocalSettings.current.haptics
  Text(
    text,
    fontFamily = MonoFamily,
    fontSize = 11.sp,
    lineHeight = 15.sp,
    color = Colors.ink.copy(alpha = PREVIEW_INK),
    maxLines = PREVIEW_LINES,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.chip)).background(Colors.bg)
      .combinedClickable(
        onClick = onOpen,
        onLongClick = {
          if (hapticsOn) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
          context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Terminal", text))
        },
      )
      .padding(horizontal = Space.m, vertical = Space.s),
  )
}

/** A one-line answer typed straight into the pane, followed by Enter. */
@Composable
private fun ReplyRow(sending: Boolean, onSend: (String) -> Unit) {
  var draft by rememberSaveable { mutableStateOf("") }
  val send = {
    if (draft.isNotBlank() && !sending) {
      onSend(draft)
      draft = ""
    }
  }
  Row(verticalAlignment = Alignment.CenterVertically) {
    OutlinedTextField(
      value = draft,
      onValueChange = { draft = it },
      modifier = Modifier.weight(1f),
      placeholder = { Text("Reply…", color = Colors.muted) },
      singleLine = true,
      textStyle = LocalTextStyle.current.copy(fontFamily = MonoFamily, fontSize = 14.sp),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
      keyboardActions = KeyboardActions(onSend = { send() }),
      shape = RoundedCornerShape(Radius.chip),
    )
    Spacer(Modifier.width(Space.xs))
    if (sending) {
      CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Colors.accent)
    } else {
      IconButton(onClick = send, enabled = draft.isNotBlank()) {
        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Colors.accent)
      }
    }
  }
}

private const val PREVIEW_LINES = 4
private const val PREVIEW_INK = 0.85f
