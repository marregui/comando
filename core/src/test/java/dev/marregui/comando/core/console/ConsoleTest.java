package dev.marregui.comando.core.console;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleTest {

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

    @Test
    void sealWithoutOverflowChangesNothing() {
        Console c = new Console();
        c.append("ok\n");
        c.seal();
        assertThat(c.text(0)).isEqualTo("ok\n");
    }
}
