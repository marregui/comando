package dev.marregui.comando.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.port.Clock;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.QueryPort;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * comando's MCP tools. A UI tool validates its input and sends an intent; it returns at once. The approval
 * tool and a database write park the calling (virtual) thread on a future that the user's answer
 * completes, up to an absolute deadline.
 */
public final class McpTools {

    private static final System.Logger LOG = System.getLogger(McpTools.class.getName());
    static final String APPROVE = "approve";
    static final int MAX_SQL_ROWS = 200;
    static final int MAX_RESULT_CHARS = 64 * 1024;
    static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    /** A tool's outcome: text for Claude, and whether it is an error. */
    record Outcome(String text, boolean error) {
        static Outcome ok(String text) {
            return new Outcome(text, false);
        }

        static Outcome fail(String text) {
            return new Outcome(text == null || text.isBlank() ? "failed without a message" : text, true);
        }
    }

    private final IntentSink sink;
    private final Supplier<AppState> state;
    private final QueryPort db;
    private final AuditLog audit;
    private final Clock clock;
    private final Duration approvalTimeout;
    private final ObjectMapper json = new ObjectMapper();
    private final ArrayNode definitions;
    private final AtomicLong approvalIds = new AtomicLong(1_000_000L);
    private final ConcurrentHashMap<Long, CompletableFuture<ApprovalDecision>> pending = new ConcurrentHashMap<>();
    /** The approval each in-flight JSON-RPC call waits on, by "session#request id", for {@code notifications/cancelled}. */
    private final ConcurrentHashMap<String, Long> approvalByCall = new ConcurrentHashMap<>();
    /** The JSON-RPC call this request thread serves; each request runs on its own thread. */
    private static final ThreadLocal<String> CALL = new ThreadLocal<>();

    public McpTools(IntentSink sink, Supplier<AppState> state, QueryPort db, AuditLog audit, Clock clock, Duration approvalTimeout) {
        this.sink = sink;
        this.state = state;
        this.db = db;
        this.audit = audit;
        this.clock = clock;
        this.approvalTimeout = approvalTimeout;
        try (var in = McpTools.class.getResourceAsStream("/mcp/tools.json")) {
            definitions = (ArrayNode) json.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the tool definitions", e);
        }
    }

    ArrayNode definitions() {
        return definitions;
    }

    /** Runs tool {@code name} for {@code session}; never throws for bad input, which becomes an error outcome. */
    Outcome call(String session, String name, JsonNode args) {
        return call(session, name, args, null);
    }

    /** As {@link #call(String, String, JsonNode)}, for the JSON-RPC request {@code callKey} names, so it can be cancelled. */
    Outcome call(String session, String name, JsonNode args, String callKey) {
        CALL.set(callKey);
        try {
            return measured(session, name, args);
        } finally {
            CALL.remove();
        }
    }

    /**
     * Claude Code cancelled a call (an interrupt): a card it waits on is withdrawn and the call denied, so a
     * statement it asked to run cannot run after the turn that wanted it was stopped.
     */
    void cancelled(String callKey) {
        Long id = callKey == null ? null : approvalByCall.remove(callKey);
        CompletableFuture<ApprovalDecision> f = id == null ? null : pending.get(id);
        if (f != null && f.complete(new ApprovalDecision.Deny("The call was cancelled."))) {
            sink.dispatch(new Intent.ApprovalWithdrawn(id, "Claude Code cancelled the call"));
        }
    }

    private Outcome measured(String session, String name, JsonNode args) {
        McpCallEvent event = new McpCallEvent();
        event.begin();
        Outcome out = callMeasured(session, name, args);
        event.end();
        if (event.shouldCommit()) {
            event.session = session;
            event.tool = name;
            event.error = out.error();
            event.commit();
        }
        return out;
    }

    private Outcome callMeasured(String session, String name, JsonNode args) {
        Outcome out;
        try {
            out = switch (name) {
                case APPROVE -> approve(session, args);
                case "ui_show_table" -> show(session, args.path("title").asText("table"),
                        ViewSpecs.table(args.path("columns"), args.path("rows")), args);
                case "ui_plot" -> show(session, args.path("title").asText("plot"), ViewSpecs.plot(args), args);
                case "ui_show_view" -> show(session, args.path("title").asText(args.path("type").asText("view")),
                        ViewSpecs.parse(args.path("type").asText(""), args.path("data")), args);
                case "ui_set_editor" -> edit(session, args, EditMode.REPLACE);
                case "ui_insert" -> edit(session, args, "append".equals(args.path("mode").asText("insert")) ? EditMode.APPEND : EditMode.INSERT);
                case "ui_open_file" -> openFile(session, args);
                case "ui_notify" -> notify(args);
                case "ui_get_state" -> Outcome.ok(stateJson());
                case "ui_set_layout" -> layout(args);
                case "ui_close_view" -> closeView(session, args);
                case "db_run_sql" -> runSql(session, args);
                case "db_describe" -> describe(session, args);
                default -> Outcome.fail("unknown tool " + name);
            };
        } catch (IllegalArgumentException e) {
            out = Outcome.fail(e.getMessage());
        } catch (RuntimeException e) {
            // every call gets an answer: an unexpected failure is a tool error, not a dropped request
            LOG.log(System.Logger.Level.WARNING, "tool failed [tool=" + name + ", session=" + session + ']', e);
            out = Outcome.fail(name + " failed: " + e);
        }
        if (!name.equals(APPROVE)) {
            audit.record(session, "tool", name, args.toString(), out.error() ? "error: " + clip(out.text(), 200) : "ok");
        }
        return out;
    }

    // ---- approvals

    private Outcome approve(String session, JsonNode args) {
        String tool = args.path("tool_name").asText("");
        JsonNode input = args.path("input");
        if (tool.isEmpty() || !input.isObject()) {
            return Outcome.fail("approve takes {tool_name, input}");
        }
        ApprovalKind kind = ApprovalKind.ofTool(tool);
        ApprovalDecision decision = ask(session, kind, tool, input.toString(), Summaries.of(tool, input));
        audit.record(session, "approval", tool, input.toString(), decision.toString());
        return Outcome.ok(permissionResult(tool, (ObjectNode) input, decision));
    }

    /** Puts a card in the inbox and waits for the user's answer, or denies at the deadline. */
    private ApprovalDecision ask(String session, ApprovalKind kind, String tool, String inputJson, String summary) {
        long id = approvalIds.incrementAndGet();
        CompletableFuture<ApprovalDecision> answer = new CompletableFuture<>();
        pending.put(id, answer);
        String call = CALL.get();
        if (call != null) {
            approvalByCall.put(call, id);
        }
        sink.dispatch(new Intent.ApprovalRequested(new Approval(id, session, kind, tool, inputJson, summary, clock.millis())));
        try {
            return answer.get(approvalTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            sink.dispatch(new Intent.ApprovalWithdrawn(id, "no answer within " + approvalTimeout.toMinutes() + " minutes"));
            return new ApprovalDecision.Deny("No answer from the user within " + approvalTimeout.toMinutes() + " minutes.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sink.dispatch(new Intent.ApprovalWithdrawn(id, "comando is shutting down"));
            return new ApprovalDecision.Deny("comando is shutting down.");
        } catch (ExecutionException e) {
            return new ApprovalDecision.Deny("Approval failed: " + e.getCause().getMessage());
        } finally {
            pending.remove(id);
            if (call != null) {
                approvalByCall.remove(call, id);
            }
        }
    }

    void resolve(Approval approval, ApprovalDecision decision) {
        CompletableFuture<ApprovalDecision> f = pending.get(approval.id());
        if (f != null) {
            f.complete(decision);
        }
    }

    /** Denies every waiting card; used at shutdown so no call outlives the server. */
    void denyAll(String reason) {
        for (CompletableFuture<ApprovalDecision> f : pending.values()) {
            f.complete(new ApprovalDecision.Deny(reason));
        }
    }

    /** The JSON Claude Code expects back from a permission-prompt tool. */
    String permissionResult(String tool, ObjectNode input, ApprovalDecision decision) {
        ObjectNode r = json.createObjectNode();
        switch (decision) {
            case ApprovalDecision.Deny d -> {
                r.put("behavior", "deny");
                r.put("message", d.message().isBlank() ? "The user denied this." : d.message());
            }
            case ApprovalDecision.Answer a -> {
                r.put("behavior", "allow");
                ObjectNode updated = input.deepCopy();
                ObjectNode answers = updated.putObject("answers");
                a.answers().forEach(answers::put);
                r.set("updatedInput", updated);
            }
            case ApprovalDecision.Allow a -> {
                r.put("behavior", "allow");
                r.set("updatedInput", input);
                if (a.scope() != ApprovalDecision.Scope.ONCE) {
                    ObjectNode update = r.putArray("updatedPermissions").addObject();
                    update.put("type", "addRules");
                    ObjectNode rule = update.putArray("rules").addObject();
                    rule.put("toolName", tool);
                    if (tool.equals("Bash") && input.hasNonNull("command")) {
                        rule.put("ruleContent", input.path("command").asText());
                    } else if (tool.equals("WebFetch") && hostOf(input.path("url").asText("")) != null) {
                        rule.put("ruleContent", "domain:" + hostOf(input.path("url").asText(""))); // that host, not every URL
                    }
                    update.put("behavior", "allow");
                    update.put("destination", a.scope() == ApprovalDecision.Scope.SESSION ? "session" : "localSettings");
                }
            }
        }
        return r.toString();
    }

    /** The host of {@code url}, or null when it has none. */
    static String hostOf(String url) {
        try {
            String host = java.net.URI.create(url.strip()).getHost();
            return host == null || host.isBlank() ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---- UI tools

    private Outcome show(String session, String title, ViewSpec spec, JsonNode args) {
        Placement placement = "side".equalsIgnoreCase(args.path("placement").asText("lower")) ? Placement.SIDE : Placement.LOWER;
        sink.dispatch(new Intent.ShowView(session, title, spec, placement));
        return Outcome.ok("shown '" + title + "' (" + spec.type() + ") in the " + placement.name().toLowerCase(Locale.ROOT) + " panel");
    }

    private Outcome edit(String session, JsonNode args, EditMode mode) {
        JsonNode text = args.path("text");
        if (!text.isTextual()) {
            return Outcome.fail("text is required");
        }
        PanelState panel = targetPanel(session, args.path("panel").asText(""));
        sink.dispatch(new Intent.EditPanel(panel.id(), text.asText(), mode));
        return Outcome.ok(mode.name().toLowerCase(Locale.ROOT) + " in panel '" + panel.title() + "'");
    }

    /**
     * A named panel; else the focused one when it is bound to the caller; else the caller's first. A session
     * bound to no panel must name one, so it never writes into another session's panel by default.
     */
    private PanelState targetPanel(String session, String name) {
        AppState s = state.get();
        if (!name.isBlank()) {
            for (PanelState p : s.panels()) {
                if (p.title().equalsIgnoreCase(name) || p.id().value().equals(name)) {
                    return p;
                }
            }
            throw new IllegalArgumentException("no panel '" + name + "'; panels: "
                    + String.join(", ", s.panels().stream().map(PanelState::title).toList()));
        }
        PanelState focused = s.focusedPanel();
        if (focused.session().equals(session)) {
            return focused;
        }
        List<PanelState> mine = s.panelsBoundToSession(session);
        if (mine.isEmpty()) {
            throw new IllegalArgumentException("this session is bound to no panel; pass panel (one of: "
                    + String.join(", ", s.panels().stream().map(PanelState::title).toList()) + ')');
        }
        return mine.getFirst();
    }

    private Outcome openFile(String session, JsonNode args) {
        String p = args.path("path").asText("");
        if (p.isBlank()) {
            return Outcome.fail("path is required");
        }
        Path path = Path.of(p);
        if (!path.isAbsolute()) {
            String base = state.get().session(session).map(x -> x.spec().workDir()).orElse(System.getProperty("user.home"));
            path = Path.of(base).resolve(path);
        }
        try {
            if (!Files.isRegularFile(path)) {
                return Outcome.fail("not a file: " + path);
            }
            if (Files.size(path) > MAX_FILE_BYTES) {
                return Outcome.fail("file larger than " + MAX_FILE_BYTES / (1024 * 1024) + " MB: " + path);
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            int line = args.path("line").asInt(0);
            sink.dispatch(new Intent.ShowView(session, path.getFileName().toString(),
                    new ViewSpec.Text(path.toString(), text, syntaxOf(path), line), Placement.SIDE));
            return Outcome.ok("opened " + path + (line > 0 ? " at line " + line : ""));
        } catch (IOException e) {
            return Outcome.fail("cannot read " + path + ": " + e.getMessage());
        }
    }

    static String syntaxOf(Path path) {
        String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1);
    }

    /**
     * Focuses a panel, selects a lower tab, shows or hides panes and moves the split; every argument is
     * optional. Every argument is checked before anything changes, so a refused call changed nothing.
     */
    private Outcome layout(JsonNode args) {
        AppState s = state.get();
        String panel = args.path("panel").asText("");
        PanelState target = null;
        if (!panel.isEmpty()) {
            target = s.panels().stream().filter(x -> x.id().value().equals(panel) || x.title().equals(panel)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no panel with id or title '" + panel + "'"));
        }
        String tab = args.path("lower_tab").asText("");
        LowerTab lower = null;
        if (!tab.isEmpty()) {
            lower = switch (tab.toLowerCase(Locale.ROOT)) {
                case "results" -> LowerTab.RESULTS;
                case "session" -> LowerTab.SESSION;
                case "approvals" -> LowerTab.APPROVALS;
                case "terminal" -> LowerTab.TERMINAL;
                default -> s.views().stream().filter(v -> v.placement() == Placement.LOWER
                                && (String.valueOf(v.id()).equals(tab) || v.title().equals(tab))).findFirst()
                        .map(v -> LowerTab.view(v.id()))
                        .orElseThrow(() -> new IllegalArgumentException("lower_tab is results, session, approvals, terminal, or the id or "
                                + "title of a view in the lower panel; no view '" + tab + "'"));
            };
        }
        Layout layout = new Layout(toggle(args.path("sidebar")), toggle(args.path("side_pane")),
                args.path("lower_share").isNumber() ? args.path("lower_share").asDouble() : Double.NaN);
        StringBuilder done = new StringBuilder();
        if (target != null) {
            sink.dispatch(new Intent.FocusPanel(target.id()));
            done.append("focused panel ").append(target.title()).append("; ");
        }
        if (lower != null) {
            sink.dispatch(new Intent.SelectLowerTab(lower));
            done.append("selected lower tab ").append(tab).append("; ");
        }
        if (!layout.changesNothing()) {
            sink.dispatch(new Intent.Arrange(layout));
            done.append("arranged ").append(layout).append("; ");
        }
        return done.isEmpty() ? Outcome.fail("nothing to change: pass panel, lower_tab, sidebar, side_pane or lower_share")
                : Outcome.ok(done.substring(0, done.length() - 2));
    }

    /** Closes a view this session placed and the user has not pinned; the user's own views are not Claude's to close. */
    private Outcome closeView(String session, JsonNode args) {
        JsonNode id = args.path("id");
        if (!id.canConvertToLong() && !(id.isTextual() && id.asText().matches("\\d+"))) {
            throw new IllegalArgumentException("id must be a view id from ui_get_state");
        }
        long viewId = id.asLong();
        ViewState v = state.get().views().stream().filter(x -> x.id() == viewId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no view with id " + viewId));
        if (!v.session().equals(session)) {
            throw new IllegalArgumentException("view " + viewId + " was not placed by this session");
        }
        if (v.pinned()) {
            throw new IllegalArgumentException("view " + viewId + " is pinned by the user");
        }
        sink.dispatch(new Intent.CloseView(viewId));
        return Outcome.ok("closed view " + viewId + " '" + v.title() + "'");
    }

    private static Layout.Toggle toggle(JsonNode n) {
        return n.isBoolean() ? (n.asBoolean() ? Layout.Toggle.SHOW : Layout.Toggle.HIDE) : Layout.Toggle.KEEP;
    }

    private Outcome notify(JsonNode args) {
        String text = args.path("text").asText("");
        if (text.isBlank()) {
            return Outcome.fail("text is required");
        }
        Notice.Level level = switch (args.path("level").asText("info").toLowerCase(Locale.ROOT)) {
            case "warn", "warning" -> Notice.Level.WARN;
            case "error" -> Notice.Level.ERROR;
            default -> Notice.Level.INFO;
        };
        sink.dispatch(new Intent.Notify(level, text, true));
        return Outcome.ok("notified");
    }

    String stateJson() {
        AppState s = state.get();
        ObjectNode root = json.createObjectNode();
        ArrayNode panels = root.putArray("panels");
        for (PanelState p : s.panels()) {
            ObjectNode n = panels.addObject();
            n.put("id", p.id().value());
            n.put("title", p.title());
            n.put("connection", p.connection());
            n.put("session", p.session());
            n.put("focused", p.id().equals(s.focused()));
            s.runOf(p.id()).ifPresent(r -> {
                ObjectNode run = n.putObject("lastRun");
                run.put("sql", clip(r.sql(), 500));
                run.put("status", r.status().name());
                run.put("rows", r.rowCount());
                if (!r.error().isEmpty()) {
                    run.put("error", r.error());
                }
            });
        }
        ArrayNode conns = root.putArray("connections");
        for (ConnectionState c : s.connections()) {
            ObjectNode n = conns.addObject();
            n.put("name", c.name());
            n.put("target", c.spec().label());
            n.put("status", c.status().name());
            n.put("prod", c.spec().prod());
        }
        ArrayNode sessions = root.putArray("sessions");
        for (SessionState x : s.sessions()) {
            ObjectNode n = sessions.addObject();
            n.put("name", x.name());
            n.put("status", x.status().name());
            n.put("workDir", x.spec().workDir());
            n.put("busy", x.isBusy());
        }
        ArrayNode views = root.putArray("views");
        for (ViewState v : s.views()) {
            ObjectNode n = views.addObject();
            n.put("id", v.id());
            n.put("title", v.title());
            n.put("type", v.spec().type());
            n.put("placement", v.placement().name().toLowerCase(Locale.ROOT));
            n.put("pinned", v.pinned());
        }
        root.put("lowerTab", s.lowerTab().kind().name().toLowerCase(Locale.ROOT));
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- database tools

    private Outcome runSql(String session, JsonNode args) {
        String sql = args.path("sql").asText("").strip();
        if (sql.isEmpty()) {
            return Outcome.fail("sql is required");
        }
        ConnectionSpec spec = connectionFor(session, args.path("connection").asText(""));
        int maxRows = Math.max(1, Math.min(args.path("max_rows").asInt(MAX_SQL_ROWS), 10_000));
        if (!SqlPolicy.isReadOnly(sql)) {
            if (spec.prod()) {
                return Outcome.fail("connection " + spec.name() + " is a production connection; comando refuses statements that are not read-only");
            }
            ObjectNode input = json.createObjectNode();
            input.put("connection", spec.name());
            input.put("sql", sql);
            ApprovalDecision d = ask(session, ApprovalKind.SQL_WRITE, "db_run_sql", input.toString(),
                    "Run on " + spec.name() + ": " + firstLine(sql));
            audit.record(session, "approval", "db_run_sql", sql, d.toString());
            if (d instanceof ApprovalDecision.Deny deny) {
                return Outcome.fail("The user denied the statement" + (deny.message().isBlank() ? "." : ": " + deny.message()));
            }
        }
        QueryPort.Rows rows;
        CompletableFuture<QueryPort.Rows> pending = db.query(spec, sql, maxRows);
        try {
            rows = pending.get(90, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return Outcome.fail(e.getCause().getMessage());
        } catch (TimeoutException e) {
            // withdrawn: a statement still queued behind the user's runs will not run after Claude was told it failed
            pending.cancel(false);
            return Outcome.fail("no result within 90 s on " + spec.name() + "; a statement still queued behind the user's runs was withdrawn "
                    + "and will not run; one already running ends at its 60 s statement timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.fail("interrupted");
        }
        if (args.path("show").asBoolean(false) && !rows.columns().isEmpty()) {
            sink.dispatch(new Intent.ShowView(session, args.path("title").asText(firstLine(sql)),
                    new ViewSpec.Table(rows.columns(), rows.rows()), Placement.LOWER));
        }
        return Outcome.ok(format(rows));
    }

    private Outcome describe(String session, JsonNode args) {
        String table = args.path("table").asText("").strip();
        if (table.isEmpty()) {
            return Outcome.fail("table is required");
        }
        ConnectionSpec spec = connectionFor(session, args.path("connection").asText(""));
        try {
            return Outcome.ok(format(db.describe(spec, table).get(60, TimeUnit.SECONDS)));
        } catch (ExecutionException e) {
            return Outcome.fail(e.getCause().getMessage());
        } catch (TimeoutException e) {
            return Outcome.fail("no answer within 60 s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.fail("interrupted");
        }
    }

    /** The named connection; else the one bound to the caller's panel; else the focused panel's. */
    private ConnectionSpec connectionFor(String session, String name) {
        AppState s = state.get();
        String chosen = name;
        if (chosen.isBlank()) {
            if (s.panelsBoundToSession(session).isEmpty()) {
                throw new IllegalArgumentException("this session is bound to no panel; pass connection (one of: " + connectionNames(s) + ')');
            }
            chosen = targetPanel(session, "").connection();
        }
        if (chosen.isBlank()) {
            throw new IllegalArgumentException("no connection given and the panel has none; connections: " + connectionNames(s));
        }
        String finalName = chosen;
        Optional<ConnectionState> c = s.connection(finalName);
        return c.map(ConnectionState::spec).orElseThrow(() -> new IllegalArgumentException("no connection '" + finalName
                + "'; connections: " + connectionNames(s)));
    }

    private static String connectionNames(AppState s) {
        return String.join(", ", s.connections().stream().map(ConnectionState::name).toList());
    }

    /** Rows as tab-separated text with a header, clipped to {@link #MAX_RESULT_CHARS}. */
    static String format(QueryPort.Rows rows) {
        if (rows.columns().isEmpty()) {
            return rows.updateCount() >= 0 ? rows.updateCount() + " rows changed" : "done";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\t", rows.columns())).append('\n');
        int shown = 0;
        for (List<String> row : rows.rows()) {
            if (sb.length() > MAX_RESULT_CHARS) {
                break;
            }
            sb.append(String.join("\t", row)).append('\n');
            shown++;
        }
        sb.append('(').append(shown).append(shown == 1 ? " row" : " rows");
        if (shown < rows.rows().size() || rows.truncated()) {
            sb.append(", more rows exist; refine the query or raise max_rows");
        }
        return sb.append(')').toString();
    }

    private static String firstLine(String s) {
        int eol = s.indexOf('\n');
        return clip(eol < 0 ? s : s.substring(0, eol), 120);
    }

    static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** For tests: approvals waiting for an answer. */
    Map<Long, CompletableFuture<ApprovalDecision>> pending() {
        return pending;
    }
}
