// Hot path: palette entries, glyph geometry and style bits are numeric by nature, and the
// draw loop stays one pass on purpose. Readability is traded for speed here and only here.
@file:Suppress(
  "MagicNumber",
  "CyclomaticComplexMethod",
  "LongMethod",
  "NestedBlockDepth",
  "ReturnCount",
  "ComplexCondition",
)

package dev.namnv.rexc.presentation.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import dev.namnv.rexc.R
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import uniffi.rexc_ffi.CursorShape
import uniffi.rexc_ffi.Frame

private const val STYLE_BOLD = 1
private const val STYLE_ITALIC = 1 shl 1
private const val STYLE_FAINT = 1 shl 2
private const val STYLE_STRIKE = 1 shl 3
private const val STYLE_OVERLINE = 1 shl 4
private const val STYLE_SELECTED = 1 shl 5

/**
 * Paints a terminal [Frame] from the engine onto a native canvas: style runs,
 * box drawing as geometry, cursor shapes, a scroll thumb. Pure drawing; the
 * composable owns gestures and state.
 */
class FrameRenderer(context: Context) {
  private val density = context.resources.displayMetrics.density
  private val typefaces: Array<Typeface> = arrayOf(
    R.font.jetbrains_mono_regular,
    R.font.jetbrains_mono_bold,
    R.font.jetbrains_mono_italic,
    R.font.jetbrains_mono_bold_italic,
  ).map { ResourcesCompat.getFont(context, it) ?: Typeface.MONOSPACE }.toTypedArray()
  private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { isLinearText = true }
  private val fill = Paint()
  private val line = Paint(Paint.ANTI_ALIAS_FLAG)

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

  init {
    updateMetrics()
  }

  private fun updateMetrics() {
    text.textSize = fontSize * density
    text.typeface = typefaces[0]
    cellW = text.measureText("M")
    val fm = text.fontMetrics
    cellH = floor((fm.descent - fm.ascent + fm.leading) * 1.08f)
    baseline = -fm.ascent + (cellH - (fm.descent - fm.ascent)) / 2f
  }

  class Layout(val scale: Float, val panX: Float, val offsetY: Float, val insetTop: Float)

  fun draw(canvas: Canvas, f: Frame, l: Layout, width: Float, height: Float, focused: Boolean, blinkOn: Boolean) {
    val bg = f.background.toInt()
    canvas.save()
    canvas.translate(padding - l.panX, l.insetTop + padding - l.offsetY)
    canvas.scale(l.scale, l.scale)
    val fgDefault = f.foreground.toInt()
    val selColor = blend(fgDefault, bg, 0.30f)
    for ((rowIdx, row) in f.lines.withIndex()) {
      val top = rowIdx * cellH
      for (run in row.runs) {
        val selected = (run.style.toInt() and STYLE_SELECTED) != 0
        val b = when {
          selected -> selColor
          run.bg != null -> run.bg!!.toInt()
          else -> continue
        }
        fill.color = b
        val x = run.col.toInt() * cellW
        canvas.drawRect(x, top, x + run.cells.toInt() * cellW, top + cellH + 0.5f, fill)
      }
      for (run in row.runs) {
        val style = run.style.toInt()
        val bold = style and STYLE_BOLD != 0
        val italic = style and STYLE_ITALIC != 0
        text.typeface = typefaces[(if (bold) 1 else 0) + (if (italic) 2 else 0)]
        var color = run.fg.toInt()
        if (style and STYLE_FAINT != 0) color = blend(color, bg, 0.5f)
        text.color = color
        val x0 = run.col.toInt() * cellW
        val y = top + baseline
        if (run.text.isNotBlank()) drawRunText(canvas, run.text, run.widths, x0, y)
        val ul = (style shr 8) and 0xF
        if (ul != 0 || style and (STYLE_STRIKE or STYLE_OVERLINE) != 0) {
          line.color = run.underlineColor?.toInt() ?: color
          line.strokeWidth = max(1f, density * 0.9f)
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
    }

    f.cursor?.let { c ->
      if (f.scrollOffset + f.scrollLen < f.scrollTotal) return@let
      if (c.blinking && !blinkOn && focused) return@let
      val cx = c.col.toInt() * cellW
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
          val run = f.lines.getOrNull(c.row.toInt())?.runs?.firstOrNull { c.col >= it.col && c.col < it.col + it.cells }
          if (run != null) {
            val g = graphemeAt(run.text, graphemeIndexAt(run.widths, (c.col - run.col).toInt()))
            if (g.isNotBlank()) {
              text.typeface = typefaces[0]
              text.color = bg
              canvas.drawText(g, cx, cy + baseline, text)
            }
          }
        }
      }
    }
    canvas.restore()

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

  private fun drawRunText(canvas: Canvas, s: String, widths: ByteArray, x0: Float, y: Float) {
    if (s.length == widths.size && widths.all { it.toInt() == 1 } && s.all { it.code < 0x2500 || it.code > 0x259F }) {
      canvas.drawText(s, x0, y, text)
      return
    }
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
