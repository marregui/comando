package dev.marregui.comando.ui.widgets.terminal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.ProcessTtyConnector;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

/** A process on a pseudo-terminal, connected to the terminal emulator. */
final class Pty extends ProcessTtyConnector {

    private final PtyProcess process;
    /** When the shell last wrote, by {@link System#nanoTime()}; {@link InputGate#NO_OUTPUT} before its first output. */
    private volatile long lastOutputNanos = InputGate.NO_OUTPUT;

    private Pty(final PtyProcess process, final List<String> command) {
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
        final Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.putIfAbsent("LANG", "en_US.UTF-8");
        env.put("TERM_PROGRAM", "comando");
        return env;
    }

    static Pty start(final List<String> command, final Path directory, final int columns, final int rows) throws IOException {
        final PtyProcess p = new PtyProcessBuilder(command.toArray(String[]::new))
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
    public int read(final char[] buf, final int offset, final int length) throws IOException {
        final int n = super.read(buf, offset, length);
        if (n > 0) {
            lastOutputNanos = System.nanoTime();
        }
        return n;
    }

    long lastOutputNanos() {
        return lastOutputNanos;
    }

    @Override
    public void resize(final TermSize size) {
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

    /**
     * SIGTERM to every process the shell started, children first, then to the shell; answers them. They are read
     * before the signals: once the shell is gone its children are no longer its descendants. pty4j's process has no
     * {@link Process#toHandle}, so they are read through its pid.
     */
    List<ProcessHandle> terminate() {
        // only while the shell lives: the JDK finds descendants by pid, and an ended shell's pid may be another's
        final List<ProcessHandle> tree = process.isAlive()
                ? ProcessHandle.of(process.pid()).map(h -> h.descendants().toList()).orElse(List.of())
                : List.of();
        tree.forEach(ProcessHandle::destroy);
        process.destroy();
        return tree;
    }

    /**
     * Waits until {@code deadlineNanos}, by {@link System#nanoTime()}, for the shell and {@code tree} to end, then sends
     * SIGKILL to what is left of them and of the shell's descendants: a job that ignores the hangup and the SIGTERM
     * (nohup, a trap, a stuck ssh). Interrupted, it kills at once.
     */
    void killRemaining(final List<ProcessHandle> tree, final long deadlineNanos) throws InterruptedException {
        try {
            process.waitFor(Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            for (final ProcessHandle h : tree) {
                try {
                    h.onExit().get(Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (final ExecutionException | TimeoutException e) {
                    // still running at the deadline: killed below
                }
            }
        } finally {
            for (final ProcessHandle h : tree) {
                if (h.isAlive()) {
                    h.destroyForcibly();
                }
            }
            // only while the shell lives: the JDK reads descendants by pid, and an ended shell's pid may be reused
            if (process.isAlive()) {
                ProcessHandle.of(process.pid()).ifPresent(h -> h.descendants().forEach(ProcessHandle::destroyForcibly));
                process.destroyForcibly();
            }
        }
    }
}
