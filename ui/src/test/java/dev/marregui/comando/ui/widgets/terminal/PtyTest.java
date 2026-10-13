package dev.marregui.comando.ui.widgets.terminal;

import com.jediterm.core.util.TermSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PtyTest {

    @Test
    void runsInTheDirectoryOnATerminalAndResizes(@TempDir Path dir) throws Exception {
        Pty pty = Pty.start(List.of("/bin/sh", "-c", "pwd; [ -t 1 ] && echo tty; echo $TERM; sleep 1; stty size"), dir, 100, 40);
        pty.resize(new TermSize(132, 50));
        StringBuilder out = new StringBuilder();
        char[] buf = new char[1024];
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            int n;
            try {
                n = pty.read(buf, 0, buf.length);
            } catch (java.io.IOException e) {
                break; // the pty closes when the process ends
            }
            if (n < 0) {
                break;
            }
            out.append(buf, 0, n);
            if (out.toString().contains("50 132")) {
                break;
            }
        }
        assertThat(pty.process().waitFor(5, TimeUnit.SECONDS)).isTrue();
        String text = out.toString();
        assertThat(text).contains(dir.toRealPath().toString()).contains("tty").contains("xterm-256color").contains("50 132");
    }

    @Test
    void readingOutputRecordsWhenTheShellLastWrote(@TempDir Path dir) throws Exception {
        Pty pty = Pty.start(List.of("/bin/sh", "-c", "echo ready; sleep 1"), dir, 80, 24);
        try {
            assertThat(pty.lastOutputNanos()).isEqualTo(InputGate.NO_OUTPUT);
            long before = System.nanoTime();
            char[] buf = new char[256];
            int n = pty.read(buf, 0, buf.length);
            assertThat(n).isPositive();
            assertThat(pty.lastOutputNanos()).isGreaterThanOrEqualTo(before);
        } finally {
            pty.process().destroy();
        }
    }

    @Test
    void loginShellIsExecutable() {
        assertThat(Pty.loginShell()).hasSize(2).last().isEqualTo("-l");
        assertThat(Path.of(Pty.loginShell().getFirst())).isExecutable();
    }
}
