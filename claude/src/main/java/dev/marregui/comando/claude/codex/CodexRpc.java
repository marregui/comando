package dev.marregui.comando.claude.codex;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.files.OwnerOnly;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.json.ObjectNode;
import dev.marregui.comando.core.loop.Errands;

/**
 * One {@code codex app-server} process and its JSON-RPC over stdio: one message per line, without the {@code jsonrpc}
 * field (ADR 0197). A request's response is matched by its id; an error response completes the request's future
 * exceptionally with a {@link Failure}. Notifications and the server's own requests (a message with both an id and a
 * method) go to the {@link Listener} on the reader, one at a time, in the order the server wrote them; a server request
 * is answered with what the listener returns, or with {@code {"decision":"decline"}}. The process's exit reaches the
 * listener once, after the lines it wrote before it, or from {@link #close}.
 */
public final class CodexRpc implements AutoCloseable {

    /** How long {@link #close} waits for the server to exit on its closed input before it kills the tree; a choice. */
    static final long CLOSE_WAIT_MILLIS = 2_000;
    private static final System.Logger LOG = System.getLogger(CodexRpc.class.getName());
    /** How long the exit waits for the rest of stdout after the process exited, as the Claude process waits. */
    private static final long DRAIN_MILLIS = 2_000;
    private static final int STDERR_TAIL_BYTES = 16 * 1024;
    private static final int STDERR_LINES = 20;

    private final String name;
    private final Process process;
    private final Path stderrFile;
    private final Listener listener;
    private final Errands errands;
    /** The server's stdin; written under {@link #writeLock}, so two messages never interleave. */
    private final OutputStream stdin;
    private final Object writeLock = new Object();
    private final AtomicLong ids = new AtomicLong();
    /** Requests sent and not answered yet, by id; any thread adds, the reader and the exit remove. */
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean exitReported = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private CodexRpc(final String name, final Process process, final Path stderrFile, final Listener listener) {
        this.name = name;
        this.process = process;
        this.stderrFile = stderrFile;
        this.listener = listener;
        this.errands = new Errands("codex-rpc-" + name);
        this.stdin = process.getOutputStream();
    }

    /**
     * Starts {@code argv} in {@code workDir}; its stdout reader and exit waiter run as errands of their own, closed by
     * {@link #close}. Throws when the process cannot start.
     */
    public static CodexRpc start(
            final String name,
            final List<String> argv,
            final Path workDir,
            final Listener listener
    ) throws IOException {
        final Path stderrFile = OwnerOnly.tempFile("comando-codex-", ".stderr");
        final Process process;
        try {
            process = new ProcessBuilder(argv).directory(workDir.toFile()).redirectError(stderrFile.toFile())
                    .start();
        } catch (final IOException | RuntimeException e) {
            Files.deleteIfExists(stderrFile);
            throw e;
        }
        final CodexRpc rpc = new CodexRpc(name, process, stderrFile, listener);
        final Thread reader = rpc.errands.start("codex-out-" + name, rpc::readStdout);
        if (reader == null || rpc.errands.start("codex-exit-" + name, () -> rpc.awaitExit(reader)) == null) {
            rpc.close();
            throw new IOException("cannot start the reader of 'codex app-server' [session=" + name + ']');
        }
        return rpc;
    }

    /** Sends {@code method}; the future completes with the response's result, or with a {@link Failure}. */
    public CompletableFuture<ObjectNode> request(final String method, final ObjectNode params) {
        final CompletableFuture<ObjectNode> future = new CompletableFuture<>();
        if (closed.get() || exitReported.get()) {
            future.completeExceptionally(new Failure(method, -1, "codex app-server is not running"));
            return future;
        }
        final long id = ids.incrementAndGet();
        pending.put(id, new Pending(method, future));
        // an exit reported between the check and the put has already failed what was pending: this one too
        if (exitReported.get() && pending.remove(id) != null) {
            future.completeExceptionally(new Failure(method, -1, "codex app-server exited"));
            return future;
        }
        final ObjectNode message = Json.object();
        message.put("id", id);
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        if (!write(message) && pending.remove(id) != null) {
            future.completeExceptionally(new Failure(method, -1, "cannot write to codex app-server"));
        }
        return future;
    }

    /** Sends the notification {@code method}; {@code params} may be null. False when the server's input is closed. */
    public boolean notify(final String method, final ObjectNode params) {
        final ObjectNode message = Json.object();
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        return write(message);
    }

    public boolean isAlive() {
        return !closed.get() && process.isAlive();
    }

    public long pid() {
        return process.pid();
    }

    /**
     * Closes the server's input, waits up to {@link #CLOSE_WAIT_MILLIS} for it to exit, then kills what is left of its
     * process tree, a child that outlived it included; reports the exit when the exit waiter has not. Blocks up to a
     * few seconds; not to be called from the listener. Idempotent.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // before the input closes: once the server has exited its children are no longer its descendants
        final List<ProcessHandle> tree = new ArrayList<>();
        if (process.isAlive()) {
            process.descendants().forEach(tree::add);
        }
        try {
            stdin.close();
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "closing codex stdin failed [session=" + name + ", cause="
                    + e.getMessage() + ']', e);
        }
        boolean interrupted = false;
        try {
            process.waitFor(CLOSE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            interrupted = true;
        }
        for (final ProcessHandle h : tree) {
            if (h.isAlive()) {
                h.destroyForcibly();
            }
        }
        if (process.isAlive()) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            try {
                process.waitFor(CLOSE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (final InterruptedException e) {
                interrupted = true;
            }
        }
        errands.close();
        reportExit(process.isAlive() ? -1 : process.exitValue());
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static ObjectNode params(final ObjectNode message) {
        return message.path("params") instanceof ObjectNode p ? p : Json.object();
    }

    private boolean write(final ObjectNode message) {
        final byte[] line = (message.toString() + '\n').getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            try {
                stdin.write(line);
                stdin.flush();
                return true;
            } catch (final IOException e) {
                LOG.log(System.Logger.Level.DEBUG, "cannot write to codex app-server [session=" + name + ", cause="
                        + e.getMessage() + ']', e);
                return false;
            }
        }
    }

    private void readStdout() {
        try (final BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(),
                StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isBlank()) {
                    accept(line);
                }
            }
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "codex stdout closed [session=" + name + ", cause=" + e.getMessage()
                    + ']', e);
        }
    }

    private void accept(final String line) {
        if (!(Json.tryReadTree(line) instanceof ObjectNode message)) {
            LOG.log(System.Logger.Level.DEBUG, "not a JSON-RPC message, skipped [session=" + name + ']');
            return;
        }
        final JsonNode id = message.path("id");
        final String method = message.path("method").asString("");
        final boolean hasId = !id.isMissingNode() && !id.isNull();
        try {
            if (!method.isEmpty() && hasId) {
                answer(id, method, params(message));
            } else if (!method.isEmpty()) {
                listener.notification(method, params(message));
            } else if (id.isIntegralNumber()) {
                respond(id.asLong(), message);
            }
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "codex message handling failed [session=" + name + ", method=" + method
                    + ']', e);
        }
    }

    private void answer(final JsonNode id, final String method, final ObjectNode params) {
        ObjectNode result = listener.request(method, params);
        if (result == null) {
            result = Json.object().put("decision", "decline");
        }
        final ObjectNode response = Json.object();
        response.set("id", id);
        response.set("result", result);
        write(response);
    }

    private void respond(final long id, final ObjectNode message) {
        final Pending p = pending.remove(id);
        if (p == null) {
            LOG.log(System.Logger.Level.DEBUG, "response to no pending request [session=" + name + ", id=" + id + ']');
            return;
        }
        final JsonNode error = message.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            p.future().completeExceptionally(new Failure(p.method(), error.path("code").asInt(0),
                    error.path("message").asString("error")));
            return;
        }
        p.future().complete(message.path("result") instanceof ObjectNode r ? r : Json.object());
    }

    private void awaitExit(final Thread reader) {
        try {
            final int code = process.waitFor();
            // the lines written before the exit reach the listener before the exit does
            reader.join(Duration.ofMillis(DRAIN_MILLIS));
            reportExit(code);
        } catch (final InterruptedException e) {
            // close interrupted the wait: it reports the exit itself
            Thread.currentThread().interrupt();
        }
    }

    private void reportExit(final int code) {
        if (!exitReported.compareAndSet(false, true)) {
            return;
        }
        for (final Long id : List.copyOf(pending.keySet())) {
            final Pending p = pending.remove(id);
            if (p != null) {
                p.future().completeExceptionally(new Failure(p.method(), -1, "codex app-server exited"));
            }
        }
        final String error = stderrTail();
        try {
            listener.exited(code, error);
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "codex exit handling failed [session=" + name + ']', e);
        }
    }

    /** The last lines of stderr, read from the end of its file, which is then deleted. */
    private String stderrTail() {
        String tail = "";
        try {
            tail = Mapped.read(stderrFile, f -> {
                final long from = Math.max(0L, f.size() - STDERR_TAIL_BYTES);
                List<String> lines = f.text(from, f.size()).lines().toList();
                if (from > 0L && !lines.isEmpty()) {
                    lines = lines.subList(1, lines.size()); // a tail cut mid-file starts with a partial line
                }
                return String.join("\n", lines.subList(Math.max(0, lines.size() - STDERR_LINES), lines.size()));
            });
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "cannot read codex stderr [session=" + name + ']', e);
        }
        try {
            Files.deleteIfExists(stderrFile);
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "cannot delete [file=" + stderrFile + ']', e);
        }
        return tail;
    }

    /** What the server sends that is not a response, and its exit. Called on the reader, one call at a time. */
    public interface Listener {

        void notification(String method, ObjectNode params);

        /** The server asks {@code method}; the result to answer with, or null to decline. */
        ObjectNode request(String method, ObjectNode params);

        /** The process ended with {@code code} (-1 when unknown); {@code error} is its stderr's last lines. */
        void exited(int code, String error);
    }

    /** An error response, or a request that the server can no longer answer. */
    public static final class Failure extends RuntimeException {

        private final int code;

        Failure(final String method, final int code, final String message) {
            super(message + " [method=" + method + ", code=" + code + ']');
            this.code = code;
        }

        /** The JSON-RPC error code; -1 when the server did not answer. */
        public int code() {
            return code;
        }
    }

    private record Pending(String method, CompletableFuture<ObjectNode> future) {
    }
}
