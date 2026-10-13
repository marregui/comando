package dev.marregui.comando.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MainTest {

    @Test
    void aSecondInstanceSaysTheFirstMayStillBeClosing() {
        assertThat(Main.lockedMessage(Path.of("/w/.comando")))
                .isEqualTo("comando is already running on /w/.comando, or still closing; switch to its window, or try again in a few seconds.");
    }
}
