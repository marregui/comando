package dev.marregui.comando.mcp;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.McpGateway;
import dev.marregui.comando.core.port.QueryPort;
import dev.marregui.comando.core.reduce.Reducer;
import dev.marregui.comando.core.reduce.Transition;
import dev.marregui.comando.core.result.ColumnKind;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.result.ResultStats;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.RunStatus;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.FakeResult;
import dev.marregui.comando.testkit.RecordingSink;
import dev.marregui.comando.testkit.States;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/// [McpServer] serves comando's MCP tools over HTTP to the sessions it launched, each with its own token.
/// - A missing or unknown token is refused, a foreign origin forbidden; a revoked token stops while a newer one works.
/// - Initialize, tool listing and unknown methods answer as the protocol says.
/// - A permission call raises an approval card and answers with the user's decision and the rule it saves.
/// - A cancelled, unanswered or closing-time approval is denied and its card withdrawn; an unanswered question is
///   denied too, and nothing of it is kept.
/// - The view tools turn their input into view intents.
/// - At most a bound of requests run at once, and a smaller bound of one session's; one past either is refused at once.
/// - A request whose body does not arrive in time has its connection closed, and its place comes back; one whose
///   headers do not arrive in time too, and the connections open at once are bounded.
class McpServerTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private final RecordingSink sink = new RecordingSink();
    private final TranscriptRegistry transcripts = new TranscriptRegistry();
    private final List<String> queries = new ArrayList<>();
    private final AtomicReference<AppState> state = new AtomicReference<>(States.twoPanels());
    private McpServer server;
    private McpGateway.Ticket ticket;
    /** Raw connections a test opened, closed after it. */
    private final List<Socket> held = new ArrayList<>();

    /** The error every query fails with, when set: a batch whose later statement the server refuses. */
    private volatile String failWith;
    private volatile QueryPort.Rows answer;

    private final QueryPort db = new QueryPort() {
        @Override
        public CompletableFuture<Rows> query(final ConnectionSpec spec, final String sql, final int maxRows) {
            synchronized (queries) {
                queries.add(spec.name() + ": " + sql);
            }
            final String failure = failWith;
            if (failure != null) {
                return CompletableFuture.failedFuture(new SQLException(failure));
            }
            final QueryPort.Rows given = answer;
            return CompletableFuture.completedFuture(given != null ? given : new Rows(List.of("x"), List.of(List.of("1"), List.of("2")), -1L, false));
        }

        @Override
        public CompletableFuture<Rows> describe(final ConnectionSpec spec, final String table) {
            return CompletableFuture.completedFuture(new Rows(List.of("column", "type"), List.of(List.of("ts", "TIMESTAMP")), -1L, false));
        }
    };

    private McpTools tools;

    @BeforeEach
    void start() throws Exception {
        server = start(Duration.ofMinutes(5));
    }

    private McpServer start(final Duration approvalTimeout) throws Exception {
        tools = new McpTools(sink, state::get, transcripts, db, AuditLog.none(), new FakeClock(), approvalTimeout);
        final McpServer s = McpServer.start(tools, 0, "test");
        ticket = s.register("work");
        return s;
    }

    @AfterEach
    void stop() throws Exception {
        for (final Socket s : held) {
            s.close();
        }
        server.close();
    }

    private HttpResponse<String> post(final String token, final String body, final String origin) throws Exception {
        final HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(server.url()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (origin != null) {
            b.header("Origin", origin);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode rpc(final String method, final String params) throws Exception {
        final HttpResponse<String> r = post(ticket.token(), "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\",\"params\":" + params + "}", null);
        assertThat(r.statusCode()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private JsonNode tool(final String name, final String args) throws Exception {
        return rpc("tools/call", "{\"name\":\"" + name + "\",\"arguments\":" + args + "}").path("result");
    }

    private static String text(final JsonNode result) {
        return result.path("content").path(0).path("text").asString();
    }

    /// No token or an unknown token answers 401, a known token from a foreign origin 403, the same from `localhost`
    /// 200, and a revoked token 401.
    @Test
    void rejectsMissingOrUnknownTokensAndForeignOrigins() throws Exception {
        final String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        assertThat(post(null, ping, null).statusCode()).isEqualTo(401);
        assertThat(post("nope", ping, null).statusCode()).isEqualTo(401);
        assertThat(post(ticket.token(), ping, "https://evil.example").statusCode()).isEqualTo(403);
        assertThat(post(ticket.token(), ping, "http://localhost:3000").statusCode()).isEqualTo(200);
        server.unregister("work", ticket);
        assertThat(post(ticket.token(), ping, null).statusCode()).isEqualTo(401);
    }

    /// A browser's opaque origin `null` (a sandboxed frame or a `file:` page) is refused, over HTTP too; no origin or a
    /// blank one (a client that is not a browser) and the loopback hosts are allowed.
    @Test
    void anOpaqueNullOriginIsRefusedWhileNoOriginAndLoopbackAreAllowed() throws Exception {
        assertThat(McpServer.originAllowed("null")).isFalse();
        assertThat(McpServer.originAllowed(null)).isTrue();
        assertThat(McpServer.originAllowed(" ")).isTrue();
        assertThat(McpServer.originAllowed("http://localhost:3000")).isTrue();
        assertThat(McpServer.originAllowed("http://127.0.0.1")).isTrue();
        assertThat(McpServer.originAllowed("http://[::1]:8080")).isTrue();
        final String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        assertThat(post(ticket.token(), ping, "null").statusCode()).isEqualTo(403);
    }

    /// Revoking an old process's token after the session got a newer one leaves the newer token answering 200 and the
    /// old 401.
    @Test
    void aLateRevocationOfAnOldProcessesTokenLeavesTheNewProcessWorking() throws Exception {
        final String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        final McpGateway.Ticket newer = server.register("work");
        assertThat(newer.token()).isNotEqualTo(ticket.token());
        server.unregister("work", ticket); // the removed session's strand runs late
        assertThat(post(newer.token(), ping, null).statusCode()).isEqualTo(200);
        assertThat(post(ticket.token(), ping, null).statusCode()).isEqualTo(401);
    }

    /// Initialize answers protocol `2025-11-25` and server name `comando` (an unknown client version gets the
    /// server's), `tools/list` names the tools, an unknown method answers -32601, and a notification answers 202.
    @Test
    void initializeListAndUnknownMethods() throws Exception {
        final JsonNode init = rpc("initialize", "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}}");
        assertThat(init.path("result").path("protocolVersion").asString()).isEqualTo("2025-11-25");
        assertThat(init.path("result").path("serverInfo").path("name").asString()).isEqualTo("comando");
        assertThat(rpc("initialize", "{\"protocolVersion\":\"1999-01-01\"}").path("result").path("protocolVersion").asString())
                .isEqualTo(McpServer.DEFAULT_PROTOCOL);

        final JsonNode tools = rpc("tools/list", "{}").path("result").path("tools");
        assertThat(tools).extracting(t -> t.path("name").asString())
                .contains("approve", "ui_show_table", "ui_plot", "ui_show_view", "db_run_sql", "db_describe",
                        "result_list", "result_read", "result_stats", "session_send", "session_read");

        // Claude Code 2.1.296 asks for server/discover first and falls back to initialize on this error
        assertThat(rpc("server/discover", "{}").path("error").path("code").asInt()).isEqualTo(-32601);
        assertThat(post(ticket.token(), "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", null).statusCode()).isEqualTo(202);
    }

    /// At most the bound of requests run at once: with a bound of one, a request made while an approval waits for the
    /// user is answered 503 at once with `Retry-After`, and once the approval is answered requests run again.
    @Test
    void requestsPastTheBoundAreRefusedAtOnce() throws Exception {
        server.close();
        tools = new McpTools(sink, state::get, transcripts, db, AuditLog.none(), new FakeClock(), Duration.ofMinutes(5));
        server = McpServer.start(tools, 0, "test", 1);
        ticket = server.register("work");
        final CompletableFuture<JsonNode> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return tool("approve", "{\"tool_name\":\"Bash\",\"input\":{\"command\":\"ls\"},\"tool_use_id\":\"t1\"}");
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        });
        final Intent.ApprovalRequested asked = sink.await(Intent.ApprovalRequested.class, WAIT);
        final HttpResponse<String> refused = post(ticket.token(), "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}", null);
        assertThat(refused.statusCode()).isEqualTo(503);
        assertThat(refused.headers().firstValue("Retry-After")).contains("1");
        server.resolve(asked.approval(), ApprovalDecision.allowOnce());
        waiting.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(post(ticket.token(), "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"ping\"}", null).statusCode()).isEqualTo(200);
    }

    /// One session's client that holds requests open without sending their bodies takes at most its own bound: with
    /// room for two requests and one per session, `rogue`'s second request is refused 503 at once, and `work`'s pings
    /// still answer 200 while `rogue` holds its first.
    @Test
    void oneSessionHoldingRequestsOpenLeavesRoomForTheOthers() throws Exception {
        restart(new McpServer.Bounds(2, 1, Duration.ofMinutes(1)));
        final McpGateway.Ticket rogue = server.register("rogue");
        final List<Socket> sockets = List.of(bodiless(rogue.token()), bodiless(rogue.token()));
        assertThat(firstAnswer(sockets).getValue()).as("rogue's request past its own bound").isEqualTo(503);
        assertThat(post(ticket.token(), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", null).statusCode()).isEqualTo(200);
        assertThat(post(ticket.token(), "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", null).statusCode()).isEqualTo(200);
    }

    /// A request that announces a body and sends none has its connection closed once its deadline (here 500 ms) passes,
    /// and the place it held comes back: `rogue`'s own ping answers 200 after it.
    @Test
    void aRequestWhoseBodyDoesNotArriveIsClosedAtItsDeadline() throws Exception {
        restart(new McpServer.Bounds(4, 1, Duration.ofMillis(500)));
        final McpGateway.Ticket rogue = server.register("rogue");
        final List<Socket> sockets = List.of(bodiless(rogue.token()), bodiless(rogue.token()));
        final Map.Entry<Socket, Integer> refused = firstAnswer(sockets);
        assertThat(refused.getValue()).as("rogue's request past its own bound").isEqualTo(503);
        final Socket holder = sockets.get(0) == refused.getKey() ? sockets.get(1) : sockets.get(0);
        holder.setSoTimeout((int) WAIT.toMillis());
        int end;
        try {
            end = holder.getInputStream().read();
        } catch (final SocketException e) {
            end = -1; // a reset is a close too
        }
        assertThat(end).as("the held request's connection is closed, nothing answered").isEqualTo(-1);
        // the connection closes at the deadline, the request's place frees when its thread has left the handler, just after
        await().atMost(WAIT).until(() -> post(rogue.token(), "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}", null).statusCode() == 200);
    }

    /// A connection that sends a request line and one header and never ends its headers, with no credential, is closed
    /// once the request's deadline (here 300 ms) passes: the JDK reads the headers before any handler runs, and the
    /// deadline covers them too, so such a client holds no socket and no thread past it.
    @Test
    void aRequestWhoseHeadersDoNotArriveIsClosedAtItsDeadline() throws Exception {
        restart(new McpServer.Bounds(4, 1, Duration.ofMillis(300)));
        final URI url = URI.create(server.url());
        final Socket s = new Socket(url.getHost(), url.getPort());
        held.add(s);
        s.getOutputStream().write(("POST " + url.getPath() + " HTTP/1.1\r\nHost: 127.0.0.1\r\n").getBytes(StandardCharsets.US_ASCII));
        s.getOutputStream().flush();
        s.setSoTimeout(3000);
        int end;
        try {
            end = s.getInputStream().read();
        } catch (final SocketTimeoutException e) {
            end = -2; // still open
        } catch (final SocketException e) {
            end = -1; // a reset is a close too
        }
        assertThat(end).as("closed by the deadline (-1), not still open at 3 s (-2)").isEqualTo(-1);
        assertThat(post(ticket.token(), "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}", null).statusCode()).as("the server still answers")
                .isEqualTo(200);
    }

    /// The connections open at once are bounded: the JDK's server is made with its connection bound set, which it reads
    /// once, so connections that send nothing, which reach no handler and no deadline, cannot grow without a bound.
    @Test
    void theOpenConnectionsAreBounded() {
        assertThat(System.getProperty(McpServer.MAX_CONNECTIONS_PROPERTY)).as("set before the JDK's server was made")
                .isEqualTo(Integer.toString(McpServer.MAX_CONNECTIONS));
        assertThat(McpServer.MAX_CONNECTIONS).as("above the requests that run at once").isGreaterThan(McpServer.MAX_REQUESTS);
    }

    private void restart(final McpServer.Bounds bounds) throws Exception {
        server.close();
        tools = new McpTools(sink, state::get, transcripts, db, AuditLog.none(), new FakeClock(), Duration.ofMinutes(5));
        server = McpServer.start(tools, 0, "test", bounds);
        ticket = server.register("work");
    }

    /// A raw connection that sends a POST from `token` announcing a body of 100 bytes and sends no body.
    private Socket bodiless(final String token) throws Exception {
        final URI url = URI.create(server.url());
        final Socket s = new Socket(url.getHost(), url.getPort());
        held.add(s);
        final OutputStream out = s.getOutputStream();
        out.write(("POST " + url.getPath() + " HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + token
                + "\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return s;
    }

    /// The first of `sockets` the server answers within [#WAIT], with the status of its answer.
    private static Map.Entry<Socket, Integer> firstAnswer(final List<Socket> sockets) throws Exception {
        final long end = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() - end < 0) {
            for (final Socket s : sockets) {
                final InputStream in = s.getInputStream();
                s.setSoTimeout(50);
                final int first;
                try {
                    first = in.read();
                } catch (final SocketTimeoutException e) {
                    continue;
                }
                s.setSoTimeout((int) WAIT.toMillis());
                final String line = (char) first + new String(in.readNBytes(11), StandardCharsets.US_ASCII); // "HTTP/1.1 503"
                return Map.entry(s, Integer.parseInt(line.substring(9, 12)));
            }
        }
        throw new AssertionError("no request was answered in " + WAIT);
    }

    /// A Bash permission call raises a tool approval for the session summarised as its command; allowing it for the
    /// session answers allow with the input unchanged and a session rule `git status:*`.
    @Test
    void bashApprovalForTheSessionAddsARuleForThatCommandPrefix() throws Exception {
        final CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> {
            try {
                return tool("approve", "{\"tool_name\":\"Bash\",\"input\":{\"command\":\"git status\"},\"tool_use_id\":\"t1\"}");
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        });
        final Intent.ApprovalRequested asked = sink.await(Intent.ApprovalRequested.class, WAIT);
        assertThat(asked.approval().kind()).isEqualTo(ApprovalKind.TOOL);
        assertThat(asked.approval().summary()).isEqualTo("git status");
        assertThat(asked.approval().session()).isEqualTo("work");

        server.resolve(asked.approval(), new ApprovalDecision.Allow(ApprovalDecision.Scope.SESSION));
        final JsonNode result = json.readTree(text(call.get()));
        assertThat(result.path("behavior").asString()).isEqualTo("allow");
        assertThat(result.path("updatedInput").path("command").asString()).isEqualTo("git status");
        final JsonNode update = result.path("updatedPermissions").path(0);
        assertThat(update.path("destination").asString()).isEqualTo("session");
        assertThat(update.path("rules").path(0).path("ruleContent").asString()).isEqualTo("git status:*");
    }

    /// Always allowing a WebFetch saves a WebFetch rule `domain:<host>` for that host only in the local settings.
    @Test
    void alwaysAllowingAFetchAllowsThatHostOnly() throws Exception {
        final CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> {
            try {
                return tool("approve", "{\"tool_name\":\"WebFetch\",\"input\":{\"url\":\"https://Docs.Example.org/x?y=1\",\"prompt\":\"p\"},\"tool_use_id\":\"t2\"}");
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        });
        final Intent.ApprovalRequested asked = sink.await(Intent.ApprovalRequested.class, WAIT);
        server.resolve(asked.approval(), new ApprovalDecision.Allow(ApprovalDecision.Scope.ALWAYS));
        final JsonNode update = json.readTree(text(call.get())).path("updatedPermissions").path(0);
        assertThat(update.path("destination").asString()).isEqualTo("localSettings");
        assertThat(update.path("rules").path(0).path("toolName").asString()).isEqualTo("WebFetch");
        assertThat(update.path("rules").path(0).path("ruleContent").asString()).isEqualTo("domain:docs.example.org");
    }

    /// A `notifications/cancelled` for a waiting permission call answers it deny and withdraws its approval card.
    @Test
    void aCancelledCallWithdrawsItsCardAndIsDenied() throws Exception {
        final CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> {
            try {
                return tool("approve", "{\"tool_name\":\"Bash\",\"input\":{\"command\":\"rm -rf build\"},\"tool_use_id\":\"t3\"}");
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        });
        final Intent.ApprovalRequested asked = sink.await(Intent.ApprovalRequested.class, WAIT);
        // the request id 7 is the one rpc() sends; Claude Code cancels it on an interrupt
        assertThat(post(ticket.token(), "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":7}}", null)
                .statusCode()).isEqualTo(202);
        assertThat(json.readTree(text(call.get())).path("behavior").asString()).isEqualTo("deny");
        assertThat(sink.await(Intent.ApprovalWithdrawn.class, WAIT).id()).isEqualTo(asked.approval().id());
    }

    /// A question approval's answers come back in the updated input beside the original questions.
    @Test
    void questionAnswersGoBackInTheUpdatedInput() throws Exception {
        final CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> {
            try {
                return tool("approve", "{\"tool_name\":\"AskUserQuestion\",\"input\":{\"questions\":[{\"question\":\"Colour?\",\"options\":[{\"label\":\"Red\"}]}]}}");
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        });
        final Intent.ApprovalRequested asked = sink.await(Intent.ApprovalRequested.class, WAIT);
        assertThat(asked.approval().kind()).isEqualTo(ApprovalKind.QUESTION);
        server.resolve(asked.approval(), new ApprovalDecision.Answer(Map.of("Colour?", "Red")));
        final JsonNode result = json.readTree(text(call.get()));
        assertThat(result.path("updatedInput").path("answers").path("Colour?").asString()).isEqualTo("Red");
        assertThat(result.path("updatedInput").path("questions").isArray()).isTrue();
    }

    /// An approval nobody answers is denied at its deadline and its card withdrawn.
    @Test
    void unansweredApprovalIsDeniedAtTheDeadlineAndWithdrawn() throws Exception {
        server.close();
        server = start(Duration.ofMillis(300));
        final JsonNode result = json.readTree(text(tool("approve", "{\"tool_name\":\"Bash\",\"input\":{\"command\":\"rm -rf /\"}}")));
        assertThat(result.path("behavior").asString()).isEqualTo("deny");
        sink.await(Intent.ApprovalWithdrawn.class, WAIT);
    }

    /// A question card (AskUserQuestion) nobody answers is denied at its deadline, the denial saying no answer came
    /// within it, and its card withdrawn; nothing else is sent, so no question is kept to be asked later.
    @Test
    void unansweredQuestionIsDeniedAtTheDeadlineAndNothingIsKept() throws Exception {
        server.close();
        server = start(Duration.ofMillis(300));
        sink.clear();
        final String questions = "{\"questions\":[{\"question\":\"Which store?\",\"header\":\"Store\","
                + "\"multiSelect\":false,\"options\":[{\"label\":\"Mapped\",\"description\":\"read in place\"},"
                + "{\"label\":\"Heap\"}]}]}";
        final JsonNode result = json.readTree(text(tool("approve",
                "{\"tool_name\":\"AskUserQuestion\",\"input\":" + questions + "}")));
        assertThat(result.path("behavior").asString()).isEqualTo("deny");
        assertThat(result.path("message").asString()).isEqualTo("No answer from the user within 300 ms.");
        sink.await(Intent.ApprovalWithdrawn.class, WAIT);
        assertThat(sink.intents()).extracting(Object::getClass)
                .containsExactly(Intent.ApprovalRequested.class, Intent.ApprovalWithdrawn.class);
    }

    /// The deadline as a denial says it: whole hours in h, so comando's own 24 h reads "24 h"; whole minutes in min;
    /// anything else in ms.
    @Test
    void theDeadlineIsSaidInItsLargestWholeUnit() {
        assertThat(McpTools.waited(Duration.ofHours(24))).isEqualTo("24 h");
        assertThat(McpTools.waited(Duration.ofMinutes(55))).isEqualTo("55 min");
        assertThat(McpTools.waited(Duration.ofMillis(90_500))).isEqualTo("90500 ms");
        assertThat(McpTools.waited(Duration.ofMillis(300))).isEqualTo("300 ms");
    }

    /// Closing the server answers every waiting permission call deny.
    @Test
    void closingTheServerDeniesWaitingApprovals() throws Exception {
        final CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> {
            try {
                return tool("approve", "{\"tool_name\":\"Bash\",\"input\":{\"command\":\"make\"}}");
            } catch (final Exception e) {
                return null;
            }
        });
        sink.await(Intent.ApprovalRequested.class, WAIT);
        server.close();
        final JsonNode r = call.get();
        if (r != null) { // the response may or may not make it out before the listener stops
            assertThat(json.readTree(text(r)).path("behavior").asString()).isEqualTo("deny");
        }
    }

    /// The table tool answers without error and shows a lower-placement table view for the session; a JSON `null` is SQL
    /// NULL, a null cell, told apart from the text `"null"`.
    @Test
    void tableBecomesAViewIntent() throws Exception {
        final JsonNode r = tool("ui_show_table", "{\"title\":\"top\",\"columns\":[\"a\",\"b\"],\"rows\":[[1,\"x\"],[2,null],[3,\"null\"]]}");
        assertThat(r.path("isError").asBoolean(false)).isFalse();
        final Intent.ShowView show = sink.await(Intent.ShowView.class, WAIT);
        assertThat(show.session()).isEqualTo("work");
        assertThat(show.placement()).isEqualTo(Placement.LOWER);
        assertThat(show.spec()).isEqualTo(new ViewSpec.Table(List.of("a", "b"),
                List.of(List.of("1", "x"), Arrays.asList("2", null), List.of("3", "null"))));
    }

    /// `ui_show_view` type `deliverables` shows the one Files written view in the side pane, whatever title the call
    /// gives, so a second call finds the same view (record 143).
    @Test
    void theFilesWrittenViewIsOneViewInTheSidePane() throws Exception {
        final JsonNode r = tool("ui_show_view", "{\"type\":\"deliverables\",\"title\":\"mine\",\"data\":{}}");
        assertThat(r.path("isError").asBoolean(false)).isFalse();
        final Intent.ShowView show = sink.await(Intent.ShowView.class, WAIT);
        assertThat(show.title()).isEqualTo(ViewSpec.Deliverables.TITLE);
        assertThat(show.spec()).isEqualTo(new ViewSpec.Deliverables());
        assertThat(show.placement()).isEqualTo(Placement.SIDE);
    }

    /// A table row of the wrong width, a plot with unequal x and y, and a path no file system can name are tool errors
    /// that say why, and no view is shown.
    @Test
    void invalidViewDataIsAToolErrorAndSendsNothing() throws Exception {
        final JsonNode r = tool("ui_show_table", "{\"title\":\"bad\",\"columns\":[\"a\",\"b\"],\"rows\":[[1]]}");
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(text(r)).contains("row 0 must be an array of 2 values");
        final JsonNode p = tool("ui_plot", "{\"title\":\"p\",\"series\":[{\"x\":[1,2],\"y\":[1]}]}");
        assertThat(text(p)).contains("x has 2 points, y has 1");
        final JsonNode t = tool("ui_show_view", "{\"type\":\"text\",\"title\":\"t\",\"data\":{\"path\":\"/a\\u0000b\",\"text\":\"x\"}}");
        assertThat(text(t)).as("a path no file system can name never reaches the window").contains("path is not a file path");
        assertThat(sink.of(Intent.ShowView.class)).isEmpty();
    }

    /// Writing into a log panel is a tool error saying it is read-only and nothing was written, with no edit sent.
    @Test
    void aLogPanelTakesNoTextFromATool() throws Exception {
        tools.readOnly(States.P2::equals);
        final JsonNode r = tool("ui_insert", "{\"text\":\"-- note\",\"panel\":\"two\",\"mode\":\"append\"}");
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(text(r)).contains("read-only").contains("nothing was written");
        assertThat(sink.of(Intent.EditPanel.class)).isEmpty();
    }

    /// `ui_set_editor` replaces the text of the caller's own panel, `ui_insert` with a panel name targets that panel,
    /// and an unknown panel name is an error.
    @Test
    void editorToolsTargetTheCallersPanel() throws Exception {
        tool("ui_set_editor", "{\"text\":\"SELECT 1\"}");
        final Intent.EditPanel edit = sink.await(Intent.EditPanel.class, WAIT);
        assertThat(edit.panel()).isEqualTo(States.P1);
        assertThat(edit.mode()).isEqualTo(EditMode.REPLACE);

        tool("ui_insert", "{\"text\":\"-- note\",\"panel\":\"two\",\"mode\":\"append\"}");
        final Intent.EditPanel insert = sink.await(Intent.EditPanel.class, e -> e.mode() == EditMode.APPEND, WAIT);
        assertThat(insert.panel()).isEqualTo(States.P2);

        assertThat(text(tool("ui_insert", "{\"text\":\"x\",\"panel\":\"nope\"}"))).contains("no panel 'nope'");
    }

    private JsonNode toolAs(final McpGateway.Ticket t, final String name, final String args) throws Exception {
        final HttpResponse<String> r = post(t.token(), "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + name + "\",\"arguments\":" + args + "}}", null);
        return json.readTree(r.body()).path("result");
    }

    /// A session bound to no panel gets a tool error for an edit or SQL without a panel name, and nothing is edited or
    /// run.
    @Test
    void aSessionWithoutAPanelMustNameOneAndNeverWritesIntoAnothersPanel() throws Exception {
        final McpGateway.Ticket other = server.register("other");
        final JsonNode edit = toolAs(other, "ui_set_editor", "{\"text\":\"DROP TABLE t\"}");
        assertThat(edit.path("isError").asBoolean()).isTrue();
        assertThat(text(edit)).contains("bound to no panel");
        final JsonNode sql = toolAs(other, "db_run_sql", "{\"sql\":\"SELECT 1\"}");
        assertThat(text(sql)).contains("bound to no panel");
        assertThat(sink.of(Intent.EditPanel.class)).isEmpty();
        assertThat(queries).isEmpty();
    }

    /// A session whose panel is closed is told so by name for an edit, while its SQL still runs on that panel's
    /// connection.
    @Test
    void aSessionWhosePanelIsClosedIsToldSoAndItsSqlUsesThatPanelsConnection() throws Exception {
        state.set(state.get().withClosed(List.of(new PanelState(new PanelId("p9"), "nine", "local", "other"))));
        final McpGateway.Ticket other = server.register("other");
        final JsonNode edit = toolAs(other, "ui_set_editor", "{\"text\":\"x\"}");
        assertThat(edit.path("isError").asBoolean()).isTrue();
        assertThat(text(edit)).contains("nine").contains("closed");
        final JsonNode sql = toolAs(other, "db_run_sql", "{\"sql\":\"SELECT 1\"}");
        assertThat(sql.path("isError").asBoolean()).as(text(sql)).isFalse();
        assertThat(queries).isNotEmpty();
    }

    /// `ui_close_view` refuses a pinned view, another session's view, a non-numeric id and an unknown id, and closes
    /// only the caller's unpinned view.
    @Test
    void closeViewClosesOnlyTheCallersUnpinnedViews() throws Exception {
        final ViewSpec.Table t = new ViewSpec.Table(List.of("a"), List.of(List.of("1")));
        state.set(state.get().withViews(List.of(
                new ViewState(1L, "mine", t, Placement.LOWER, false, "work", 1L),
                new ViewState(2L, "pinned", t, Placement.LOWER, true, "work", 1L),
                new ViewState(3L, "gradle build", t, Placement.LOWER, false, "", 1L))));
        assertThat(text(tool("ui_close_view", "{\"id\":2}"))).contains("pinned");
        assertThat(text(tool("ui_close_view", "{\"id\":3}"))).contains("not placed by this session");
        assertThat(text(tool("ui_close_view", "{\"id\":\"abc\"}"))).contains("must be a view id");
        assertThat(text(tool("ui_close_view", "{\"id\":9}"))).contains("no view with id 9");
        assertThat(sink.of(Intent.CloseView.class)).isEmpty();
        assertThat(text(tool("ui_close_view", "{\"id\":1}"))).contains("closed view 1");
        assertThat(sink.await(Intent.CloseView.class, WAIT).id()).isEqualTo(1L);
    }

    /// A layout with a lower share outside 0.05–0.95 is refused and sends no layout change, the panel and tab included.
    @Test
    void aRefusedLayoutChangesNothing() throws Exception {
        assertThat(text(tool("ui_set_layout", "{\"panel\":\"two\",\"lower_tab\":\"results\",\"lower_share\":2}"))).contains("between 0.05 and 0.95");
        assertThat(sink.of(Intent.SessionLayout.class)).isEmpty();
    }

    /// `ui_get_state` lists the two panels, the connection and the session by name, and no decisions kept for later:
    /// a card waits only while its session waits.
    @Test
    void stateDescribesPanelsConnectionsAndSessions() throws Exception {
        final JsonNode s = json.readTree(text(tool("ui_get_state", "{}")));
        assertThat(s.path("panels")).hasSize(2);
        assertThat(s.path("connections").path(0).path("name").asString()).isEqualTo("local");
        assertThat(s.path("sessions").path(0).path("name").asString()).isEqualTo("work");
        assertThat(s.has("later")).as("no Later group").isFalse();
    }

    /// `ui_set_layout` sends one SessionLayout naming the session, panel, lower tab and share; it says when focus waits
    /// for the user to pause typing or is not needed, and refuses an unknown tab, a share out of range and an empty
    /// request.
    @Test
    void layoutFocusesSelectsAndArranges() throws Exception {
        // work's own panel is one; two is a document attached to it
        state.set(state.get().withPanel(new PanelState(States.P2, "two", "local", "work", true)));
        final JsonNode r = tool("ui_set_layout", "{\"panel\":\"two\",\"lower_tab\":\"approvals\",\"sidebar\":false,\"lower_share\":0.5}");
        assertThat(r.path("isError").asBoolean(false)).isFalse();
        // one intent naming the session: the reducer decides what the user's window allows
        assertThat(sink.await(Intent.SessionLayout.class, WAIT)).isEqualTo(new Intent.SessionLayout("work", States.P2, LowerTab.APPROVALS,
                new Layout(Layout.Toggle.HIDE, Layout.Toggle.KEEP, 0.5)));

        assertThat(text(tool("ui_set_layout", "{\"panel\":\"two\"}"))).contains("will focus panel two once the user pauses typing");
        // the focused panel needs no focus: the reply says so rather than promise a pending one
        assertThat(text(tool("ui_set_layout", "{\"panel\":\"one\",\"lower_tab\":\"session\"}")))
                .isEqualTo("panel one already has the focus; selected lower tab session");
        assertThat(text(tool("ui_set_layout", "{\"lower_tab\":\"nope\"}"))).contains("no view 'nope'");
        assertThat(text(tool("ui_set_layout", "{\"lower_share\":2}"))).contains("between 0.05 and 0.95");
        assertThat(text(tool("ui_set_layout", "{}"))).contains("nothing to change");
    }

    /// `ui_set_layout` with `transcript_above` false alone sends one SessionLayout that puts the transcript below the
    /// prompt and changes nothing else (record 144).
    @Test
    void layoutPutsTheTranscriptBelow() throws Exception {
        final JsonNode r = tool("ui_set_layout", "{\"transcript_above\":false}");
        assertThat(r.path("isError").asBoolean(false)).isFalse();
        assertThat(sink.await(Intent.SessionLayout.class, WAIT)).isEqualTo(new Intent.SessionLayout("work", null, null,
                new Layout(Layout.Toggle.KEEP, Layout.Toggle.KEEP, Double.NaN, null, null, "", Layout.Toggle.HIDE)));
    }

    /// A panel named in another letter case is found, as the editor tools find it.
    @Test
    void layoutNamesAPanelInAnyCaseAsTheEditorToolsDo() throws Exception {
        // work's own panel is one; two is a document attached to it
        state.set(state.get().withPanel(new PanelState(States.P2, "two", "local", "work", true)));
        final JsonNode r = tool("ui_set_layout", "{\"panel\":\"TWO\"}");
        assertThat(r.path("isError").asBoolean()).as(text(r)).isFalse();
        assertThat(sink.await(Intent.SessionLayout.class, WAIT).panel()).isEqualTo(States.P2);
    }

    /// `ui_open_file` on a README titles its view with the folder, `docs/README.md`.
    @Test
    void anOpenedFileIsTitledWithItsFolder(@TempDir final Path dir) throws Exception {
        final Path docs = Files.createDirectories(dir.resolve("docs"));
        final Path readme = Files.writeString(docs.resolve("README.md"), "# hi\n");
        assertThat(text(tool("ui_open_file", "{\"path\":\"" + readme + "\"}"))).contains("opened");
        assertThat(sink.await(Intent.ShowView.class, WAIT).title()).isEqualTo("docs/README.md");
    }

    /// A relative path resolves against the session's folder and Markdown renders in the side pane; at a line it is
    /// text; a `~` path resolves under home; a binary file is said to be one; a folder or missing file is a tool error
    /// showing nothing.
    @Test
    void anOpenedFileIsReadAsTheUsersClickReadsIt(@TempDir final Path dir) throws Exception {
        final Path docs = Files.createDirectories(dir.resolve("docs"));
        Files.writeString(docs.resolve("README.md"), "# hi\n");
        Files.write(docs.resolve("logo.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, (byte) 0xff, (byte) 0xfe});
        state.set(state.get().withSessions(List.of(SessionState.closed(SessionSpec.fresh("work", dir.toString())))));

        // relative to the session's directory; Markdown at no line is rendered
        assertThat(text(tool("ui_open_file", "{\"path\":\"docs/README.md\"}"))).contains("opened " + docs.resolve("README.md"));
        final Intent.ShowView rendered = sink.await(Intent.ShowView.class, WAIT);
        assertThat(rendered.spec()).isEqualTo(new ViewSpec.Markdown("# hi\n"));
        assertThat(rendered.placement()).isEqualTo(Placement.SIDE);
        assertThat(rendered.session()).isEqualTo("work");

        // at a line it is text at that line
        sink.clear();
        tool("ui_open_file", "{\"path\":\"docs/README.md\",\"line\":1}");
        assertThat(sink.await(Intent.ShowView.class, WAIT).spec()).isInstanceOf(ViewSpec.Text.class);

        // under the home folder as typed
        sink.clear();
        final String underHome = "~/" + Path.of(System.getProperty("user.home")).relativize(docs.resolve("README.md"));
        final JsonNode home = tool("ui_open_file", "{\"path\":\"" + underHome + "\"}");
        assertThat(home.path("isError").asBoolean()).as(text(home)).isFalse();
        assertThat(sink.await(Intent.ShowView.class, WAIT).title()).isEqualTo("docs/README.md");

        // a binary file is said to be one
        sink.clear();
        tool("ui_open_file", "{\"path\":\"docs/logo.png\"}");
        assertThat(((ViewSpec.Text) sink.await(Intent.ShowView.class, WAIT).spec()).text()).contains("is a binary file of 10 bytes");

        // a folder or a missing file is a tool error, and nothing is shown
        sink.clear();
        final JsonNode folder = tool("ui_open_file", "{\"path\":\"docs\"}");
        assertThat(folder.path("isError").asBoolean()).isTrue();
        assertThat(text(folder)).isEqualTo("cannot open " + docs + ": not a file");
        final JsonNode missing = tool("ui_open_file", "{\"path\":\"docs/nope.md\"}");
        assertThat(missing.path("isError").asBoolean()).isTrue();
        assertThat(text(missing)).isEqualTo("cannot open " + docs.resolve("nope.md") + ": no such file");
        assertThat(sink.of(Intent.ShowView.class)).isEmpty();
    }

    /// A read-only query runs on the panel's connection without approval, answers its rows as text with a count, and
    /// shows the result under the given title.
    @Test
    void readOnlySqlRunsAtOnceOnThePanelsConnection() throws Exception {
        final JsonNode r = tool("db_run_sql", "{\"sql\":\"SELECT x FROM t\",\"show\":true,\"title\":\"xs\"}");
        assertThat(text(r)).isEqualTo("x\n1\n2\n(2 rows)");
        assertThat(queries).containsExactly("local: SELECT x FROM t");
        assertThat(sink.await(Intent.ShowView.class, WAIT).title()).isEqualTo("xs");
        assertThat(sink.of(Intent.ApprovalRequested.class)).isEmpty();
    }

    /// A shown query result keeps SQL NULL as a null cell, told apart from the text `"null"` (FUI-11).
    @Test
    void aShownResultKeepsSqlNullApartFromTheTextNull() throws Exception {
        answer = new QueryPort.Rows(List.of("x"), List.of(Arrays.asList((String) null), List.of("null")), -1L, false);
        tool("db_run_sql", "{\"sql\":\"SELECT x FROM t\",\"show\":true,\"title\":\"xs\"}");
        assertThat(sink.await(Intent.ShowView.class, WAIT).spec())
                .isEqualTo(new ViewSpec.Table(List.of("x"), List.of(Arrays.asList((String) null), List.of("null"))));
    }

    /// A write raises an SQL-write approval; denying it with a reason answers a tool error carrying the reason, and
    /// nothing runs.
    @Test
    void writesNeedApprovalAndADenialRunsNothing() throws Exception {
        final CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> {
            try {
                return tool("db_run_sql", "{\"sql\":\"DROP TABLE t\"}");
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        });
        final Intent.ApprovalRequested asked = sink.await(Intent.ApprovalRequested.class, WAIT);
        assertThat(asked.approval().kind()).isEqualTo(ApprovalKind.SQL_WRITE);
        server.resolve(asked.approval(), new ApprovalDecision.Deny("not today"));
        final JsonNode r = call.get();
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(text(r)).contains("not today");
        assertThat(queries).isEmpty();
    }

    /// A write approved after its connection was pointed at another server runs on neither and says the connection
    /// changed while the card was open.
    @Test
    void aWriteApprovedAfterItsConnectionWasRepointedRunsNothing() throws Exception {
        final CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> {
            try {
                return tool("db_run_sql", "{\"sql\":\"DROP TABLE t\"}");
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        });
        final Intent.ApprovalRequested asked = sink.await(Intent.ApprovalRequested.class, WAIT);
        // while the card is open, the user points the connection at another server
        final ConnectionSpec elsewhere = new ConnectionSpec("local", "elsewhere", 8812, "qdb", "admin", "quest", false);
        state.set(state.get().withConnection(ConnectionState.closed(elsewhere)));
        server.resolve(asked.approval(), ApprovalDecision.allowOnce());
        final JsonNode r = call.get();
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(text(r)).contains("changed while the card was open; nothing ran");
        assertThat(queries).as("neither the old target nor the new").isEmpty();
    }

    /// When the asking session's process exits while its SQL-write card waits, the call answers a tool error saying the
    /// session ended before the user answered, the card is withdrawn, and a late allow runs nothing.
    @Test
    void aSessionThatEndsWhileItsCardWaitsIsDeniedAndItsCardWithdrawn() throws Exception {
        final CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> {
            try {
                return tool("db_run_sql", "{\"sql\":\"DROP TABLE t\"}");
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        });
        final Intent.ApprovalRequested asked = sink.await(Intent.ApprovalRequested.class, WAIT);
        // the process exits: the adapter removes its ticket before it reports the exit
        server.unregister("work", ticket);
        final JsonNode r = call.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(text(r)).contains("The session ended before the user answered");
        assertThat(sink.await(Intent.ApprovalWithdrawn.class, WAIT).id()).as("its card goes").isEqualTo(asked.approval().id());
        server.resolve(asked.approval(), ApprovalDecision.allowOnce());
        assertThat(queries).as("a late allow runs nothing").isEmpty();
    }

    /// A DDL batch that fails part-way may have run its earlier statements: the schema is reloaded all the same, so
    /// completion and the explorer do not keep the schema from before, and the call answers the server's error.
    @Test
    void aDdlBatchThatFailsPartWayStillReloadsTheSchema() throws Exception {
        failWith = "syntax error at end of input";
        final CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> {
            try {
                return tool("db_run_sql", "{\"sql\":\"CREATE TABLE a (x INT); CREATE TABLE b (bad\"}");
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        });
        server.resolve(sink.await(Intent.ApprovalRequested.class, WAIT).approval(), ApprovalDecision.allowOnce());
        final JsonNode r = call.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(text(r)).contains("syntax error at end of input");
        assertThat(sink.of(Intent.SchemaChanged.class)).extracting(Intent.SchemaChanged::connection).containsExactly("local");
    }

    /// A write on a connection marked production is refused as a tool error naming it, with no approval card.
    @Test
    void productionConnectionsRefuseWrites() throws Exception {
        final AppState s = state.get();
        final ConnectionSpec prod = new ConnectionSpec("local", "db", 8812, "qdb", "admin", "quest", true);
        state.set(s.withConnection(ConnectionState.closed(prod)));
        final JsonNode r = tool("db_run_sql", "{\"sql\":\"INSERT INTO t VALUES (1)\"}");
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(text(r)).contains("production connection");
        assertThat(sink.of(Intent.ApprovalRequested.class)).isEmpty();
    }

    /** Panel two bound to a second session, "reviews", running. */
    private void reviewsInPanelTwo() {
        final AppState s = state.get();
        state.set(withSession(s, "reviews").withPanel(s.panel(States.P2).orElseThrow().withSession("reviews")));
    }

    private static AppState withSession(final AppState s, final String name) {
        final List<SessionState> sessions = new ArrayList<>(s.sessions());
        sessions.add(SessionState.closed(SessionSpec.fresh(name, "/tmp")).withStatus(Status.OPEN, ""));
        return s.withSessions(sessions);
    }

    private CompletableFuture<JsonNode> async(final String name, final String args) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return tool(name, args);
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /// `session_send` hands the prompt, headed with the sender, to the other session as a prompt from another session,
    /// which the reducer appends to that session's panel and runs there, with no approval card; it answers which panel
    /// and the block to read after.
    @Test
    void aPromptToAnotherSessionIsAddedToItsPanelAndRunThereHeadedWithTheSenderWithoutACard() throws Exception {
        reviewsInPanelTwo();
        transcripts.of("reviews").append(new Block.UserPrompt("earlier", "two", ""));
        final JsonNode r = tool("session_send", "{\"session\":\"reviews\",\"text\":\"Review the diff\"}");
        assertThat(r.path("isError").asBoolean()).isFalse();
        assertThat(sink.of(Intent.ApprovalRequested.class)).as("the sessions are trusted with each other: nothing is asked").isEmpty();
        final String prompt = "[from session work]\nReview the diff";
        assertThat(text(r)).contains("sent to reviews in panel two").contains("\"after\": 1");
        assertThat(sink.of(Intent.PromptSession.class)).containsExactly(new Intent.PromptSession("reviews", prompt, "work"));
        final Transition t = new Reducer(new FakeClock()).apply(state.get(), sink.of(Intent.PromptSession.class).getFirst());
        assertThat(t.effects()).anyMatch(e -> e instanceof Effect.SendToClaude c && c.name().equals("reviews") && c.text().equals(prompt));
    }

    /// A prompt `session_send` sends to a session whose checkpoint runs goes through and queues behind the checkpoint
    /// (record 130): the tool says it waits for its turn, and the reducer sends it to Claude.
    @Test
    void aPromptToASessionWhoseCheckpointRunsQueuesAndRuns() throws Exception {
        reviewsInPanelTwo();
        final Reducer reducer = new Reducer(new FakeClock());
        state.set(reducer.apply(state.get(), new Intent.Checkpoint("reviews")).state());
        assertThat(state.get().session("reviews").orElseThrow().isCheckpointing()).as("precondition: the checkpoint runs").isTrue();
        final JsonNode r = tool("session_send", "{\"session\":\"reviews\",\"text\":\"Review the diff\"}");
        assertThat(r.path("isError").asBoolean()).isFalse();
        assertThat(text(r)).contains("the prompt waits for its turn");
        final Transition t = reducer.apply(state.get(), sink.of(Intent.PromptSession.class).getFirst());
        assertThat(t.effects()).anyMatch(e -> e instanceof Effect.SendToClaude c && c.name().equals("reviews") && c.text().contains("Review the diff"));
    }

    /// Stopping a session is the user's circuit breaker: a prompt to a stopped session is a tool error saying it is not
    /// running, and nothing runs.
    @Test
    void aStoppedSessionIsNotStartedByAnotherSessionsPrompt() throws Exception {
        // stopping a session is the user's circuit breaker for sessions that keep prompting each other
        reviewsInPanelTwo();
        final AppState s = state.get();
        state.set(s.withSession(s.session("reviews").orElseThrow().withStatus(Status.CLOSED, "")));
        final JsonNode r = tool("session_send", "{\"session\":\"reviews\",\"text\":\"again\"}");
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(text(r)).contains("not running");
        assertThat(sink.of(Intent.PromptSession.class)).as("nothing runs, so nothing starts the session").isEmpty();
    }

    /// A prompt to the caller itself, to an unknown session (the answer names the known ones), to a session without an
    /// open panel, or without text is refused, with no card.
    @Test
    void promptsToItselfAnUnknownSessionOrOneWithoutAnOpenPanelAreRefused() throws Exception {
        reviewsInPanelTwo();
        assertThat(text(tool("session_send", "{\"session\":\"work\",\"text\":\"x\"}"))).contains("is this session");
        assertThat(text(tool("session_send", "{\"session\":\"nope\",\"text\":\"x\"}"))).contains("no session 'nope'").contains("reviews");
        state.set(withSession(state.get(), "headless"));
        assertThat(text(tool("session_send", "{\"session\":\"headless\",\"text\":\"x\"}"))).contains("has no open panel");
        assertThat(text(tool("session_send", "{\"session\":\"reviews\"}"))).contains("required");
        assertThat(sink.of(Intent.ApprovalRequested.class)).isEmpty();
    }

    /// `session_read` shows the session's state and numbered blocks from the cursor, and the next cursor stops before a
    /// block still running, so a later read shows it finished.
    @Test
    void readingASessionShowsNumberedBlocksAndTheCursorStopsAtABlockStillRunning() throws Exception {
        reviewsInPanelTwo();
        final Transcript t = transcripts.of("reviews");
        t.append(new Block.UserPrompt("[from session work]\nwhich branch?", "two", ""));
        t.append(new Block.AssistantText("m1", 0, "Checking.", false, ""));
        final int running = t.append(new Block.ToolCall("u1", "Bash", "{\"command\":\"git branch --show-current\"}", "", Block.ToolStatus.RUNNING, ""));
        t.append(new Block.AssistantText("m1", 1, "still", true, ""));

        final String first = text(tool("session_read", "{\"session\":\"reviews\",\"after\":0}"));
        assertThat(first).startsWith("session reviews · idle · 4 blocks, shown from 0 · next read: after 2")
                .contains("[0] prompt from panel two:\n  [from session work]\n  which branch?")
                .contains("[1] Claude:\n  Checking.")
                .contains("[2] tool Bash {\"command\":\"git branch --show-current\"} → running")
                .contains("[3] Claude (still writing):");

        t.replace(running, ((Block.ToolCall) t.get(running)).withResult("main", false));
        t.replace(3, new Block.AssistantText("m1", 1, "On main.", false, ""));
        t.append(new Block.TurnEnd(0.01, 1500, 2, false, "On main."));
        final String second = text(tool("session_read", "{\"session\":\"reviews\",\"after\":2}"));
        assertThat(second).contains("next read: after 5")
                .contains("[2] tool Bash {\"command\":\"git branch --show-current\"} → done:\n  main")
                .contains("[3] Claude:\n  On main.")
                .contains("[4] turn ended: 2 turns, 1.5 s")
                .doesNotContain("[1]");
    }

    /// Without a cursor the last block is shown; a cursor past the end reads from block 0 and says it restarted; an
    /// unknown session is an error.
    @Test
    void withoutACursorTheEndIsShownAndACursorBeyondTheEndReadsFromTheStart() throws Exception {
        reviewsInPanelTwo();
        final Transcript t = transcripts.of("reviews");
        for (int i = 0; i < McpTools.READ_TAIL + 5; i++) {
            t.append(new Block.Note(Block.NoteKind.INFO, "n" + i, ""));
        }
        final String tail = text(tool("session_read", "{\"session\":\"reviews\"}"));
        assertThat(tail).contains("shown from 5").contains("[5] note info: n5").doesNotContain("[4]");
        final String restarted = text(tool("session_read", "{\"session\":\"reviews\",\"after\":999}"));
        assertThat(restarted).contains("restarted").contains("[0] note info: n0");
        assertThat(text(tool("session_read", "{\"session\":\"nope\"}"))).contains("no session 'nope'");
    }

    /// A read with a wait on a busy session says it is still working after the wait, keeps waiting while the session
    /// stays busy, and returns once its turn has ended, showing the turn-ended block.
    @Test
    void readingWithAWaitReturnsOnceTheSessionsTurnHasEndedOrSaysItIsStillWorking() throws Exception {
        reviewsInPanelTwo();
        state.set(state.get().withSession("reviews", x -> x.withStatus(Status.OPEN, "").withTurns(1, 0L)));
        final Transcript t = transcripts.of("reviews");
        t.append(new Block.UserPrompt("go", "two", ""));

        final String timedOut = text(tool("session_read", "{\"session\":\"reviews\",\"after\":0,\"wait_seconds\":1}"));
        assertThat(timedOut).contains("busy, still working after 1 s");

        final CompletableFuture<JsonNode> call = async("session_read", "{\"session\":\"reviews\",\"after\":0,\"wait_seconds\":30}");
        // the session is still busy: the read keeps waiting
        assertThatThrownBy(() -> call.get(400, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        t.append(new Block.AssistantText("m", 0, "done", false, ""));
        t.append(new Block.TurnEnd(0.0, 10, 1, false, "done"));
        state.set(state.get().withSession("reviews", x -> x.withTurns(0, 0L)));
        final String read = text(call.get());
        assertThat(read).startsWith("session reviews · idle").contains("[2] turn ended");
    }

    /// A cursor past a transcript cleared since the last read returns at once from the start, without waiting.
    @Test
    void aCursorPastATranscriptClearedSinceReadsAtOnceAndSaysItRestarted() throws Exception {
        reviewsInPanelTwo();
        final Transcript t = transcripts.of("reviews");
        t.append(new Block.UserPrompt("go", "two", ""));
        t.append(new Block.AssistantText("m", 0, "done", false, ""));
        t.append(new Block.TurnEnd(0.0, 10, 1, false, "done"));
        t.clear();
        t.append(new Block.Note(Block.NoteKind.INFO, "after the clear", ""));

        final CompletableFuture<JsonNode> call = async("session_read", "{\"session\":\"reviews\",\"after\":3,\"wait_seconds\":60}");
        final String read = text(call.get(WAIT.toSeconds(), TimeUnit.SECONDS));
        assertThat(read).startsWith("session reviews · idle · 1 blocks").doesNotContain("still working")
                .contains("restarted").contains("[0] note info: after the clear");
    }

    /// A stored conversation's turn end reads as `turn ended (stored conversation)` with no made-up duration.
    @Test
    void aStoredConversationsTurnEndReadsAsSuchWithoutNumbers() throws Exception {
        reviewsInPanelTwo();
        final Transcript t = transcripts.of("reviews");
        t.append(new Block.UserPrompt("go", "", ""));
        t.append(new Block.AssistantText("m", 0, "done", false, ""));
        t.append(Block.TurnEnd.stored());
        final String read = text(tool("session_read", "{\"session\":\"reviews\",\"after\":0}"));
        assertThat(read).contains("[2] turn ended (stored conversation)").doesNotContain("-0.0 s");
    }

    /// `db_describe` answers the table's columns and types as tab-separated text with a header.
    @Test
    void describeListsColumns() throws Exception {
        assertThat(text(tool("db_describe", "{\"table\":\"trades\"}"))).startsWith("column\ttype\nts\tTIMESTAMP");
    }


    /// Holds `data` as panel p1's run 42, with its columns `id` (LONG) and `name` (TEXT), the run in `status`.
    private ResultRegistry holdRows(final List<List<Object>> data, final SqlRun run) {
        final ResultRegistry registry = new ResultRegistry();
        registry.put(new FakeResult(42L, List.of("id", "name"), List.of(ColumnKind.LONG, ColumnKind.TEXT), data));
        state.set(state.get().withRun(run));
        tools.results(registry);
        return registry;
    }

    /// Holds a result of `rows` rows (`id` 0, 1, … and `name` "n0", "n1", …, every third name NULL) for panel
    /// p1's run 42, with the run in `status`.
    private ResultRegistry holdResult(final int rows, final RunStatus status) {
        final List<List<Object>> data = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            final List<Object> row = new ArrayList<>(2);
            row.add((long) i);
            row.add(i % 3 == 2 ? null : "n" + i);
            data.add(row);
        }
        return holdRows(data, run42().withStatus(status));
    }

    /// Panel p1's run 42, started.
    private static SqlRun run42() {
        return SqlRun.queued(42L, States.P1, "local", "select id, name from t").started(0L);
    }

    /// `result_list` names the panel's held result with its id, panel, connection, SQL, status, rows and typed
    /// columns, and `ui_get_state` names the same id on the panel's last run.
    @Test
    void resultListNamesTheHeldResultsAndStateLinksThem() throws Exception {
        holdResult(5, RunStatus.DONE);
        final JsonNode root = json.readTree(text(tool("result_list", "{}")));
        assertThat(root.path("omitted").asInt()).isZero();
        final JsonNode list = root.path("results");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("id").asString()).isEqualTo("r42");
        assertThat(list.get(0).path("panel").asString()).isEqualTo("one");
        assertThat(list.get(0).path("connection").asString()).isEqualTo("local");
        assertThat(list.get(0).path("status").asString()).isEqualTo("DONE");
        assertThat(list.get(0).path("rows").asInt()).isEqualTo(5);
        assertThat(list.get(0).path("columns").get(1).path("type").asString()).isEqualTo("TEXT");
        final JsonNode panels = json.readTree(tools.stateJson()).path("panels");
        assertThat(panels.get(0).path("lastRun").path("result").asString()).isEqualTo("r42");
    }

    /// A list over 64 kB stays valid JSON: an entry that does not fit is left out whole and counted in `omitted`.
    @Test
    void resultListLeavesOutWholeEntriesPastTheBudget() throws Exception {
        final List<String> names = new ArrayList<>();
        final List<ColumnKind> kinds = new ArrayList<>();
        for (int c = 0; c < 700; c++) {
            names.add("column_with_a_long_name_" + c);
            kinds.add(ColumnKind.LONG);
        }
        final ResultRegistry registry = new ResultRegistry();
        registry.put(new FakeResult(42L, names, kinds, List.of()));
        registry.put(new FakeResult(43L, names, kinds, List.of()));
        state.set(state.get().withRun(run42())
                .withRun(SqlRun.queued(43L, States.P2, "local", "select * from wide").started(0L)));
        tools.results(registry);
        final String answer = text(tool("result_list", "{}"));
        assertThat(answer.length()).isLessThanOrEqualTo(McpTools.MAX_RESULT_CHARS);
        final JsonNode root = json.readTree(answer);
        assertThat(root.path("results")).hasSize(1);
        assertThat(root.path("omitted").asInt()).isEqualTo(1);
    }

    /// `result_read` returns a page from an offset as COPY text, NULL as `\N`, with the rows shown, the total and
    /// the next offset; a column subset comes in the order asked; the last page names no next offset.
    @Test
    void resultReadPagesTheHeldRows() throws Exception {
        holdResult(10, RunStatus.DONE);
        assertThat(text(tool("result_read", "{\"result\":\"r42\",\"offset\":2,\"limit\":3}")))
                .isEqualTo("id\tname\n2\t\\N\n3\tn3\n4\tn4\n(rows 2-4 of 10; next offset 5)");
        assertThat(text(tool("result_read", "{\"result\":\"42\",\"offset\":8,\"columns\":[\"name\",\"id\"]}")))
                .isEqualTo("name\tid\n\\N\t8\nn9\t9\n(rows 8-9 of 10)");
        assertThat(text(tool("result_read", "{\"result\":\"r42\",\"offset\":10}")))
                .endsWith("(no rows from offset 10 of 10)");
    }

    /// A result still fetching is read from the rows that have arrived, and says so; one whose fetch stopped at
    /// its row limit says that.
    @Test
    void resultReadSaysWhenTheResultIsStillFetchingOrWasCut() throws Exception {
        holdResult(3, RunStatus.RUNNING);
        assertThat(text(tool("result_read", "{\"result\":\"r42\"}"))).endsWith("(rows 0-2 of 3, still fetching)");
        final List<List<Object>> one = List.of(List.of(1L, "a"));
        holdRows(one, run42().completed(1, -1L, true, 0L, 0L, 0L));
        assertThat(text(tool("result_read", "{\"result\":\"r42\"}")))
                .endsWith("(rows 0-0 of 1, the fetch stopped at its row limit)");
    }

    /// A row cut at the 64 kB budget is not counted as read, so the next offset starts with it; a first row that can
    /// never fit is passed, so paging moves on.
    @Test
    void resultReadNextOffsetRereadsACutRowAndPassesOneThatCannotFit() throws Exception {
        final String half = "x".repeat(40_000);
        final List<List<Object>> data = List.of(List.of(0L, half), List.of(1L, half), List.of(2L, "c"));
        holdRows(data, run42().completed(3, -1L, false, 0L, 0L, 0L));
        final String first = text(tool("result_read", "{\"result\":\"r42\"}"));
        assertThat(first.length()).isLessThan(McpTools.MAX_RESULT_CHARS + 200);
        assertThat(first).endsWith("(rows 0-1 of 3, the last cut at 64 kB; next offset 1)");
        final List<List<Object>> huge = List.of(List.of(0L, "y".repeat(70_000)), List.of(1L, "b"));
        holdRows(huge, run42().completed(2, -1L, false, 0L, 0L, 0L));
        assertThat(text(tool("result_read", "{\"result\":\"r42\"}")))
                .endsWith("(rows 0-0 of 2, the last cut at 64 kB; next offset 1)");
    }

    /// An unknown column is an error naming the columns; a result the registry let go is an error that points to
    /// `result_list`, which no longer lists it; a malformed id is an error too.
    @Test
    void resultReadRefusesUnknownColumnsAndResultsNotHeld() throws Exception {
        final ResultRegistry registry = holdResult(3, RunStatus.DONE);
        final JsonNode unknown = tool("result_read", "{\"result\":\"r42\",\"columns\":[\"price\"]}");
        assertThat(unknown.path("isError").asBoolean()).isTrue();
        assertThat(text(unknown)).isEqualTo("no column 'price'; columns: id, name");
        registry.release(42L);
        final JsonNode gone = tool("result_read", "{\"result\":\"r42\"}");
        assertThat(gone.path("isError").asBoolean()).isTrue();
        assertThat(text(gone)).startsWith("result r42 is not held").contains("result_list");
        assertThat(json.readTree(text(tool("result_list", "{}"))).path("results")).isEmpty();
        assertThat(tool("result_stats", "{\"result\":\"rx\"}").path("isError").asBoolean()).isTrue();
    }

    /// `result_stats` answers per column its type, NULLs, smallest and largest value and distinct values, and how
    /// many rows it scanned.
    @Test
    void resultStatsSummarisesEachColumn() throws Exception {
        holdResult(6, RunStatus.DONE);
        assertThat(text(tool("result_stats", "{\"result\":\"r42\"}"))).isEqualTo("""
                column\ttype\tnulls\tmin\tmax\tdistinct
                id\tLONG\t0\t0\t5\t6
                name\tTEXT\t2\tn0\tn4\t4
                (6 of 6 rows scanned)""");
    }

    /// The stats text says when the scan stopped at its limit, when a distinct count reached its cap, and when
    /// columns did not fit the 64 kB budget.
    @Test
    void statsTextSaysWhatTheScanDidNotCover() {
        final ResultStats.Column capped = new ResultStats.Column("id", "LONG", 0, "0", "9", ResultStats.DISTINCT_CAP);
        assertThat(McpTools.statsText(new ResultStats(100_000, 65_536, List.of(capped)), true))
                .endsWith("id\tLONG\t0\t0\t9\tat least 10000\n"
                        + "(65536 of 100000 rows scanned, stopped at 20 s, still fetching)");
        final String wide = "z".repeat(40_000);
        final ResultStats.Column big = new ResultStats.Column("a", "TEXT", 0, wide, wide, 1);
        assertThat(McpTools.statsText(new ResultStats(1, 1, List.of(big, big, big)), false))
                .endsWith("(1 of 1 rows scanned; 3 columns cut or left out at 64 kB: pass columns)");
    }
}
