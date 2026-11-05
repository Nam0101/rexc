package dev.namnv.rexc.lock

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLockTest {
  @Test
  fun relocks_onlyAfterTheGraceInTheBackground() {
    assertFalse(relocks(stoppedAtMs = null, nowMs = 100_000))
    assertFalse(relocks(stoppedAtMs = 1_000, nowMs = 1_000 + LOCK_GRACE_MS - 1))
    assertTrue(relocks(stoppedAtMs = 1_000, nowMs = 1_000 + LOCK_GRACE_MS))
  }

  @Test
  fun coldStart_beginsLocked() {
    assertTrue(AppLock().locked.value)
  }

  @Test
  fun shortTrip_staysUnlocked_longOneLocks() {
    val lock = AppLock().apply { unlock() }
    lock.onStop(0)
    lock.onStart(LOCK_GRACE_MS - 1)
    assertFalse(lock.locked.value)
    lock.onStop(LOCK_GRACE_MS)
    lock.onStart(2 * LOCK_GRACE_MS)
    assertTrue(lock.locked.value)
  }

  @Test
  fun unlock_forgetsTheBackgroundStintItEnded() {
    // A slow screen-lock confirmation: its result can arrive before the activity starts again.
    val lock = AppLock().apply { unlock() }
    lock.onStop(0)
    lock.unlock()
    lock.onStart(LOCK_GRACE_MS * 2)
    assertFalse(lock.locked.value)
  }
}
