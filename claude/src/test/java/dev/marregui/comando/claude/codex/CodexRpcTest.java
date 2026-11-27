package dev.marregui.comando.claude.codex;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [CodexRpc] speaks newline JSON-RPC with `fake-codex.sh` standing in for `codex app-server`.
///
/// - Matches responses to requests by id; an error response fails the request with its code.
/// - Delivers notifications and the server's requests to the listener, answering a request with its result.
/// - Reports the exit once, failing what is pending, whether the server dies or is closed.
class CodexRpcTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private final Recorder listener = new Recorder();
    @TempDir
    Path tmp;
    private Path fake;
    private Path log;

    @BeforeEach
    void setUp() throws IOException {
        fake = tmp.resolve("fake-codex.sh");
        try (final InputStream in = getClass().getResourceAsStream("/fake-codex.sh")) {
            Files.write(fake, in.readAllBytes());
        }
        assertThat(fake.toFile().setExecutable(true)).isTrue();
        log = tmp.resolve("received.jsonl");
    }

    /// A response completes its own request; an error response fails it with the server's code.
    @Test
    void responsesMatchTheirRequests() throws Exception {
        try (final CodexRpc rpc = start("error")) {
            final CompletableFuture<ObjectNode> init = rpc.request("initialize", Json.object());
            final CompletableFuture<ObjectNode> thread = rpc.request("thread/start", Json.object().put("cwd", "/"));

            assertThat(get(init).path("userAgent").asString()).startsWith("comando/0.172.0");
            assertThatThrownBy(() -> get(thread)).isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOfSatisfying(CodexRpc.Failure.class, f -> assertThat(f.code()).isEqualTo(-32600));
        }
    }

    /// Notifications reach the listener in order; a server request is answered with what the listener returns, and
    /// the message carries no `jsonrpc` field.
    @Test
    void theServerIsHeardAndAnswered() throws Exception {
        listener.answer = Json.object().put("decision", "accept");
        try (final CodexRpc rpc = start("request")) {
            get(rpc.request("initialize", Json.object()));
            assertThat(rpc.notify("initialized", null)).isTrue();
            get(rpc.request("turn/start", Json.object().put("threadId", "thr-1")));

            Awaitility.await().atMost(WAIT).until(() -> listener.calls.contains("notification turn/completed"));
            assertThat(listener.calls).containsSubsequence("notification turn/started",
                    "request item/commandExecution/requestApproval", "notification turn/completed");
            final List<String> lines = Mapped.lines(log);
            assertThat(lines).contains("{\"method\":\"initialized\"}");
            assertThat(lines).contains("{\"id\":\"srv-1\",\"result\":{\"decision\":\"accept\"}}");
            assertThat(lines).noneMatch(l -> l.contains("jsonrpc"));
        }
    }

    /// A server that dies fails what is pending and reports its code and stderr once, a later close included; a
    /// request after the exit fails at once.
    @Test
    void anExitIsReportedOnce() throws Exception {
        final CodexRpc rpc = start("crash");
        get(rpc.request("initialize", Json.object()));
        final CompletableFuture<ObjectNode> turn = rpc.request("turn/start", Json.object());

        assertThatThrownBy(() -> get(turn)).cause().isInstanceOfSatisfying(CodexRpc.Failure.class,
                f -> assertThat(f.code()).isEqualTo(-1));
        Awaitility.await().atMost(WAIT).until(() -> listener.calls.size() >= 1 && !rpc.isAlive());
        rpc.close();
        assertThat(listener.calls).containsExactly("exited 3 fake codex crashed");
        assertThat(rpc.request("initialize", Json.object())).isCompletedExceptionally();
    }

    /// Close ends a server that is waiting for input, reports its exit once, and leaves its input closed.
    @Test
    void closeEndsTheServer() throws Exception {
        final CodexRpc rpc = start("");
        get(rpc.request("initialize", Json.object()));
        final long pid = rpc.pid();

        rpc.close();
        rpc.close();
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        assertThat(rpc.isAlive()).isFalse();
        assertThat(rpc.notify("initialized", null)).isFalse();
        assertThat(listener.calls).containsExactly("exited 0 ");
    }

    private static ObjectNode get(final CompletableFuture<ObjectNode> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private CodexRpc start(final String mode) throws IOException {
        return CodexRpc.start("s", List.of("/usr/bin/env", "FAKE_CODEX_MODE=" + mode, "FAKE_CODEX_LOG=" + log,
                fake.toString(), "app-server"), tmp, listener);
    }

    /// What the server sent the listener, one line each; answers a request with {@link #answer}.
    private static final class Recorder implements CodexRpc.Listener {
        private final List<String> calls = new CopyOnWriteArrayList<>();
        // written by the test thread before the server starts; read by the reader
        private volatile ObjectNode answer;

        @Override
        public void notification(final String method, final ObjectNode params) {
            calls.add("notification " + method);
        }

        @Override
        public ObjectNode request(final String method, final ObjectNode params) {
            calls.add("request " + method);
            return answer;
        }

        @Override
        public void exited(final int code, final String error) {
            calls.add("exited " + code + ' ' + error);
        }
    }
}
