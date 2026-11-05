package dev.namnv.rexc.presentation.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostQrTest {
  @Test
  fun parses_the_cli_payload_with_a_name() {
    assertEquals(
      HostQr("gencloud-1.tail1234.ts.net:7297", "gen cloud"),
      parseHostQr("rexc://add?address=gencloud-1.tail1234.ts.net%3A7297&name=gen%20cloud"),
    )
  }

  @Test
  fun payload_without_a_name_and_a_bare_address_both_work() {
    assertEquals(HostQr("100.64.0.7", ""), parseHostQr("rexc://add?address=100.64.0.7"))
    assertEquals(HostQr("https://box.tail1234.ts.net", ""), parseHostQr("  https://box.tail1234.ts.net\n"))
  }

  @Test
  fun rejects_prose_and_empty_codes() {
    assertNull(parseHostQr(""))
    assertNull(parseHostQr("hello world"))
    assertNull(parseHostQr("rexc://add?name=only"))
  }
}
