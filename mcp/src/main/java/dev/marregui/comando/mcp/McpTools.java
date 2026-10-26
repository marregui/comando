package dev.marregui.comando.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.marregui.comando.core.dispatch.Classifier;
import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.PermissionRules;
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
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.core.view.FileView;
import dev.marregui.comando.core.view.Layout;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.function.Predicate;
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
    /** Blocks {@code session_read} shows without a cursor: the end of the transcript. */
    static final int READ_TAIL = 40;
    static final int MAX_WAIT_SECONDS = 600;

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
    private final TranscriptRegistry transcripts;
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
    /** The session each pending approval was asked by, so a session's exit denies what it still waits on. */
    private final ConcurrentHashMap<Long, String> askedBy = new ConcurrentHashMap<>();
    /** Whether a session's process still runs; set by the server, which knows its tickets. */
    private volatile Predicate<String> live = session -> true;
    /** Panels that take no text: a log follows its program and is never written. */
    private volatile Predicate<PanelId> readOnly = panel -> false;
    /** The answer to every card of a session that ended while the card waited. */
    private static final ApprovalDecision.Deny SESSION_ENDED = new ApprovalDecision.Deny("The session ended before the user answered.");
    /** The JSON-RPC call this request thread serves; each request runs on its own thread. */
    private static final ThreadLocal<String> CALL = new ThreadLocal<>();

    public McpTools(IntentSink sink, Supplier<AppState> state, TranscriptRegistry transcripts, QueryPort db, AuditLog audit, Clock clock,
                    Duration approvalTimeout) {
        this.sink = sink;
        this.state = state;
        this.transcripts = transcripts;
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
            return invoke(session, name, args);
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

    private Outcome invoke(String session, String name, JsonNode args) {
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
                // neither lands inside the user's text: append at the end, insert after the block the user ran last
                case "ui_insert" -> edit(session, args, "insert".equals(args.path("mode").asText("append")) ? EditMode.INSERT : EditMode.APPEND);
                case "ui_open_file" -> openFile(session, args);
                case "ui_notify" -> notify(args);
                case "ui_get_state" -> Outcome.ok(stateJson());
                case "ui_set_layout" -> layout(session, args);
                case "ui_close_view" -> closeView(session, args);
                case "db_run_sql" -> runSql(session, args);
                case "db_describe" -> describe(session, args);
                case "session_send" -> sessionSend(session, args);
                case "session_read" -> sessionRead(args);
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
        ApprovalDecision decision = ask(session, kind, tool, input.toString(), Summaries.of(tool, input), args.path("tool_use_id").asText(""));
        audit.record(session, "approval", tool, input.toString(), decision.toString());
        return Outcome.ok(permissionResult(tool, (ObjectNode) input, decision));
    }

    /** Puts a card in the inbox and waits for the user's answer, or denies at the deadline. */
    private ApprovalDecision ask(String session, ApprovalKind kind, String tool, String inputJson, String summary, String toolUseId) {
        long id = approvalIds.incrementAndGet();
        CompletableFuture<ApprovalDecision> answer = new CompletableFuture<>();
        pending.put(id, answer);
        askedBy.put(id, session);
        String call = CALL.get();
        if (call != null) {
            approvalByCall.put(call, id);
        }
        try {
            // a session that ended before its card was shown gets no card: its process is gone and nothing it asked runs
            if (!live.test(session)) {
                return SESSION_ENDED;
            }
            sink.dispatch(new Intent.ApprovalRequested(new Approval(id, session, kind, tool, inputJson, summary, clock.millis(), toolUseId)));
            ApprovalDecision d = answer.get(approvalTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (d == SESSION_ENDED) {
                sink.dispatch(new Intent.ApprovalWithdrawn(id, "the session ended"));
            }
            return d;
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
            askedBy.remove(id);
            if (call != null) {
                approvalByCall.remove(call, id);
            }
        }
    }

    /** Tells the tools which panels take no text (a log); the application calls it once, with the store's view. */
    public void readOnly(Predicate<PanelId> readOnly) {
        this.readOnly = readOnly;
    }

    /** Tells the tools which sessions still run; the server calls it once, with its view of the tickets. */
    void live(Predicate<String> live) {
        this.live = live;
    }

    /** A session's process ended: every card it still waits on is denied, and withdrawn by the request it answers. */
    void sessionEnded(String session) {
        askedBy.forEach((id, asker) -> {
            if (asker.equals(session)) {
                CompletableFuture<ApprovalDecision> answer = pending.get(id);
                if (answer != null) {
                    answer.complete(SESSION_ENDED);
                }
            }
        });
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
                if (a.scope() == ApprovalDecision.Scope.ONCE) {
                    break;
                }
                ObjectNode update = r.putArray("updatedPermissions").addObject();
                if (ApprovalKind.ofTool(tool) == ApprovalKind.PLAN) {
                    // a plan approved beyond once switches the session to accepting edits, as the terminal's third plan choice does
                    update.put("type", "setMode");
                    update.put("mode", "acceptEdits");
                    update.put("destination", "session");
                    break;
                }
                PermissionRules.Rules rules = PermissionRules.rules(tool, field -> input.path(field).asText(""));
                update.put("type", "addRules");
                ArrayNode entries = update.putArray("rules");
                for (PermissionRules.Rule rule : rules.rules()) {
                    ObjectNode entry = entries.addObject();
                    entry.put("toolName", rule.toolName());
                    if (!rule.ruleContent().isEmpty()) {
                        entry.put("ruleContent", rule.ruleContent());
                    }
                }
                update.put("behavior", "allow");
                // the card offers no Always for a rule that may not be saved; an Always for one still stays in the session
                boolean save = a.scope() == ApprovalDecision.Scope.ALWAYS && rules.persistable();
                update.put("destination", save ? "localSettings" : "session");
            }
        }
        return r.toString();
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
        if (!panel.session().isEmpty() && !panel.session().equals(session) && !args.path("force").asBoolean(false)) {
            return Outcome.fail("panel '" + panel.title() + "' belongs to session " + panel.session()
                    + "; pass force: true only when the user asked you to write there");
        }
        if (readOnly.test(panel.id())) {
            return Outcome.fail("panel '" + panel.title() + "' shows a log, which is read-only; nothing was written");
        }
        sink.dispatch(new Intent.EditPanel(panel.id(), text.asText(), mode, session));
        return Outcome.ok(mode.name().toLowerCase(Locale.ROOT) + " in panel '" + panel.title() + "'");
    }

    /**
     * A named panel; else the focused one when it is bound to the caller; else the caller's first. A session
     * bound to no panel must name one, so it never writes into another session's panel by default.
     */
    private PanelState targetPanel(String session, String name) {
        AppState s = state.get();
        if (!name.isBlank()) {
            return panelNamed(s, name).orElseThrow(() -> new IllegalArgumentException("no panel '" + name + "'; panels: "
                    + String.join(", ", s.panels().stream().map(PanelState::title).toList())));
        }
        PanelState focused = s.focusedPanel();
        if (focused.session().equals(session)) {
            return focused;
        }
        List<PanelState> mine = s.panelsBoundToSession(session);
        if (mine.isEmpty()) {
            String panels = String.join(", ", s.panels().stream().map(PanelState::title).toList());
            Optional<PanelState> closed = s.closedPanelOfSession(session);
            throw new IllegalArgumentException(closed.isPresent()
                    ? "this session's panel " + closed.get().title() + " is closed; the user reopens it by opening the session in the sidebar, or pass panel (one of: " + panels + ')'
                    : "this session is bound to no panel; pass panel (one of: " + panels + ')');
        }
        return mine.getFirst();
    }

    /** The panel with id {@code name}, or titled {@code name} in any case: the one lookup every tool naming a panel uses. */
    private static Optional<PanelState> panelNamed(AppState s, String name) {
        return s.panels().stream().filter(p -> p.id().value().equals(name) || p.title().equalsIgnoreCase(name)).findFirst();
    }

    /** Shows a file in the side pane, read as the user's click on a link reads it ({@link FileView}). */
    private Outcome openFile(String session, JsonNode args) {
        String p = args.path("path").asText("");
        if (p.isBlank()) {
            return Outcome.fail("path is required");
        }
        String base = state.get().session(session).map(x -> x.spec().workDir()).orElse(HomeDir.home());
        Path path = FileView.resolve(p, base);
        int line = args.path("line").asInt(0);
        FileView file;
        try {
            file = FileView.read(path, line);
        } catch (IOException e) {
            return Outcome.fail("cannot open " + path + ": " + e.getMessage());
        }
        sink.dispatch(new Intent.ShowView(session, file.title(), file.spec(), Placement.SIDE));
        return Outcome.ok(file.binary() ? "opened " + path + ": a binary file, so the view says so instead of showing it"
                : "opened " + path + " (" + file.spec().type() + ")" + (line > 0 ? " at line " + line : ""));
    }

    /**
     * Focuses a panel, selects a lower tab, shows or hides panes and moves the split; every argument is
     * optional. Every argument is checked before anything changes, so a refused call changed nothing.
     */
    private Outcome layout(String session, JsonNode args) {
        AppState s = state.get();
        String panel = args.path("panel").asText("");
        PanelState target = null;
        if (!panel.isEmpty()) {
            target = panelNamed(s, panel).orElseThrow(() -> new IllegalArgumentException("no panel with id or title '" + panel + "'"));
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
        if (target == null && lower == null && layout.changesNothing()) {
            return Outcome.fail("nothing to change: pass panel, lower_tab, sidebar, side_pane or lower_share");
        }
        // the user's window: a session may focus only its own panels, and select a tab only while the user looks at
        // one of them; the user sees a notice and can undo an arrangement with View > Restore layout
        if (target != null && !target.session().equals(session)) {
            return Outcome.fail("panel " + target.title() + " is not bound to this session; a session may focus only its own panels");
        }
        sink.dispatch(new Intent.SessionLayout(session, target == null ? null : target.id(), lower, layout.changesNothing() ? null : layout));
        StringBuilder done = new StringBuilder();
        if (target != null && s.focused().equals(target.id())) {
            done.append("panel ").append(target.title()).append(" already has the focus")
                    .append(lower != null ? "; selected lower tab " + tab : "").append("; ");
        } else if (target != null) {
            // the focus, and a tab with it, wait for a pause in the user's typing
            done.append("will focus panel ").append(target.title()).append(lower != null ? " and select lower tab " + tab : "")
                    .append(" once the user pauses typing; ");
        } else if (lower != null) {
            boolean mine = s.focusedPanel().session().equals(session);
            done.append(mine ? "selected lower tab " + tab : "did not select lower tab " + tab
                    + ": the user is looking at a panel of another session").append("; ");
        }
        if (!layout.changesNothing()) {
            done.append("arranged ").append(layout).append(" once the user pauses typing; ");
        }
        return Outcome.ok(done.substring(0, done.length() - 2));
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
            if (x.isWaiting()) {
                n.put("waitingOn", x.waitingFor());
            }
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

    // ---- sessions

    /** The first line of a prompt one session sends another: the receiving session and the user both see who sent it. */
    static String fromLine(String session) {
        return "[from session " + session + "]";
    }

    /**
     * Sends a prompt to another session through its panel, without asking: the sessions are trusted with each other
     * (record 44). The prompt, headed with the sender, is added to that panel as a block of its own and run there, so
     * the user sees what was sent, by whom, and what the session does with it; the audit log records it.
     */
    private Outcome sessionSend(String session, JsonNode args) {
        String to = args.path("session").asText("").strip();
        String text = args.path("text").asText("").strip();
        if (to.isEmpty() || text.isEmpty()) {
            return Outcome.fail("session and text are required");
        }
        if (to.equals(session)) {
            return Outcome.fail("session " + to + " is this session; session_send prompts another session");
        }
        AppState s = state.get();
        if (s.session(to).isEmpty()) {
            return Outcome.fail("no session '" + to + "'; sessions: " + sessionNames(s));
        }
        if (!s.session(to).orElseThrow().status().isUsable()) {
            // stopping a session is the user's circuit breaker for sessions that keep prompting each other: another
            // session never starts it again (record 44)
            return Outcome.fail("session " + to + " is not running; a session another session prompts must be running: the user starts it");
        }
        // a log panel takes no text, so the user would not see the prompt there
        List<PanelState> panels = s.panelsBoundToSession(to).stream().filter(p -> !readOnly.test(p.id())).toList();
        if (panels.isEmpty()) {
            return Outcome.fail("session " + to + " has no open panel that takes text; the prompt goes through its panel so the user "
                    + "sees it: the user opens one for it first");
        }
        PanelState panel = panels.getFirst();
        String prompt = fromLine(session) + '\n' + text;
        ObjectNode input = json.createObjectNode();
        input.put("session", to);
        input.put("panel", panel.title());
        input.put("text", prompt);
        audit.record(session, "call", "session_send", input.toString(), "sent");
        int cursor = transcripts.of(to).size();
        sink.dispatch(new Intent.EditPanel(panel.id(), prompt, EditMode.APPEND, session));
        sink.dispatch(new Intent.RunText(panel.id(), prompt, Target.CLAUDE));
        boolean busy = s.session(to).map(SessionState::isBusy).orElse(false);
        return Outcome.ok("sent to " + to + " in panel " + panel.title() + (busy ? "; it is busy, so the prompt waits for its turn" : "")
                + "; read what it does with session_read {\"session\": \"" + to + "\", \"after\": " + cursor + ", \"wait_seconds\": 300}");
    }

    /** Another session's transcript from a cursor on, after waiting, when asked, for that session to finish its turns. */
    private Outcome sessionRead(JsonNode args) {
        String of = args.path("session").asText("").strip();
        if (of.isEmpty()) {
            return Outcome.fail("session is required");
        }
        if (state.get().session(of).isEmpty()) {
            return Outcome.fail("no session '" + of + "'; sessions: " + sessionNames(state.get()));
        }
        Transcript t = transcripts.of(of);
        JsonNode cursor = args.path("after");
        int after = cursor.canConvertToInt() ? Math.max(0, cursor.asInt()) : Math.max(0, t.size() - READ_TAIL);
        int wait = Math.max(0, Math.min(args.path("wait_seconds").asInt(0), MAX_WAIT_SECONDS));
        boolean settled = wait == 0 || awaitTurnEnd(of, t, after, wait);
        SessionState x = state.get().session(of).orElse(null);
        String status = x == null ? "removed" : x.isBusy() ? "busy" : x.isWaiting() ? "waiting on background work: " + x.waitingFor()
                : x.status().isUsable() ? "idle" : x.status().name().toLowerCase(Locale.ROOT);
        if (!settled) {
            status += ", still working after " + wait + " s";
        }
        return Outcome.ok(TranscriptText.read(of, status, t, after, MAX_RESULT_CHARS).text());
    }

    /**
     * Waits until the session has blocks after {@code after}, none of them still streaming or running, and its turns
     * are over (a turn end last, or the session no longer running); false at the deadline. A transcript shorter than
     * the cursor was cleared since: the read returns at once and says it restarted, rather than wait for blocks past a
     * cursor that no longer applies.
     */
    private boolean awaitTurnEnd(String session, Transcript t, int after, int seconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (true) {
            Optional<SessionState> x = state.get().session(session);
            if (x.isEmpty()) {
                return true;
            }
            int size = t.size();
            if (size < after) {
                return true;
            }
            if (!x.get().isBusy() && settled(t, after, size) && (t.get(size - 1) instanceof Block.TurnEnd || !x.get().status().isUsable())) {
                return true;
            }
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static boolean settled(Transcript t, int after, int size) {
        if (size <= after) {
            return false;
        }
        for (int i = after; i < size; i++) {
            Block b = t.get(i);
            if (b == null || !TranscriptText.finished(b)) {
                return false;
            }
        }
        return true;
    }

    private static String sessionNames(AppState s) {
        return String.join(", ", s.sessions().stream().map(SessionState::name).toList());
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
                    "Run on " + spec.name() + ": " + firstLine(sql), "");
            audit.record(session, "approval", "db_run_sql", sql, d.toString());
            if (d instanceof ApprovalDecision.Deny deny) {
                return Outcome.fail("The user denied the statement" + (deny.message().isBlank() ? "." : ": " + deny.message()));
            }
            // the user approved the target the card named: a connection repointed or removed meanwhile is not it
            ConnectionSpec now = connectionFor(session, args.path("connection").asText(""));
            if (!now.equals(spec)) {
                return Outcome.fail("connection " + spec.name() + " changed while the card was open; nothing ran. Ask again.");
            }
        }
        QueryPort.Rows rows;
        CompletableFuture<QueryPort.Rows> result = db.query(spec, sql, maxRows);
        // a schema change reloads the schema behind completion and an open explorer, as a panel's run does; a failed
        // block may have run its earlier statements
        boolean ddl = Classifier.anyDdl(sql);
        try {
            rows = result.get(90, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (ddl) {
                sink.dispatch(new Intent.SchemaChanged(spec.name()));
            }
            return Outcome.fail(e.getCause().getMessage());
        } catch (TimeoutException e) {
            // withdrawn: a statement still queued behind the user's runs will not run after Claude was told it failed
            result.cancel(false);
            return Outcome.fail("no result within 90 s on " + spec.name() + "; a statement still queued behind the user's runs was withdrawn "
                    + "and will not run; one already running ends at its 60 s statement timeout");
        } catch (InterruptedException e) {
            // comando is closing: a statement still queued must not run after the caller was told it failed
            result.cancel(false);
            Thread.currentThread().interrupt();
            return Outcome.fail("interrupted");
        }
        if (ddl) {
            sink.dispatch(new Intent.SchemaChanged(spec.name()));
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
        CompletableFuture<QueryPort.Rows> result = db.describe(spec, table);
        try {
            return Outcome.ok(format(result.get(60, TimeUnit.SECONDS)));
        } catch (ExecutionException e) {
            return Outcome.fail(e.getCause().getMessage());
        } catch (TimeoutException e) {
            result.cancel(false);
            return Outcome.fail("no answer within 60 s");
        } catch (InterruptedException e) {
            result.cancel(false);
            Thread.currentThread().interrupt();
            return Outcome.fail("interrupted");
        }
    }

    /** The named connection; else the one bound to the caller's panel; else the focused panel's. */
    private ConnectionSpec connectionFor(String session, String name) {
        AppState s = state.get();
        String chosen = name;
        if (chosen.isBlank()) {
            Optional<PanelState> closed = s.closedPanelOfSession(session);
            if (s.panelsBoundToSession(session).isEmpty() && closed.isEmpty()) {
                throw new IllegalArgumentException("this session is bound to no panel; pass connection (one of: " + connectionNames(s) + ')');
            }
            // a closed panel keeps its bindings: its connection is still the session's
            chosen = s.panelsBoundToSession(session).isEmpty() ? closed.get().connection() : targetPanel(session, "").connection();
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
        sb.append(clip(String.join("\t", rows.columns()), MAX_RESULT_CHARS)).append('\n');
        int shown = 0;
        boolean cut = false;
        for (List<String> row : rows.rows()) {
            int room = MAX_RESULT_CHARS - sb.length();
            if (room <= 0) {
                break;
            }
            // the budget holds for every character: one huge cell is cut, not passed whole
            String line = String.join("\t", row);
            cut = line.length() > room;
            sb.append(clip(line, room)).append('\n');
            shown++;
            if (cut) {
                break;
            }
        }
        sb.append('(').append(shown).append(shown == 1 ? " row" : " rows");
        if (cut) {
            sb.append(", the last cut at " + MAX_RESULT_CHARS / 1024 + " kB");
        }
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
        return Clip.keepStart(s, max);
    }

    /** For tests: approvals waiting for an answer. */
    Map<Long, CompletableFuture<ApprovalDecision>> pending() {
        return pending;
    }
}
