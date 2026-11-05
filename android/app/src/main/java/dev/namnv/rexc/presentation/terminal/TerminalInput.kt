package dev.namnv.rexc.presentation.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.namnv.rexc.data.TerminalController
import uniffi.rexc_ffi.KeyMods

/**
 * Everything that sends bytes to the current terminal: typed text, named keys,
 * ctrl chords, paste, plus the dock's sticky modifiers. Keys are encoded by the
 * Rust engine from the terminal's own modes; nothing here guesses escape codes.
 */
@Stable
class TerminalInput(private val context: Context) {
  var terminal: TerminalController? by mutableStateOf(null)

  var ctrl by mutableStateOf(false)
    private set
  var alt by mutableStateOf(false)
    private set
  var shift by mutableStateOf(false)
    private set
  var ctrlLocked by mutableStateOf(false)
    private set
  var altLocked by mutableStateOf(false)
    private set

  private fun consume(): KeyMods {
    val r = KeyMods(shift = shift, ctrl = ctrl, alt = alt, meta = false)
    if (!ctrlLocked) ctrl = false
    if (!altLocked) alt = false
    shift = false
    return r
  }

  fun toggleCtrl() {
    if (ctrlLocked) {
      ctrlLocked = false
      ctrl = false
    } else {
      ctrl = !ctrl
    }
  }

  fun lockCtrl() {
    ctrlLocked = true
    ctrl = true
  }

  fun toggleAlt() {
    if (altLocked) {
      altLocked = false
      alt = false
    } else {
      alt = !alt
    }
  }

  fun lockAlt() {
    altLocked = true
    alt = true
  }

  fun toggleShift() {
    shift = !shift
  }

  /** Text from the soft keyboard. A pending ctrl/alt turns the first character into a chord. */
  fun type(s: String) {
    val t = terminal ?: return
    for ((i, part) in s.split('\n').withIndex()) {
      if (i > 0) key("enter")
      if (part.isEmpty()) continue
      val mods = consume()
      if (mods.ctrl || mods.alt) {
        val c = part[0]
        Keys.fromChar(c)?.let { t.sendKey(it, mods.copy(shift = mods.shift || Keys.isShifted(c))) }
        if (part.length > 1) t.sendText(part.substring(1))
      } else {
        t.sendText(if (mods.shift) part.uppercase() else part)
      }
    }
  }

  /** A named key (`escape`, `arrow_up`, `tab`...) with the sticky modifiers applied. */
  fun key(name: String, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) {
    val t = terminal ?: return
    val sticky = consume()
    t.sendKey(
      name,
      KeyMods(shift = shift || sticky.shift, ctrl = ctrl || sticky.ctrl, alt = alt || sticky.alt, meta = false),
    )
  }

  /** `c` → ^C, from the dock's chord sheet. */
  fun chord(c: Char) {
    val name = Keys.fromChar(c) ?: return
    terminal?.sendKey(name, KeyMods(shift = false, ctrl = true, alt = false, meta = false))
  }

  fun raw(s: String) {
    terminal?.sendText(s)
  }

  fun paste() {
    val cm = context.getSystemService(ClipboardManager::class.java)
    val s = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
    terminal?.paste(s)
  }

  /** Types [s] at the prompt, without Enter. */
  fun insert(s: String) {
    terminal?.insert(s)
  }

  fun copy(s: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Terminal", s))
  }

  /** A hardware key. Returns true when handled. Ctrl+Tab goes to [onSwitchPane]. */
  fun hardwareKey(e: KeyEvent, onSwitchPane: (Int) -> Unit): Boolean {
    val t = terminal
    val ctrlShift = e.isCtrlPressed && e.isShiftPressed
    return when {
      t == null || e.action != KeyEvent.ACTION_DOWN -> false
      e.isCtrlPressed && e.keyCode == KeyEvent.KEYCODE_TAB -> {
        onSwitchPane(if (e.isShiftPressed) -1 else 1)
        true
      }
      ctrlShift && e.keyCode == KeyEvent.KEYCODE_V -> {
        paste()
        true
      }
      ctrlShift && e.keyCode == KeyEvent.KEYCODE_C -> {
        t.selectionText()?.let(::copy)
        true
      }
      else -> namedKey(e) || printableKey(t, e)
    }
  }

  private fun namedKey(e: KeyEvent): Boolean {
    val name = Keys.fromKeyCode(e.keyCode) ?: return false
    key(name, ctrl = e.isCtrlPressed, alt = e.isAltPressed, shift = e.isShiftPressed)
    return true
  }

  /** A printable key: a chord when ctrl/alt (physical or sticky) is held, text otherwise. */
  private fun printableKey(t: TerminalController, e: KeyEvent): Boolean {
    val uc = e.getUnicodeChar(e.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
    if (uc == 0) return false
    val ch = String(Character.toChars(uc))
    val sticky = consume()
    val ctrl = e.isCtrlPressed || sticky.ctrl
    val alt = e.isAltPressed || sticky.alt
    if (ctrl || alt) {
      Keys.fromChar(ch[0])?.let { name ->
        t.sendKey(
          name,
          KeyMods(shift = e.isShiftPressed || Keys.isShifted(ch[0]), ctrl = ctrl, alt = alt, meta = e.isMetaPressed),
        )
      }
    } else {
      t.sendText(ch)
    }
    return true
  }
}
