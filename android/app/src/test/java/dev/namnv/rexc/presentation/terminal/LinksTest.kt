package dev.namnv.rexc.presentation.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinksTest {
  @Test
  fun linkKind_opensWebAndMailDirectlyAndAsksForTheRest() {
    assertEquals(LinkKind.WEB, linkKind("https://rex.app/docs?a=(1)"))
    assertEquals(LinkKind.WEB, linkKind("HTTP://x.io"))
    assertEquals(LinkKind.MAIL, linkKind("mailto:me@x.io"))
    assertEquals(LinkKind.OTHER, linkKind("file:///etc/hosts"))
    assertEquals(LinkKind.OTHER, linkKind("vscode://file/a.rs:12"))
    assertEquals(LinkKind.OTHER, linkKind("intent://scan#Intent;scheme=zxing;end"))
  }

  @Test
  fun linkKind_ignoresMalformedUris() {
    assertNull(linkKind(""))
    assertNull(linkKind("no scheme here"))
    assertNull(linkKind("/tmp/a.txt"))
    assertNull(linkKind("1http://x.io"))
    assertNull(linkKind("https:"))
    assertNull(linkKind("https://"))
    assertNull(linkKind("https:///path"))
    assertNull(linkKind("http:x.io"))
    assertNull(linkKind("https://x.io/a b"))
    assertNull(linkKind("https://x.io/\u0007"))
  }
}
