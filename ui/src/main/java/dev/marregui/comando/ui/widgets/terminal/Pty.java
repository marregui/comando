package dev.marregui.comando.ui.widgets.terminal;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.ProcessTtyConnector;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A process on a pseudo-terminal, connected to the terminal emulator. */
final class Pty extends ProcessTtyConnector {

    private final PtyProcess process;
    /** When the shell last wrote, from {@link System#nanoTime()}; {@link InputGate#NO_OUTPUT} before its first output. */
    private volatile long lastOutputNanos = InputGate.NO_OUTPUT;

    private Pty(PtyProcess process, List<String> command) {
        super(process, StandardCharsets.UTF_8, command);
        this.process = process;
    }

    /**
     * Loads pty4j's native code, which its first use otherwise loads on the EDT that opens a terminal (over
     * 200 ms on macOS: JNA's dispatch library and pty4j's own). Call off the EDT; a failure is left for the
     * first terminal to report.
     */
    static void preload() {
        try {
            Class.forName("com.pty4j.unix.PtyHelpers", true, Pty.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            System.getLogger(Pty.class.getName()).log(System.Logger.Level.DEBUG, "pty4j preload skipped: " + e);
        }
    }

    /** The user's login shell: {@code $SHELL -l}, so an app started from Finder gets the user's PATH. */
    static List<String> loginShell() {
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isBlank() || !Files.isExecutable(Path.of(shell))) {
            shell = Files.isExecutable(Path.of("/bin/zsh")) ? "/bin/zsh" : "/bin/sh";
        }
        return List.of(shell, "-l");
    }

    static Map<String, String> environment() {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.putIfAbsent("LANG", "en_US.UTF-8");
        env.put("TERM_PROGRAM", "comando");
        return env;
    }

    static Pty start(List<String> command, Path directory, int columns, int rows) throws IOException {
        PtyProcess p = new PtyProcessBuilder(command.toArray(String[]::new))
                .setDirectory(directory.toString())
                .setEnvironment(environment())
                .setInitialColumns(columns)
                .setInitialRows(rows)
                .setRedirectErrorStream(true)
                .start();
        return new Pty(p, command);
    }

    /** Read by the emulator's thread; records when output arrives, so input can wait for the shell to settle. */
    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        int n = super.read(buf, offset, length);
        if (n > 0) {
            lastOutputNanos = System.nanoTime();
        }
        return n;
    }

    long lastOutputNanos() {
        return lastOutputNanos;
    }

    @Override
    public void resize(TermSize size) {
        if (process.isAlive()) {
            process.setWinSize(new WinSize(size.getColumns(), size.getRows()));
        }
    }

    @Override
    public String getName() {
        return "shell";
    }

    PtyProcess process() {
        return process;
    }
}
