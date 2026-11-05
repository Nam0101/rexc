package dev.namnv.rexc.presentation.terminal

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.namnv.rexc.core.ui.theme.Colors
import dev.namnv.rexc.core.ui.theme.MonoFamily
import dev.namnv.rexc.core.ui.theme.Motion
import dev.namnv.rexc.core.ui.theme.Space
import dev.namnv.rexc.data.DockAction
import dev.namnv.rexc.data.DockItem
import dev.namnv.rexc.presentation.common.pressScale
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val chords = listOf('c', 'd', 'z', 'l', 'a', 'e', 'r', 'u', 'w', 'k')
private const val REPEAT_DELAY_MS = 450L
private const val REPEAT_EVERY_MS = 110L
private const val CHORD_COLUMNS = 5

/**
 * The key row under the grid: the keys a phone keyboard lacks, snippets, and
 * shortcuts to the sheets. The row is the user's own ([DockLayout]); long press
 * a gap or a plain key to edit it. Ctrl and Alt latch for the next key (long
 * press locks; long press Ctrl opens chords), the arrows key is a joystick you
 * hold and slide.
 */
@Composable
fun Dock(
  input: TerminalInput,
  keyboardVisible: Boolean,
  onToggleKeyboard: () -> Unit,
  background: Color,
  modifier: Modifier = Modifier,
  hapticsEnabled: Boolean = true,
  onOpenSheet: (TerminalSheet) -> Unit = {},
) {
  val viewModel: DockViewModel = hiltViewModel()
  val layout by viewModel.layout.collectAsStateWithLifecycle()
  var editing by remember { mutableStateOf(false) }
  val haptics = LocalHapticFeedback.current
  val edit = {
    if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    editing = true
  }
  val ink = remember(background) { onColor(background) }
  CompositionLocalProvider(LocalDockInk provides ink, LocalDockHaptics provides hapticsEnabled) {
    Row(
      modifier.fillMaxWidth().background(background)
        .drawBehind { drawLine(ink.copy(alpha = HAIRLINE), Offset.Zero, Offset(size.width, 0f)) }
        .pointerInput(Unit) { detectTapGestures(onLongPress = { edit() }) }
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = Space.s, vertical = Space.s),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      layout.items.forEach { item ->
        DockCap(item, input, keyboardVisible, onToggleKeyboard, onOpenSheet, edit)
      }
    }
  }
  if (editing) {
    DockEditor(layout, onSave = viewModel::save, onReset = viewModel::reset, onDismiss = { editing = false })
  }
}

@Composable
private fun DockCap(
  item: DockItem,
  input: TerminalInput,
  keyboardVisible: Boolean,
  onToggleKeyboard: () -> Unit,
  onOpenSheet: (TerminalSheet) -> Unit,
  onEdit: () -> Unit,
) {
  when (item) {
    is DockItem.Key -> Key(item.label, onEdit) { input.key(item.name, item.ctrl, item.alt, item.shift) }
    is DockItem.Text -> Key(item.label, onEdit) { sendSnippet(input, item.text, item.enter) }
    is DockItem.Action -> when (DockAction.of(item.id)) {
      DockAction.CTRL -> CtrlKey(input)
      DockAction.ALT -> ModKey(
        "alt",
        active = input.alt,
        locked = input.altLocked,
        onClick = input::toggleAlt,
        onLongClick = input::lockAlt,
      )
      DockAction.SHIFT ->
        ModKey(
          "⇧",
          active = input.shift,
          locked = false,
          onClick = input::toggleShift,
          onLongClick = input::toggleShift,
        )
      DockAction.ARROWS -> ArrowsKey(input)
      DockAction.PASTE -> IconKey(Icons.Default.ContentPaste, "Paste") { input.paste() }
      DockAction.KEYBOARD -> IconKey(
        if (keyboardVisible) Icons.Default.KeyboardHide else Icons.Default.Keyboard,
        "Keyboard",
        onToggleKeyboard,
      )
      DockAction.PALETTE -> Key("⌘", onEdit) { onOpenSheet(TerminalSheet.PALETTE) }
      DockAction.SWITCHER -> Key("⇄", onEdit) { onOpenSheet(TerminalSheet.SWITCHER) }
      DockAction.MAP -> Key("▦", onEdit) { onOpenSheet(TerminalSheet.MAP) }
      DockAction.MIC -> VoiceKey(input)
      null -> Unit
    }
  }
}

/** A snippet is typed as is: sticky modifiers do not turn it into chords, and a new line is Enter. */
internal fun sendSnippet(input: TerminalInput, text: String, enter: Boolean) {
  text.split('\n').forEachIndexed { i, line ->
    if (i > 0) input.key("enter")
    if (line.isNotEmpty()) input.raw(line)
  }
  if (enter) input.key("enter")
}

/** Ink for the dock's keys, derived from the terminal background so the dock sits on any theme. */
private val LocalDockInk = compositionLocalOf { Color.Unspecified }
private val LocalDockHaptics = compositionLocalOf { true }
private const val KEY_TINT = 0.1f
private const val HAIRLINE = 0.08f
private const val KEY_PRESSED = 0.9f
private const val LATCH_POP = 1.1f
private const val MORPH_SCALE = 0.6f

/**
 * A key cap. [modifier] carries the gesture; the cap clips its ripple. It sinks under a
 * finger, its tint slides between plain and accent, and it pops when it latches.
 */
@Composable
private fun KeyBox(active: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  val ink = LocalDockInk.current
  val fill by animateColorAsState(
    if (active) Colors.accent else ink.copy(alpha = KEY_TINT),
    Motion.move(Motion.QUICK),
    label = "key",
  )
  val latch = remember { Animatable(1f) }
  LaunchedEffect(active) {
    if (active) {
      latch.snapTo(LATCH_POP)
      latch.animateTo(1f, Motion.touch())
    }
  }
  Box(
    Modifier.pressScale(KEY_PRESSED)
      .graphicsLayer {
        scaleX = latch.value
        scaleY = latch.value
      }
      .height(42.dp).defaultMinSize(minWidth = 44.dp).clip(RoundedCornerShape(12.dp))
      .drawBehind { drawRect(fill) }
      .then(modifier)
      .padding(horizontal = Space.m),
    contentAlignment = Alignment.Center,
  ) { content() }
}

/** [onClick] with the click of a real key. */
@Composable
private fun tap(onClick: () -> Unit): () -> Unit {
  val haptics = LocalHapticFeedback.current
  val enabled = LocalDockHaptics.current
  return {
    if (enabled) haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
    onClick()
  }
}

/** A plain key; [onLongClick] is where a long press goes (the dock editor), if anywhere. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Key(label: String, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
  KeyBox(active = false, Modifier.combinedClickable(onClick = tap(onClick), onLongClick = onLongClick)) {
    Text(
      label,
      fontFamily = MonoFamily,
      fontSize = 14.sp,
      fontWeight = FontWeight.Medium,
      color = LocalDockInk.current,
      maxLines = 1,
    )
  }
}

/** An icon key; a new icon grows in as the old one shrinks away (keyboard show/hide). */
@Composable
private fun IconKey(icon: ImageVector, description: String, onClick: () -> Unit) {
  KeyBox(active = false, Modifier.combinedClickable(onClick = tap(onClick))) {
    AnimatedContent(
      targetState = icon,
      transitionSpec = {
        (scaleIn(Motion.enter(Motion.STANDARD), MORPH_SCALE) + fadeIn(Motion.enter(Motion.STANDARD)))
          .togetherWith(scaleOut(Motion.leave(), MORPH_SCALE) + fadeOut(Motion.leave()))
      },
      label = "iconKey",
    ) { shown ->
      Icon(shown, contentDescription = description, tint = LocalDockInk.current, modifier = Modifier.size(18.dp))
    }
  }
}

/**
 * Speech to text through the system recognizer, which holds the microphone itself (no permission here).
 * The first result is pasted without Enter, so a misheard command never runs.
 */
@Composable
private fun VoiceKey(input: TerminalInput) {
  val context = LocalContext.current
  val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(input::insert)
  }
  IconKey(Icons.Default.Mic, "Voice input") {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
      .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    try {
      listen.launch(intent)
    } catch (_: ActivityNotFoundException) {
      Toast.makeText(context, "Voice input isn't available", Toast.LENGTH_SHORT).show()
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModKey(label: String, active: Boolean, locked: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
  KeyBox(active = active, Modifier.combinedClickable(onClick = tap(onClick), onLongClick = tap(onLongClick))) {
    Text(
      if (locked) "$label •" else label,
      fontFamily = MonoFamily,
      fontSize = 14.sp,
      fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
      color = if (active) Colors.onAccent else LocalDockInk.current,
    )
  }
}

/** Tap latches Ctrl for the next key; long press opens the chord grid (^C, ^D...). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CtrlKey(input: TerminalInput) {
  var chordsOpen by remember { mutableStateOf(false) }
  Box {
    KeyBox(
      active = input.ctrl,
      Modifier.combinedClickable(onClick = tap(input::toggleCtrl), onLongClick = tap { chordsOpen = true }),
    ) {
      Text(
        if (input.ctrlLocked) "ctrl •" else "ctrl",
        fontFamily = MonoFamily,
        fontSize = 14.sp,
        fontWeight = if (input.ctrl) FontWeight.Bold else FontWeight.Normal,
        color = if (input.ctrl) Colors.onAccent else LocalDockInk.current,
      )
    }
    DropdownMenu(expanded = chordsOpen, onDismissRequest = { chordsOpen = false }) {
      CompositionLocalProvider(LocalDockInk provides Colors.ink) { ChordSheet(input) { chordsOpen = false } }
    }
  }
}

/** The ^C, ^D... grid plus "lock ctrl"; [onPick] closes the sheet after a choice. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChordSheet(input: TerminalInput, onPick: () -> Unit) {
  Column(Modifier.padding(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
    chords.chunked(CHORD_COLUMNS).forEach { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        row.forEach { c ->
          KeyBox(
            active = false,
            Modifier.combinedClickable(onClick = {
              input.chord(c)
              onPick()
            }),
          ) {
            Text(
              "^${c.uppercaseChar()}",
              fontFamily = MonoFamily,
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
              color = Colors.ink,
            )
          }
        }
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
      KeyBox(
        active = input.ctrlLocked,
        Modifier.combinedClickable(onClick = {
          input.lockCtrl()
          onPick()
        }),
      ) {
        Text(
          "lock ctrl",
          fontFamily = MonoFamily,
          fontSize = 13.sp,
          color = if (input.ctrlLocked) Colors.onAccent else Colors.ink,
        )
      }
    }
  }
}

/**
 * Hold and slide to send arrows (repeating while held, like a key); a plain tap
 * opens a four-arrow pad for one-off presses.
 */
@Composable
private fun ArrowsKey(input: TerminalInput) {
  val scope = rememberCoroutineScope()
  val haptics = LocalHapticFeedback.current
  val hapticsEnabled = LocalDockHaptics.current
  val send by rememberUpdatedState<(String) -> Unit> { input.key("arrow_$it") }
  var active by remember { mutableStateOf(false) }
  var direction by remember { mutableStateOf<String?>(null) }
  var padOpen by remember { mutableStateOf(false) }
  Box {
    KeyBox(
      active = active || padOpen,
      Modifier.pointerInput(Unit) {
        var repeating: Job? = null
        trackSlide(
          onPressed = { pressed ->
            active = pressed
            if (!pressed) {
              repeating?.cancel()
              direction = null
            }
          },
          onTap = { padOpen = true },
          onDirection = { next ->
            repeating?.cancel()
            direction = next
            if (next != null) {
              if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
              send(next)
              repeating = scope.launch { repeatKey { send(next) } }
            }
          },
        )
      },
    ) {
      val label = arrowGlyphs[direction]
      if (label != null) {
        Text(label, fontFamily = MonoFamily, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Colors.onAccent)
      } else {
        Icon(
          Icons.Default.OpenWith,
          contentDescription = "Arrow keys",
          tint = if (active || padOpen) Colors.onAccent else LocalDockInk.current,
          modifier = Modifier.size(18.dp),
        )
      }
    }
    DropdownMenu(expanded = padOpen, onDismissRequest = { padOpen = false }) {
      CompositionLocalProvider(LocalDockInk provides Colors.ink) { ArrowPad(send) }
    }
  }
}

/** Four arrows for one-off presses. */
@Composable
private fun ArrowPad(send: (String) -> Unit) {
  Column(
    Modifier.padding(Space.s),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(Space.xs),
  ) {
    Key("↑") { send("up") }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
      Key("←") { send("left") }
      Key("↓") { send("down") }
      Key("→") { send("right") }
    }
    Box(Modifier.width(1.dp))
  }
}

private val arrowGlyphs = mapOf("up" to "↑", "down" to "↓", "left" to "←", "right" to "→")

/**
 * One press on the arrows key: [onDirection] whenever the slide direction changes
 * (null back in the dead zone), [onTap] when the finger lifts without having moved.
 */
private suspend fun PointerInputScope.trackSlide(
  onPressed: (Boolean) -> Unit,
  onTap: () -> Unit,
  onDirection: (String?) -> Unit,
) {
  val slop = viewConfiguration.touchSlop
  awaitEachGesture {
    val down = awaitFirstDown()
    down.consume()
    val center = Offset(size.width / 2f, size.height / 2f)
    var moved = false
    var direction: String? = null
    onPressed(true)
    try {
      while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
        change.consume()
        if (!change.pressed) break
        moved = moved || (change.position - down.position).getDistance() >= slop
        val next = if (moved) directionOf(change.position - center, slop) else null
        if (next != direction) {
          direction = next
          onDirection(next)
        }
      }
      if (!moved) onTap()
    } finally {
      onPressed(false)
    }
  }
}

/** Keyboard-style autorepeat: one delay, then a steady rate until the coroutine is cancelled. */
private suspend fun repeatKey(action: () -> Unit) {
  delay(REPEAT_DELAY_MS)
  while (true) {
    action()
    delay(REPEAT_EVERY_MS)
  }
}

private fun directionOf(delta: Offset, deadZone: Float): String? {
  if (delta.getDistance() < deadZone) return null
  val x = abs(delta.x)
  val y = abs(delta.y)
  return if (x > y) {
    if (delta.x < 0) "left" else "right"
  } else {
    if (delta.y < 0) "up" else "down"
  }
}
