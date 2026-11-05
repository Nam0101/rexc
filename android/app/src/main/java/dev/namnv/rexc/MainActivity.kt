package dev.namnv.rexc

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
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
import dev.namnv.rexc.navigation.Home
import dev.namnv.rexc.navigation.Settings
import dev.namnv.rexc.navigation.Terminal
import dev.namnv.rexc.presentation.common.LocalSettings
import dev.namnv.rexc.presentation.common.LocalSharedTransitionScope
import dev.namnv.rexc.presentation.common.sharedBoundsFor
import dev.namnv.rexc.presentation.home.HomeRoute
import dev.namnv.rexc.presentation.settings.SettingsRoute
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

  /** A terminal a notification asked for; consumed by the nav host once. */
  private var pendingTerminal by mutableStateOf<Terminal?>(null)

  @OptIn(ExperimentalSharedTransitionApi::class)
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    readIntent(intent)
    askNotificationPermission()
    engine.connectAll()
    setContent {
      val settings by settingsRepository.settings.collectAsStateWithLifecycle()
      RexcTheme(settings) {
        val backStack = rememberNavBackStack(Home)
        pendingTerminal?.let { target ->
          pendingTerminal = null
          if (backStack.lastOrNull() != target) {
            if (backStack.lastOrNull().let { it is Terminal || it == Settings }) backStack.removeLastOrNull()
            backStack.add(target)
          }
        }
        CompositionLocalProvider(LocalSettings provides settings) {
          SharedTransitionLayout {
            CompositionLocalProvider(LocalSharedTransitionScope provides this) {
              NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxSize(),
                entryDecorators = listOf(
                  rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
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
                    SettingsRoute(onBack = { backStack.removeLastOrNull() })
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
      }
    }
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
    val host = intent?.getStringExtra(EXTRA_HOST) ?: return
    val session = intent.getStringExtra(EXTRA_SESSION) ?: return
    pendingTerminal = Terminal(host, session, intent.getStringExtra(EXTRA_BLOCK))
  }

  companion object {
    const val EXTRA_HOST = "host_id"
    const val EXTRA_SESSION = "session_id"
    const val EXTRA_BLOCK = "block_id"
  }
}
