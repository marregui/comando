package dev.marregui.comando.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/// [Main] describes the message shown when a data-root instance lock is already held.
///
/// - includes the contended root path
/// - explains that the earlier process may still be closing
class MainTest {

    /// A contended root names its exact path and directs the user to the existing window or a later retry.
    /// The fixed Unix path makes interpolation visible in the complete message.
    @Test
    void aSecondInstanceSaysTheFirstMayStillBeClosing() {
        assertThat(Main.lockedMessage(Path.of("/w/.comando")))
                .isEqualTo("comando is already running on /w/.comando, or still closing; switch to its window, or try again in a few seconds.");
    }
}
