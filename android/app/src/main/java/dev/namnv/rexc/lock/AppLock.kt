package dev.namnv.rexc.lock

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How long the app may sit in the background before it locks: the QR scanner, voice input and share flows fit. */
const val LOCK_GRACE_MS = 30_000L

/** Whether a stint in the background that began at [stoppedAtMs] has run long enough to lock the app again. */
internal fun relocks(stoppedAtMs: Long?, nowMs: Long, graceMs: Long = LOCK_GRACE_MS): Boolean =
  stoppedAtMs != null && nowMs - stoppedAtMs >= graceMs

/**
 * Whether the app is locked, for the screen to cover itself while the app lock setting is on.
 * A cold start begins locked; times are monotonic (`SystemClock.elapsedRealtime`).
 */
@Singleton
class AppLock @Inject constructor() {
  private val _locked = MutableStateFlow(true)
  val locked: StateFlow<Boolean> = _locked.asStateFlow()

  private var stoppedAtMs: Long? = null

  fun onStop(nowMs: Long) {
    stoppedAtMs = nowMs
  }

  fun onStart(nowMs: Long) {
    if (relocks(stoppedAtMs, nowMs)) _locked.value = true
    stoppedAtMs = null
  }

  /** The owner proved themselves; a background stint that began before this does not count. */
  fun unlock() {
    stoppedAtMs = null
    _locked.value = false
  }
}
