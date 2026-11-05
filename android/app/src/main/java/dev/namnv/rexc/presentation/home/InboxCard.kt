package dev.namnv.rexc.presentation.home

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.namnv.rexc.core.ui.theme.Colors
import dev.namnv.rexc.core.ui.theme.MonoFamily
import dev.namnv.rexc.core.ui.theme.Radius
import dev.namnv.rexc.core.ui.theme.Space
import dev.namnv.rexc.navigation.Terminal
import dev.namnv.rexc.presentation.common.AgentBadge
import dev.namnv.rexc.presentation.common.LocalSettings
import dev.namnv.rexc.presentation.common.pressScale
import dev.namnv.rexc.presentation.common.sharedBoundsFor
import uniffi.rexc_ffi.ProgramState

/**
 * One program that needs you, as a row in the "Needs you" group: what it asks and where.
 * A blocked one also shows its last screen lines and a reply box that needs no terminal.
 */
@Composable
internal fun InboxCard(item: InboxUi, onEvent: (HomeUiEvent) -> Unit) {
  Column(
    modifier = Modifier.fillMaxWidth()
      .sharedBoundsFor(Terminal(item.hostId, item.sessionId, item.blockId).sharedKey)
      .pressScale()
      .background(Colors.surface)
      .clickable { onEvent(HomeUiEvent.OpenInbox(item)) }
      .padding(horizontal = Space.l, vertical = Space.m),
    verticalArrangement = Arrangement.spacedBy(Space.s),
  ) {
    Row(verticalAlignment = Alignment.Top) {
      AgentBadge(item.app, item.state, BADGE_SIZE)
      Spacer(Modifier.width(Space.m))
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val label = stateLabel(item.state, item.kind)
        val detail = item.message?.takeIf { it.isNotBlank() } ?: item.title?.takeIf { it.isNotBlank() } ?: label
        Text(detail, color = Colors.ink, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
          listOf(item.app, label, item.where).filter { it.isNotBlank() }.joinToString(" · "),
          color = Colors.muted,
          fontSize = 12.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    if (item.state == ProgramState.BLOCKED) {
      item.preview?.takeIf { it.isNotBlank() }?.let {
        ScreenPreview(it, onOpen = { onEvent(HomeUiEvent.OpenInbox(item)) })
      }
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
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.chip)).background(Colors.key).height(REPLY_HEIGHT),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BasicTextField(
      value = draft,
      onValueChange = { draft = it },
      modifier = Modifier.weight(1f).padding(start = Space.m),
      singleLine = true,
      textStyle = TextStyle(fontFamily = MonoFamily, fontSize = 14.sp, color = Colors.ink),
      cursorBrush = SolidColor(Colors.accent),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
      keyboardActions = KeyboardActions(onSend = { send() }),
      decorationBox = { field ->
        Box(contentAlignment = Alignment.CenterStart) {
          if (draft.isEmpty()) Text("Reply…", color = Colors.muted, fontSize = 14.sp, fontFamily = MonoFamily)
          field()
        }
      },
    )
    if (sending) {
      CircularProgressIndicator(
        Modifier.padding(horizontal = Space.m).size(18.dp),
        strokeWidth = 2.dp,
        color = Colors.accent,
      )
    } else {
      IconButton(onClick = send, enabled = draft.isNotBlank()) {
        Icon(
          Icons.AutoMirrored.Filled.Send,
          contentDescription = "Send",
          tint = if (draft.isBlank()) Colors.muted else Colors.accent,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

private const val PREVIEW_LINES = 3
private val BADGE_SIZE = 28.dp
private const val PREVIEW_INK = 0.85f
private val REPLY_HEIGHT = 40.dp
