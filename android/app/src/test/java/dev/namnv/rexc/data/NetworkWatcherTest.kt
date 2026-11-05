package dev.namnv.rexc.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkWatcherTest {
  private val wifi = NetworkSignature(network = 1, transports = setOf(1), validated = true)

  @Test
  fun a_new_network_is_worth_reconnecting_on() {
    assertTrue(shouldReconnect(null, wifi))
    assertTrue(shouldReconnect(wifi, wifi.copy(network = 2, transports = setOf(0))))
    assertTrue(shouldReconnect(wifi, wifi.copy(transports = setOf(1, 4))))
  }

  @Test
  fun the_same_network_reporting_again_is_ignored() {
    assertFalse(shouldReconnect(wifi, wifi))
  }

  @Test
  fun a_network_without_internet_waits_until_it_is_validated() {
    assertFalse(shouldReconnect(null, wifi.copy(validated = false)))
  }
}
