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

/// [ClaudeVersion] parses installed version output and warns when it lies outside the supported half-open range.
///
/// - Finds semantic versions embedded in command output.
/// - Accepts versions from `TESTED_FROM` up to but excluding `TESTED_UNTIL`.
/// - Returns promptly when a child keeps the version command's output pipe open.
class ClaudeVersionTest {

    /// Text containing `2.1.296` yields that version, while text without three numeric components yields no version.
    @Test
    void parsesTheCliOutput() {
        assertThat(ClaudeVersion.parse("2.1.296 (Claude Code)")).contains(new ClaudeVersion(2, 1, 296));
        assertThat(ClaudeVersion.parse("no version here")).isEmpty();
    }

    /// The tested range includes `2.1.296` and later `2.1` patches but excludes the preceding patch and `2.2.0`,
    /// establishing the lower-inclusive, upper-exclusive boundary.
    @Test
    void testedRangeIsHalfOpen() {
        assertThat(new ClaudeVersion(2, 1, 296).tested()).isTrue();
        assertThat(new ClaudeVersion(2, 1, 999).tested()).isTrue();
        assertThat(new ClaudeVersion(2, 1, 295).tested()).isFalse();
        assertThat(new ClaudeVersion(2, 2, 0).tested()).isFalse();
    }

    /// Checking an absent executable returns a user-facing not-found message rather than throwing process startup I/O.
    @Test
    void aMissingExecutableIsReported() {
        assertThat(ClaudeVersion.check("/nonexistent/claude", RootLaunch.INHERIT)).contains("not found");
    }

    /// A version printed before a background child holds stdout open is read within 7 s: the check does not wait for
    /// the pipe to close.
    @Test
    void aChildHoldingTheOutputOpenAfterTheExitDoesNotHoldTheCheck(@TempDir Path dir) throws IOException {
        Path script = dir.resolve("claude");
        Files.writeString(script, "#!/bin/sh\necho '2.1.296 (Claude Code)'\nsleep 8 &\nexit 0\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));

        String message = assertTimeoutPreemptively(Duration.ofSeconds(7), () -> ClaudeVersion.check(script.toString(), RootLaunch.INHERIT));

        assertThat(message).isEmpty();
    }
}
