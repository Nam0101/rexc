package dev.namnv.rexc.presentation.terminal

import android.view.KeyEvent
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.namnv.rexc.core.ui.theme.Colors

/** The terminal's bottom sheets. */
enum class TerminalSheet { PALETTE, SWITCHER, MAP }

/** Which sheet is open. Shared by the dock, the pane menu and the hardware shortcut. */
@Stable
class SheetsState {
  var shown by mutableStateOf<TerminalSheet?>(null)
    private set

  fun open(sheet: TerminalSheet) {
    shown = sheet
  }

  fun close() {
    shown = null
  }

  /** Ctrl+Shift+P opens the palette. Returns true when [e] was it. */
  fun onHardwareKey(e: KeyEvent): Boolean {
    val palette = e.action == KeyEvent.ACTION_DOWN && e.isCtrlPressed && e.isShiftPressed &&
      e.keyCode == KeyEvent.KEYCODE_P
    if (palette) open(TerminalSheet.PALETTE)
    return palette
  }
}

@Composable
fun rememberSheetsState(): SheetsState = remember { SheetsState() }

/** What the palette needs from the screen to run a phone-side action. */
class SheetHost(
  val onEvent: (TerminalUiEvent) -> Unit,
  val onRename: (Rename) -> Unit,
  val onCopyScreen: () -> Unit,
  val onPaste: () -> Unit,
  val onToggleKeyboard: () -> Unit,
)

/** The palette, quick switcher and pane map: one sheet at a time, content picked by [SheetsState.shown]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalSheets(sheets: SheetsState, state: TerminalUiState, host: SheetHost) {
  val sheet = sheets.shown ?: return
  val viewModel: PaletteViewModel = hiltViewModel<PaletteViewModel, PaletteViewModel.Factory>(
    key = "palette:${state.hostId}/${state.sessionId}",
  ) { it.create(state.hostId, state.sessionId) }
  ModalBottomSheet(
    onDismissRequest = sheets::close,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Colors.surface,
  ) {
    val content = Modifier.imePadding().navigationBarsPadding()
    when (sheet) {
      TerminalSheet.PALETTE -> PaletteSheet(state, host, sheets, viewModel, content)
      TerminalSheet.SWITCHER -> SwitcherSheet(state, host.onEvent, sheets::close, viewModel, content)
      TerminalSheet.MAP -> PaneMapSheet(state, host.onEvent, sheets::close, content)
    }
  }
}
