package dev.namnv.rexc.presentation.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.max

/**
 * The one non-Compose piece: an invisible focusable view that gives the soft
 * keyboard a raw InputConnection (no autocorrect, no composing) and forwards
 * what it types to [TerminalInput]. Compose has no public API for this yet.
 */
@SuppressLint("ViewConstructor")
class ImeBridge(context: Context, private val input: TerminalInput) : View(context) {
  var onHardwareKey: ((KeyEvent) -> Boolean)? = null

  init {
    isFocusable = true
    isFocusableInTouchMode = true
    defaultFocusHighlightEnabled = false
  }

  override fun onCheckIsTextEditor() = true

  override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
    outAttrs.inputType =
      InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
      InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    outAttrs.imeOptions =
      EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
    return object : BaseInputConnection(this, false) {
      override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        input.type(text.toString())
        return true
      }

      override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean = true
      override fun finishComposingText(): Boolean = true

      override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        repeat(max(1, beforeLength)) { input.key("backspace") }
        return true
      }

      override fun sendKeyEvent(event: KeyEvent): Boolean {
        onHardwareKey?.invoke(event)
        return true
      }
    }
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
    (onHardwareKey?.invoke(event) ?: false) || super.onKeyDown(keyCode, event)

  fun show() {
    requestFocus()
    context.getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
  }

  fun hide() {
    context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
  }
}
