// Hot path: palette entries, glyph geometry and style bits are numeric by nature, and the
// draw loop stays one pass on purpose. Readability is traded for speed here and only here.
@file:Suppress(
  "MagicNumber",
  "CyclomaticComplexMethod",
  "LongMethod",
  "NestedBlockDepth",
  "ReturnCount",
  "ComplexCondition",
  "TooManyFunctions",
)

package dev.namnv.rexc.presentation.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import dev.namnv.rexc.R
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import uniffi.rexc_ffi.Cursor
import uniffi.rexc_ffi.CursorShape
import uniffi.rexc_ffi.Frame
import uniffi.rexc_ffi.Prediction
import uniffi.rexc_ffi.Run

private const val STYLE_BOLD = 1
private const val STYLE_ITALIC = 1 shl 1
private const val STYLE_FAINT = 1 shl 2
private const val STYLE_STRIKE = 1 shl 3
private const val STYLE_OVERLINE = 1 shl 4
private const val STYLE_SELECTED = 1 shl 5

private const val BOX_FIRST = 0x2500
private const val BOX_LAST = 0x259F
private const val ASCII_LIMIT = 0x80
private const val GRID_TOLERANCE = 0.5f

// Search matches: a light wash on every match, a strong one with an outline on the current one.
private const val MATCH_COLOR = 0xFFFFC107.toInt()
private const val MATCH_ALPHA = 80
private const val CURRENT_MATCH_ALPHA = 170

/**
 * Paints a terminal [Frame] from the engine onto a native canvas: style runs,
 * box drawing as geometry, cursor shapes, a scroll thumb. Pure drawing; the
 * composable owns gestures and state. The grid and the overlay (cursor, typed-ahead
 * text, scroll thumb) draw separately, so a blinking cursor never repaints the text.
 */
class FrameRenderer(context: Context) {
  private val appContext = context.applicationContext
  private val density = context.resources.displayMetrics.density
  private var typefaces: Array<Typeface> = loadTypefaces(TerminalFont.JETBRAINS_MONO)
  private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { isLinearText = true }
  private val fill = Paint()
  private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = max(1f, density * 0.9f) }
  private val matchOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 1.5f * density
    color = MATCH_COLOR
  }

  // What the text paint holds during one grid pass, so unchanged settings are not set again.
  private var paintedFace = -1
  private var paintedColor = 0
  private var paintedColorSet = false

  var cellW = 8f
    private set
  var cellH = 16f
    private set
  private var baseline = 12f
  val padding = 6f * density

  var fontSize = 12.5f
    set(value) {
      field = value.coerceIn(7f, 28f)
      updateMetrics()
    }

  /** The grid's typeface; programming ligatures form wherever the font has them. */
  var font = TerminalFont.JETBRAINS_MONO
    set(value) {
      if (field == value) return
      field = value
      typefaces = loadTypefaces(value)
      updateMetrics()
    }

  init {
    updateMetrics()
  }

  private fun loadTypefaces(font: TerminalFont): Array<Typeface> {
    fun res(id: Int) = ResourcesCompat.getFont(appContext, id) ?: Typeface.MONOSPACE
    return when (font) {
      TerminalFont.JETBRAINS_MONO -> arrayOf(
        res(R.font.jetbrains_mono_regular),
        res(R.font.jetbrains_mono_bold),
        res(R.font.jetbrains_mono_italic),
        res(R.font.jetbrains_mono_bold_italic),
      )
      TerminalFont.FIRA_CODE -> {
        // Fira Code has no italics: Android slants the upright face.
        val regular = res(R.font.fira_code_regular)
        val bold = res(R.font.fira_code_bold)
        arrayOf(regular, bold, Typeface.create(regular, Typeface.ITALIC), Typeface.create(bold, Typeface.ITALIC))
      }
      TerminalFont.SYSTEM -> arrayOf(
        Typeface.MONOSPACE,
        Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
        Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC),
        Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC),
      )
    }
  }

  private fun updateMetrics() {
    text.textSize = fontSize * density
    text.typeface = typefaces[0]
    text.fontFeatureSettings = if (font == TerminalFont.SYSTEM) null else "liga, calt"
    cellW = text.measureText("M")
    val fm = text.fontMetrics
    cellH = floor((fm.descent - fm.ascent + fm.leading) * 1.08f)
    baseline = -fm.ascent + (cellH - (fm.descent - fm.ascent)) / 2f
  }

  class Layout(val scale: Float, val panX: Float, val offsetY: Float, val insetTop: Float)

  private inline fun onGrid(canvas: Canvas, l: Layout, block: () -> Unit) {
    canvas.save()
    canvas.translate(padding - l.panX, l.insetTop + padding - l.offsetY)
    canvas.scale(l.scale, l.scale)
    block()
    canvas.restore()
  }

  private fun setFace(index: Int) {
    if (index == paintedFace) return
    paintedFace = index
    text.typeface = typefaces[index]
  }

  private fun setInk(color: Int) {
    if (paintedColorSet && color == paintedColor) return
    paintedColorSet = true
    paintedColor = color
    text.color = color
  }

  /** Backgrounds, search matches, then text and its decorations. */
  fun drawGrid(canvas: Canvas, f: Frame, l: Layout) {
    val bg = f.background.toInt()
    val selColor = blend(f.foreground.toInt(), bg, 0.30f)
    paintedFace = -1
    paintedColorSet = false
    onGrid(canvas, l) {
      val rows = f.lines
      for (rowIdx in rows.indices) drawBackgrounds(canvas, rows[rowIdx].runs, rowIdx * cellH, selColor)
      for (h in f.highlights) drawMatch(canvas, h.col.toInt(), h.row.toInt(), h.len.toInt(), h.current)
      for (rowIdx in rows.indices) {
        val runs = rows[rowIdx].runs
        for (i in runs.indices) drawRun(canvas, runs[i], rowIdx * cellH, bg)
      }
    }
  }

  /** Adjacent runs on the same color paint as one rectangle. */
  private fun drawBackgrounds(canvas: Canvas, runs: List<Run>, top: Float, selColor: Int) {
    var start = 0f
    var end = 0f
    var open = 0
    var isOpen = false
    for (i in runs.indices) {
      val run = runs[i]
      val color = when {
        (run.style.toInt() and STYLE_SELECTED) != 0 -> selColor
        run.bg != null -> run.bg!!.toInt()
        else -> {
          if (isOpen) fillRect(canvas, start, end, top, open)
          isOpen = false
          continue
        }
      }
      val x = run.col.toInt() * cellW
      if (isOpen && color == open && x <= end) {
        end = x + run.cells.toInt() * cellW
      } else {
        if (isOpen) fillRect(canvas, start, end, top, open)
        start = x
        end = x + run.cells.toInt() * cellW
        open = color
        isOpen = true
      }
    }
    if (isOpen) fillRect(canvas, start, end, top, open)
  }

  private fun fillRect(canvas: Canvas, x0: Float, x1: Float, top: Float, color: Int) {
    fill.color = color
    canvas.drawRect(x0, top, x1, top + cellH + 0.5f, fill)
  }

  private fun drawMatch(canvas: Canvas, col: Int, row: Int, len: Int, current: Boolean) {
    fill.color = MATCH_COLOR
    fill.alpha = if (current) CURRENT_MATCH_ALPHA else MATCH_ALPHA
    val x = col * cellW
    val y = row * cellH
    canvas.drawRect(x, y, x + len * cellW, y + cellH, fill)
    fill.alpha = 0xFF
    if (current) canvas.drawRect(x, y, x + len * cellW, y + cellH, matchOutline)
  }

  private fun drawRun(canvas: Canvas, run: Run, top: Float, bg: Int) {
    val style = run.style.toInt()
    setFace((if (style and STYLE_BOLD != 0) 1 else 0) + (if (style and STYLE_ITALIC != 0) 2 else 0))
    var color = run.fg.toInt()
    if (style and STYLE_FAINT != 0) color = blend(color, bg, 0.5f)
    setInk(color)
    val x0 = run.col.toInt() * cellW
    drawRunText(canvas, run, x0, top + baseline)
    val ul = (style shr 8) and 0xF
    if (ul != 0 || style and (STYLE_STRIKE or STYLE_OVERLINE) != 0) {
      line.color = run.underlineColor?.toInt() ?: color
      val x1 = x0 + run.cells.toInt() * cellW
      if (ul != 0) {
        val uy = top + cellH - line.strokeWidth * 1.5f
        when (ul) {
          2 -> {
            canvas.drawLine(x0, uy, x1, uy, line)
            canvas.drawLine(x0, uy - 3 * density, x1, uy - 3 * density, line)
          }
          3 -> drawCurly(canvas, x0, x1, uy)
          4 -> drawDashed(canvas, x0, x1, uy, 1.5f * density)
          5 -> drawDashed(canvas, x0, x1, uy, 4f * density)
          else -> canvas.drawLine(x0, uy, x1, uy, line)
        }
      }
      if (style and STYLE_STRIKE != 0) canvas.drawLine(x0, top + cellH / 2, x1, top + cellH / 2, line)
      if (style and STYLE_OVERLINE != 0) canvas.drawLine(x0, top + 1, x1, top + 1, line)
    }
  }

  /** Cursor, typed-ahead text and scroll thumb: what changes without the grid changing. */
  fun drawOverlay(
    canvas: Canvas,
    f: Frame,
    l: Layout,
    width: Float,
    height: Float,
    focused: Boolean,
    blinkOn: Boolean,
  ) {
    val bg = f.background.toInt()
    val fgDefault = f.foreground.toInt()
    paintedFace = -1
    paintedColorSet = false
    onGrid(canvas, l) {
      val ahead = f.prediction?.also { drawPrediction(canvas, it, bg, fgDefault) }
      f.cursor?.let { c ->
        if (f.scrollOffset + f.scrollLen < f.scrollTotal) return@let
        if (c.blinking && !blinkOn && focused) return@let
        val shift = if (ahead != null && ahead.row == c.row) ahead.text.length else 0
        drawCursor(canvas, f, c, shift, bg, fgDefault, focused)
      }
    }
    if (f.scrollTotal > f.scrollLen && f.scrollOffset + f.scrollLen < f.scrollTotal) {
      val trackH = height - padding * 2
      val thumbH = max(24 * density, trackH * f.scrollLen.toFloat() / f.scrollTotal.toFloat())
      val y = padding + (trackH - thumbH) * f.scrollOffset.toFloat() / (f.scrollTotal - f.scrollLen).toFloat()
      fill.color = blend(fgDefault, bg, 0.55f)
      canvas.drawRoundRect(
        RectF(width - 5 * density, y, width - 2 * density, y + thumbH),
        2 * density,
        2 * density,
        fill,
      )
    }
  }

  /** Typed text the server hasn't echoed yet: dimmed and underlined, over a patch of background. */
  private fun drawPrediction(canvas: Canvas, p: Prediction, bg: Int, fg: Int) {
    val x = p.col.toInt() * cellW
    val top = p.row.toInt() * cellH
    val x1 = x + p.text.length * cellW
    fill.color = bg
    canvas.drawRect(x, top, x1, top + cellH, fill)
    setFace(0)
    val ink = blend(fg, bg, 0.5f)
    setInk(ink)
    canvas.drawText(p.text, x, top + baseline, text)
    line.color = ink
    val uy = top + cellH - line.strokeWidth * 1.5f
    canvas.drawLine(x, uy, x1, uy, line)
  }

  private fun drawCursor(canvas: Canvas, f: Frame, c: Cursor, shift: Int, bg: Int, fgDefault: Int, focused: Boolean) {
    val cx = (c.col.toInt() + shift) * cellW
    val cy = c.row.toInt() * cellH
    val w = if (c.wide) cellW * 2 else cellW
    fill.color = f.cursorColor?.toInt() ?: fgDefault
    when {
      c.password -> drawLock(canvas, cx, cy)
      !focused || c.shape == CursorShape.HOLLOW_BLOCK -> {
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = density
        canvas.drawRect(cx + 0.5f, cy + 0.5f, cx + w - 0.5f, cy + cellH - 0.5f, fill)
        fill.style = Paint.Style.FILL
      }
      c.shape == CursorShape.BAR -> canvas.drawRect(cx, cy, cx + 2 * density, cy + cellH, fill)
      c.shape == CursorShape.UNDERLINE -> canvas.drawRect(cx, cy + cellH - 2 * density, cx + w, cy + cellH, fill)
      else -> {
        canvas.drawRect(cx, cy, cx + w, cy + cellH, fill)
        val g = if (shift == 0) glyphAt(f, c) else ""
        if (g.isNotBlank()) {
          setFace(0)
          setInk(bg)
          canvas.drawText(g, cx, cy + baseline, text)
        }
      }
    }
  }

  private fun glyphAt(f: Frame, c: Cursor): String {
    val run = f.lines.getOrNull(c.row.toInt())?.runs?.firstOrNull { c.col >= it.col && c.col < it.col + it.cells }
      ?: return ""
    val offset = (c.col - run.col).toInt()
    return if (run.widths.isEmpty()) {
      run.text.substring(
        offset,
        offset + 1,
      )
    } else {
      graphemeAt(run.text, graphemeIndexAt(run.widths, offset))
    }
  }

  private fun drawRunText(canvas: Canvas, run: Run, x0: Float, y: Float) {
    if (run.text.isBlank()) return
    if (run.widths.isEmpty()) drawCells(canvas, run.text, x0, y) else drawGraphemes(canvas, run.text, run.widths, x0, y)
  }

  /** One cell per character: drawn whole, so the font can join ligatures, with box drawing cut out as geometry. */
  private fun drawCells(canvas: Canvas, s: String, x0: Float, y: Float) {
    var from = 0
    for (i in s.indices) {
      val c = s[i].code
      if (c < BOX_FIRST || c > BOX_LAST) continue
      if (i > from) drawSegment(canvas, s, from, i, x0 + from * cellW, y)
      if (!drawBoxChar(canvas, c, x0 + i * cellW, y - baseline, 1)) drawSegment(canvas, s, i, i + 1, x0 + i * cellW, y)
      from = i + 1
    }
    if (from < s.length) drawSegment(canvas, s, from, s.length, x0 + from * cellW, y)
  }

  /** Whole when the shaped text stays on the cell grid; character by character when a fallback font breaks it. */
  private fun drawSegment(canvas: Canvas, s: String, from: Int, to: Int, x: Float, y: Float) {
    if (to - from > 1 && !staysOnGrid(s, from, to)) {
      for (k in from until to) canvas.drawText(s, k, k + 1, x + (k - from) * cellW, y, text)
    } else {
      canvas.drawText(s, from, to, x, y, text)
    }
  }

  private fun staysOnGrid(s: String, from: Int, to: Int): Boolean {
    var ascii = true
    for (k in from until to) {
      if (s[k].code >= ASCII_LIMIT) {
        ascii = false
        break
      }
    }
    return ascii || abs(text.measureText(s, from, to) - (to - from) * cellW) < GRID_TOLERANCE
  }

  /** Wide, combining or astral text: each grapheme on its own cell(s). */
  private fun drawGraphemes(canvas: Canvas, s: String, widths: ByteArray, x0: Float, y: Float) {
    var col = 0
    var i = 0
    var g = 0
    while (i < s.length) {
      val cp = s.codePointAt(i)
      var end = i + Character.charCount(cp)
      while (end < s.length) {
        val next = s.codePointAt(end)
        val t = Character.getType(next)
        val joiner = next == 0x200D || next == 0xFE0F || (end > 0 && s.codePointBefore(end) == 0x200D)
        if (t == Character.NON_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt() || joiner) {
          end += Character.charCount(next)
        } else {
          break
        }
      }
      val w = widths.getOrNull(g)?.toInt() ?: 1
      val x = x0 + col * cellW
      if (!drawBoxChar(canvas, cp, x, y - baseline, w)) canvas.drawText(s, i, end, x, y, text)
      col += w
      i = end
      g++
    }
  }

  /** Box-drawing and blocks as geometry, so lines join without gaps. */
  private fun drawBoxChar(canvas: Canvas, cp: Int, x: Float, top: Float, w: Int): Boolean {
    if (cp !in 0x2500..0x259F) return false
    val cw = cellW * w
    val cx = x + cw / 2
    val cy = top + cellH / 2
    fill.color = text.color
    val t = max(1f, (density * 0.9f).roundToInt().toFloat())
    val tt = t * 2
    fun h(x1: Float, x2: Float, th: Float = t) = canvas.drawRect(x1, cy - th / 2, x2, cy + th / 2, fill)
    fun v(y1: Float, y2: Float, th: Float = t) = canvas.drawRect(cx - th / 2, y1, cx + th / 2, y2, fill)
    val r = x + cw
    val b = top + cellH
    when (cp) {
      0x2500 -> h(x, r)
      0x2501 -> h(x, r, tt)
      0x2502 -> v(top, b)
      0x2503 -> v(top, b, tt)
      0x250C, 0x256D -> {
        h(cx - t / 2, r)
        v(cy - t / 2, b)
      }
      0x2510, 0x256E -> {
        h(x, cx + t / 2)
        v(cy - t / 2, b)
      }
      0x2514, 0x2570 -> {
        h(cx - t / 2, r)
        v(top, cy + t / 2)
      }
      0x2518, 0x256F -> {
        h(x, cx + t / 2)
        v(top, cy + t / 2)
      }
      0x251C -> {
        v(top, b)
        h(cx, r)
      }
      0x2524 -> {
        v(top, b)
        h(x, cx)
      }
      0x252C -> {
        h(x, r)
        v(cy, b)
      }
      0x2534 -> {
        h(x, r)
        v(top, cy)
      }
      0x253C -> {
        h(x, r)
        v(top, b)
      }
      0x2550 -> {
        canvas.drawRect(x, cy - tt, r, cy - tt + t, fill)
        canvas.drawRect(x, cy + tt - t, r, cy + tt, fill)
      }
      0x2551 -> {
        canvas.drawRect(cx - tt, top, cx - tt + t, b, fill)
        canvas.drawRect(cx + tt - t, top, cx + tt, b, fill)
      }
      0x2580 -> canvas.drawRect(x, top, r, cy, fill)
      0x2584 -> canvas.drawRect(x, cy, r, b, fill)
      0x2588 -> canvas.drawRect(x, top, r, b, fill)
      0x258C -> canvas.drawRect(x, top, cx, b, fill)
      0x2590 -> canvas.drawRect(cx, top, r, b, fill)
      0x2591, 0x2592, 0x2593 -> {
        val a = fill.alpha
        fill.alpha = when (cp) {
          0x2591 -> 64
          0x2592 -> 128
          else -> 192
        }
        canvas.drawRect(x, top, r, b, fill)
        fill.alpha = a
      }
      in 0x2581..0x2587 -> canvas.drawRect(x, b - cellH * (cp - 0x2580) / 8f, r, b, fill)
      in 0x2589..0x258F -> canvas.drawRect(x, top, x + cw * (0x2590 - cp) / 8f, b, fill)
      else -> return false
    }
    return true
  }

  private fun drawCurly(canvas: Canvas, x0: Float, x1: Float, y: Float) {
    val amp = 1.5f * density
    val step = 3f * density
    var x = x0
    var up = true
    while (x < x1) {
      val nx = min(x + step, x1)
      canvas.drawLine(x, y + (if (up) amp else -amp), nx, y + (if (up) -amp else amp), line)
      x = nx
      up = !up
    }
  }

  private fun drawDashed(canvas: Canvas, x0: Float, x1: Float, y: Float, dash: Float) {
    var x = x0
    while (x < x1) {
      canvas.drawLine(x, y, min(x + dash, x1), y, line)
      x += dash * 2
    }
  }

  private fun drawLock(canvas: Canvas, x: Float, y: Float) {
    val w = cellW
    fill.style = Paint.Style.FILL
    canvas.drawRoundRect(
      RectF(x + w * 0.15f, y + cellH * 0.45f, x + w * 0.85f, y + cellH * 0.9f),
      density,
      density,
      fill,
    )
    fill.style = Paint.Style.STROKE
    fill.strokeWidth = density * 1.2f
    canvas.drawArc(RectF(x + w * 0.28f, y + cellH * 0.15f, x + w * 0.72f, y + cellH * 0.65f), 180f, 180f, false, fill)
    fill.style = Paint.Style.FILL
  }

  private fun graphemeIndexAt(widths: ByteArray, cellOffset: Int): Int {
    var c = 0
    for ((i, w) in widths.withIndex()) {
      if (cellOffset < c + w) return i
      c += w
    }
    return widths.size - 1
  }

  private fun graphemeAt(s: String, index: Int): String {
    var i = 0
    var g = 0
    while (i < s.length) {
      val n = Character.charCount(s.codePointAt(i))
      if (g == index) return s.substring(i, i + n)
      i += n
      g++
    }
    return ""
  }

  private fun blend(a: Int, b: Int, t: Float): Int {
    fun ch(c: Int, s: Int) = (c shr s) and 0xFF
    fun mix(s: Int) = (ch(a, s) * (1 - t) + ch(b, s) * t).roundToInt()
    return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
  }
}
