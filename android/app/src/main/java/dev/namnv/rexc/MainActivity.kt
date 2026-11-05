package dev.namnv.rexc

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import dagger.hilt.android.AndroidEntryPoint
import dev.namnv.rexc.core.ui.theme.RexcTheme
import dev.namnv.rexc.data.EngineRepository
import dev.namnv.rexc.data.SettingsRepository
import dev.namnv.rexc.lock.AppLock
import dev.namnv.rexc.lock.DeviceAuth
import dev.namnv.rexc.lock.LockCover
import dev.namnv.rexc.navigation.Home
import dev.namnv.rexc.navigation.Settings
import dev.namnv.rexc.navigation.Terminal
import dev.namnv.rexc.presentation.common.LocalSettings
import dev.namnv.rexc.presentation.common.LocalSharedTransitionScope
import dev.namnv.rexc.presentation.common.sharedBoundsFor
import dev.namnv.rexc.presentation.home.HomeRoute
import dev.namnv.rexc.presentation.settings.SettingsRoute
import dev.namnv.rexc.presentation.share.PendingShare
import dev.namnv.rexc.presentation.share.ShareSheet
import dev.namnv.rexc.presentation.share.sharedText
import dev.namnv.rexc.presentation.terminal.TerminalFont
import dev.namnv.rexc.presentation.terminal.TerminalRoute
import javax.inject.Inject
import kotlinx.coroutines.launch

// Screens slide in from a quarter of the width; the rest is a fade.
private const val NAV_SLIDE_DIVISOR = 4

// Predictive back shrinks the leaving screen a little while the one below slides back in.
private const val BACK_SCALE = 0.9f

/** Marks entries that morph out of a list card: their push and pop only cross-fade around that. */
private const val SHARED_ENTRY = "rexc.sharedEntry"

/** What the terminal asks the shell to open for it (the palette's "Settings" entry). */
private const val REQUEST_SETTINGS = "settings"

private fun Scene<NavKey>.isShared() = entries.lastOrNull()?.metadata?.containsKey(SHARED_ENTRY) == true

private fun AnimatedContentTransitionScope<Scene<NavKey>>.push(): ContentTransform = if (targetState.isShared()) {
  fadeIn() togetherWith fadeOut()
} else {
  slideInHorizontally { it / NAV_SLIDE_DIVISOR } + fadeIn() togetherWith fadeOut()
}

private fun AnimatedContentTransitionScope<Scene<NavKey>>.pop(): ContentTransform = if (initialState.isShared()) {
  fadeIn() togetherWith fadeOut()
} else {
  fadeIn() togetherWith slideOutHorizontally { it / NAV_SLIDE_DIVISOR } + fadeOut()
}

private fun AnimatedContentTransitionScope<Scene<NavKey>>.predictivePop(): ContentTransform =
  if (initialState.isShared()) {
    fadeIn() togetherWith fadeOut()
  } else {
    slideInHorizontally {
      -it / NAV_SLIDE_DIVISOR
    } + fadeIn() togetherWith scaleOut(targetScale = BACK_SCALE) + fadeOut()
  }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  @Inject lateinit var engine: EngineRepository

  @Inject lateinit var settingsRepository: SettingsRepository

  @Inject lateinit var appLock: AppLock

  @Inject lateinit var shares: PendingShare

  private val deviceAuth = DeviceAuth(this)

  /** A prompt is open; leaving the app for it (screen lock confirmation) must not queue another. */
  private var authenticating = false

  /** Ask for the owner on the next resume when the app is locked: on launch and on every return. */
  private var promptOnResume = true

  /** A terminal a notification asked for; consumed by the nav host once. */
  private var pendingTerminal by mutableStateOf<Terminal?>(null)

  /** The Quick Settings tile asked for Home: drop whatever is open above it. */
  private var pendingHome by mutableStateOf(false)

  /** Text another app shared, waiting for the user to pick a terminal. */
  private var pendingShare by mutableStateOf<String?>(null)

  @OptIn(ExperimentalSharedTransitionApi::class)
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    // A recreated activity gets its launch intent again: a share, tile or notification must not replay.
    if (savedInstanceState == null) readIntent(intent)
    askNotificationPermission()
    engine.connectAll()
    setContent {
      val settings by settingsRepository.settings.collectAsStateWithLifecycle()
      RexcTheme(settings) {
        val backStack = rememberNavBackStack(Home)
        // Out here so screen state outlives the lock cover, which takes NavDisplay out of composition.
        val saveableStateHolder = rememberSaveableStateHolder()
        followIntent(backStack)
        if (lockCover(settings.appLock)) return@RexcTheme
        CompositionLocalProvider(LocalSettings provides settings) {
          SharedTransitionLayout {
            CompositionLocalProvider(LocalSharedTransitionScope provides this) {
              NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxSize(),
                entryDecorators = listOf(
                  rememberSaveableStateHolderNavEntryDecorator<NavKey>(saveableStateHolder),
                  rememberViewModelStoreNavEntryDecorator(),
                ),
                transitionSpec = { push() },
                popTransitionSpec = { pop() },
                predictivePopTransitionSpec = { predictivePop() },
                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() else finish() },
                entryProvider = entryProvider {
                  entry<Home> {
                    HomeRoute(onOpenTerminal = { backStack.add(it) }, onOpenSettings = { backStack.add(Settings) })
                  }
                  entry<Settings> {
                    SettingsRoute(onBack = { backStack.removeLastOrNull() }, authenticate = ::authenticate)
                  }
                  entry<Terminal>(metadata = mapOf(SHARED_ENTRY to true)) { key ->
                    TerminalRoute(
                      key = key,
                      onBack = { backStack.removeLastOrNull() },
                      modifier = Modifier.sharedBoundsFor(key.sharedKey),
                      onOpen = { backStack.add(it) },
                      onRequest = { if (it == REQUEST_SETTINGS) backStack.add(Settings) },
                      font = TerminalFont.fromKey(settings.font),
                      fontSize = settings.fontSize,
                      onFontSizeChanged = ::saveFontSize,
                      localEcho = settings.localEcho,
                    )
                  }
                },
              )
            }
          }
        }
        SharePicker()
      }
    }
  }

  override fun onStart() {
    super.onStart()
    appLock.onStart(SystemClock.elapsedRealtime())
  }

  override fun onResume() {
    super.onResume()
    if (promptOnResume) {
      promptOnResume = false
      if (settingsRepository.settings.value.appLock && appLock.locked.value) authenticate {}
    }
  }

  override fun onStop() {
    super.onStop()
    appLock.onStop(SystemClock.elapsedRealtime())
    if (!authenticating) promptOnResume = true
  }

  /** Asks for the owner; success also unlocks the app. Without a screen lock there is nothing to ask. */
  private fun authenticate(onResult: (Boolean) -> Unit) {
    if (authenticating) return
    if (!DeviceAuth.available(this)) {
      appLock.unlock()
      onResult(true)
      return
    }
    authenticating = true
    deviceAuth.authenticate("Unlock ${getString(R.string.app_name)}") { ok ->
      authenticating = false
      if (ok) appLock.unlock()
      onResult(ok)
    }
  }

  /** Keeps the app's content out of the recents thumbnail while the lock is on (and screenshots, below 13). */
  private fun hideFromRecents(hide: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      setRecentsScreenshotEnabled(!hide)
    } else if (hide) {
      window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
      window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
  }

  /** Draws the lock cover in place of the app while it is locked; true when it did. */
  @Composable
  private fun lockCover(lockOn: Boolean): Boolean {
    val locked by appLock.locked.collectAsStateWithLifecycle()
    LaunchedEffect(lockOn) { hideFromRecents(lockOn) }
    val shown = lockOn && locked
    if (shown) LockCover(onUnlock = { authenticate {} })
    return shown
  }

  /** Where shared text goes; cancelling drops it. */
  @Composable
  private fun SharePicker() {
    val text = pendingShare ?: return
    val hosts by engine.hosts.collectAsStateWithLifecycle()
    ShareSheet(text, hosts, onPick = { shareTo(it, text) }, onDismiss = { pendingShare = null })
  }

  /** Opens the picked terminal; it pastes the text once its pane is up. */
  private fun shareTo(target: Terminal, text: String) {
    shares.send(target, text)
    pendingShare = null
    pendingTerminal = target
  }

  /** Applies what the last intent asked for: Home alone, or a terminal on top. */
  private fun followIntent(backStack: NavBackStack<NavKey>) {
    if (pendingHome) {
      pendingHome = false
      while (backStack.size > 1) backStack.removeLastOrNull()
    }
    val target = pendingTerminal ?: return
    pendingTerminal = null
    val open = backStack.indexOf(target)
    if (open >= 0) {
      while (backStack.size > open + 1) backStack.removeLastOrNull()
      return
    }
    if (backStack.lastOrNull().let { it is Terminal || it == Settings }) backStack.removeLastOrNull()
    backStack.add(target)
  }

  private fun askNotificationPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      registerForActivityResult(ActivityResultContracts.RequestPermission()) {
      }.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  /** A pinch on the grid changes the size for every terminal, like the Settings slider. */
  private fun saveFontSize(size: Float) {
    lifecycleScope.launch { settingsRepository.update { it.copy(fontSize = size) } }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    readIntent(intent)
  }

  private fun readIntent(intent: Intent?) {
    if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/plain") == true) {
      pendingShare = sharedText(
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
        intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString(),
      )
    } else if (intent?.getBooleanExtra(EXTRA_HOME, false) == true) {
      pendingHome = true
    } else {
      val host = intent?.getStringExtra(EXTRA_HOST) ?: return
      val session = intent.getStringExtra(EXTRA_SESSION) ?: return
      pendingTerminal = Terminal(host, session, intent.getStringExtra(EXTRA_BLOCK))
    }
  }

  companion object {
    const val EXTRA_HOST = "host_id"
    const val EXTRA_SESSION = "session_id"
    const val EXTRA_BLOCK = "block_id"

    /** Open on Home, whatever screen was last shown. */
    const val EXTRA_HOME = "home"
  }
}
