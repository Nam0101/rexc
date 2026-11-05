package dev.namnv.rexc.presentation.share

import dev.namnv.rexc.navigation.Terminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingShareTest {
  @Test
  fun sharedText_putsADifferentSubjectOnItsOwnLine() {
    assertEquals("Title\nbody", sharedText("body", "Title"))
    assertEquals("body", sharedText("body", "body"))
    assertEquals("body", sharedText("body", " "))
    assertEquals("Title", sharedText(null, "Title\n"))
  }

  @Test
  fun sharedText_dropsTrailingLineBreaksAndEmptyText() {
    assertEquals("ls -la", sharedText("ls -la\r\n\n", null))
    assertNull(sharedText("\n", null))
    assertNull(sharedText(null, null))
  }

  @Test
  fun sharedText_capsAt64KiBWithoutSplittingACharacter() {
    assertEquals(
      MAX_SHARE_BYTES,
      checkNotNull(sharedText("a".repeat(MAX_SHARE_BYTES + 10), null)).encodeToByteArray().size,
    )
    // Three-byte characters: 64 KiB is not a multiple of three, so the last one must be dropped whole.
    val cut = checkNotNull(sharedText("€".repeat(MAX_SHARE_BYTES), null))
    assertTrue(cut.encodeToByteArray().size <= MAX_SHARE_BYTES)
    assertTrue(cut.all { it == '€' })
    assertEquals(MAX_SHARE_BYTES / 3, cut.length)
  }

  @Test
  fun take_givesTheTextToItsTargetOnce() {
    val shares = PendingShare()
    val target = Terminal("h", "s", "b")
    shares.send(target, "hi")
    assertNull(shares.take(Terminal("h", "s")))
    assertEquals("hi", shares.take(target))
    assertNull(shares.take(target))
  }
}
