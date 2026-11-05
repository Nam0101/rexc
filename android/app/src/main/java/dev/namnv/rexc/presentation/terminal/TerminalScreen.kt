package dev.namnv.rexc.presentation.terminal

import android.view.KeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.namnv.rexc.core.ui.theme.Colors
import dev.namnv.rexc.core.ui.theme.Radius
import dev.namnv.rexc.core.ui.theme.Space
import dev.namnv.rexc.data.TerminalController
import dev.namnv.rexc.presentation.common.LocalSettings
import dev.namnv.rexc.presentation.common.MenuEntry
import dev.namnv.rexc.presentation.home.StateDot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import uniffi.rexc_ffi.Frame
import uniffi.rexc_ffi.HostStatus
import uniffi.rexc_ffi.ProgramState

/** One session: tabs, panes, the terminal grid, and the key dock. Stateless. */
@Composable
fun TerminalScreen(
  state: TerminalUiState,
  input: TerminalInput,
  frames: Flow<Frame>,
  snackbar: SnackbarHostState,
  onEvent: (TerminalUiEvent) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var termBg by remember { mutableStateOf<Color?>(null) }
  val chrome = termBg ?: Colors.bg
  val ink = remember(chrome) { onColor(chrome) }
  var bridge by remember { mutableStateOf<ImeBridge?>(null) }
  var renaming by remember { mutableStateOf<Rename?>(null) }
  val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
  val sheets = rememberSheetsState()
  val toggleKeyboard: () -> Unit = { bridge?.let { b -> if (imeVisible) b.hide() else b.show() } }
  val copyScreen: () -> Unit = { state.terminal?.allText()?.takeIf { it.isNotEmpty() }?.let(input::copy) }
  val settings = LocalSettings.current
  var closing by remember { mutableStateOf<TerminalUiEvent?>(null) }
  val send: (
    TerminalUiEvent,
  ) -> Unit = { if (settings.confirmClosePane && it.closesPane()) closing = it else onEvent(it) }
  KeepScreenOn(settings.keepScreenOn)
  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = chrome,
    snackbarHost = { SnackbarHost(snackbar) },
    contentWindowInsets = WindowInsets(0),
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding().imePadding().navigationBarsPadding()) {
      TopBar(
        state = state,
        background = chrome,
        onEvent = send,
        onBack = onBack,
        onRename = { renaming = it },
        onCopyScreen = copyScreen,
        onSheet = sheets::open,
      )
      TabsRow(state, ink, send)
      if (state.panes.size > 1) PaneStrip(state, ink, send, onMap = { sheets.open(TerminalSheet.MAP) })
      HorizontalDivider(thickness = Dp.Hairline, color = ink.copy(alpha = HAIRLINE))
      PaneArea(
        state = state,
        input = input,
        frames = frames,
        bridge = bridge,
        imeVisible = imeVisible,
        onBridge = { bridge = it },
        onBackground = { termBg = it },
        onEvent = send,
        onShortcut = sheets::onHardwareKey,
        modifier = Modifier.weight(1f).fillMaxWidth(),
      )
      Dock(
        input = input,
        keyboardVisible = imeVisible,
        onToggleKeyboard = toggleKeyboard,
        background = chrome,
        onOpenSheet = sheets::open,
        hapticsEnabled = settings.haptics,
      )
    }
    TerminalSheets(sheets, state, SheetHost(send, { renaming = it }, copyScreen, input::paste, toggleKeyboard))
  }
  renaming?.let { target -> RenameDialog(target, state, onDismiss = { renaming = null }, onEvent = send) }
  closing?.let { event -> ClosePaneDialog(onConfirm = { onEvent(event) }, onDismiss = { closing = null }) }
}

/** The grid with its keyboard bridge and the overlays over it. */
@Composable
private fun PaneArea(
  state: TerminalUiState,
  input: TerminalInput,
  frames: Flow<Frame>,
  bridge: ImeBridge?,
  imeVisible: Boolean,
  onBridge: (ImeBridge) -> Unit,
  onBackground: (Color) -> Unit,
  onEvent: (TerminalUiEvent) -> Unit,
  modifier: Modifier,
  onShortcut: (KeyEvent) -> Boolean = { false },
) {
  Box(modifier) {
    PaneCarousel(state) { shown ->
      TerminalCanvas(
        controller = shown.controller,
        mirror = shown.mirror,
        input = input,
        frames = frames,
        onSwipePane = { onEvent(TerminalUiEvent.SwipePane(it)) },
        onLink = { onEvent(TerminalUiEvent.OpenUrl(it)) },
        onTap = { bridge?.let { b -> if (imeVisible) b.hide() else b.show() } },
        onBackground = onBackground,
        modifier = Modifier.fillMaxSize(),
      )
    }
    // The soft keyboard's raw input connection; invisible, focused on tap.
    AndroidView(
      factory = { ctx ->
        ImeBridge(ctx, input).also { v ->
          v.onHardwareKey = { e ->
            onShortcut(e) || input.hardwareKey(e) { d -> onEvent(TerminalUiEvent.SwipePane(d)) }
          }
          onBridge(v)
        }
      },
      modifier = Modifier.size(1.dp).alpha(0f).align(Alignment.BottomStart),
    )
    StatusOverlay(state, onEvent, Modifier.align(Alignment.TopCenter))
    PositionDots(state, Modifier.align(Alignment.BottomCenter))
  }
}

/** What the rename dialog edits. */
enum class Rename { TAB, SESSION }

/** The pane on screen, kept through the switch so the old one can slide out while the next one opens. */
@Immutable
private data class ShownPane(val controller: TerminalController, val mirror: Boolean, val ordinal: Int)

/**
 * Slides panes past each other in reading order: the next pane comes in from the
 * right, the previous one from the left. Nothing is shown before the first opens.
 */
@Composable
private fun PaneCarousel(state: TerminalUiState, pane: @Composable (ShownPane) -> Unit) {
  val last = remember { Ref<ShownPane>() }
  val shown = state.terminal?.let { ShownPane(it, state.info?.mirror == true, state.paneOrdinal) } ?: last.value
  last.value = shown
  AnimatedContent(
    targetState = shown,
    contentKey = { it?.controller?.id },
    transitionSpec = {
      val forward = (targetState?.ordinal ?: 0) >= (initialState?.ordinal ?: 0)
      val sign = if (forward) 1 else -1
      (slideInHorizontally { sign * it } + fadeIn()) togetherWith (slideOutHorizontally { -sign * it } + fadeOut())
    },
    label = "pane",
  ) { target ->
    // The outgoing canvas keeps drawing its last frame while it slides away.
    if (target != null) pane(target) else Box(Modifier.fillMaxSize())
  }
}

/** Where you are among the tab's panes (or among tabs), shown briefly after a switch. */
@Composable
private fun PositionDots(state: TerminalUiState, modifier: Modifier) {
  val (index, total) = if (state.panes.size > 1) {
    state.panes.indexOfFirst { it.blockId == state.currentBlockId } to state.panes.size
  } else {
    state.tabs.indexOfFirst { it.id == state.currentTabId } to state.tabs.size
  }
  var visible by remember { mutableStateOf(false) }
  LaunchedEffect(state.currentBlockId) {
    visible = total > 1
    delay(DOTS_MS)
    visible = false
  }
  AnimatedVisibility(visible, modifier = modifier.padding(bottom = Space.m), enter = fadeIn(), exit = fadeOut()) {
    Row(
      Modifier.clip(RoundedCornerShape(10.dp)).background(Colors.scrim).padding(horizontal = Space.s, vertical = 5.dp),
      horizontalArrangement = Arrangement.spacedBy(5.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (total > MAX_DOTS) {
        Text("${index + 1} / $total", color = Colors.ink, fontSize = 11.sp)
      } else {
        repeat(total) { i ->
          Box(
            Modifier.size(if (i == index) 7.dp else 5.dp).clip(CircleShape)
              .background(if (i == index) Colors.ink else Colors.ink.copy(alpha = MUTED_INK)),
          )
        }
      }
    }
  }
}

@Composable
private fun RenameDialog(
  target: Rename,
  state: TerminalUiState,
  onDismiss: () -> Unit,
  onEvent: (TerminalUiEvent) -> Unit,
) {
  val current = when (target) {
    Rename.TAB -> state.tabs.firstOrNull { it.id == state.currentTabId }?.name.orEmpty()
    Rename.SESSION -> state.sessionLabel
  }
  val onRename = { name: String ->
    onDismiss()
    onEvent(if (target == Rename.TAB) TerminalUiEvent.RenameTab(name) else TerminalUiEvent.RenameSession(name))
  }
  var name by remember { mutableStateOf(current) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (target == Rename.TAB) "Rename tab" else "Rename session") },
    text = {
      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onRename(name.trim()) }),
      )
    },
    confirmButton = { TextButton(onClick = { onRename(name.trim()) }) { Text("Rename") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
private fun TopBar(
  state: TerminalUiState,
  background: Color,
  onEvent: (TerminalUiEvent) -> Unit,
  onBack: () -> Unit,
  onRename: (Rename) -> Unit,
  onCopyScreen: () -> Unit,
  onSheet: (TerminalSheet) -> Unit = {},
) {
  val onBg = onColor(background)
  Row(
    Modifier.fillMaxWidth().background(background).padding(end = Space.xs),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = onBg) }
    Column(Modifier.weight(1f)) {
      Text(
        state.sessionLabel.ifBlank {
          "Session"
        },
        color = onBg,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          Modifier.size(
            6.dp,
          ).clip(CircleShape).background(if (state.hostStatus == HostStatus.CONNECTED) Colors.ok else Colors.amber),
        )
        Spacer(Modifier.width(6.dp))
        Text(
          listOfNotNull(state.hostName, state.info?.let { "${it.cols}×${it.rows}" }).joinToString(" · "),
          color = onBg.copy(alpha = 0.6f),
          fontSize = 12.sp,
          maxLines = 1,
        )
      }
    }
    PaneMenu(state, tint = onBg, onEvent = onEvent, onRename = onRename, onCopyScreen = onCopyScreen, onSheet = onSheet)
  }
}

@Composable
private fun PaneMenu(
  state: TerminalUiState,
  tint: Color,
  onEvent: (TerminalUiEvent) -> Unit,
  onRename: (Rename) -> Unit,
  onCopyScreen: () -> Unit,
  onSheet: (TerminalSheet) -> Unit = {},
) {
  var menu by remember { mutableStateOf(false) }
  fun pick(action: () -> Unit) {
    menu = false
    action()
  }
  Box {
    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = tint) }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
      MenuEntry("Command palette") { pick { onSheet(TerminalSheet.PALETTE) } }
      MenuEntry("Quick switcher") { pick { onSheet(TerminalSheet.SWITCHER) } }
      MenuEntry("Pane map") { pick { onSheet(TerminalSheet.MAP) } }
      MenuEntry("New tab") { pick { onEvent(TerminalUiEvent.NewTab) } }
      MenuEntry("Split right") { pick { onEvent(TerminalUiEvent.Split(false)) } }
      MenuEntry("Split down") { pick { onEvent(TerminalUiEvent.Split(true)) } }
      when (state.info?.ownedByMe) {
        true -> MenuEntry("Release to desktop") { pick { onEvent(TerminalUiEvent.Release) } }
        false -> MenuEntry("Take over size") { pick { onEvent(TerminalUiEvent.TakeOver) } }
        null -> Unit
      }
      MenuEntry("Copy screen") { pick(onCopyScreen) }
      MenuEntry("Rename tab") { pick { onRename(Rename.TAB) } }
      MenuEntry("Rename session") { pick { onRename(Rename.SESSION) } }
      MenuEntry("Clear screen") { pick { onEvent(TerminalUiEvent.Clear) } }
      MenuEntry("Close pane", color = Colors.danger) { pick { onEvent(TerminalUiEvent.ClosePane) } }
      if (state.tabs.size > 1) {
        MenuEntry("Close tab", color = Colors.danger) { pick { onEvent(TerminalUiEvent.CloseTab) } }
      }
    }
  }
}

@Composable
private fun PaneStrip(state: TerminalUiState, ink: Color, onEvent: (TerminalUiEvent) -> Unit, onMap: () -> Unit = {}) {
  LazyRow(
    contentPadding = PaddingValues(horizontal = Space.m, vertical = 2.dp),
    horizontalArrangement = Arrangement.spacedBy(Space.xs),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    item(key = "map") { LayoutThumb(state, ink, onMap) }
    items(state.panes, key = { it.blockId }) { pane ->
      val selected = pane.blockId == state.currentBlockId
      Row(
        Modifier.clip(
          RoundedCornerShape(8.dp),
        ).background(if (selected) ink.copy(alpha = CHROME_TINT) else Color.Transparent)
          .clickable { onEvent(TerminalUiEvent.SelectPane(pane.blockId)) }
          .padding(horizontal = Space.s, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        pane.state?.takeIf { it != ProgramState.IDLE }?.let {
          StateDot(it, 6.dp)
          Spacer(Modifier.width(5.dp))
        }
        Text(
          pane.name,
          fontSize = 12.sp,
          fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
          color = if (selected) ink else ink.copy(alpha = MUTED_INK),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/** A tab pill; tinted from the terminal's own background so it sits on any theme. */
@Composable
internal fun Chip(
  selected: Boolean,
  ink: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable RowScope.() -> Unit,
) {
  Row(
    modifier.clip(RoundedCornerShape(Radius.chip))
      .background(if (selected) Colors.accent else ink.copy(alpha = CHROME_TINT))
      .clickable(onClick = onClick)
      .padding(horizontal = Space.m, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) { content() }
}

/** What the pill over the grid says, and what its button does. */
private data class Status(val text: String, val action: Pair<String, TerminalUiEvent>? = null)

private fun statusOf(state: TerminalUiState): Status? {
  val info = state.info
  return when {
    info == null -> if (state.terminal == null) Status("Opening…") else null
    info.stream == StreamUi.RECONNECTING -> Status("Reconnecting…")
    info.stream == StreamUi.ENDED -> Status("Ended" + (info.endedReason?.let { ": $it" } ?: ""))
    info.exitCode != null -> Status("Process exited (${info.exitCode})")
    info.mirror -> Status("Mirroring ${info.ownerLabel ?: "another client"}", "Take over" to TerminalUiEvent.TakeOver)
    else -> null
  }
}

/** Ownership and connection state over the grid: mirroring, reconnecting, exited. */
@Composable
private fun StatusOverlay(state: TerminalUiState, onEvent: (TerminalUiEvent) -> Unit, modifier: Modifier) {
  val status = statusOf(state)
  val text = status?.text
  val action = status?.action
  AnimatedVisibility(
    visible = text != null,
    modifier = modifier.padding(top = Space.s),
    enter = fadeIn() + slideInVertically { -it / 2 },
    exit = fadeOut() + slideOutVertically { -it / 2 },
  ) {
    Row(
      Modifier.shadow(8.dp, RoundedCornerShape(20.dp))
        .clip(RoundedCornerShape(20.dp))
        .background(Colors.raised)
        .padding(start = Space.m, end = Space.xs, top = 4.dp, bottom = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(text.orEmpty(), color = Colors.ink, fontSize = 12.sp)
      if (action != null) {
        Spacer(Modifier.width(Space.s))
        Text(
          action.first,
          color = Colors.accent,
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
            onEvent(action.second)
          }.padding(horizontal = Space.s, vertical = 2.dp),
        )
      } else {
        Spacer(Modifier.width(Space.s))
      }
    }
  }
}

private const val LIGHT_BACKGROUND_LUMINANCE = 0.5f
private const val CHROME_TINT = 0.1f
private const val MUTED_INK = 0.6f
private const val HAIRLINE = 0.08f
private const val DOTS_MS = 1_200L
private const val MAX_DOTS = 10
private val inkOnLight = Color(0xFF1C1C1E)
private val inkOnDark = Color(0xFFF2F2F7)

/** Ink that reads on a terminal background of any brightness. */
fun onColor(bg: Color): Color = if (bg.luminance() > LIGHT_BACKGROUND_LUMINANCE) inkOnLight else inkOnDark
