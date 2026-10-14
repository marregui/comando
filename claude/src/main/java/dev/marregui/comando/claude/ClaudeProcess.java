package dev.marregui.comando.claude;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * One headless {@code claude -p} process and its three threads: stdout lines go to {@code onLine},
 * stdin lines come from a queue, stderr is kept as a tail for error messages. {@code onExit} runs after
 * the last stdout line was handed over.
 */
final class ClaudeProcess {

    private static final System.Logger LOG = System.getLogger(ClaudeProcess.class.getName());
    private static final String END = new String("end-of-input");
    private static final int STDERR_LINES = 40;

    private final Process process;
    private final LinkedBlockingQueue<String> stdin = new LinkedBlockingQueue<>();
    private final ArrayDeque<String> stderrTail = new ArrayDeque<>();

    private ClaudeProcess(Process process) {
        this.process = process;
    }

    static ClaudeProcess start(String name, List<String> argv, Path workDir, Map<String, String> env,
                               Consumer<String> onLine, IntConsumer onExit) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(argv).directory(workDir.toFile());
        pb.environment().putAll(env);
        ClaudeProcess p = new ClaudeProcess(pb.start());
        Thread reader = Thread.ofPlatform().name("claude-out-" + name).daemon(true).start(() -> p.readStdout(onLine));
        Thread.ofPlatform().name("claude-in-" + name).daemon(true).start(p::writeStdin);
        Thread.ofPlatform().name("claude-err-" + name).daemon(true).start(p::readStderr);
        Thread.ofPlatform().name("claude-exit-" + name).daemon(true).start(() -> {
            int code;
            try {
                code = p.process.waitFor();
                reader.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            onExit.accept(code);
        });
        return p;
    }

    private void readStdout(Consumer<String> onLine) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isBlank()) {
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

    private void readStderr() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                synchronized (stderrTail) {
                    if (stderrTail.size() == STDERR_LINES) {
                        stderrTail.removeFirst();
                    }
                    stderrTail.addLast(line);
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "stderr closed: " + e.getMessage());
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

    String stderrTail() {
        synchronized (stderrTail) {
            return String.join("\n", stderrTail);
        }
    }
}
