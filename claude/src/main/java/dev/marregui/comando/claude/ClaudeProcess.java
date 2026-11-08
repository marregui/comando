package dev.marregui.comando.claude;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.files.OwnerOnly;
import dev.marregui.comando.core.loop.Errands;
import dev.marregui.comando.core.loop.Queues;

/**
 * One headless {@code claude -p} process: stdout lines go to {@code onLine}, stdin lines come from a queue, stderr goes
 * to a file whose last lines are kept for the error message. {@code onExit} runs after the last stdout line was handed
 * over, or {@link #DRAIN_MILLIS} after the process exited when a child of it still holds stdout open; lines after that
 * are dropped.
 *
 * <p>Threads: stdout is read on a platform thread, the one operating-system thread a session costs: a read of a process
 * pipe holds its thread even from a virtual thread (measured on JDK 25). The stdin writer waits on its queue and the
 * exit on the process, both errands of the owner's {@link Errands} (virtual threads, which hold no operating-system
 * thread while they wait), so the owner's close interrupts and collects them. stderr needs no thread: the file is read
 * once, at the exit, and deleted.</p>
 */
final class ClaudeProcess {

    private static final System.Logger LOG = System.getLogger(ClaudeProcess.class.getName());
    private static final String END = new String("end-of-input");
    private static final int STDERR_LINES = 40;
    /** How much of the end of stderr is read for its last lines. */
    private static final int STDERR_TAIL_BYTES = 64 * 1024;
    /** How long the exit waits for the rest of stdout after the process exited. */
    static final long DRAIN_MILLIS = 2_000;

    private final Process process;
    private final LinkedBlockingQueue<String> stdin = new LinkedBlockingQueue<>();
    /** The stdin writer has ended (the pipe closed, or its input did): no line is queued after. Guarded by {@link #stdin}. */
    private boolean writerGone;
    /** The stdin queue's name and its depth and peak, for the metrics log ({@link Queues}). */
    private final String stdinName;
    private final Queues.Gauge stdinQueued;
    /** Where stderr goes while the process runs; read and deleted at the exit. */
    private final Path stderrFile;
    /** The last {@link #STDERR_LINES} lines of stderr, read at the exit. */
    private volatile String stderrTail = "";
    /** Set when the exit stopped waiting for stdout: a line read later is not handed over. */
    private final AtomicBoolean drained = new AtomicBoolean();
    /**
     * Lines being handed over now: raised before the reader looks at {@link #drained}, so the exit, which sets it first,
     * waits for a hand-over already past the look and no line is handed over after the exit; no lock is held while the
     * caller's {@code onLine} runs.
     */
    private final AtomicInteger handing = new AtomicInteger();
    /**
     * The descendants recorded while Claude Code lived ({@link #recordTree}, {@link #terminate}), kept for
     * {@link #terminate} and {@link #kill}: once Claude Code exits they are no longer its descendants, and one that
     * ignored the SIGTERM would outlive comando. Guarded by {@link #treeLock}.
     */
    private List<ProcessHandle> tree = List.of();
    /** Guards {@link #tree}: the stopping thread records it, the timer's thread signals it. */
    private final Object treeLock = new Object();

    private ClaudeProcess(final Process process, final Path stderrFile, final String name) {
        this.process = process;
        this.stderrFile = stderrFile;
        this.stdinName = "claude-stdin-" + name;
        this.stdinQueued = Queues.gauge(stdinName);
    }

    /**
     * Starts the process; its stdin writer and its exit waiter run as {@code helpers}' errands. Throws when the process
     * cannot start, or when {@code helpers} has closed, the process then ended at once.
     */
    static ClaudeProcess start(final String name, final List<String> argv, final Path workDir, final RootLaunch launch,
                               final Errands helpers, final Consumer<String> onLine, final IntConsumer onExit) throws IOException {
        return start(name, argv, workDir, launch, helpers, onLine, onExit, Duration.ofMillis(DRAIN_MILLIS));
    }

    /**
     * As {@link #start(String, List, Path, RootLaunch, Errands, Consumer, IntConsumer)}, with {@code drain} in place of
     * {@link #DRAIN_MILLIS}: how long after the exit a child of the process may hold its stdout open.
     */
    static ClaudeProcess start(final String name, final List<String> argv, final Path workDir, final RootLaunch launch,
                               final Errands helpers, final Consumer<String> onLine, final IntConsumer onExit,
                               final Duration drain) throws IOException {
        final Path stderrFile = OwnerOnly.tempFile("comando-claude-", ".stderr");
        Process process;
        try {
            final ProcessBuilder pb = new ProcessBuilder(argv).directory(workDir.toFile()).redirectError(stderrFile.toFile());
            launch.environment(pb.environment());
            process = pb.start();
        } catch (final IOException | RuntimeException e) {
            Files.deleteIfExists(stderrFile);
            throw e;
        }
        final ClaudeProcess p = new ClaudeProcess(process, stderrFile, name);
        if (helpers.start("claude-in-" + name, p::writeStdin) == null) {
            p.kill();
            p.dropStderr();
            throw new IOException("comando is closing");
        }
        final Thread reader = Thread.ofPlatform().name("claude-out-" + name).daemon(true).start(() -> p.readStdout(onLine));
        final Thread exit = helpers.start("claude-exit-" + name, () -> {
            int code;
            try {
                code = p.process.waitFor();
                if (!reader.join(drain)) {
                    p.drained.set(true);
                    // a line already past its look at drained is handed over first, and none follows the exit
                    while (p.handing.get() > 0) {
                        Thread.sleep(1);
                    }
                    LOG.log(System.Logger.Level.WARNING, "claude exited but a child still holds its output open; stopped reading it [session="
                            + name + ", exit=" + code + ']');
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                p.dropStderr();
                return;
            }
            p.readStderrTail();
            onExit.accept(code);
        });
        if (exit == null) {
            // closed between the two errands: nothing will wait for the exit, so the process ends now
            p.kill();
            p.closeInput();
            throw new IOException("comando is closing");
        }
        return p;
    }

    private void readStdout(final Consumer<String> onLine) {
        try (final BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                handing.incrementAndGet();
                try {
                    if (drained.get()) {
                        return;
                    }
                    onLine.accept(line);
                } finally {
                    handing.decrementAndGet();
                }
            }
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "stdout closed [cause=" + e.getMessage() + ']');
        }
    }

    private void writeStdin() {
        try (final BufferedWriter out = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            while (true) {
                final String line = stdin.take();
                stdinQueued.removed();
                if (line == END) {
                    return;
                }
                out.write(line);
                out.write('\n');
                out.flush();
            }
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "stdin closed [cause=" + e.getMessage() + ']');
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // nothing writes the queue from now on: it takes no more lines, and its gauge goes with it
            synchronized (stdin) {
                writerGone = true;
                stdin.clear();
            }
            Queues.drop(stdinName, stdinQueued);
        }
    }

    /** Keeps the last lines of stderr, read from the end of its file, and deletes the file. */
    private void readStderrTail() {
        try {
            stderrTail = Mapped.read(stderrFile, f -> {
                final long from = Math.max(0L, f.size() - STDERR_TAIL_BYTES);
                List<String> lines = f.text(from, f.size()).lines().toList();
                if (from > 0L && !lines.isEmpty()) {
                    lines = lines.subList(1, lines.size()); // a tail cut mid-file starts with a partial line
                }
                return String.join("\n", lines.subList(Math.max(0, lines.size() - STDERR_LINES), lines.size()));
            });
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "cannot read stderr", e);
        }
        dropStderr();
    }

    private void dropStderr() {
        try {
            Files.deleteIfExists(stderrFile);
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "cannot delete [file=" + stderrFile + ']', e);
        }
    }

    /** Queues {@code line} for the process's stdin; false, the line dropped, once the writer has ended. */
    boolean send(final String line) {
        return queue(line);
    }

    /** Ends input: Claude Code finishes the turn in progress and exits. */
    void closeInput() {
        queue(END);
    }

    private boolean queue(final String line) {
        synchronized (stdin) {
            if (writerGone) {
                LOG.log(System.Logger.Level.DEBUG, "stdin closed, line dropped [queue=" + stdinName + ']');
                return false;
            }
            stdinQueued.added();
            stdin.add(line);
            return true;
        }
    }

    boolean isAlive() {
        return process.isAlive();
    }

    long pid() {
        return process.pid();
    }

    /**
     * SIGTERM to every process Claude Code started (MCP servers, a running command), children first, then to Claude
     * Code itself, which stops the turn, runs SessionEnd hooks and exits.
     */
    void terminate() {
        for (final ProcessHandle h : recordTree()) {
            if (h.isAlive()) {
                h.destroy();
            }
        }
        process.destroy();
    }

    /**
     * Adds every process Claude Code has started by now to the recorded tree and returns the tree; called before
     * Claude Code is asked to exit, as afterwards its children are no longer its descendants. Reads nothing once Claude
     * Code has ended: the JDK finds descendants by pid, and an ended process's pid may already be another's.
     */
    List<ProcessHandle> recordTree() {
        synchronized (treeLock) {
            if (process.isAlive()) {
                final List<ProcessHandle> now = new ArrayList<>(tree);
                process.descendants().filter(h -> !now.contains(h)).forEach(now::add);
                tree = List.copyOf(now);
            }
            return tree;
        }
    }

    /**
     * SIGKILL to what is left of the recorded tree, to every descendant now, then to Claude Code. Safe
     * after Claude Code has exited: the recorded handles carry their start time, and the
     * descendants are read only while Claude Code is alive, since the JDK reads them by pid and an ended process's pid
     * may already belong to another process.
     */
    void kill() {
        for (final ProcessHandle h : recordTree()) {
            if (h.isAlive()) {
                h.destroyForcibly();
            }
        }
        if (process.isAlive()) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    /** Waits up to {@code millis} for the process to exit; true when it has. */
    boolean awaitExit(final long millis) {
        try {
            return process.waitFor(millis, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    /** Where stderr goes while the process runs; for tests. */
    Path stderrFile() {
        return stderrFile;
    }

    /** The last lines of stderr; empty until the process has exited. */
    String stderrTail() {
        return stderrTail;
    }
}
