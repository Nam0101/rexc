package dev.namnv.rexc.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the code a cold start and the first screens run, so release builds can precompile it.
 * Run `./gradlew :app:generateBaselineProfile` (needs an emulator or device; see this module's build file).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
  @get:Rule
  val rule = BaselineProfileRule()

  @Test
  fun startupAndFirstSession() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.pkg(PACKAGE)), UI_TIMEOUT_MS)
    if (device.hasObject(By.desc("Server menu"))) openFirstSession() else openAndCloseServerEditor()
  }

  /** A saved server: its first session row is the first clickable that is not a menu or the new-session row. */
  private fun MacrobenchmarkScope.openFirstSession() {
    val row = device.findObjects(By.clickable(true)).firstOrNull {
      it.contentDescription != "Server menu" && it.text?.startsWith("New session") != true
    } ?: return
    row.click()
    device.waitForIdle()
    // The terminal draws and listens for input from here; give the engine a moment to stream a frame.
    Thread.sleep(TERMINAL_SETTLE_MS)
  }

  /** No server yet (a fresh emulator): the editor sheet is the next screen a user meets. */
  private fun MacrobenchmarkScope.openAndCloseServerEditor() {
    device.findObject(By.text("Add server"))?.click()
    device.wait(Until.hasObject(By.text("Address")), UI_TIMEOUT_MS)
    device.pressBack()
    device.waitForIdle()
  }

  private companion object {
    const val PACKAGE = "dev.namnv.rexc"
    const val UI_TIMEOUT_MS = 5_000L
    const val TERMINAL_SETTLE_MS = 2_000L
  }
}
