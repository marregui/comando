package dev.marregui.comando.core.loop;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs a program with a time limit and collects what it writes: the one way comando runs a short command. Standard
 * output and standard error go to temporary files, and standard input comes from one, so no thread reads or writes a
 * pipe while the program runs: a pipe read holds its carrier even on a virtual thread ({@code docs/architecture.md} §6,
 * K3), and a program that writes more than a pipe holds still never blocks. Each output keeps at most its limit when
 * read back. Past the time limit, or when the caller is interrupted, the program and every process it started are ended
 * forcibly, children first. The files are deleted before the call returns.
 */
public final class Processes {

    /** How long an ended program is given to be gone. */
    static final long END_MILLIS = 1_000;

    /**
     * What a run gave.
     *
     * @param exitCode the program's exit code; -1 when it was ended for its time limit
     * @param stdout   standard output, at most {@code maxOut} bytes
     * @param stderr   standard error as UTF-8 text, at most its limit of bytes; a longer one loses its end silently
     * @param timedOut true when the program was ended for its time limit
     * @param cut      true when standard output was longer than {@code maxOut} and its end dropped
     */
    public record Result(int exitCode, byte[] stdout, String stderr, boolean timedOut, boolean cut) {

        /** Standard output as UTF-8 text. */
        public String out() {
            return new String(stdout, StandardCharsets.UTF_8);
        }
    }

    private Processes() {
    }

    /** Runs {@code argv} in the current folder with comando's environment and no input. */
    public static Result run(List<String> argv, Duration timeout, int maxOut) throws IOException, InterruptedException {
        return run(argv, null, null, null, timeout, maxOut);
    }

    /**
     * Runs {@code argv}; waits at most {@code timeout}.
     *
     * @param environment changes to the inherited environment, or null for none
     * @param folder      the working folder, or null for the current one
     * @param stdin       the bytes standard input gets before it is closed, or null for none
     * @throws IOException          when the program cannot be started
     * @throws InterruptedException when the caller is interrupted; the program has been ended by then
     */
    public static Result run(List<String> argv, Consumer<Map<String, String>> environment, Path folder, byte[] stdin,
                             Duration timeout, int maxOut) throws IOException, InterruptedException {
        return run(argv, environment, folder, stdin, timeout, maxOut, maxOut, null);
    }

    /**
     * As {@link #run(List, Consumer, Path, byte[], Duration, int)}, standard error kept up to {@code maxErr} bytes, and
     * {@code started}, when not null, told the process once it runs, so that its owner can end it on closing.
     */
    public static Result run(List<String> argv, Consumer<Map<String, String>> environment, Path folder, byte[] stdin,
                             Duration timeout, int maxOut, int maxErr, Consumer<Process> started)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(argv);
        if (environment != null) {
            environment.accept(pb.environment());
        }
        if (folder != null) {
            pb.directory(folder.toFile());
        }
        Path out = Files.createTempFile("comando-out-", ".tmp");
        Path err = null;
        Path in = null;
        try {
            err = Files.createTempFile("comando-err-", ".tmp");
            pb.redirectOutput(out.toFile()).redirectError(err.toFile());
            if (stdin != null) {
                in = Files.createTempFile("comando-in-", ".tmp");
                Files.write(in, stdin);
                pb.redirectInput(in.toFile());
            }
            Process p = pb.start();
            if (stdin == null) {
                p.getOutputStream().close(); // no input: the program sees its end at once
            }
            if (started != null) {
                started.accept(p);
            }
            boolean ended;
            try {
                ended = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                end(p);
                throw e;
            } finally {
                if (p.isAlive()) {
                    end(p);
                }
            }
            byte[] stdout = head(out, maxOut);
            boolean cut = Files.size(out) > maxOut;
            return new Result(ended ? p.exitValue() : -1, stdout, new String(head(err, maxErr), StandardCharsets.UTF_8), !ended, cut);
        } finally {
            deleteQuietly(out);
            deleteQuietly(err);
            deleteQuietly(in);
        }
    }

    /** Ends {@code p} and every process it started, children first; waits a moment for it to be gone. */
    static void end(Process p) throws InterruptedException {
        // only while it lives: the JDK finds descendants by pid, and an ended process's pid may be another's
        if (p.isAlive()) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
        }
        p.destroyForcibly();
        p.waitFor(END_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** The first {@code max} bytes of {@code file}. */
    private static byte[] head(Path file, int max) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(max);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // a temporary file left behind; the system cleans its temporary folder
        }
    }
}
