package dev.namnv.rexc.presentation.terminal

import dev.namnv.rexc.BuildConfig
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber

/**
 * Debug-only render counters (frame build including the FFI hop, grid draw, frames drawn per second),
 * logged every two seconds under the tag "render". Release builds skip the clock reads entirely.
 */
internal object RenderStats {
  private const val WINDOW_NANOS = 2_000_000_000L
  private const val MICROS = 1_000L
  private const val NANOS_PER_SECOND = 1e9

  private val buildNanos = AtomicLong()
  private val builds = AtomicInteger()
  private val drawNanos = AtomicLong()
  private val draws = AtomicInteger()
  private val windowStart = AtomicLong(System.nanoTime())

  inline fun <T> timedBuild(block: () -> T): T {
    if (!BuildConfig.DEBUG) return block()
    val start = System.nanoTime()
    val result = block()
    recordBuild(System.nanoTime() - start)
    return result
  }

  inline fun <T> timedDraw(block: () -> T): T {
    if (!BuildConfig.DEBUG) return block()
    val start = System.nanoTime()
    val result = block()
    recordDraw(System.nanoTime() - start)
    return result
  }

  fun recordBuild(nanos: Long) {
    buildNanos.addAndGet(nanos)
    builds.incrementAndGet()
  }

  fun recordDraw(nanos: Long) {
    drawNanos.addAndGet(nanos)
    draws.incrementAndGet()
    val now = System.nanoTime()
    val start = windowStart.get()
    if (now - start >= WINDOW_NANOS && windowStart.compareAndSet(start, now)) log(now - start)
  }

  private fun log(window: Long) {
    val b = builds.getAndSet(0)
    val d = draws.getAndSet(0)
    val bn = buildNanos.getAndSet(0)
    val dn = drawNanos.getAndSet(0)
    val perSecond = NANOS_PER_SECOND / window
    Timber.tag("render").d(
      "build avg %d us (%.0f/s), grid draw avg %d us (%.0f/s)",
      if (b > 0) bn / b / MICROS else 0,
      b * perSecond,
      if (d > 0) dn / d / MICROS else 0,
      d * perSecond,
    )
  }
}
