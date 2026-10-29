package dev.marregui.comando.core.console;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [Console] retains command output up to its cap and restores the final retained tail when sealed.
///
/// - overflow announces held-back output before completion
/// - sealing leaves uncapped output unchanged
class ConsoleTest {

    /// Output above `MAX_CHARS` holds back middle text, then sealing appends the last `TAIL_CHARS` so the failure and
    /// exit lines remain visible.
    @Test
    void outputPastTheCapKeepsItsEndForTheSeal() {
        Console c = new Console();
        String line = "x".repeat(1023) + "\n";
        for (int i = 0; i < Console.MAX_CHARS / 1024 + 200; i++) {
            c.append(line);
        }
        c.append("BUILD FAILED\n");
        assertThat(c.text(0)).doesNotContain("BUILD FAILED").contains("held back");
        c.seal();
        c.append("Exit code 1\n");
        String all = c.text(0);
        assertThat(all).endsWith("BUILD FAILED\nExit code 1\n").contains("characters not shown");
        assertThat(all.length()).isLessThan(Console.MAX_CHARS + Console.TAIL_CHARS + 1_000);
    }

    /// Sealing an output shorter than `MAX_CHARS` neither adds a truncation marker nor changes its existing text.
    @Test
    void sealWithoutOverflowChangesNothing() {
        Console c = new Console();
        c.append("ok\n");
        c.seal();
        assertThat(c.text(0)).isEqualTo("ok\n");
    }
}
