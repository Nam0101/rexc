package dev.namnv.rexc.presentation.common

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** One line of a dropdown menu; the caller closes the menu in [onClick]. */
@Composable
fun MenuEntry(label: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
  DropdownMenuItem(text = { Text(label, color = color) }, onClick = onClick)
}
