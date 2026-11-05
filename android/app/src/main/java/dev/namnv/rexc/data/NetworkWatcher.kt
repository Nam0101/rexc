package dev.namnv.rexc.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** What identifies the connection the device is using; signal-strength updates leave it unchanged. */
internal data class NetworkSignature(val network: Long, val transports: Set<Int>, val validated: Boolean)

/** A connection that can reach the internet and differs from the last one is worth reconnecting on. */
internal fun shouldReconnect(previous: NetworkSignature?, current: NetworkSignature): Boolean =
  current.validated && current != previous

/** Calls [onChange] when the default network switches (wifi, cellular, VPN) or comes back, not on the first report. */
internal class NetworkWatcher(context: Context, private val onChange: () -> Unit) {
  private val manager = context.getSystemService(ConnectivityManager::class.java)
  private var last: NetworkSignature? = null
  private var seenFirst = false

  fun start() {
    manager.registerDefaultNetworkCallback(
      object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
          val now = NetworkSignature(
            network.networkHandle,
            TRANSPORTS.filterTo(HashSet()) { capabilities.hasTransport(it) },
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
          )
          val changed = shouldReconnect(last, now)
          if (now.validated) last = now
          // The engine has just been created: whatever is connected now was not lost.
          if (changed && seenFirst) onChange()
          if (now.validated) seenFirst = true
        }

        override fun onLost(network: Network) {
          last = null
        }
      },
    )
  }

  private companion object {
    val TRANSPORTS = listOf(
      NetworkCapabilities.TRANSPORT_WIFI,
      NetworkCapabilities.TRANSPORT_CELLULAR,
      NetworkCapabilities.TRANSPORT_VPN,
      NetworkCapabilities.TRANSPORT_ETHERNET,
    )
  }
}
