package dev.marregui.comando.ui.shell;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/// [Notifier] runs the desktop notification command, bounded, and quotes its text for AppleScript.
/// - A notification that does not finish is stopped at its bound.
/// - A notification that waits for input gets none and ends.
/// - The text is escaped for AppleScript and clipped to 200 characters.
class NotifierTest {

    /// `sleep 30` with a 200 ms bound returns false well within 5 s.
    @Test
    void aNotificationThatDoesNotFinishIsStoppedAtItsBound() {
        final boolean done = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> Notifier.run(List.of("sleep", "30"), Duration.ofMillis(200)));
        assertThat(done).isFalse();
    }

    /// `cat` returns true within its bound because its standard input is closed.
    @Test
    void aNotificationThatWaitsForInputGetsNoneAndEnds() {
        final boolean done = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> Notifier.run(List.of("cat"), Duration.ofSeconds(4)));
        assertThat(done).as("stdin is closed, so cat ends").isTrue();
    }

    /// [Notifier#escape] backslash-escapes quotes and backslashes, turns a newline into a space, and clips 300
    /// characters to 200 ending in `…`.
    @Test
    void theNotificationTextIsClippedAndQuotedForAppleScript() {
        assertThat(Notifier.escape("say \"hi\"\\\nnow")).isEqualTo("say \\\"hi\\\"\\\\ now");
        assertThat(Notifier.escape("x".repeat(300))).hasSize(200).endsWith("…");
    }
}
