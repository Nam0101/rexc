package dev.namnv.rexc.presentation.terminal

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.namnv.rexc.core.ui.theme.Colors

/** How a tapped link opens: a web page in the browser, a mail address in a mail app, anything else only after asking. */
enum class LinkKind { WEB, MAIL, OTHER }

private val SCHEME = Regex("([A-Za-z][A-Za-z0-9+.-]*):(.+)")

/** What [url] is, or `null` when it is not a usable URI (no scheme, blanks inside, a web address without a host). */
fun linkKind(url: String): LinkKind? {
  val usable = url.none { it.isWhitespace() || it.isISOControl() }
  val (scheme, rest) = SCHEME.matchEntire(url)?.takeIf { usable }?.destructured ?: return null
  return when (scheme.lowercase()) {
    "http", "https" -> LinkKind.WEB.takeIf { rest.startsWith("//") && rest.length > 2 && rest[2] != '/' }
    "mailto" -> LinkKind.MAIL
    else -> LinkKind.OTHER
  }
}

/** Asks before opening a link whose scheme hands it to some other app; programs choose OSC 8 targets, so never silently. */
@Composable
fun OpenLinkDialog(url: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Colors.surface,
    title = { Text("Open this link?") },
    text = { Text(url, modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) },
    confirmButton = {
      TextButton(onClick = {
        onOpen()
        onDismiss()
      }) { Text("Open") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
