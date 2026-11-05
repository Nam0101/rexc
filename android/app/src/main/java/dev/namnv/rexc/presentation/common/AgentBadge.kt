package dev.namnv.rexc.presentation.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.namnv.rexc.core.ui.theme.Colors
import dev.namnv.rexc.core.ui.theme.MonoFamily
import dev.namnv.rexc.presentation.home.stateColor
import kotlin.math.cos
import kotlin.math.sin
import uniffi.rexc_ffi.ProgramState

// Each agent's colour, close to its own branding, fixed across themes.
private val claudeClay = Color(0xFFD97757)
private val codexGreen = Color(0xFF10A37F)
private val geminiBlue = Color(0xFF4E8DF5)
private val copilotViolet = Color(0xFF8957E5)
private val aiderTeal = Color(0xFF1FA2B8)
private val cursorGrey = Color(0xFF8A8A8E)
private val opencodeCoral = Color(0xFFF2545B)
private val ampCrimson = Color(0xFFE0245E)
private val gooseLilac = Color(0xFFB5A2F6)

/** A coding agent the badges tell apart, matched on the app name the server reports ("claude-code", "codex"...). */
enum class Agent(val mark: String, private val brand: Color?, private vararg val keys: String) {
  CLAUDE("", claudeClay, "claude"),
  CODEX("cx", codexGreen, "codex", "openai"),
  GEMINI("", geminiBlue, "gemini"),
  COPILOT("cp", copilotViolet, "copilot"),
  AIDER("ai", aiderTeal, "aider"),
  CURSOR("cu", cursorGrey, "cursor"),
  OPENCODE("oc", opencodeCoral, "opencode"),
  AMP("am", ampCrimson, "amp"),
  GOOSE("go", gooseLilac, "goose"),

  /** A plain shell, or nothing known at all. */
  SHELL(">_", null),

  /** A program the server named that is not on the list: its initial. */
  OTHER("", null),
  ;

  /** The badge's colour: the brand's, or the theme's for shells and strangers. */
  @Composable
  fun tint(): Color = brand ?: if (this == SHELL) Colors.muted else Colors.accent

  companion object {
    private val shells = setOf("sh", "zsh", "bash", "fish", "nu", "nushell", "pwsh", "dash", "ksh", "tcsh")
    private val separators = Regex("[^a-z0-9]+")

    /** Matches a whole word of the name ("claude-code", "gemini-cli", "claude.js"), never a fragment ("sample"). */
    fun of(app: String?): Agent {
      val name = app?.trim()?.lowercase()?.substringAfterLast('/').orEmpty()
      if (name.isEmpty() || name in shells) return SHELL
      val words = name.split(separators)
      return entries.firstOrNull { agent -> agent.keys.any { it in words } } ?: OTHER
    }

    /** What the badge writes: the agent's mark, or the program's initial. */
    fun markFor(app: String?): String = of(app).let { if (it == OTHER) app.orEmpty().take(1).uppercase() else it.mark }
  }
}

private const val BADGE_TINT = 0.16f
private const val BADGE_CORNER = 0.32f
private const val GLYPH_SHARE = 0.56f
private const val DOT_SHARE = 0.4f
private const val DOT_OVERHANG = 0.12f
private const val SPOKE_WIDTH = 0.13f
private const val SPOKE_INNER = 0.22f
private const val SPOKES = 8
private const val BREATH_MS = 1_100
private const val PING_MS = 1_600
private const val BREATH_MIN_ALPHA = 0.55f
private const val BREATH_MIN_SCALE = 0.85f
private const val PING_REACH = 1.6f
private const val PING_ALPHA = 0.7f
private const val PING_STROKE = 0.35f
private const val DOT_ON_RING = 0.72f
private const val MARK_SHARE = 0.75f

/**
 * Who runs in a pane: a rounded tile in the agent's colour with its mark, and the
 * program's state as a dot on the corner. A working dot breathes; a blocked one pings.
 */
@Composable
fun AgentBadge(app: String?, state: ProgramState?, size: Dp = 28.dp, modifier: Modifier = Modifier) {
  val agent = Agent.of(app)
  val tint = agent.tint()
  Box(modifier.size(size)) {
    Box(
      Modifier.size(size).clip(RoundedCornerShape(size * BADGE_CORNER)).background(tint.copy(alpha = BADGE_TINT)),
      contentAlignment = Alignment.Center,
    ) {
      Glyph(agent, Agent.markFor(app), tint, size * GLYPH_SHARE)
    }
    state?.takeIf { it != ProgramState.IDLE }?.let {
      LiveDot(
        state = it,
        size = size * DOT_SHARE,
        ring = Colors.surface,
        modifier = Modifier.align(Alignment.BottomEnd).offset(size * DOT_OVERHANG, size * DOT_OVERHANG),
      )
    }
  }
}

/** The agent's mark alone, for chips and other tight spots. */
@Composable
fun AgentGlyph(app: String?, size: Dp, modifier: Modifier = Modifier) {
  val agent = Agent.of(app)
  Box(modifier.size(size), contentAlignment = Alignment.Center) { Glyph(agent, Agent.markFor(app), agent.tint(), size) }
}

/**
 * A program's state as a dot that lives: WORKING breathes, BLOCKED pings outward,
 * the rest hold still. [ring] draws a thin keyline so the dot reads on a badge.
 */
@Composable
fun LiveDot(state: ProgramState, size: Dp, modifier: Modifier = Modifier, ring: Color? = null) {
  val color = stateColor(state)
  when (state) {
    ProgramState.WORKING -> {
      val breath by rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BREATH_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
      )
      Box(
        modifier.size(size).drawBehind {
          val scale = BREATH_MIN_SCALE + (1f - BREATH_MIN_SCALE) * breath
          dot(color.copy(alpha = BREATH_MIN_ALPHA + (1f - BREATH_MIN_ALPHA) * breath), ring, scale)
        },
      )
    }
    ProgramState.BLOCKED -> {
      val ping by rememberInfiniteTransition(label = "ping").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PING_MS, easing = LinearOutSlowInEasing), RepeatMode.Restart),
        label = "ping",
      )
      Box(
        modifier.size(size).drawBehind {
          val r = this.size.minDimension / 2
          val reach = r * (1f + PING_REACH * ping)
          drawCircle(color.copy(alpha = (1f - ping) * PING_ALPHA), reach, style = Stroke(r * PING_STROKE))
          dot(color, ring, 1f)
        },
      )
    }
    else -> Box(modifier.size(size).drawBehind { dot(color, ring, 1f) })
  }
}

/** The dot itself, on its keyline when it sits on a badge. */
private fun DrawScope.dot(color: Color, ring: Color?, scale: Float) {
  val r = size.minDimension / 2
  if (ring != null) drawCircle(ring, r)
  drawCircle(color, (if (ring != null) r * DOT_ON_RING else r) * scale)
}

@Composable
private fun Glyph(agent: Agent, mark: String, tint: Color, size: Dp) {
  when (agent) {
    Agent.CLAUDE -> Canvas(Modifier.size(size)) { spokes(tint) }
    Agent.GEMINI -> Canvas(Modifier.size(size)) { star(tint) }
    else -> Text(
      mark,
      fontFamily = MonoFamily,
      fontWeight = FontWeight.Bold,
      fontSize = with(LocalDensity.current) { (size * MARK_SHARE).toSp() },
      color = tint,
      maxLines = 1,
    )
  }
}

/** Eight rounded spokes: the asterisk Claude signs with. */
private fun DrawScope.spokes(tint: Color) {
  val c = center
  val r = size.minDimension / 2
  repeat(SPOKES) { i ->
    val a = i * (2 * Math.PI / SPOKES).toFloat()
    val dir = Offset(cos(a), sin(a))
    drawLine(tint, c + dir * (r * SPOKE_INNER), c + dir * r, strokeWidth = r * 2 * SPOKE_WIDTH, cap = StrokeCap.Round)
  }
}

/** A four-point star with pinched sides: Gemini's spark. */
private fun DrawScope.star(tint: Color) {
  val c = center
  val r = size.minDimension / 2
  val path = Path().apply {
    moveTo(c.x, c.y - r)
    quadraticTo(c.x, c.y, c.x + r, c.y)
    quadraticTo(c.x, c.y, c.x, c.y + r)
    quadraticTo(c.x, c.y, c.x - r, c.y)
    quadraticTo(c.x, c.y, c.x, c.y - r)
    close()
  }
  drawPath(path, tint)
}
