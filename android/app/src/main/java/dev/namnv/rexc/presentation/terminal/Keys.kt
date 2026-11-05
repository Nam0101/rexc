package dev.namnv.rexc.presentation.terminal

import android.view.KeyEvent

/** W3C key codes in snake_case, the names the Rust encoder understands. */
object Keys {
  fun fromKeyCode(code: Int): String? = when (code) {
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
    KeyEvent.KEYCODE_TAB -> "tab"
    KeyEvent.KEYCODE_DEL -> "backspace"
    KeyEvent.KEYCODE_FORWARD_DEL -> "delete"
    KeyEvent.KEYCODE_ESCAPE -> "escape"
    KeyEvent.KEYCODE_DPAD_UP -> "arrow_up"
    KeyEvent.KEYCODE_DPAD_DOWN -> "arrow_down"
    KeyEvent.KEYCODE_DPAD_LEFT -> "arrow_left"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "arrow_right"
    KeyEvent.KEYCODE_MOVE_HOME -> "home"
    KeyEvent.KEYCODE_MOVE_END -> "end"
    KeyEvent.KEYCODE_PAGE_UP -> "page_up"
    KeyEvent.KEYCODE_PAGE_DOWN -> "page_down"
    KeyEvent.KEYCODE_INSERT -> "insert"
    in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "f${code - KeyEvent.KEYCODE_F1 + 1}"
    else -> null
  }

  /** The key a printable character sits on, for ctrl/alt chords. */
  fun fromChar(c: Char): String? = when (c) {
    in 'a'..'z' -> "key_$c"
    in 'A'..'Z' -> "key_${c.lowercaseChar()}"
    in '0'..'9' -> "digit_$c"
    ' ' -> "space"
    '-', '_' -> "minus"
    '=', '+' -> "equal"
    '[', '{' -> "bracket_left"
    ']', '}' -> "bracket_right"
    '\\', '|' -> "backslash"
    ';', ':' -> "semicolon"
    '\'', '"' -> "quote"
    ',', '<' -> "comma"
    '.', '>' -> "period"
    '/', '?' -> "slash"
    '`', '~' -> "backquote"
    else -> null
  }

  fun isShifted(c: Char): Boolean = c.isUpperCase() || c in "_+{}|:\"<>?~!@#$%^&*()"
}
