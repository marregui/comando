package dev.marregui.comando.app;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.claude.ClaudeAdapter;

import static org.assertj.core.api.Assertions.assertThat;

/// How long a card waits for the user, and how long Claude Code is told to wait for the answer.
///
/// - A card waits 24 h before it is denied.
/// - Claude Code's per-server timeout is longer, so comando always answers first.
class ApprovalTimeoutTest {

    /// An unanswered card is denied after 24 h, and the MCP timeout written for a session outlasts it.
    @Test
    void aCardIsDeniedBeforeClaudeCodeDropsTheCall() {
        assertThat(App.APPROVAL_TIMEOUT).isEqualTo(Duration.ofHours(24));
        assertThat(ClaudeAdapter.MCP_TIMEOUT).isGreaterThan(App.APPROVAL_TIMEOUT);
    }
}
