package dev.marregui.comando.ui.widgets.terminal;

import com.jediterm.core.util.TermSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// [Pty] runs a process on a pseudo-terminal.
/// - the process runs in the given directory on a tty with `TERM=xterm-256color` and sees resizes
/// - reading output records when the shell last wrote
/// - the login shell is an executable run with `-l`
class PtyTest {

    /// The child prints the real path of its directory, finds stdout a tty, sees `xterm-256color`, and reports the resized
    /// 50 rows by 132 columns.
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
            } catch (IOException e) {
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

    /// Before any read the last output time is [InputGate#NO_OUTPUT]; a read that returns bytes sets it to no earlier
    /// than the read began.
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

    /// [Pty#loginShell] is an executable path followed by `-l`.
    @Test
    void loginShellIsExecutable() {
        assertThat(Pty.loginShell()).hasSize(2).last().isEqualTo("-l");
        assertThat(Path.of(Pty.loginShell().getFirst())).isExecutable();
    }
}
