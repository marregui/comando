package dev.marregui.comando.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.marregui.comando.core.action.Action;
import dev.marregui.comando.core.action.Actions;
import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.StartMode;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.ServerSwitch;
import dev.marregui.comando.core.reduce.Reducer;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.ViewState;

/**
 * The actions of the registry that are not among comando's first tools (record 121): their schemas, and performing them
 * for a caller. Two rules hold for every caller: an approval card is never answered by the session that raised it nor
 * by one of its creators, and a destructive action runs only for a caller the user granted it in the data root's
 * {@code agents.json}, or, on a session, for one of that session's creators. Every act posts a notice naming the caller.
 */
final class ActionTools {

    /**
     * The grant that lets a caller run destructive actions, in {@code agents.json}:
     * {@code {"grants": {"<caller>": ["destructive"]}}}.
     */
    static final String DESTRUCTIVE = "destructive";
    private static final int CHAIN_LIMIT = 32;

    private final IntentSink sink;
    private final Supplier<AppState> state;
    private final ObjectMapper json = new ObjectMapper();
    private volatile Path agentsFile;
    private volatile List<ServerSwitch> servers = List.of();

    ActionTools(final IntentSink sink, final Supplier<AppState> state) {
        this.sink = sink;
        this.state = state;
    }

    /** Where the user's grants live; without one no caller holds a grant. */
    void agentsFile(final Path file) {
        this.agentsFile = file;
    }

    /** The servers {@code server_start} and {@code server_stop} reach; none until the application sets them. */
    void servers(final List<ServerSwitch> servers) {
        this.servers = List.copyOf(servers);
    }

    /** The servers, for the state's {@code servers}. */
    List<ServerSwitch> servers() {
        return servers;
    }

    /** Whether {@code tool} is one of the actions this class performs. */
    static boolean handles(final String tool, final Set<String> builtIn) {
        return !builtIn.contains(tool) && Actions.byTool(tool).isPresent();
    }

    /** The tool definitions of the actions not in {@code builtIn}, in the registry's order. */
    ArrayNode definitions(final Set<String> builtIn) {
        final ArrayNode out = json.createArrayNode();
        for (final Action a : Actions.ALL) {
            if (builtIn.contains(a.tool())) {
                continue;
            }
            final ObjectNode d = out.addObject();
            d.put("name", a.tool());
            d.put("description", a.description() + (a.kind() == Action.Kind.DESTRUCTIVE
                    ? " Destructive: runs only if the user granted this caller \"destructive\" in agents.json"
                    + (onSession(a) ? ", or this caller created the session." : ".") : ""));
            final ObjectNode schema = d.putObject("inputSchema");
            schema.put("type", "object");
            final ObjectNode props = schema.putObject("properties");
            final ArrayNode required = json.createArrayNode();
            for (final Action.Arg arg : a.args()) {
                final ObjectNode p = props.putObject(arg.name());
                switch (arg.type()) {
                    case STRING -> p.put("type", "string");
                    case INTEGER -> p.put("type", "integer");
                    case BOOLEAN -> p.put("type", "boolean");
                    case STRINGS -> p.put("type", "array").putObject("items").put("type", "string");
                    case OBJECT -> p.put("type", "object");
                    case ANY -> { }
                }
                if (!arg.description().isEmpty()) {
                    p.put("description", arg.description());
                }
                if (!arg.choices().isEmpty()) {
                    arg.choices().forEach(p.putArray("enum")::add);
                }
                if (arg.required()) {
                    required.add(arg.name());
                }
            }
            if (!required.isEmpty()) {
                schema.set("required", required);
            }
        }
        return out;
    }

    /**
     * Performs action {@code tool} for {@code caller}; bad input is an {@link IllegalArgumentException} the caller
     * turns into a tool error.
     */
    McpTools.Outcome run(final String caller, final String tool, final JsonNode args) {
        final Action action = Actions.byTool(tool).orElseThrow(() -> new IllegalArgumentException("unknown tool " + tool));
        final AppState s = state.get();
        if (action.kind() == Action.Kind.DESTRUCTIVE && !granted(caller, DESTRUCTIVE) && !created(caller, action, s, args)) {
            return McpTools.Outcome.fail(tool + " is destructive: the user has not granted " + caller + " \"destructive\" (agents.json in comando's "
                    + "data root, {\"grants\": {\"" + caller + "\": [\"destructive\"]}})" + (onSession(action) ? " and " + caller
                    + " did not create the session" : "") + "; ask the user to do it or to grant it");
        }
        final String done = switch (tool) {
            case "session_new" -> sessionNew(caller, s, args);
            case "session_edit" -> sessionEdit(s, args);
            case "session_open" -> send(new Intent.OpenSession(session(s, args).name(), s.focused(), PanelId.random()));
            case "session_start" -> startable(caller, s, session(s, args), "start", new Intent.StartSession(session(s, args).name()));
            case "session_fresh" -> startable(caller, s, session(s, args), "start fresh", new Intent.StartFresh(session(s, args).name()));
            case "session_stop" -> send(new Intent.StopSession(session(s, args).name()));
            case "session_interrupt" -> send(new Intent.InterruptSession(session(s, args).name()));
            case "session_checkpoint" -> send(new Intent.Checkpoint(session(s, args).name()));
            case "session_command" -> send(new Intent.SessionCommand(session(s, args).name(), command(args)));
            case "session_model" -> send(new Intent.SetSessionModel(session(s, args).name(), args.path("model").asString("").strip()));
            case "session_effort" -> send(new Intent.SetSessionEffort(session(s, args).name(), args.path("effort").asString("").strip()));
            case "session_order" -> send(new Intent.OrderSessions(strings(args, "names")));
            case "session_import" -> send(new Intent.ImportSessions(List.of(SessionSpec.imported(name(args, "name"), dir(args),
                    text(args, "conversation")).withCreatedBy(caller))));
            case "session_clear" -> send(new Intent.ClearTranscript(session(s, args).name()));
            case "session_remove" -> send(new Intent.RemoveSession(session(s, args).name()));
            case "session_subagent" -> send(new Intent.OpenSubagent(session(s, args).name(), text(args, "tool_use_id")));
            case "panel_add" -> panelAdd(s, args);
            case "panel_close" -> send(new Intent.ClosePanel(panel(s, args, false).id()));
            case "panel_reopen" -> send(new Intent.ReopenPanel(panel(s, args, true).id()));
            case "panel_remove" -> send(new Intent.RemovePanel(panel(s, args, true).id()));
            case "panel_rename" -> send(new Intent.RenamePanel(panel(s, args, false).id(), text(args, "title")));
            case "panel_move" -> send(new Intent.MovePanel(panel(s, args, false).id(), args.path("index").asInt(0)));
            case "panel_focus" -> send(new Intent.FocusPanel(panel(s, args, false).id()));
            case "panel_bind_session" -> send(new Intent.BindSession(panel(s, args, false).id(), args.path("session").asString("").strip()));
            case "panel_bind_connection" -> send(new Intent.BindConnection(panel(s, args, false).id(), args.path("connection").asString("").strip()));
            case "panel_run" -> send(new Intent.RunText(panel(s, args, false).id(), text(args, "text"), target(args)));
            case "panel_cancel" -> send(new Intent.CancelRun(panel(s, args, false).id()));
            case "panel_refresh" -> send(new Intent.RefreshSql(panel(s, args, false).id(), text(args, "sql")));
            case "approval_answer" -> approvalAnswer(caller, s, args);
            case "connection_save" -> connectionSave(s, args);
            case "connection_test" -> send(new Intent.TestConnection(connection(s, args).spec()));
            case "connection_open" -> send(new Intent.OpenConnection(connection(s, args).name()));
            case "connection_close" -> send(new Intent.CloseConnection(connection(s, args).name()));
            case "connection_schema" -> send(new Intent.ExploreSchema(connection(s, args).name()));
            case "connection_remove" -> send(new Intent.RemoveConnection(connection(s, args).name()));
            case "view_pin" -> send(new Intent.PinView(view(s, args).id(), args.path("pinned").asBoolean(true)));
            case "view_close" -> viewClose(s, args);
            case "notice_dismiss" -> send(new Intent.DismissNotice());
            case "gradle_run" -> send(new Intent.RunGradle(panel(s, args, false).id(), text(args, "tasks")));
            case "command_cancel" -> send(new Intent.CancelCommand(args.path("run_id").asLong(0)));
            case "server_start" -> server(args).want(true);
            case "server_stop" -> server(args).want(false);
            case "folders_browse" -> send(new Intent.BrowseFolders(args.has("names") ? strings(args, "names")
                    : s.folders().stream().map(f -> f.name()).toList()));
            default -> throw new IllegalArgumentException(tool + " has no runner yet");
        };
        if (action.kind() != Action.Kind.READ && !done.startsWith("refused")) {
            sink.dispatch(new Intent.Notify(Notice.Level.INFO, caller + ": " + tool.replace('_', ' ') + " — " + done, false, ""));
        }
        return done.startsWith("refused") ? McpTools.Outcome.fail(done) : McpTools.Outcome.ok(done);
    }

    private String sessionNew(final String caller, final AppState s, final JsonNode args) {
        final String name = name(args, "name");
        if (s.session(name).isPresent()) {
            throw new IllegalArgumentException("a session called " + name + " exists; sessions: " + names(s));
        }
        final SessionSpec spec = new SessionSpec(name, dir(args), args.path("model").asString("").strip(), args.path("effort").asString("").strip(),
                args.path("permission_mode").asString("").strip(), args.has("add_dirs") ? strings(args, "add_dirs") : List.of(), 0.0, "",
                StartMode.NEW, args.has("checkpoint") ? args.path("checkpoint").asString("") : SessionSpec.DEFAULT_CHECKPOINT, "", caller);
        // a new session comes with its own panel (record 88); the prompt goes there, resolved when it is reduced
        sink.dispatch(new Intent.SaveSession("", spec));
        final String prompt = args.path("prompt").asString("").strip();
        if (prompt.isEmpty()) {
            return "created session " + name + " in " + spec.workDir() + ", open in its panel; start it with session_start or give it a prompt "
                    + "with session_send once started";
        }
        sink.dispatch(new Intent.PromptSession(name, "[from session " + caller + "]\n" + prompt, caller));
        return "created session " + name + " in " + spec.workDir() + " and started it with the prompt; read what it does with session_read "
                + "{\"session\": \"" + name + "\", \"wait_seconds\": 300}";
    }

    private String sessionEdit(final AppState s, final JsonNode args) {
        final SessionSpec old = session(s, args).spec();
        final String name = args.has("new_name") ? name(args, "new_name") : old.name();
        final SessionSpec spec = new SessionSpec(name,
                args.has("work_dir") ? dir(args) : old.workDir(),
                args.has("model") ? args.path("model").asString("").strip() : old.model(),
                args.has("effort") ? args.path("effort").asString("").strip() : old.effort(),
                args.has("permission_mode") ? args.path("permission_mode").asString("").strip() : old.permissionMode(),
                args.has("add_dirs") ? strings(args, "add_dirs") : old.addDirs(), old.budgetUsd(), old.claudeSessionId(), old.startMode(),
                args.has("checkpoint") ? args.path("checkpoint").asString("") : old.checkpoint(), old.carried(), old.createdBy());
        return send(new Intent.SaveSession(old.name(), spec));
    }

    /** Whether the action works on one session, named by its {@code session} argument. */
    private static boolean onSession(final Action a) {
        return a.area().equals("session") && a.args().stream().anyMatch(x -> x.name().equals("session"));
    }

    /**
     * Whether {@code caller} created, directly or through others, the session a session action names: the creator of a
     * session may clear or remove it without a grant, as it may start it again (record 44).
     */
    private static boolean created(final String caller, final Action a, final AppState s, final JsonNode args) {
        final String name = args.path("session").asString("").strip();
        return onSession(a) && !name.isEmpty() && s.session(name).isPresent() && creators(s, name).contains(caller);
    }

    private ServerSwitch server(final JsonNode args) {
        final String name = text(args, "server");
        return servers.stream().filter(x -> x.name().equalsIgnoreCase(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no server '" + name + "'; servers: "
                        + servers.stream().map(x -> x.name().toLowerCase(Locale.ROOT)).toList()));
    }

    /**
     * Record 44's circuit breaker, kept: a stopped session is started again by the user or by the session that created
     * it (directly or through others), never by an unrelated one.
     */
    private String startable(final String caller, final AppState s, final SessionState target, final String what, final Intent intent) {
        if (!creators(s, target.name()).contains(caller)) {
            return "refused: " + caller + " did not create session " + target.name() + "; only the user or its creator may " + what
                    + " it (record 44: stopping is the user's circuit breaker)";
        }
        return send(intent);
    }

    /** Who created {@code session}, then who created that one, and so on; the user is not in it. */
    static Set<String> creators(final AppState s, final String session) {
        final Set<String> chain = new HashSet<>();
        String at = session;
        for (int i = 0; i < CHAIN_LIMIT; i++) {
            final Optional<SessionState> x = s.session(at);
            if (x.isEmpty() || x.get().spec().createdBy().isEmpty() || !chain.add(x.get().spec().createdBy())) {
                break;
            }
            at = x.get().spec().createdBy();
        }
        return chain;
    }

    private String approvalAnswer(final String caller, final AppState s, final JsonNode args) {
        final long id = args.path("id").asLong(-1);
        final Approval card = s.approvals().stream().filter(a -> a.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no approval card " + id + "; pending: "
                        + s.approvals().stream().map(a -> a.id() + " (" + a.session() + ", " + a.toolName() + ")").toList()));
        if (card.session().equals(caller) || creators(s, card.session()).contains(caller)) {
            return "refused: card " + id + " was raised by " + card.session() + (card.session().equals(caller) ? ", this caller" : ", which " + caller
                    + " created") + "; someone other than the asker decides (record 121, rule 1)";
        }
        final ApprovalDecision decision = switch (args.path("decision").asString("").strip()) {
            case "allow" -> new ApprovalDecision.Allow(switch (args.path("scope").asString("once").strip()) {
                case "session" -> ApprovalDecision.Scope.SESSION;
                case "always" -> ApprovalDecision.Scope.ALWAYS;
                default -> ApprovalDecision.Scope.ONCE;
            });
            case "deny" -> new ApprovalDecision.Deny(args.path("message").asString("Denied by " + caller + "."));
            case "answer" -> {
                final Map<String, String> answers = new LinkedHashMap<>();
                args.path("answers").properties().forEach(e -> answers.put(e.getKey(), e.getValue().asString("")));
                if (answers.isEmpty()) {
                    throw new IllegalArgumentException("answer needs answers: {question text: chosen option label}");
                }
                yield new ApprovalDecision.Answer(answers);
            }
            default -> throw new IllegalArgumentException("decision is allow, deny or answer");
        };
        return send(new Intent.ApprovalAnswered(id, decision));
    }

    private String connectionSave(final AppState s, final JsonNode args) {
        final String previous = args.path("previous_name").asString("").strip();
        final Optional<ConnectionState> old = previous.isEmpty() ? s.connection(name(args, "name")) : s.connection(previous);
        final String password = args.has("password") ? args.path("password").asString("") : old.map(c -> c.spec().password()).orElse("");
        final ConnectionSpec spec = new ConnectionSpec(name(args, "name"), text(args, "host"), args.path("port").asInt(ConnectionSpec.DEFAULT_PORT),
                text(args, "database"), args.path("user").asString("").strip(), password, args.path("production").asBoolean(false),
                args.path("ssl_mode").asString("").strip());
        return send(new Intent.SaveConnection(old.isPresent() ? old.get().name() : previous, spec));
    }

    private String panelAdd(final AppState s, final JsonNode args) {
        String title = args.path("title").asString("").strip();
        if (title.isEmpty()) {
            int n = 1;
            while (titled(s, "panel " + n)) {
                n++;
            }
            title = "panel " + n;
        } else if (titled(s, title)) {
            throw new IllegalArgumentException("a panel called " + title + " exists");
        }
        return send(new Intent.AddPanel(PanelId.random(), title, args.path("document").asBoolean(false)));
    }

    private String viewClose(final AppState s, final JsonNode args) {
        final ViewState v = view(s, args);
        if (v.pinned()) {
            return "refused: view " + v.id() + " is pinned; unpin it with view_pin first";
        }
        return send(new Intent.CloseView(v.id()));
    }

    private static boolean titled(final AppState s, final String title) {
        return Stream.concat(s.panels().stream(), s.closed().stream()).anyMatch(p -> p.title().equalsIgnoreCase(title));
    }

    private String send(final Intent intent) {
        // the reducer drops an action on a session whose checkpoint runs without a word: say so to the agent (record 130)
        if (Reducer.refusedWhileCheckpointing(state.get(), intent)) {
            return "refused: the session is running its checkpoint (record 130); only session_interrupt or session_stop act on it "
                    + "until the checkpoint and its compaction end";
        }
        sink.dispatch(intent);
        return "done: " + intent.getClass().getSimpleName();
    }

    private static SessionState session(final AppState s, final JsonNode args) {
        final String name = text(args, "session");
        return s.session(name).orElseThrow(() -> new IllegalArgumentException("no session '" + name + "'; sessions: " + names(s)));
    }

    private static PanelState panel(final AppState s, final JsonNode args, final boolean orClosed) {
        final String key = text(args, "panel");
        final Stream<PanelState> all = orClosed ? Stream.concat(s.panels().stream(), s.closed().stream()) : s.panels().stream();
        return all.filter(p -> p.id().value().equals(key) || p.title().equalsIgnoreCase(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no " + (orClosed ? "" : "open ") + "panel '" + key + "'; panels: "
                        + s.panels().stream().map(PanelState::title).toList()
                        + (orClosed && !s.closed().isEmpty() ? ", closed: " + s.closed().stream().map(PanelState::title).toList() : "")));
    }

    private static ConnectionState connection(final AppState s, final JsonNode args) {
        final String name = text(args, "name");
        return s.connection(name).orElseThrow(() -> new IllegalArgumentException("no connection '" + name + "'; connections: "
                + s.connections().stream().map(ConnectionState::name).toList()));
    }

    private static ViewState view(final AppState s, final JsonNode args) {
        final long id = args.path("id").asLong(-1);
        return s.views().stream().filter(v -> v.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no view " + id + "; views: " + s.views().stream().map(v -> v.id() + " " + v.title()).toList()));
    }

    private static Target target(final JsonNode args) {
        return switch (args.path("target").asString("").strip().toLowerCase(Locale.ROOT)) {
            case "sql" -> Target.SQL;
            case "claude" -> Target.CLAUDE;
            default -> Target.AUTO;
        };
    }

    private static String command(final JsonNode args) {
        final String c = text(args, "command");
        if (!c.startsWith("/")) {
            throw new IllegalArgumentException("a command starts with a slash: /compact, /clear, …");
        }
        return c;
    }

    private static String dir(final JsonNode args) {
        final String d = HomeDir.expand(text(args, "work_dir"));
        if (!Files.isDirectory(Path.of(d))) {
            throw new IllegalArgumentException("work_dir " + d + " is not a folder");
        }
        return Path.of(d).toAbsolutePath().normalize().toString();
    }

    /** A session's or connection's name; the spec's constructor checks its form, and its error reaches the caller. */
    private static String name(final JsonNode args, final String field) {
        return text(args, field);
    }

    private static String text(final JsonNode args, final String field) {
        final String t = args.path(field).asString("").strip();
        if (t.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return t;
    }

    private static List<String> strings(final JsonNode args, final String field) {
        final JsonNode a = args.path(field);
        if (!a.isArray()) {
            throw new IllegalArgumentException(field + " is a list of strings");
        }
        final List<String> out = new ArrayList<>();
        a.forEach(x -> out.add(x.asString("").strip()));
        return out;
    }

    private static String names(final AppState s) {
        return s.sessions().stream().map(SessionState::name).toList().toString();
    }

    /** Whether {@code agents.json} grants {@code caller} the grant {@code what}; an unreadable file grants nothing. */
    boolean granted(final String caller, final String what) {
        final Path file = agentsFile;
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        try {
            final JsonNode grants = json.readTree(Files.readString(file)).path("grants").path(caller);
            for (final JsonNode g : grants) {
                if (what.equals(g.asString(""))) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
