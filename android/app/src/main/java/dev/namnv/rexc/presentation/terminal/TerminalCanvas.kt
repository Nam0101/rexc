package dev.namnv.rexc.presentation.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.namnv.rexc.core.ui.theme.Colors
import dev.namnv.rexc.core.ui.theme.Space
import dev.namnv.rexc.data.TerminalController
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import uniffi.rexc_ffi.Frame

private const val BLINK_MS = 530L
private const val FONT_SAVE_DEBOUNCE_MS = 400L
private const val SWIPE_VELOCITY = 900f
const val DEFAULT_FONT_SP = 12.5f
private val FONT_SP_RANGE = 7f..28f
private const val MAX_MIRROR_ZOOM = 4f

// A mirrored desktop grid squeezed to the phone's width is unreadable; start zoomed to this much of
// full size at least and let the user pan, pinching out to see the whole width if they want.
private const val MIN_READABLE_SCALE = 0.6f
private const val MIN_COLS = 10
private const val MIN_ROWS = 4

/**
 * The terminal grid as a Compose canvas: frames come from the Rust engine,
 * [FrameRenderer] paints them, gestures scroll, select, zoom and swipe panes.
 *
 * Fit (we own the pane): the grid is sized to this box; pinch changes the font.
 * Mirror (another client owns it): the grid is scaled to fit the width; pinch zooms.
 *
 * Frames are read in the draw phase only, so a new frame redraws without recomposing. The grid and the
 * overlay (cursor, typed-ahead text, scroll thumb) are separate layers: a blinking cursor repaints only the overlay.
 * Frames are built only while the screen is started, so a backgrounded pane costs nothing.
 *
 * [initialFontSize] is the saved size; [onFontSizeChanged] reports the size once a pinch settles.
 * [localEcho] shows typed text ahead of a slow server.
 */
@Suppress("LongParameterList") // gestures and settings are separate callbacks by design
@Composable
fun TerminalCanvas(
  controller: TerminalController,
  mirror: Boolean,
  input: TerminalInput,
  frames: Flow<Frame>,
  onSwipePane: (Int) -> Unit,
  onLink: (String) -> Unit,
  /** A tap that needs the keyboard: `toggle` flips it; otherwise it only comes up when hidden. */
  onTap: (toggle: Boolean) -> Unit,
  onBackground: (Color) -> Unit,
  modifier: Modifier = Modifier,
  font: TerminalFont = TerminalFont.JETBRAINS_MONO,
  initialFontSize: Float = DEFAULT_FONT_SP,
  onFontSizeChanged: (Float) -> Unit = {},
  localEcho: Boolean = true,
) {
  val context = LocalContext.current
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val renderer = remember(context) { FrameRenderer(context) }
  var fontSize by rememberSaveable { mutableFloatStateOf(initialFontSize) }
  val currentOnFontSize by rememberUpdatedState(onFontSizeChanged)
  val box = remember { mutableStateOf(IntSize.Zero) }
  val state = remember(controller, mirror) { CanvasState(renderer, mirror, box) }
  val blinkOn = rememberBlink(state)
  val currentOnBackground by rememberUpdatedState(onBackground)
  renderer.font = font
  renderer.fontSize = fontSize

  LaunchedEffect(initialFontSize) { fontSize = initialFontSize.coerceIn(FONT_SP_RANGE) }
  LaunchedEffect(fontSize) {
    if (fontSize == initialFontSize) return@LaunchedEffect
    delay(FONT_SAVE_DEBOUNCE_MS)
    currentOnFontSize(fontSize)
  }
  LaunchedEffect(controller, localEcho) { controller.setLocalEcho(localEcho) }
  LaunchedEffect(state, frames, lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { frames.collect { state.show(it) } }
  }
  LaunchedEffect(state) {
    snapshotFlow { state.frame?.background }.filterNotNull().collect { currentOnBackground(Color(it.toInt())) }
  }
  PublishViewport(controller, box.value, renderer, key = fontSize to font)

  Box(
    modifier
      .clipToBounds()
      .onSizeChanged { box.value = it }
      .selectionGestures(state, controller, onLink = onLink, onTap = onTap)
      .viewGestures(state, controller, onFontScale = {
        fontSize = (fontSize * it).coerceIn(FONT_SP_RANGE)
      }, onSwipePane = onSwipePane),
  ) {
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
      val f = state.frame
      drawRect(if (f != null) Color(f.background.toInt()) else Colors.bg)
      if (f != null) {
        drawIntoCanvas { RenderStats.timedDraw { renderer.drawGrid(it.nativeCanvas, f, state.layout()) } }
      }
    }
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
      val f = state.frame
      if (f != null) {
        drawIntoCanvas {
          renderer.drawOverlay(it.nativeCanvas, f, state.layout(), size.width, size.height, true, blinkOn.value)
        }
      }
    }
    if (state.toolbar) {
      SelectionToolbar(controller, input, onDone = { state.toolbar = false })
    }
  }
}

/** Our grid for this pane; the engine applies it on the server when we own the pane. [key] changes when cells resize. */
@Composable
private fun PublishViewport(controller: TerminalController, box: IntSize, renderer: FrameRenderer, key: Any) {
  LaunchedEffect(controller, box, key) {
    val (w, h) = box
    if (w > 0 && h > 0) {
      val cols = max(MIN_COLS, ((w - renderer.padding * 2) / renderer.cellW).toInt())
      val rows = max(MIN_ROWS, ((h - renderer.padding * 2) / renderer.cellH).toInt())
      controller.setViewport(cols, rows, renderer.cellW.roundToInt(), renderer.cellH.roundToInt())
    }
  }
}

/** Whether a blinking cursor is lit now; stays lit when it doesn't blink. */
@Composable
private fun rememberBlink(state: CanvasState): State<Boolean> {
  val blinkOn = remember { mutableStateOf(true) }
  LaunchedEffect(state) {
    snapshotFlow { state.frame?.cursor?.blinking == true }.collectLatest { blinking ->
      blinkOn.value = true
      while (blinking) {
        delay(BLINK_MS)
        blinkOn.value = !blinkOn.value
      }
    }
  }
  return blinkOn
}

/** Geometry and gesture state of one pane view; a new pane or ownership mode starts fresh. */
@Stable
private class CanvasState(val renderer: FrameRenderer, val mirror: Boolean, private val box: State<IntSize>) {
  var frame: Frame? by mutableStateOf(null)
    private set
  var selectionAnchor by mutableStateOf<Pair<Int, Int>?>(null)
  var toolbar by mutableStateOf(false)
  private var zoom by mutableFloatStateOf(1f)
  private var panX by mutableFloatStateOf(0f)
  private var zoomed = false

  /** A new frame; the first one of a mirror picks a readable starting zoom. */
  fun show(f: Frame?) {
    frame = f
    val unzoomedMirror = mirror && !zoomed
    if (f != null && unzoomedMirror && size.width > 0) startZoom(fitScale(f))
  }

  private fun startZoom(fit: Float) {
    zoomed = true
    if (fit < MIN_READABLE_SCALE) zoom = (MIN_READABLE_SCALE / fit).coerceAtMost(MAX_MIRROR_ZOOM)
  }

  private fun fitScale(f: Frame): Float = min(1f, size.width / (f.cols.toInt() * renderer.cellW + renderer.padding * 2))

  private val size: IntSize get() = box.value

  /** Mirror shrinks the owner's grid to our width (then pinch zooms); Fit draws 1:1. */
  fun scale(): Float {
    val f = frame
    return if (f == null || !mirror) 1f else fitScale(f) * zoom
  }

  /** Keeps the cursor row on screen when the grid is taller than the box. */
  fun offsetY(): Float {
    val f = frame ?: return 0f
    val s = scale()
    val contentH = f.rows.toInt() * renderer.cellH * s + renderer.padding * 2
    val anchorRow = (f.cursor?.row?.toInt() ?: (f.rows.toInt() - 1)) + 1
    val needBottom = anchorRow * renderer.cellH * s + renderer.padding * 2
    return if (contentH <= size.height) 0f else (needBottom - size.height).coerceIn(0f, contentH - size.height)
  }

  fun cellAt(p: Offset): Pair<Int, Int> {
    val s = scale()
    val col = ((p.x - renderer.padding + panX) / s / renderer.cellW).toInt()
    val row = ((p.y - renderer.padding + offsetY()) / s / renderer.cellH).toInt()
    return col.coerceIn(0, (frame?.cols?.toInt() ?: 1) - 1) to row.coerceIn(0, (frame?.rows?.toInt() ?: 1) - 1)
  }

  fun centerCell(): Pair<Int, Int> = cellAt(Offset(size.width / 2f, size.height / 2f))

  fun canPan(): Boolean = (mirror && scale() < 1f) || zoom > 1f

  /** Finger moved by [dx]: the content follows it. */
  fun dragBy(dx: Float) {
    panX = clampedPan(panX - dx)
  }

  fun zoomBy(factor: Float) {
    zoom = (zoom * factor).coerceIn(1f, MAX_MIRROR_ZOOM)
    panX = clampedPan(panX)
  }

  fun layout(): FrameRenderer.Layout = FrameRenderer.Layout(scale(), panX, offsetY(), 0f)

  private fun clampedPan(x: Float): Float {
    val f = frame ?: return x
    val contentW = f.cols.toInt() * renderer.cellW * scale() + renderer.padding * 2
    return x.coerceIn(0f, max(0f, contentW - size.width))
  }
}

private fun CanvasState.startSelection(pos: Offset, controller: TerminalController, haptics: HapticFeedback) {
  val (c, r) = cellAt(pos)
  if (controller.selectWord(c, r)) {
    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    selectionAnchor = c to r
    toolbar = false
  }
}

/**
 * A tap: dismiss the toolbar, else open a link, else toggle the keyboard. A mouse-tracking
 * program gets the click too, and the keyboard comes up if hidden so the tap still lets you type.
 */
private fun CanvasState.handleTap(
  pos: Offset,
  controller: TerminalController,
  onLink: (String) -> Unit,
  onTap: (toggle: Boolean) -> Unit,
) {
  if (toolbar) {
    controller.clearSelection()
    toolbar = false
    return
  }
  val (c, r) = cellAt(pos)
  val link = controller.linkAt(c, r)
  when {
    link != null -> onLink(link)
    frame?.mouseTracking == true -> {
      controller.click(c, r, right = false)
      onTap(false)
    }
    else -> onTap(true)
  }
}

/** Tap opens a link or focuses input, double tap selects a word, long press then drag extends a selection. */
@Composable
private fun Modifier.selectionGestures(
  state: CanvasState,
  controller: TerminalController,
  onLink: (String) -> Unit,
  onTap: (toggle: Boolean) -> Unit,
): Modifier {
  val haptics = LocalHapticFeedback.current
  var pressAt by remember(state) { mutableStateOf(Offset.Zero) }
  var dragged by remember(state) { mutableStateOf(false) }
  return this
    .pointerInput(state) {
      detectTapGestures(
        onTap = { pos -> state.handleTap(pos, controller, onLink, onTap) },
        onDoubleTap = { pos ->
          val (c, r) = state.cellAt(pos)
          if (controller.selectWord(c, r)) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            state.selectionAnchor = c to r
            state.toolbar = true
          }
        },
      )
    }
    .pointerInput(state) {
      detectDragGesturesAfterLongPress(
        onDragStart = { pos ->
          pressAt = pos
          dragged = false
          state.selectionAnchor = null
          if (state.frame?.mouseTracking != true) state.startSelection(pos, controller, haptics)
        },
        onDrag = { change, _ ->
          if (!dragged) {
            dragged = true
            if (state.selectionAnchor == null) state.startSelection(pressAt, controller, haptics)
          }
          state.selectionAnchor?.let { (ac, ar) ->
            val (c, r) = state.cellAt(change.position)
            controller.select(ac, ar, c, r)
          }
        },
        onDragEnd = {
          if (!dragged && state.frame?.mouseTracking == true) {
            val (c, r) = state.cellAt(pressAt)
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            controller.click(c, r, right = true)
          } else if (state.selectionAnchor != null) {
            state.toolbar = true
          }
        },
        onDragCancel = { if (state.selectionAnchor != null) state.toolbar = true },
      )
    }
}

/** Vertical scroll through history, horizontal pan (or a pane swipe when nothing pans), pinch to size the font or zoom a mirror. */
@Composable
private fun Modifier.viewGestures(
  state: CanvasState,
  controller: TerminalController,
  onFontScale: (Float) -> Unit,
  onSwipePane: (Int) -> Unit,
): Modifier {
  val haptics = LocalHapticFeedback.current
  val currentOnSwipe by rememberUpdatedState(onSwipePane)
  var scrollRemainder by remember(state) { mutableFloatStateOf(0f) }
  val scrollState = rememberScrollableState { delta ->
    // Finger down = content down = back into history, which the engine counts as negative rows.
    scrollRemainder += -delta / (state.renderer.cellH * state.scale())
    val rows = scrollRemainder.toInt()
    if (rows != 0) {
      scrollRemainder -= rows
      val (c, r) = state.centerCell()
      controller.scroll(rows, c, r)
    }
    delta
  }
  val dragState = rememberDraggableState { dx -> if (state.canPan()) state.dragBy(dx) }
  val transformState = rememberTransformableState { zoomChange, _, _ ->
    if (state.mirror) state.zoomBy(zoomChange) else onFontScale(zoomChange)
  }
  return this
    .scrollable(scrollState, Orientation.Vertical)
    .draggable(
      dragState,
      Orientation.Horizontal,
      onDragStopped = { velocity ->
        if (!state.canPan() && abs(velocity) > SWIPE_VELOCITY) {
          haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
          currentOnSwipe(if (velocity < 0) 1 else -1)
        }
      },
    )
    .transformable(transformState, canPan = { false })
}

/** Copy / paste / copy all over a selection; every action, and dismissal, clears the selection. */
@Composable
private fun SelectionToolbar(controller: TerminalController, input: TerminalInput, onDone: () -> Unit) {
  fun finish(action: () -> Unit = {}) {
    action()
    controller.clearSelection()
    onDone()
  }
  Popup(alignment = Alignment.TopCenter, onDismissRequest = { finish() }) {
    Row(
      Modifier.padding(
        top = Space.m,
      ).clip(RoundedCornerShape(14.dp)).background(Colors.raised).padding(horizontal = Space.xs, vertical = Space.xs),
    ) {
      ToolbarAction("Copy") { finish { controller.selectionText()?.let(input::copy) } }
      ToolbarAction("Paste") { finish(input::paste) }
      ToolbarAction("Copy all") { finish { controller.allText().takeIf { it.isNotEmpty() }?.let(input::copy) } }
    }
  }
}

@Composable
private fun ToolbarAction(label: String, onClick: () -> Unit) {
  Text(
    label,
    color = Colors.accent,
    fontSize = 14.sp,
    fontWeight = FontWeight.SemiBold,
    modifier = Modifier.clip(
      RoundedCornerShape(10.dp),
    ).clickable(onClick = onClick).padding(horizontal = Space.m, vertical = Space.s),
  )
}
