package dev.namnv.rexc.presentation.common

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentTest {
  @Test
  fun of_matchesServerAppNamesAndFallsBackSensibly() {
    assertEquals(Agent.CLAUDE, Agent.of("claude-code"))
    assertEquals(Agent.CLAUDE, Agent.of("Claude"))
    assertEquals(Agent.CODEX, Agent.of("codex"))
    assertEquals(Agent.GEMINI, Agent.of("gemini-cli"))
    assertEquals(Agent.SHELL, Agent.of(null))
    assertEquals(Agent.SHELL, Agent.of("/bin/zsh"))
    assertEquals(Agent.SHELL, Agent.of("fish"))
    assertEquals(Agent.CLAUDE, Agent.of("claude.js"))
    assertEquals(Agent.AMP, Agent.of("amp"))
    assertEquals(Agent.OTHER, Agent.of("sample"))
    assertEquals(Agent.OTHER, Agent.of("vim"))
  }

  @Test
  fun markFor_isTheAgentsMarkOrTheProgramsInitial() {
    assertEquals("cx", Agent.markFor("codex"))
    assertEquals(">_", Agent.markFor("bash"))
    assertEquals("V", Agent.markFor("vim"))
    assertEquals("", Agent.markFor("claude"))
  }
}
