package dev.namnv.rexc.presentation.home

import java.net.URLDecoder

/** A server read from a QR code: what `rexc qr ADDR [NAME]` prints, or any code that is just an address. */
data class HostQr(val address: String, val name: String)

private const val SCHEME = "rexc://add"

/** The server in [text], or null when the code is not one (binary, prose, an empty value). */
fun parseHostQr(text: String): HostQr? {
  val trimmed = text.trim()
  return when {
    trimmed.isEmpty() -> null
    trimmed.startsWith(SCHEME) -> fromPayload(trimmed.substringAfter('?', ""))
    trimmed.none(Char::isWhitespace) -> HostQr(address = trimmed, name = "")
    else -> null
  }
}

private fun fromPayload(query: String): HostQr? {
  val params = query.split('&').filter { it.isNotEmpty() }.associate { pair ->
    val (k, v) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    k to URLDecoder.decode(v, Charsets.UTF_8)
  }
  val address = params["address"]?.trim().orEmpty()
  return address.takeIf { it.isNotEmpty() }?.let { HostQr(address = it, name = params["name"]?.trim().orEmpty()) }
}
