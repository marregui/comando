package dev.marregui.comando.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marregui.comando.core.dispatch.Target;
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
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.RecordingSink;
import dev.marregui.comando.testkit.States;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private final QueryPort db = new QueryPort() {
        @Override
        public CompletableFuture<Rows> query(ConnectionSpec spec, String sql, int maxRows) {
            synchronized (queries) {
                queries.add(spec.name() + ": " + sql);
            }
            return CompletableFuture.completedFuture(new Rows(List.of("x"), List.of(List.of("1"), List.of("2")), -1L, false));
        }

        @Override
        public CompletableFuture<Rows> describe(ConnectionSpec spec, String table) {
            return CompletableFuture.completedFuture(new Rows(List.of("column", "type"), List.of(List.of("ts", "TIMESTAMP")), -1L, false));
        }
    };

    private McpTools tools;

    @BeforeEach
    void start() throws Exception {
        server = start(Duration.ofMinutes(5));
    }

    private McpServer start(Duration approvalTimeout) throws Exception {
        tools = new McpTools(sink, state::get, transcripts, db, AuditLog.none(), new FakeClock(), approvalTimeout);
        McpServer s = McpServer.start(tools, 0, "test");
        ticket = s.register("work");
        return s;
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<String> post(String token, String body, String origin) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(server.url()))
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

    private JsonNode rpc(String method, String params) throws Exception {
        HttpResponse<String> r = post(ticket.token(), "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\",\"params\":" + params + "}", null);
        assertThat(r.statusCode()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private JsonNode tool(String name, String args) throws Exception {
        return rpc("tools/call", "{\"name\":\"" + name + "\",\"arguments\":" + args + "}").path("result");
    }

    private static String text(JsonNode result) {
        return result.path("content").path(0).path("text").asText();
    }

    // ---- transport

    @Test
    void rejectsMissingOrUnknownTokensAndForeignOrigins() throws Exception {
        String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        assertThat(post(null, ping, null).statusCode()).isEqualTo(401);
        assertThat(post("nope", ping, null).statusCode()).isEqualTo(401);
        assertThat(post(ticket.token(), ping, "https://evil.example").statusCode()).isEqualTo(403);
        assertThat(post(ticket.token(), ping, "http://localhost:3000").statusCode()).isEqualTo(200);
        server.unregister("work", ticket);
        assertThat(post(ticket.token(), ping, null).statusCode()).isEqualTo(401);
    }

    @Test
    void aLateRevocationOfAnOldProcessesTokenLeavesTheNewProcessWorking() throws Exception {
        String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        McpGateway.Ticket newer = server.register("work");
        assertThat(newer.token()).isNotEqualTo(ticket.token());
        server.unregister("work", ticket); // the removed session's strand runs late
        assertThat(post(newer.token(), ping, null).statusCode()).isEqualTo(200);
        assertThat(post(ticket.token(), ping, null).statusCode()).isEqualTo(401);
    }

    @Test
    void initializeListAndUnknownMethods() throws Exception {
        JsonNode init = rpc("initialize", "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}}");
        assertThat(init.path("result").path("protocolVersion").asText()).isEqualTo("2025-11-25");
        assertThat(init.path("result").path("serverInfo").path("name").asText()).isEqualTo("comando");
        assertThat(rpc("initialize", "{\"protocolVersion\":\"1999-01-01\"}").path("result").path("protocolVersion").asText())
                .isEqualTo(McpServer.DEFAULT_PROTOCOL);

        JsonNode tools = rpc("tools/list", "{}").path("result").path("tools");
        assertThat(tools).extracting(t -> t.path("name").asText())
                .contains("approve", "ui_show_table", "ui_plot", "ui_show_view", "db_run_sql", "db_describe", "session_send", "session_read");

        // Claude Code 2.1.296 asks for server/discover first and falls back to initialize on this error
        assertThat(rpc("server/discover", "{}").path("error").path("code").asInt()).isEqualTo(-32601);
        assertThat(post(ticket.token(), "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", null).statusCode()).isEqualTo(202);
    }

    // ---- approvals

    @Test
    void bashApprovalForTheSessionAddsARuleForThatCommandPrefix() throws Exception {
        CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> {
            try {
                return tool("approve", "{\"tool_name\":\"Bash\",\"input\":{\"command\":\"git status\"},\"tool_use_id\":\"t1\"}");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Intent.ApprovalRequested asked = sink.await(Intent.ApprovalRequested.class, WAIT);
        assertThat(asked.approval().kind()).isEqualTo(ApprovalKind.TOOL);
        assertThat(asked.approval().summary()).isEqualTo("git status");
        assertThat(asked.approval().session()).isEqualTo("work");

        server.resolve(asked.approval(), new ApprovalDecision.Allow(ApprovalDecision.Scope.SESSION));
        JsonNode result = json.readTree(text(call.get()));
        assertThat(result.path("behavior").asText()).isEqualTo("allow");
        assertThat(result.path("updatedInput").path("command").asText()).isEqualTo("git status");
        JsonNode update = result.path("updatedPermissions").path(0);
        assertThat(update.path("destination").asText()).isEqualTo("session");
        assertThat(update.path("rules").path(0).path("ruleContent").asText()).isEqualTo("git status:*");
    }

    @Test
    void alwaysAllowingAFetchAllowsThatHostOnly() throws Exception {
        CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> {
            try {
                return tool("approve", "{\"tool_name\":\"WebFetch\",\"input\":{\"url\":\"https://Docs.Example.org/x?y=1\",\"prompt\":\"p\"},\"tool_use_id\":\"t2\"}");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Intent.ApprovalRequested asked = sink.await(Intent.ApprovalRequested.class, WAIT);
        server.resolve(asked.approval(), new ApprovalDecision.Allow(ApprovalDecision.Scope.ALWAYS));
        JsonNode update = json.readTree(text(call.get())).path("updatedPermissions").path(0);
        assertThat(update.path("destination").asText()).isEqualTo("localSettings");
        assertThat(update.path("rules").path(0).path("toolName").asText()).isEqualTo("WebFetch");
        assertThat(update.path("rules").path(0).path("ruleContent").asText()).isEqualTo("domain:docs.example.org");
    }

    @Test
    void aCancelledCallWithdrawsItsCardAndIsDenied() throws Exception {
        CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> {
            try {
                return tool("approve", "{\"tool_name\":\"Bash\",\"input\":{\"command\":\"rm -rf build\"},\"tool_use_id\":\"t3\"}");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Intent.ApprovalRequested asked = sink.await(Intent.ApprovalRequested.class, WAIT);
        // the request id 7 is the one rpc() sends; Claude Code cancels it on an interrupt
        assertThat(post(ticket.token(), "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":7}}", null)
                .statusCode()).isEqualTo(202);
        assertThat(json.readTree(text(call.get())).path("behavior").asText()).isEqualTo("deny");
        assertThat(sink.await(Intent.ApprovalWithdrawn.class, WAIT).id()).isEqualTo(asked.approval().id());
    }

    @Test
    void questionAnswersGoBackInTheUpdatedInput() throws Exception {
        CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> {
            try {
                return tool("approve", "{\"tool_name\":\"AskUserQuestion\",\"input\":{\"questions\":[{\"question\":\"Colour?\",\"options\":[{\"label\":\"Red\"}]}]}}");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Intent.ApprovalRequested asked = sink.await(Intent.ApprovalRequested.class, WAIT);
        assertThat(asked.approval().kind()).isEqualTo(ApprovalKind.QUESTION);
        server.resolve(asked.approval(), new ApprovalDecision.Answer(Map.of("Colour?", "Red")));
        JsonNode result = json.readTree(text(call.get()));
        assertThat(result.path("updatedInput").path("answers").path("Colour?").asText()).isEqualTo("Red");
        assertThat(result.path("updatedInput").path("questions").isArray()).isTrue();
    }

    @Test
    void unansweredApprovalIsDeniedAtTheDeadlineAndWithdrawn() throws Exception {
        server.close();
        server = start(Duration.ofMillis(300));
        JsonNode result = json.readTree(text(tool("approve", "{\"tool_name\":\"Bash\",\"input\":{\"command\":\"rm -rf /\"}}")));
        assertThat(result.path("behavior").asText()).isEqualTo("deny");
        sink.await(Intent.ApprovalWithdrawn.class, WAIT);
    }

    @Test
    void closingTheServerDeniesWaitingApprovals() throws Exception {
        CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> {
            try {
                return tool("approve", "{\"tool_name\":\"Bash\",\"input\":{\"command\":\"make\"}}");
            } catch (Exception e) {
                return null;
            }
        });
        sink.await(Intent.ApprovalRequested.class, WAIT);
        server.close();
        JsonNode r = call.get();
        if (r != null) { // the response may or may not make it out before the listener stops
            assertThat(json.readTree(text(r)).path("behavior").asText()).isEqualTo("deny");
        }
    }

    // ---- UI tools

    @Test
    void tableBecomesAViewIntent() throws Exception {
        JsonNode r = tool("ui_show_table", "{\"title\":\"top\",\"columns\":[\"a\",\"b\"],\"rows\":[[1,\"x\"],[2,null]]}");
        assertThat(r.path("isError").asBoolean(false)).isFalse();
        Intent.ShowView show = sink.await(Intent.ShowView.class, WAIT);
        assertThat(show.session()).isEqualTo("work");
        assertThat(show.placement()).isEqualTo(Placement.LOWER);
        assertThat(show.spec()).isEqualTo(new ViewSpec.Table(List.of("a", "b"), List.of(List.of("1", "x"), List.of("2", "null"))));
    }

    @Test
    void invalidViewDataIsAToolErrorAndSendsNothing() throws Exception {
        JsonNode r = tool("ui_show_table", "{\"title\":\"bad\",\"columns\":[\"a\",\"b\"],\"rows\":[[1]]}");
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(text(r)).contains("row 0 must be an array of 2 values");
        JsonNode p = tool("ui_plot", "{\"title\":\"p\",\"series\":[{\"x\":[1,2],\"y\":[1]}]}");
        assertThat(text(p)).contains("x has 2 points, y has 1");
        JsonNode t = tool("ui_show_view", "{\"type\":\"text\",\"title\":\"t\",\"data\":{\"path\":\"/a\\u0000b\",\"text\":\"x\"}}");
        assertThat(text(t)).as("a path no file system can name never reaches the window").contains("path is not a file path");
        assertThat(sink.of(Intent.ShowView.class)).isEmpty();
    }

    @Test
    void aLogPanelTakesNoTextFromATool() throws Exception {
        tools.readOnly(States.P2::equals);
        JsonNode r = tool("ui_insert", "{\"text\":\"-- note\",\"panel\":\"two\",\"mode\":\"append\"}");
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(text(r)).contains("read-only").contains("nothing was written");
        assertThat(sink.of(Intent.EditPanel.class)).isEmpty();
    }

    @Test
    void editorToolsTargetTheCallersPanel() throws Exception {
        tool("ui_set_editor", "{\"text\":\"SELECT 1\"}");
        Intent.EditPanel edit = sink.await(Intent.EditPanel.class, WAIT);
        assertThat(edit.panel()).isEqualTo(States.P1);
        assertThat(edit.mode()).isEqualTo(EditMode.REPLACE);

        tool("ui_insert", "{\"text\":\"-- note\",\"panel\":\"two\",\"mode\":\"append\"}");
        Intent.EditPanel insert = sink.await(Intent.EditPanel.class, e -> e.mode() == EditMode.APPEND, WAIT);
        assertThat(insert.panel()).isEqualTo(States.P2);

        assertThat(text(tool("ui_insert", "{\"text\":\"x\",\"panel\":\"nope\"}"))).contains("no panel 'nope'");
    }

    private JsonNode toolAs(McpGateway.Ticket t, String name, String args) throws Exception {
        HttpResponse<String> r = post(t.token(), "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + name + "\",\"arguments\":" + args + "}}", null);
        return json.readTree(r.body()).path("result");
    }

    @Test
    void aSessionWithoutAPanelMustNameOneAndNeverWritesIntoAnothersPanel() throws Exception {
        McpGateway.Ticket other = server.register("other");
        JsonNode edit = toolAs(other, "ui_set_editor", "{\"text\":\"DROP TABLE t\"}");
        assertThat(edit.path("isError").asBoolean()).isTrue();
        assertThat(text(edit)).contains("bound to no panel");
        JsonNode sql = toolAs(other, "db_run_sql", "{\"sql\":\"SELECT 1\"}");
        assertThat(text(sql)).contains("bound to no panel");
        assertThat(sink.of(Intent.EditPanel.class)).isEmpty();
        assertThat(queries).isEmpty();
    }

    @Test
    void aSessionWhosePanelIsClosedIsToldSoAndItsSqlUsesThatPanelsConnection() throws Exception {
        state.set(state.get().withClosed(List.of(new PanelState(new PanelId("p9"), "nine", "local", "other"))));
        McpGateway.Ticket other = server.register("other");
        JsonNode edit = toolAs(other, "ui_set_editor", "{\"text\":\"x\"}");
        assertThat(edit.path("isError").asBoolean()).isTrue();
        assertThat(text(edit)).contains("nine").contains("closed");
        JsonNode sql = toolAs(other, "db_run_sql", "{\"sql\":\"SELECT 1\"}");
        assertThat(sql.path("isError").asBoolean()).as(text(sql)).isFalse();
        assertThat(queries).isNotEmpty();
    }

    @Test
    void closeViewClosesOnlyTheCallersUnpinnedViews() throws Exception {
        ViewSpec.Table t = new ViewSpec.Table(List.of("a"), List.of(List.of("1")));
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

    @Test
    void aRefusedLayoutChangesNothing() throws Exception {
        assertThat(text(tool("ui_set_layout", "{\"panel\":\"two\",\"lower_tab\":\"results\",\"lower_share\":2}"))).contains("between 0.05 and 0.95");
        assertThat(sink.of(Intent.SessionLayout.class)).isEmpty();
    }

    @Test
    void stateDescribesPanelsConnectionsAndSessions() throws Exception {
        JsonNode s = json.readTree(text(tool("ui_get_state", "{}")));
        assertThat(s.path("panels")).hasSize(2);
        assertThat(s.path("connections").path(0).path("name").asText()).isEqualTo("local");
        assertThat(s.path("sessions").path(0).path("name").asText()).isEqualTo("work");
    }

    @Test
    void layoutFocusesSelectsAndArranges() throws Exception {
        // work's own panel is one; two is a document attached to it
        state.set(state.get().withPanel(new PanelState(States.P2, "two", "local", "work", true)));
        JsonNode r = tool("ui_set_layout", "{\"panel\":\"two\",\"lower_tab\":\"approvals\",\"sidebar\":false,\"lower_share\":0.5}");
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

    @Test
    void layoutNamesAPanelInAnyCaseAsTheEditorToolsDo() throws Exception {
        // work's own panel is one; two is a document attached to it
        state.set(state.get().withPanel(new PanelState(States.P2, "two", "local", "work", true)));
        JsonNode r = tool("ui_set_layout", "{\"panel\":\"TWO\"}");
        assertThat(r.path("isError").asBoolean()).as(text(r)).isFalse();
        assertThat(sink.await(Intent.SessionLayout.class, WAIT).panel()).isEqualTo(States.P2);
    }

    /** A file the session opens is titled with its folder, so two READMEs get two tabs. */
    @Test
    void anOpenedFileIsTitledWithItsFolder(@TempDir Path dir) throws Exception {
        Path docs = Files.createDirectories(dir.resolve("docs"));
        Path readme = Files.writeString(docs.resolve("README.md"), "# hi\n");
        assertThat(text(tool("ui_open_file", "{\"path\":\"" + readme + "\"}"))).contains("opened");
        assertThat(sink.await(Intent.ShowView.class, WAIT).title()).isEqualTo("docs/README.md");
    }

    /** {@code ui_open_file} reads a file as the user's click on its link does: the same path forms, views and refusals. */
    @Test
    void anOpenedFileIsReadAsTheUsersClickReadsIt(@TempDir Path dir) throws Exception {
        Path docs = Files.createDirectories(dir.resolve("docs"));
        Files.writeString(docs.resolve("README.md"), "# hi\n");
        Files.write(docs.resolve("logo.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, (byte) 0xff, (byte) 0xfe});
        state.set(state.get().withSessions(List.of(SessionState.closed(SessionSpec.fresh("work", dir.toString())))));

        // relative to the session's directory; Markdown at no line is rendered
        assertThat(text(tool("ui_open_file", "{\"path\":\"docs/README.md\"}"))).contains("opened " + docs.resolve("README.md"));
        Intent.ShowView rendered = sink.await(Intent.ShowView.class, WAIT);
        assertThat(rendered.spec()).isEqualTo(new ViewSpec.Markdown("# hi\n"));
        assertThat(rendered.placement()).isEqualTo(Placement.SIDE);
        assertThat(rendered.session()).isEqualTo("work");

        // at a line it is text at that line
        sink.clear();
        tool("ui_open_file", "{\"path\":\"docs/README.md\",\"line\":1}");
        assertThat(sink.await(Intent.ShowView.class, WAIT).spec()).isInstanceOf(ViewSpec.Text.class);

        // under the home folder as typed
        sink.clear();
        String underHome = "~/" + Path.of(System.getProperty("user.home")).relativize(docs.resolve("README.md"));
        JsonNode home = tool("ui_open_file", "{\"path\":\"" + underHome + "\"}");
        assertThat(home.path("isError").asBoolean()).as(text(home)).isFalse();
        assertThat(sink.await(Intent.ShowView.class, WAIT).title()).isEqualTo("docs/README.md");

        // a binary file is said to be one
        sink.clear();
        tool("ui_open_file", "{\"path\":\"docs/logo.png\"}");
        assertThat(((ViewSpec.Text) sink.await(Intent.ShowView.class, WAIT).spec()).text()).contains("is a binary file of 10 bytes");

        // a folder or a missing file is a tool error, and nothing is shown
        sink.clear();
        JsonNode folder = tool("ui_open_file", "{\"path\":\"docs\"}");
        assertThat(folder.path("isError").asBoolean()).isTrue();
        assertThat(text(folder)).isEqualTo("cannot open " + docs + ": not a file");
        JsonNode missing = tool("ui_open_file", "{\"path\":\"docs/nope.md\"}");
        assertThat(missing.path("isError").asBoolean()).isTrue();
        assertThat(text(missing)).isEqualTo("cannot open " + docs.resolve("nope.md") + ": no such file");
        assertThat(sink.of(Intent.ShowView.class)).isEmpty();
    }

    // ---- database tools

    @Test
    void readOnlySqlRunsAtOnceOnThePanelsConnection() throws Exception {
        JsonNode r = tool("db_run_sql", "{\"sql\":\"SELECT x FROM t\",\"show\":true,\"title\":\"xs\"}");
        assertThat(text(r)).isEqualTo("x\n1\n2\n(2 rows)");
        assertThat(queries).containsExactly("local: SELECT x FROM t");
        assertThat(sink.await(Intent.ShowView.class, WAIT).title()).isEqualTo("xs");
        assertThat(sink.of(Intent.ApprovalRequested.class)).isEmpty();
    }

    @Test
    void writesNeedApprovalAndADenialRunsNothing() throws Exception {
        CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> {
            try {
                return tool("db_run_sql", "{\"sql\":\"DROP TABLE t\"}");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Intent.ApprovalRequested asked = sink.await(Intent.ApprovalRequested.class, WAIT);
        assertThat(asked.approval().kind()).isEqualTo(ApprovalKind.SQL_WRITE);
        server.resolve(asked.approval(), new ApprovalDecision.Deny("not today"));
        JsonNode r = call.get();
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(text(r)).contains("not today");
        assertThat(queries).isEmpty();
    }

    @Test
    void aWriteApprovedAfterItsConnectionWasRepointedRunsNothing() throws Exception {
        CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> {
            try {
                return tool("db_run_sql", "{\"sql\":\"DROP TABLE t\"}");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Intent.ApprovalRequested asked = sink.await(Intent.ApprovalRequested.class, WAIT);
        // while the card is open, the user points the connection at another server
        ConnectionSpec elsewhere = new ConnectionSpec("local", "elsewhere", 8812, "qdb", "admin", "quest", false);
        state.set(state.get().withConnection(ConnectionState.closed(elsewhere)));
        server.resolve(asked.approval(), ApprovalDecision.allowOnce());
        JsonNode r = call.get();
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(text(r)).contains("changed while the card was open; nothing ran");
        assertThat(queries).as("neither the old target nor the new").isEmpty();
    }

    @Test
    void aSessionThatEndsWhileItsCardWaitsIsDeniedAndItsCardWithdrawn() throws Exception {
        CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> {
            try {
                return tool("db_run_sql", "{\"sql\":\"DROP TABLE t\"}");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Intent.ApprovalRequested asked = sink.await(Intent.ApprovalRequested.class, WAIT);
        // the process exits: the adapter removes its ticket before it reports the exit
        server.unregister("work", ticket);
        JsonNode r = call.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(text(r)).contains("The session ended before the user answered");
        assertThat(sink.await(Intent.ApprovalWithdrawn.class, WAIT).id()).as("its card goes").isEqualTo(asked.approval().id());
        server.resolve(asked.approval(), ApprovalDecision.allowOnce());
        assertThat(queries).as("a late allow runs nothing").isEmpty();
    }

    @Test
    void productionConnectionsRefuseWrites() throws Exception {
        AppState s = state.get();
        ConnectionSpec prod = new ConnectionSpec("local", "db", 8812, "qdb", "admin", "quest", true);
        state.set(s.withConnection(ConnectionState.closed(prod)));
        JsonNode r = tool("db_run_sql", "{\"sql\":\"INSERT INTO t VALUES (1)\"}");
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(text(r)).contains("production connection");
        assertThat(sink.of(Intent.ApprovalRequested.class)).isEmpty();
    }

    // ---- sessions

    /** Panel two bound to a second session, "reviews", running. */
    private void reviewsInPanelTwo() {
        AppState s = state.get();
        state.set(withSession(s, "reviews").withPanel(s.panel(States.P2).orElseThrow().withSession("reviews")));
    }

    private static AppState withSession(AppState s, String name) {
        List<SessionState> sessions = new ArrayList<>(s.sessions());
        sessions.add(SessionState.closed(SessionSpec.fresh(name, "/tmp")).withStatus(Status.OPEN, ""));
        return s.withSessions(sessions);
    }

    private CompletableFuture<JsonNode> async(String name, String args) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return tool(name, args);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void aPromptToAnotherSessionIsAddedToItsPanelAndRunThereHeadedWithTheSenderWithoutACard() throws Exception {
        reviewsInPanelTwo();
        transcripts.of("reviews").append(new Block.UserPrompt("earlier", "two", ""));
        JsonNode r = tool("session_send", "{\"session\":\"reviews\",\"text\":\"Review the diff\"}");
        assertThat(r.path("isError").asBoolean()).isFalse();
        assertThat(sink.of(Intent.ApprovalRequested.class)).as("the sessions are trusted with each other: nothing is asked").isEmpty();
        String prompt = "[from session work]\nReview the diff";
        assertThat(text(r)).contains("sent to reviews in panel two").contains("\"after\": 1");
        assertThat(sink.of(Intent.EditPanel.class)).containsExactly(new Intent.EditPanel(States.P2, prompt, EditMode.APPEND, "work"));
        assertThat(sink.of(Intent.RunText.class)).containsExactly(new Intent.RunText(States.P2, prompt, Target.CLAUDE));
    }

    @Test
    void aStoppedSessionIsNotStartedByAnotherSessionsPrompt() throws Exception {
        // stopping a session is the user's circuit breaker for sessions that keep prompting each other
        reviewsInPanelTwo();
        AppState s = state.get();
        state.set(s.withSession(s.session("reviews").orElseThrow().withStatus(Status.CLOSED, "")));
        JsonNode r = tool("session_send", "{\"session\":\"reviews\",\"text\":\"again\"}");
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(text(r)).contains("not running");
        assertThat(sink.of(Intent.RunText.class)).as("nothing runs, so nothing starts the session").isEmpty();
    }

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

    @Test
    void readingASessionShowsNumberedBlocksAndTheCursorStopsAtABlockStillRunning() throws Exception {
        reviewsInPanelTwo();
        Transcript t = transcripts.of("reviews");
        t.append(new Block.UserPrompt("[from session work]\nwhich branch?", "two", ""));
        t.append(new Block.AssistantText("m1", 0, "Checking.", false, ""));
        int running = t.append(new Block.ToolCall("u1", "Bash", "{\"command\":\"git branch --show-current\"}", "", Block.ToolStatus.RUNNING, ""));
        t.append(new Block.AssistantText("m1", 1, "still", true, ""));

        String first = text(tool("session_read", "{\"session\":\"reviews\",\"after\":0}"));
        assertThat(first).startsWith("session reviews · idle · 4 blocks, shown from 0 · next read: after 2")
                .contains("[0] prompt from panel two:\n  [from session work]\n  which branch?")
                .contains("[1] Claude:\n  Checking.")
                .contains("[2] tool Bash {\"command\":\"git branch --show-current\"} → running")
                .contains("[3] Claude (still writing):");

        t.replace(running, ((Block.ToolCall) t.get(running)).withResult("main", false));
        t.replace(3, new Block.AssistantText("m1", 1, "On main.", false, ""));
        t.append(new Block.TurnEnd(0.01, 1500, 2, false, "On main."));
        String second = text(tool("session_read", "{\"session\":\"reviews\",\"after\":2}"));
        assertThat(second).contains("next read: after 5")
                .contains("[2] tool Bash {\"command\":\"git branch --show-current\"} → done:\n  main")
                .contains("[3] Claude:\n  On main.")
                .contains("[4] turn ended: 2 turns, 1.5 s")
                .doesNotContain("[1]");
    }

    @Test
    void withoutACursorTheEndIsShownAndACursorBeyondTheEndReadsFromTheStart() throws Exception {
        reviewsInPanelTwo();
        Transcript t = transcripts.of("reviews");
        for (int i = 0; i < McpTools.READ_TAIL + 5; i++) {
            t.append(new Block.Note(Block.NoteKind.INFO, "n" + i, ""));
        }
        String tail = text(tool("session_read", "{\"session\":\"reviews\"}"));
        assertThat(tail).contains("shown from 5").contains("[5] note info: n5").doesNotContain("[4]");
        String restarted = text(tool("session_read", "{\"session\":\"reviews\",\"after\":999}"));
        assertThat(restarted).contains("restarted").contains("[0] note info: n0");
        assertThat(text(tool("session_read", "{\"session\":\"nope\"}"))).contains("no session 'nope'");
    }

    @Test
    void readingWithAWaitReturnsOnceTheSessionsTurnHasEndedOrSaysItIsStillWorking() throws Exception {
        reviewsInPanelTwo();
        state.set(state.get().withSession("reviews", x -> x.withStatus(Status.OPEN, "").withTurns(1, 0L)));
        Transcript t = transcripts.of("reviews");
        t.append(new Block.UserPrompt("go", "two", ""));

        String timedOut = text(tool("session_read", "{\"session\":\"reviews\",\"after\":0,\"wait_seconds\":1}"));
        assertThat(timedOut).contains("busy, still working after 1 s");

        CompletableFuture<JsonNode> call = async("session_read", "{\"session\":\"reviews\",\"after\":0,\"wait_seconds\":30}");
        // the session is still busy: the read keeps waiting
        assertThatThrownBy(() -> call.get(400, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        t.append(new Block.AssistantText("m", 0, "done", false, ""));
        t.append(new Block.TurnEnd(0.0, 10, 1, false, "done"));
        state.set(state.get().withSession("reviews", x -> x.withTurns(0, 0L)));
        String read = text(call.get());
        assertThat(read).startsWith("session reviews · idle").contains("[2] turn ended");
    }

    @Test
    void aCursorPastATranscriptClearedSinceReadsAtOnceAndSaysItRestarted() throws Exception {
        reviewsInPanelTwo();
        Transcript t = transcripts.of("reviews");
        t.append(new Block.UserPrompt("go", "two", ""));
        t.append(new Block.AssistantText("m", 0, "done", false, ""));
        t.append(new Block.TurnEnd(0.0, 10, 1, false, "done"));
        t.clear();
        t.append(new Block.Note(Block.NoteKind.INFO, "after the clear", ""));

        CompletableFuture<JsonNode> call = async("session_read", "{\"session\":\"reviews\",\"after\":3,\"wait_seconds\":60}");
        String read = text(call.get(WAIT.toSeconds(), TimeUnit.SECONDS));
        assertThat(read).startsWith("session reviews · idle · 1 blocks").doesNotContain("still working")
                .contains("restarted").contains("[0] note info: after the clear");
    }

    @Test
    void aStoredConversationsTurnEndReadsAsSuchWithoutNumbers() throws Exception {
        reviewsInPanelTwo();
        Transcript t = transcripts.of("reviews");
        t.append(new Block.UserPrompt("go", "", ""));
        t.append(new Block.AssistantText("m", 0, "done", false, ""));
        t.append(Block.TurnEnd.stored());
        String read = text(tool("session_read", "{\"session\":\"reviews\",\"after\":0}"));
        assertThat(read).contains("[2] turn ended (stored conversation)").doesNotContain("-0.0 s");
    }

    @Test
    void describeListsColumns() throws Exception {
        assertThat(text(tool("db_describe", "{\"table\":\"trades\"}"))).startsWith("column\ttype\nts\tTIMESTAMP");
    }
}
