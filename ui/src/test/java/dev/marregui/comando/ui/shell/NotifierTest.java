package dev.marregui.comando.ui.shell;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class NotifierTest {

    @Test
    void aNotificationThatDoesNotFinishIsStoppedAtItsBound() {
        boolean done = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> Notifier.run(List.of("sleep", "30"), Duration.ofMillis(200)));
        assertThat(done).isFalse();
    }

    @Test
    void aNotificationThatWaitsForInputGetsNoneAndEnds() {
        boolean done = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> Notifier.run(List.of("cat"), Duration.ofSeconds(4)));
        assertThat(done).as("stdin is closed, so cat ends").isTrue();
    }

    @Test
    void theNotificationTextIsClippedAndQuotedForAppleScript() {
        assertThat(Notifier.escape("say \"hi\"\\\nnow")).isEqualTo("say \\\"hi\\\"\\\\ now");
        assertThat(Notifier.escape("x".repeat(300))).hasSize(200).endsWith("…");
    }
}
