package dev.marregui.comando.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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
        assertThat(ClaudeVersion.check("/nonexistent/claude", RootLaunch.INHERIT)).contains("not found");
    }

    @Test
    void aChildHoldingTheOutputOpenAfterTheExitDoesNotHoldTheCheck(@TempDir Path dir) throws IOException {
        Path script = dir.resolve("claude");
        Files.writeString(script, "#!/bin/sh\necho '2.1.296 (Claude Code)'\nsleep 8 &\nexit 0\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));

        String message = assertTimeoutPreemptively(Duration.ofSeconds(7), () -> ClaudeVersion.check(script.toString(), RootLaunch.INHERIT));

        assertThat(message).isEmpty();
    }
}
