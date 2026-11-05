package dev.namnv.rexc.presentation.terminal

/** The typeface of the terminal grid. Both bundled ones draw programming ligatures; the system one does not. */
enum class TerminalFont(val key: String, val label: String) {
  JETBRAINS_MONO("jetbrains", "JetBrains Mono"),
  FIRA_CODE("fira", "Fira Code"),
  SYSTEM("system", "System monospace"),
  ;

  companion object {
    /** The font saved under [key], or the default when it is unknown. */
    fun fromKey(key: String?): TerminalFont = entries.firstOrNull { it.key == key } ?: JETBRAINS_MONO
  }
}
