package dev.namnv.rexc.presentation.home

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.namnv.rexc.core.ui.theme.Colors
import dev.namnv.rexc.core.ui.theme.MonoFamily
import dev.namnv.rexc.core.ui.theme.Radius
import dev.namnv.rexc.core.ui.theme.Space
import dev.namnv.rexc.presentation.common.MenuEntry
import uniffi.rexc_ffi.HostStatus
import uniffi.rexc_ffi.ProgramState

/** The home list: what needs you, then every host with its sessions. Stateless. */
@Composable
fun HomeScreen(
  state: HomeUiState,
  snackbar: SnackbarHostState,
  onEvent: (HomeUiEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = Colors.bg,
    snackbarHost = { SnackbarHost(snackbar) },
    contentWindowInsets = WindowInsets(0),
  ) { padding ->
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(start = Space.l, end = Space.l, top = top + Space.s, bottom = bottom + Space.xl),
      verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
      item(key = "header") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
          Text(
            "rexc",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Colors.ink,
            modifier = Modifier.weight(1f),
          )
          IconButton(onClick = { onEvent(HomeUiEvent.AddHostClicked) }) {
            Icon(Icons.Default.Add, contentDescription = "Add server", tint = Colors.accent)
          }
        }
      }
      if (state.inbox.isNotEmpty()) {
        item(key = "inbox-title") { SectionTitle("Inbox") }
        items(state.inbox, key = { "${it.hostId}/${it.blockId}" }) { item ->
          InboxCard(item, onEvent)
        }
      }
      if (state.hosts.isEmpty()) {
        item(key = "empty") { EmptyState { onEvent(HomeUiEvent.AddHostClicked) } }
      }
      items(state.hosts, key = { it.id }) { host ->
        HostCard(host, busy = state.busyHostId == host.id, onEvent = onEvent)
      }
    }
  }
  state.editor?.let { editor ->
    HostEditorSheet(editor, onEvent)
  }
}

@Composable
private fun SectionTitle(text: String) {
  Text(
    text,
    fontSize = 13.sp,
    fontWeight = FontWeight.SemiBold,
    color = Colors.muted,
    modifier = Modifier.padding(top = Space.s),
  )
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxWidth().clip(
      RoundedCornerShape(Radius.card),
    ).background(Colors.surface).padding(Space.xl),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("No servers yet", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Colors.ink)
    Spacer(Modifier.height(Space.s))
    Text(
      "Add your Rex server by its tailnet name (name.tailxxxx.ts.net), " +
        "a 100.x address with a port, or a full https:// URL.",
      fontSize = 14.sp,
      color = Colors.muted,
      lineHeight = 20.sp,
    )
    Spacer(Modifier.height(Space.l))
    Button(onClick = onAdd) { Text("Add server") }
  }
}

/** One program that needs you: what it asks, its last screen lines, and a reply box that needs no terminal. */
@Composable
private fun InboxCard(item: InboxUi, onEvent: (HomeUiEvent) -> Unit) {
  val tint = stateColor(item.state)
  Column(
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Colors.surface)
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
    item.preview?.takeIf { it.isNotBlank() }?.let { ScreenPreview(it, onOpen = { onEvent(HomeUiEvent.OpenInbox(item)) }) }
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
          haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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

@Composable
private fun HostCard(host: HostUi, busy: Boolean, onEvent: (HomeUiEvent) -> Unit) {
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Colors.surface)) {
    Row(
      Modifier.fillMaxWidth().padding(start = Space.l, end = Space.xs, top = Space.m, bottom = Space.xs),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(Modifier.size(8.dp).clip(CircleShape).background(hostColor(host.status)))
      Spacer(Modifier.width(Space.s))
      Column(Modifier.weight(1f)) {
        Text(host.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Colors.ink)
        Text(
          host.error?.takeIf { host.status != HostStatus.CONNECTED } ?: hostLabel(host.status, host.sessions.size),
          fontSize = 12.sp,
          color = if (host.error != null && host.status != HostStatus.CONNECTED) Colors.danger else Colors.muted,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      HostMenu(host, onEvent)
    }
    host.sessions.forEach { session ->
      SessionRow(session) { onEvent(HomeUiEvent.OpenSession(host.id, session.id)) }
    }
    NewSessionRow(enabled = host.status == HostStatus.CONNECTED && !busy, busy = busy) {
      onEvent(HomeUiEvent.NewSession(host.id))
    }
  }
}

@Composable
private fun HostMenu(host: HostUi, onEvent: (HomeUiEvent) -> Unit) {
  var menu by remember { mutableStateOf(false) }
  val pick = { event: HomeUiEvent ->
    menu = false
    onEvent(event)
  }
  Box {
    IconButton(onClick = {
      menu = true
    }) { Icon(Icons.Default.MoreVert, contentDescription = "Server menu", tint = Colors.muted) }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
      if (host.status == HostStatus.OFFLINE) {
        MenuEntry("Connect") { pick(HomeUiEvent.Connect(host.id)) }
      } else {
        MenuEntry("Disconnect") { pick(HomeUiEvent.Disconnect(host.id)) }
      }
      MenuEntry("Edit") { pick(HomeUiEvent.EditHostClicked(host.id)) }
      MenuEntry("Remove", color = Colors.danger) { pick(HomeUiEvent.RemoveHost(host.id)) }
    }
  }
}

@Composable
private fun NewSessionRow(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
  val tint = if (enabled) Colors.accent else Colors.muted
  Row(
    Modifier.fillMaxWidth().clickable(
      enabled = enabled,
      onClick = onClick,
    ).padding(horizontal = Space.l, vertical = Space.m),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (busy) {
      CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Colors.accent)
    } else {
      Icon(Icons.Default.Add, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
    Spacer(Modifier.width(Space.s))
    Text("New session", color = tint, fontSize = 15.sp)
  }
}

@Composable
private fun SessionRow(session: SessionUi, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Space.l, vertical = Space.m),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Colors.badge),
      contentAlignment = Alignment.Center,
    ) {
      Text(">_", fontFamily = MonoFamily, fontWeight = FontWeight.Bold, color = Colors.badgeInk, fontSize = 13.sp)
    }
    Spacer(Modifier.width(Space.m))
    Column(Modifier.weight(1f)) {
      Text(session.label, color = Colors.ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(
        listOf(session.summary, "${session.tabCount} tab${if (session.tabCount == 1) "" else "s"}").filter {
          it.isNotBlank()
        }.joinToString(" · "),
        color = Colors.muted,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    session.state?.takeIf { it != ProgramState.IDLE }?.let { StateDot(it) }
  }
}

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
private fun hostColor(status: HostStatus): Color = when (status) {
  HostStatus.CONNECTED -> Colors.ok
  HostStatus.CONNECTING, HostStatus.RECONNECTING -> Colors.amber
  HostStatus.OFFLINE -> Colors.line
}

private fun hostLabel(status: HostStatus, sessions: Int): String = when (status) {
  HostStatus.CONNECTED -> if (sessions == 0) "Connected · no sessions" else "Connected"
  HostStatus.CONNECTING -> "Connecting…"
  HostStatus.RECONNECTING -> "Reconnecting…"
  HostStatus.OFFLINE -> "Offline"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostEditorSheet(editor: HostEditor, onEvent: (HomeUiEvent) -> Unit) {
  ModalBottomSheet(onDismissRequest = { onEvent(HomeUiEvent.DismissEditor) }, containerColor = Colors.surface) {
    Column(Modifier.padding(horizontal = Space.xl).padding(bottom = Space.xl)) {
      Text(
        if (editor.hostId == null) "Add server" else "Edit server",
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = Colors.ink,
      )
      Spacer(Modifier.height(Space.l))
      OutlinedTextField(
        value = editor.address,
        onValueChange = { onEvent(HomeUiEvent.EditorChanged(editor.name, it, editor.backgroundAlerts)) },
        label = { Text("Address") },
        placeholder = { Text("name.tailxxxx.ts.net or 100.64.0.1:7777") },
        supportingText = { Text(editor.error ?: "Tailnet name, host:port, or an https:// URL") },
        isError = editor.error != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
          keyboardType = KeyboardType.Uri,
          capitalization = KeyboardCapitalization.None,
        ),
        modifier = Modifier.fillMaxWidth(),
      )
      Spacer(Modifier.height(Space.s))
      OutlinedTextField(
        value = editor.name,
        onValueChange = { onEvent(HomeUiEvent.EditorChanged(it, editor.address, editor.backgroundAlerts)) },
        label = { Text("Name (optional)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      Spacer(Modifier.height(Space.m))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("Alerts in the background", color = Colors.ink, fontSize = 15.sp)
          Text(
            "Keep this server connected while the app is away, behind a quiet notification, " +
              "so a program that needs you can reach you.",
            color = Colors.muted,
            fontSize = 12.sp,
          )
        }
        Switch(checked = editor.backgroundAlerts, onCheckedChange = {
          onEvent(HomeUiEvent.EditorChanged(editor.name, editor.address, it))
        })
      }
      Spacer(Modifier.height(Space.l))
      Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { onEvent(HomeUiEvent.DismissEditor) }) { Text("Cancel") }
        Spacer(Modifier.width(Space.s))
        Button(onClick = {
          onEvent(HomeUiEvent.SaveHost)
        }, enabled = !editor.saving) { Text(if (editor.saving) "Saving…" else "Save") }
      }
    }
  }
}

private const val PREVIEW_LINES = 4
private const val PREVIEW_INK = 0.85f
