package dev.namnv.rexc.alerts

import uniffi.rexc_ffi.AlertKind

/** A one-tap answer on a notification. [raw] sends [text] as is, without Enter. */
internal enum class QuickAction(val label: String, val text: String, val raw: Boolean) {
  Yes("Yes", "y", raw = false),
  No("No", "n", raw = false),
  Esc("Esc", "\u001b", raw = true),
}

/** Only a permission prompt has an obvious yes/no; questions and auth need real text. */
internal fun quickActionsFor(kind: AlertKind): List<QuickAction> =
  if (kind is AlertKind.Blocked && kind.kind == "permission") QuickAction.entries else emptyList()
