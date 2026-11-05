package dev.namnv.rexc.presentation.terminal

import android.view.Choreographer
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import uniffi.rexc_ffi.Frame

/**
 * Holds the runs of rows the engine reports unchanged, so a frame crosses the FFI with only
 * the rows that changed. The engine answers "changed since revision N" from its own per-row
 * revisions, so a frame this cache never saw is not lost: the next read still carries its rows.
 */
internal class RowCache {
  private var rev = 0uL
  private var lines: List<uniffi.rexc_ffi.Line> = emptyList()

  /** The next frame from [read] (which takes the revision we hold), with unchanged rows filled in. */
  fun next(read: (since: ULong) -> Frame?): Frame? {
    val frame = read(rev) ?: return null
    // A cache that disagrees with the engine (should not happen) starts over from a full frame.
    return merge(frame) ?: read(0uL)?.let(::merge)
  }

  private fun merge(f: Frame): Frame? {
    val held = lines
    val merged = ArrayList<uniffi.rexc_ffi.Line>(f.lines.size)
    for ((i, line) in f.lines.withIndex()) {
      val use = if (line.dirty) line else held.getOrNull(i)
      if (use == null) {
        rev = 0uL
        lines = emptyList()
        return null
      }
      merged += use
    }
    rev = f.rev
    lines = merged
    return f.copy(lines = merged)
  }
}

/**
 * Lets at most one frame be built per display frame. The first request after a quiet spell goes
 * straight through (an echo shouldn't wait for a vsync); under a flood each request waits for the next one.
 */
internal class VsyncGate(private val main: CoroutineDispatcher) {
  private var lastNanos = 0L

  suspend fun await() {
    if (System.nanoTime() - lastNanos > QUIET_NANOS) {
      lastNanos = System.nanoTime()
      return
    }
    withContext(main) {
      suspendCancellableCoroutine { cont ->
        val choreographer = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback { cont.resume(Unit) }
        choreographer.postFrameCallback(callback)
        cont.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
      }
    }
    lastNanos = System.nanoTime()
  }

  private companion object {
    const val QUIET_NANOS = 20_000_000L
  }
}
