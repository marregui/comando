package dev.marregui.comando.claude;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeVersionTest {

    @Test
    void parsesTheCliOutput() {
        assertThat(ClaudeVersion.parse("2.1.296 (Claude Code)")).contains(new ClaudeVersion(2, 1, 296));
        assertThat(ClaudeVersion.parse("no version here")).isEmpty();
    }

    @Test
    void testedRangeIsHalfOpen() {
        assertThat(new ClaudeVersion(2, 1, 296).tested()).isTrue();
        assertThat(new ClaudeVersion(2, 1, 999).tested()).isTrue();
        assertThat(new ClaudeVersion(2, 1, 295).tested()).isFalse();
        assertThat(new ClaudeVersion(2, 2, 0).tested()).isFalse();
    }

    @Test
    void aMissingExecutableIsReported() {
        assertThat(ClaudeVersion.check("/nonexistent/claude")).contains("not found");
    }
}
