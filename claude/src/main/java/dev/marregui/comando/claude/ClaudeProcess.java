package dev.marregui.comando.claude;

import dev.marregui.comando.core.loop.Threads;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * One headless {@code claude -p} process: stdout lines go to {@code onLine}, stdin lines come from a queue, stderr goes to
 * a file whose last lines are kept for the error message. {@code onExit} runs after the last stdout line was handed over,
 * or {@link #DRAIN_MILLIS} after the process exited when a child of it still holds stdout open; lines after that are
 * dropped.
 *
 * <p>Threads: stdout is read on a platform thread, the one operating-system thread a session costs: a read of a process
 * pipe holds its thread even from a virtual thread (measured on JDK 25). The stdin writer waits on its queue and the exit
 * on the process, both on virtual threads, which hold no operating-system thread while they wait. stderr needs no
 * thread: the file is read once, at the exit, and deleted.</p>
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
    /** Where stderr goes while the process runs; read and deleted at the exit. */
    private final Path stderrFile;
    /** The last {@link #STDERR_LINES} lines of stderr, read at the exit. */
    private volatile String stderrTail = "";
    /** Set when the exit stopped waiting for stdout: a line read later is not handed over. Guarded by {@link #handOver}. */
    private boolean drained;
    /** Held while a line is handed over and while {@link #drained} is set, so no line is handed over after the exit. */
    private final Object handOver = new Object();

    private ClaudeProcess(Process process, Path stderrFile) {
        this.process = process;
        this.stderrFile = stderrFile;
    }

    static ClaudeProcess start(String name, List<String> argv, Path workDir, RootLaunch launch,
                               Consumer<String> onLine, IntConsumer onExit) throws IOException {
        Path stderrFile = Files.createTempFile("comando-claude-", ".stderr",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).directory(workDir.toFile()).redirectError(stderrFile.toFile());
            launch.environment(pb.environment());
            process = pb.start();
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(stderrFile);
            throw e;
        }
        ClaudeProcess p = new ClaudeProcess(process, stderrFile);
        Thread reader = Thread.ofPlatform().name("claude-out-" + name).daemon(true).start(() -> p.readStdout(onLine));
        Threads.fork("claude-in-" + name, p::writeStdin);
        Threads.fork("claude-exit-" + name, () -> {
            int code;
            try {
                code = p.process.waitFor();
                if (!reader.join(Duration.ofMillis(DRAIN_MILLIS))) {
                    // under the hand-over's lock: a line being handed over finishes first, and none follows the exit
                    synchronized (p.handOver) {
                        p.drained = true;
                    }
                    LOG.log(System.Logger.Level.WARNING, "claude exited but a child still holds its output open; stopped reading it [session="
                            + name + ", exit=" + code + ']');
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.dropStderr();
                return;
            }
            p.readStderrTail();
            onExit.accept(code);
        });
        return p;
    }

    private void readStdout(Consumer<String> onLine) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                synchronized (handOver) {
                    if (drained) {
                        return;
                    }
                    onLine.accept(line);
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "stdout closed: " + e.getMessage());
        }
    }

    private void writeStdin() {
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            while (true) {
                String line = stdin.take();
                if (line == END) {
                    return;
                }
                out.write(line);
                out.write('\n');
                out.flush();
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "stdin closed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Keeps the last lines of stderr, read from the end of its file, and deletes the file. */
    private void readStderrTail() {
        try (RandomAccessFile f = new RandomAccessFile(stderrFile.toFile(), "r")) {
            long length = f.length();
            long from = Math.max(0L, length - STDERR_TAIL_BYTES);
            byte[] end = new byte[(int) (length - from)];
            f.seek(from);
            f.readFully(end);
            List<String> lines = new String(end, StandardCharsets.UTF_8).lines().toList();
            if (from > 0L && !lines.isEmpty()) {
                lines = lines.subList(1, lines.size()); // a tail cut mid-file starts with a partial line
            }
            stderrTail = String.join("\n", lines.subList(Math.max(0, lines.size() - STDERR_LINES), lines.size()));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "cannot read stderr: " + e.getMessage());
        }
        dropStderr();
    }

    private void dropStderr() {
        try {
            Files.deleteIfExists(stderrFile);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "cannot delete " + stderrFile + ": " + e.getMessage());
        }
    }

    void send(String line) {
        stdin.add(line);
    }

    /** Ends input: Claude Code finishes the turn in progress and exits. */
    void closeInput() {
        stdin.add(END);
    }

    boolean isAlive() {
        return process.isAlive();
    }

    long pid() {
        return process.pid();
    }

    /** SIGTERM; Claude Code stops the turn, runs SessionEnd hooks and exits. */
    void terminate() {
        process.destroy();
    }

    void kill() {
        process.destroyForcibly();
    }

    /** Waits up to {@code millis} for the process to exit; true when it has. */
    boolean awaitExit(long millis) {
        try {
            return process.waitFor(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
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
