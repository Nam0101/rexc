package dev.namnv.rexc.presentation.terminal

import androidx.compose.runtime.Immutable

/** A palette entry that the phone runs itself. */
@Immutable
internal class AppAction(val entry: PaletteEntry, val run: () -> Unit)

private const val PANES = "Panes"
private const val TABS = "Tabs"
private const val SCREEN = "Screen"
private const val APP = "App"

/** The phone's own actions for the shown pane; ones that make no sense now (close tab with one tab) are left out. */
internal fun appActions(state: TerminalUiState, host: SheetHost, open: (TerminalSheet) -> Unit): List<AppAction> {
  val send = host.onEvent
  fun action(id: String, title: String, category: String, keywords: String = "", run: () -> Unit) =
    AppAction(PaletteEntry("app:$id", title, category = category, keywords = keywords), run)
  return listOfNotNull(
    action("new_tab", "New tab", TABS, "window add") { send(TerminalUiEvent.NewTab) },
    action("split_right", "Split right", PANES, "pane vertical beside") { send(TerminalUiEvent.Split(false)) },
    action("split_down", "Split down", PANES, "pane horizontal below") { send(TerminalUiEvent.Split(true)) },
    action("close_pane", "Close pane", PANES, "kill remove") { send(TerminalUiEvent.ClosePane) },
    action("close_tab", "Close tab", TABS, "kill remove window") { send(TerminalUiEvent.CloseTab) }
      .takeIf { state.tabs.size > 1 },
    when (state.info?.ownedByMe) {
      true -> action("release", "Release to desktop", PANES, "size give back") { send(TerminalUiEvent.Release) }
      false -> action("take_over", "Take over size", PANES, "resize own mirror") { send(TerminalUiEvent.TakeOver) }
      null -> null
    },
    action("clear", "Clear screen", SCREEN, "reset wipe") { send(TerminalUiEvent.Clear) },
    action("copy_screen", "Copy screen", SCREEN, "clipboard text") { host.onCopyScreen() },
    action("paste", "Paste", SCREEN, "clipboard") { host.onPaste() },
    action("find", "Find in scrollback", SCREEN, "search") { send(TerminalUiEvent.Request("find")) },
    action("rename_tab", "Rename tab", TABS, "label") { host.onRename(Rename.TAB) },
    action("rename_session", "Rename session", APP, "label") { host.onRename(Rename.SESSION) },
    action("keyboard", "Toggle keyboard", APP, "ime show hide") { host.onToggleKeyboard() },
    action("switcher", "Quick switcher", APP, "go to pane session host") { open(TerminalSheet.SWITCHER) },
    action("map", "Pane map", APP, "layout split overview") { open(TerminalSheet.MAP) },
    action("settings", "Open settings", APP, "preferences") { send(TerminalUiEvent.Request("settings")) },
  )
}
