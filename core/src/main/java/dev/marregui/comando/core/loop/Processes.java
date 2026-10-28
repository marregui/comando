package dev.marregui.comando.core.loop;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs a program with a time limit and collects what it writes: the one way comando runs a short command. Standard
 * output and standard error are read while the program runs, each on a fork of its own ({@link Threads#fork}), so a
 * program that writes more than a pipe holds never blocks; each keeps at most its limit and reads and drops the rest. Standard input gets the bytes given, from a fork too, then is closed. Past the time limit, or when the
 * caller is interrupted, the program and every process it started are ended forcibly, children first.
 */
public final class Processes {

    /** How long the readers may take to reach the end of the output once the program has ended. */
    static final long DRAIN_MILLIS = 1_000;

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
        Process p = pb.start();
        if (started != null) {
            started.accept(p);
        }
        Drain out = new Drain(p.getInputStream(), maxOut);
        Drain err = new Drain(p.getErrorStream(), maxErr);
        Thread outReader = Threads.fork("process-out", out);
        Thread errReader = Threads.fork("process-err", err);
        Thread inWriter = Threads.fork("process-in", () -> feed(p.getOutputStream(), stdin));
        try {
            boolean ended = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!ended) {
                end(p);
            }
            finish(outReader, p.getInputStream());
            finish(errReader, p.getErrorStream());
            inWriter.join(DRAIN_MILLIS);
            return new Result(ended ? p.exitValue() : -1, out.bytes(), new String(err.bytes(), StandardCharsets.UTF_8), !ended, out.cut);
        } catch (InterruptedException e) {
            end(p);
            throw e;
        } finally {
            if (p.isAlive()) {
                end(p);
            }
        }
    }

    /** Ends {@code p} and every process it started, children first; waits a moment for it to be gone. */
    static void end(Process p) throws InterruptedException {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        p.waitFor(DRAIN_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Lets {@code reader} reach the end of its output; a child still holding the pipe open is cut off by closing it. */
    private static void finish(Thread reader, InputStream stream) throws InterruptedException {
        reader.join(DRAIN_MILLIS);
        if (reader.isAlive()) {
            try {
                stream.close();
            } catch (IOException e) {
                // closing to end the read: nothing more to do
            }
            reader.join(DRAIN_MILLIS);
        }
    }

    private static void feed(OutputStream in, byte[] bytes) {
        try (in) {
            if (bytes != null) {
                in.write(bytes);
            }
        } catch (IOException e) {
            // the program closed its input or ended before reading all of it
        }
    }

    /** Reads a stream to its end, keeping its first {@code max} bytes. */
    private static final class Drain implements Runnable {

        private final InputStream in;
        private final int max;
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private volatile boolean cut;

        Drain(InputStream in, int max) {
            this.in = in;
            this.max = max;
        }

        @Override
        public void run() {
            byte[] buf = new byte[8_192];
            try (in) {
                for (int n; (n = in.read(buf)) >= 0; ) {
                    synchronized (kept) {
                        int keep = Math.min(n, max - kept.size());
                        if (keep > 0) {
                            kept.write(buf, 0, keep);
                        }
                        if (keep < n) {
                            cut = true;
                        }
                    }
                }
            } catch (IOException e) {
                // the stream was closed to end the read, or the program's end broke the pipe
            }
        }

        byte[] bytes() {
            synchronized (kept) {
                return kept.toByteArray();
            }
        }
    }
}
