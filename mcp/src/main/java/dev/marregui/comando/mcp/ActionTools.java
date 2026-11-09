package dev.marregui.comando.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.marregui.comando.core.action.Action;
import dev.marregui.comando.core.action.Actions;
import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.Decision;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.StartMode;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.ServerSwitch;
import dev.marregui.comando.core.port.TerminalPort;
import dev.marregui.comando.core.reduce.Reducer;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.view.Placement;

/**
 * The actions of the registry that are not among comando's first tools (record 121): their schemas, and performing them
 * for a caller. Two rules hold for every caller: an approval card is never answered from the creation family of the
 * session that raised it (itself, its creators, and every session it or any of them made, siblings too), only by the
 * user or an unrelated session, and a destructive action runs only for a caller the user granted it
 * in the data root's {@code agents.json}, or, on a session, for one of that session's creators. A stopped session is
 * started again only for its creators (record 44), by whichever action reaches it. A connection's production flag and
 * TLS mode are lowered only through a card the user allows, and a connection pointed elsewhere loses its stored password
 * (records 20, 23). Text reaches a panel's Terminal only through a card the user allows, unless the user granted the
 * caller {@link #TERMINAL} (record 148). Every act posts a notice naming the caller.
 * <p>
 * An action asks the reducer first whether it would refuse the intent ({@link Reducer#refusal}) and answers that
 * refusal; otherwise it answers {@code sent: <intent>}: the intent is applied in order, after what the window queued
 * before it, and a refusal that arises meanwhile (a checkpoint that began) is a notice naming the caller.
 */
final class ActionTools {

    /** Puts a card before the user for {@code session} and waits for the answer: {@link McpTools}' approval machinery. */
    @FunctionalInterface
    interface Asker {
        ApprovalDecision ask(String session, ApprovalKind kind, String tool, String inputJson, String summary);
    }

    /**
     * The grant that lets a caller run destructive actions, in {@code agents.json}:
     * {@code {"grants": {"<caller>": ["destructive"]}}}.
     */
    static final String DESTRUCTIVE = "destructive";
    /**
     * The grant that lets a caller type into a panel's Terminal without a card, in {@code agents.json}:
     * {@code {"grants": {"<caller>": ["terminal"]}}} (record 148).
     */
    static final String TERMINAL = "terminal";
    private final IntentSink sink;
    private final Supplier<AppState> state;
    private final Asker asker;
    private final ObjectMapper json = new ObjectMapper();
    private final TerminalTools terminals;
    private volatile Path agentsFile;
    private volatile List<ServerSwitch> servers = List.of();

    ActionTools(final IntentSink sink, final Supplier<AppState> state, final Asker asker) {
        this.sink = sink;
        this.state = state;
        this.asker = asker;
        this.terminals = new TerminalTools(asker, caller -> granted(caller, TERMINAL), json);
    }

    /** Where the user's grants live; without one no caller holds a grant. */
    void agentsFile(final Path file) {
        this.agentsFile = file;
    }

    /** The servers {@code server_start} and {@code server_stop} reach; none until the application sets them. */
    void servers(final List<ServerSwitch> servers) {
        this.servers = List.copyOf(servers);
    }

    /** The panels' Terminals the terminal tools reach; until the application sets them, every one answers not ready. */
    void terminals(final TerminalPort port) {
        terminals.port(port);
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
                    case INTEGERS -> p.put("type", "array").putObject("items").put("type", "integer");
                    case OBJECT -> p.put("type", "object");
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
        checkArgs(action, args);
        final AppState s = state.get();
        if (action.kind() == Action.Kind.DESTRUCTIVE && !granted(caller, DESTRUCTIVE) && !created(caller, action, s, args)) {
            return McpTools.Outcome.fail(tool + " is destructive: the user has not granted " + caller + " \"destructive\" (agents.json in comando's "
                    + "data root, {\"grants\": {\"" + caller + "\": [\"destructive\"]}})" + (onSession(action) ? " and " + caller
                    + " did not create the session" : "") + "; ask the user to do it or to grant it");
        }
        final String done = switch (tool) {
            case "session_new" -> sessionNew(caller, s, args);
            case "session_edit" -> sessionEdit(caller, s, args);
            case "session_open" -> send(new Intent.OpenSession(session(s, args).name(), s.focused(), PanelId.random()));
            case "session_start" -> startable(caller, s, session(s, args), "start", new Intent.StartSession(session(s, args).name()));
            case "session_fresh" -> startable(caller, s, session(s, args), "start fresh", new Intent.StartFresh(session(s, args).name(), caller));
            case "session_stop" -> send(new Intent.StopSession(session(s, args).name()));
            case "session_interrupt" -> send(new Intent.InterruptSession(session(s, args).name()));
            case "session_checkpoint" -> send(new Intent.Checkpoint(session(s, args).name(), caller));
            case "session_command" -> send(new Intent.SessionCommand(session(s, args).name(), command(args), caller));
            case "session_model" -> send(new Intent.SetSessionModel(session(s, args).name(), args.path("model").asString("").strip(), caller));
            case "session_effort" -> send(new Intent.SetSessionEffort(session(s, args).name(), args.path("effort").asString("").strip(), caller));
            case "session_order" -> send(new Intent.OrderSessions(strings(args, "names")));
            case "session_import" -> send(new Intent.ImportSessions(List.of(SessionSpec.imported(name(args, "name"), dir(args),
                    text(args, "conversation")).withCreatedBy(caller))));
            case "session_clear" -> send(new Intent.ClearTranscript(session(s, args).name(), caller));
            case "session_remove" -> send(new Intent.RemoveSession(session(s, args).name(), caller));
            case "session_subagent" -> send(new Intent.OpenSubagent(session(s, args).name(), text(args, "tool_use_id")));
            case "panel_add" -> panelAdd(s, args);
            case "panel_close" -> send(new Intent.ClosePanel(panel(s, args, false).id()));
            case "panel_reopen" -> send(new Intent.ReopenPanel(panel(s, args, true).id()));
            case "panel_remove" -> send(new Intent.RemovePanel(panel(s, args, true).id(), caller));
            case "panel_rename" -> send(new Intent.RenamePanel(panel(s, args, false).id(), text(args, "title")));
            case "panel_move" -> send(new Intent.MovePanel(panel(s, args, false).id(), args.path("index").asInt(0)));
            case "panel_place" -> send(new Intent.PlacePanel(panel(s, args, false).id(),
                    args.path("side").asBoolean(true)));
            case "panel_pin" -> send(new Intent.PinPanel(panel(s, args, false).id()));
            case "panel_focus" -> send(new Intent.FocusPanel(panel(s, args, false).id()));
            case "panel_bind_session" -> send(new Intent.BindSession(panel(s, args, false).id(), args.path("session").asString("").strip()));
            case "panel_bind_connection" -> send(new Intent.BindConnection(panel(s, args, false).id(), args.path("connection").asString("").strip()));
            case "panel_run" -> send(new Intent.RunText(panel(s, args, false).id(), text(args, "text"), target(args), caller));
            case "panel_cancel" -> send(new Intent.CancelRun(panel(s, args, false).id()));
            case "panel_refresh" -> send(new Intent.RefreshSql(panel(s, args, false).id(), text(args, "sql")));
            case "approval_answer" -> approvalAnswer(caller, s, args);
            case "decision_hold" -> decisionHold(caller, s, args);
            case "decisions_settled" -> send(new Intent.DecisionsSettled(ids(args)));
            case "decision_settle" -> send(new Intent.SettleDecision(decision(s, args).id(), caller));
            case "decision_dismiss" -> send(new Intent.DismissDecision(decision(s, args).id()));
            case "connection_save" -> connectionSave(caller, s, args);
            case "connection_test" -> send(new Intent.TestConnection(connection(s, args).spec()));
            case "connection_open" -> send(new Intent.OpenConnection(connection(s, args).name()));
            case "connection_close" -> send(new Intent.CloseConnection(connection(s, args).name()));
            case "connection_schema" -> send(new Intent.ExploreSchema(connection(s, args).name()));
            case "connection_remove" -> send(new Intent.RemoveConnection(connection(s, args).name()));
            case "view_pin" -> send(new Intent.PinView(view(s, args).id(), args.path("pinned").asBoolean(true)));
            case "view_place" -> send(new Intent.PlaceView(view(s, args).id(), placement(args)));
            case "view_close" -> viewClose(s, args);
            case "notice_dismiss" -> send(new Intent.DismissNotice());
            case "gradle_run" -> send(new Intent.RunGradle(panel(s, args, false).id(), text(args, "tasks")));
            case "command_cancel" -> send(new Intent.CancelCommand(args.path("run_id").asLong(0)));
            case "server_start" -> server(args).want(true);
            case "server_stop" -> server(args).want(false);
            case "folders_browse" -> send(new Intent.BrowseFolders(args.has("names") ? strings(args, "names")
                    : s.folders().stream().map(f -> f.name()).toList()));
            case "terminal_list" -> terminals.list();
            case "terminal_start" -> terminals.start(s, args);
            case "terminal_send" -> terminals.send(caller, s, args);
            case "terminal_read" -> terminals.read(s, args);
            case "terminal_interrupt" -> terminals.interrupt(s, args);
            case "terminal_stop" -> terminals.stop(s, args);
            default -> throw new IllegalArgumentException(tool + " has no runner yet");
        };
        // a decision held back brings nothing forward (record 131): no notice
        if (action.kind() != Action.Kind.READ && !done.startsWith("refused") && !"decision_hold".equals(tool)) {
            sink.dispatch(new Intent.Notify(Notice.Level.INFO, caller + ": " + tool.replace('_', ' ') + " — " + done, false, ""));
        }
        // a page of a Terminal's text may itself begin with "refused": a read fails only for its own refusals
        final boolean refused = "terminal_read".equals(tool) ? !TerminalTools.isPage(done) : done.startsWith("refused");
        return refused ? McpTools.Outcome.fail(done) : McpTools.Outcome.ok(done);
    }

    private String sessionNew(final String caller, final AppState s, final JsonNode args) {
        final String name = name(args, "name");
        if (s.session(name).isPresent()) {
            throw new IllegalArgumentException("a session called " + name + " exists; sessions: " + names(s));
        }
        final SessionSpec spec = new SessionSpec(name, dir(args), args.path("model").asString("").strip(), args.path("effort").asString("").strip(),
                0.0, "", StartMode.NEW, args.has("checkpoint") ? args.path("checkpoint").asString("") : SessionSpec.DEFAULT_CHECKPOINT, "", caller);
        // a new session comes with its own panel (record 88), whose title no other panel may hold: refused as the reducer
        // would refuse it, so the answer never reports a session that is not made
        final Intent.SaveSession save = new Intent.SaveSession("", spec, caller);
        final Optional<String> refused = Reducer.refusal(s, save);
        if (refused.isPresent()) {
            return "refused: " + refused.get();
        }
        // the prompt goes to its panel, resolved when it is reduced
        sink.dispatch(save);
        final String prompt = args.path("prompt").asString("").strip();
        if (prompt.isEmpty()) {
            return "created session " + name + " in " + spec.workDir() + ", open in its panel; start it with session_start or give it a prompt "
                    + "with session_send once started";
        }
        sink.dispatch(new Intent.PromptSession(name, "[from session " + caller + "]\n" + prompt, caller));
        return "created session " + name + " in " + spec.workDir() + " and started it with the prompt; read what it does with session_read "
                + "{\"session\": \"" + name + "\", \"wait_seconds\": 300}";
    }

    private String sessionEdit(final String caller, final AppState s, final JsonNode args) {
        final SessionSpec old = session(s, args).spec();
        final String name = args.has("new_name") ? name(args, "new_name") : old.name();
        final SessionSpec spec = new SessionSpec(name,
                args.has("work_dir") ? dir(args) : old.workDir(),
                args.has("model") ? args.path("model").asString("").strip() : old.model(),
                args.has("effort") ? args.path("effort").asString("").strip() : old.effort(),
                old.budgetUsd(), old.claudeSessionId(), old.startMode(),
                args.has("checkpoint") ? args.path("checkpoint").asString("") : old.checkpoint(), old.carried(), old.createdBy());
        return send(new Intent.SaveSession(old.name(), spec, caller));
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
        return onSession(a) && !name.isEmpty() && s.session(name).isPresent() && s.creators(name).contains(caller);
    }

    private ServerSwitch server(final JsonNode args) {
        final String name = text(args, "server");
        return servers.stream().filter(x -> x.name().equalsIgnoreCase(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no server '" + name + "'; servers: "
                        + servers.stream().map(x -> x.name().toLowerCase(Locale.ROOT)).toList()));
    }

    /**
     * Refuses a call whose arguments break the published schema before anything reads them: a required argument absent
     * (or null), a value outside its choices, an integer that is none. An absent argument would otherwise read as
     * {@code ""} or {@code 0}, and move a tab to the front, unbind a panel or cancel run 0 for a call that named none.
     * An empty string given on purpose stays a value: "empty unbinds".
     */
    static void checkArgs(final Action action, final JsonNode args) {
        for (final Action.Arg arg : action.args()) {
            final JsonNode v = args == null ? null : args.get(arg.name());
            if (v == null || v.isNull()) {
                if (arg.required()) {
                    throw new IllegalArgumentException(action.tool() + " needs \"" + arg.name() + "\""
                            + (arg.description().isEmpty() ? "" : ": " + arg.description())
                            + (arg.choices().isEmpty() ? "" : "; one of " + arg.choices()));
                }
                continue;
            }
            if (arg.type() == Action.Type.STRING && !arg.choices().isEmpty() && !arg.choices().contains(v.asString("").strip())) {
                throw new IllegalArgumentException(action.tool() + ": \"" + arg.name() + "\" is " + v + "; one of " + arg.choices());
            }
            if (arg.type() == Action.Type.INTEGER && !v.isIntegralNumber() && !(v.isString() && v.asString("").strip().matches("-?\\d{1,18}"))) {
                throw new IllegalArgumentException(action.tool() + ": \"" + arg.name() + "\" is " + v + "; an integer");
            }
        }
    }

    /**
     * Record 44's circuit breaker, kept: a stopped session is started again by the user or by the session that created
     * it (directly or through others), never by an unrelated one.
     */
    private String startable(final String caller, final AppState s, final SessionState target, final String what, final Intent intent) {
        if (!s.creators(target.name()).contains(caller)) {
            return "refused: " + caller + " did not create session " + target.name() + "; only the user or its creator may " + what
                    + " it (record 44: stopping is the user's circuit breaker)";
        }
        return send(intent);
    }

    private String approvalAnswer(final String caller, final AppState s, final JsonNode args) {
        final long id = args.path("id").asLong(-1);
        final Approval card = s.approvals().stream().filter(a -> a.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no approval card " + id + "; pending: "
                        + s.approvals().stream().map(a -> a.id() + " (" + a.session() + ", " + a.toolName() + ")").toList()));
        // anyone of the asker's creation family: itself, its creators, and every session it or any of them made,
        // siblings and cousins too; their work is one, so it would decide its own card (record 121, rule 1)
        final String relation = card.session().equals(caller) ? ", this caller"
                : s.creators(card.session()).contains(caller) ? ", which " + caller + " created"
                : s.creators(caller).contains(card.session()) ? ", which created " + caller
                : s.sameFamily(card.session(), caller) ? ", of " + caller + "'s creation family" : null;
        if (relation != null) {
            return "refused: card " + id + " was raised by " + card.session() + relation + "; someone other than the asker decides (record 121, rule 1)";
        }
        if (card.kind() == ApprovalKind.CONNECTION) {
            // clearing production or lowering TLS guards the user's data against agents: no agent allows it (D2)
            return "refused: card " + id + " weakens connection guards; only the user answers it, in the window (record 23)";
        }
        if (card.kind() == ApprovalKind.TERMINAL) {
            // what the card types runs with the user's rights: no agent allows it for another
            return "refused: card " + id + " types into a Terminal with the user's rights; only the user answers it, "
                    + "in the window (record 148)";
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

    /**
     * Holds a decision back for the calling session (records 131 and 142), with the options its card offers, if any.
     * Only a session holds a decision back: the Later group lists each under the session whose checkpoint or report
     * held it. It answers no id: the checkpoint that settles it later reads the ids from {@code ui_get_state}.
     */
    private String decisionHold(final String caller, final AppState s, final JsonNode args) {
        if (s.session(caller).isEmpty()) {
            return "refused: " + caller + " is not a session; a decision is held back by the session whose automatic checkpoint runs";
        }
        final List<String> options = args.has("options") ? options(args) : List.of();
        final String detail = args.path("detail").asString("").strip();
        final String sent = send(new Intent.DecisionHeldBack(caller, text(args, "title"), detail, options));
        return sent.startsWith("refused") ? sent
                : "held back; it waits in the Approvals tab's Later group until the user answers it there or a "
                        + "checkpoint the user starts settles it";
    }

    private static Decision decision(final AppState s, final JsonNode args) {
        final long id = args.path("id").asLong(-1);
        return s.decision(id).orElseThrow(() -> new IllegalArgumentException("no decision " + id + "; held back: "
                + s.later().stream().map(d -> d.id() + " (" + d.session() + ", " + d.title() + ")").toList()));
    }

    /**
     * {@code decision_hold}'s options: a JSON array of strings, or that array as JSON text, which Claude Code sends for
     * an array argument at times.
     */
    private List<String> options(final JsonNode args) {
        return list(args, "options", "a list of strings, the answers offered", JsonNode::isString, JsonNode::asString);
    }

    /**
     * {@code decisions_settled}'s ids: a JSON array of integers, or that array as JSON text, which Claude Code sends for
     * an array argument at times.
     */
    private List<Long> ids(final JsonNode args) {
        return list(args, "ids", "a list of integers, the decisions' ids from ui_get_state",
                x -> x.isIntegralNumber() && x.canConvertToLong(), JsonNode::asLong);
    }

    /**
     * The array {@code args} holds under {@code field}, or that array as JSON text; every element must be one
     * {@code fits} accepts, and {@code read} takes its value. {@code what} says what the field is, for the refusal.
     */
    private <T> List<T> list(
            final JsonNode args,
            final String field,
            final String what,
            final Predicate<JsonNode> fits,
            final Function<JsonNode, T> read
    ) {
        JsonNode a = args.path(field);
        if (a.isString()) {
            try {
                a = json.readTree(a.asString());
            } catch (final RuntimeException e) {
                throw new IllegalArgumentException(
                        field + " is " + what + " [" + field + "=" + args.path(field) + ']', e);
            }
        }
        if (!a.isArray()) {
            throw new IllegalArgumentException(field + " is " + what + " [" + field + "=" + a + ']');
        }
        final List<T> out = new ArrayList<>();
        for (final JsonNode x : a) {
            if (!fits.test(x)) {
                throw new IllegalArgumentException(field + " is " + what + " [element=" + x + ']');
            }
            out.add(read.apply(x));
        }
        return out;
    }

    /**
     * Adds or changes a connection. A field the call does not name keeps its stored value. A change of host, port,
     * database or user drops the stored password, unless the call gives one: a password never follows a target the user
     * did not name with it (record 20). Clearing the production flag or weakening the TLS mode puts a card before the
     * user, and nothing changes until it is allowed: both guard the user's data against this very caller (record 23).
     */
    private String connectionSave(final String caller, final AppState s, final JsonNode args) {
        final String previous = args.path("previous_name").asString("").strip();
        final Optional<ConnectionState> found = previous.isEmpty() ? s.connection(name(args, "name")) : s.connection(previous);
        final ConnectionSpec spec = connectionSpec(args, found.map(ConnectionState::spec));
        final Intent.SaveConnection save = new Intent.SaveConnection(found.isPresent() ? found.get().name() : previous, spec, args.has("password"));
        if (found.isEmpty()) {
            return send(save);
        }
        final ConnectionSpec old = found.get().spec();
        final List<String> weakened = new ArrayList<>(2);
        if (old.prod() && !spec.prod()) {
            weakened.add("clears production, which refuses Claude's writes");
        }
        if (spec.weakerTls(old)) {
            weakened.add("lowers TLS from " + old.sslMode() + " to " + spec.sslMode());
        }
        if (weakened.isEmpty()) {
            return send(save);
        }
        final ObjectNode input = json.createObjectNode();
        input.put("connection", old.name());
        input.put("change", String.join("; ", weakened));
        input.put("host", spec.host());
        input.put("port", spec.port());
        input.put("database", spec.database());
        input.put("user", spec.user());
        input.put("production", spec.prod());
        input.put("ssl_mode", spec.sslMode());
        final ApprovalDecision d = asker.ask(caller, ApprovalKind.CONNECTION, "connection_save", input.toString(),
                old.name() + ": " + String.join("; ", weakened));
        if (!(d instanceof ApprovalDecision.Allow)) {
            return "refused: the user did not allow " + caller + " to change connection " + old.name() + " (" + String.join("; ", weakened)
                    + "): " + (d instanceof ApprovalDecision.Deny n && !n.message().isBlank() ? n.message() : "denied");
        }
        // the connection may have changed while the card waited: the user's answer was about the one shown
        if (!state.get().connection(old.name()).map(c -> c.spec().equals(old)).orElse(false)) {
            return "refused: connection " + old.name() + " changed while the card waited; read it again and ask again";
        }
        return send(save);
    }

    /** The spec a {@code connection_save} asks for: its fields, and {@code old}'s for what it does not name. */
    private static ConnectionSpec connectionSpec(final JsonNode args, final Optional<ConnectionSpec> old) {
        final String host = args.has("host") || old.isEmpty() ? text(args, "host") : old.get().host();
        final int port = args.has("port") ? args.path("port").asInt(ConnectionSpec.DEFAULT_PORT) : old.map(ConnectionSpec::port).orElse(ConnectionSpec.DEFAULT_PORT);
        final String database = args.has("database") || old.isEmpty() ? text(args, "database") : old.get().database();
        final String user = args.has("user") ? args.path("user").asString("").strip() : old.map(ConnectionSpec::user).orElse("");
        final boolean production = args.has("production") ? args.path("production").asBoolean(false) : old.map(ConnectionSpec::prod).orElse(false);
        final String ssl = args.has("ssl_mode") ? args.path("ssl_mode").asString("").strip() : old.map(ConnectionSpec::sslMode).orElse("");
        final ConnectionSpec asked = new ConnectionSpec(name(args, "name"), host, port, database, user, "", production, ssl);
        // the stored password stays only with the target it was stored for
        final String password = args.has("password") ? args.path("password").asString("")
                : old.filter(asked::sameTarget).map(ConnectionSpec::password).orElse("");
        return asked.withPassword(password);
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

    /**
     * Sends {@code intent} unless the reducer would refuse it on the latest state: the refusal is the answer. Otherwise
     * the intent is applied in order after what was queued before it; a refusal that arises meanwhile is a notice that
     * names the caller.
     */
    private String send(final Intent intent) {
        final Optional<String> refused = Reducer.refusal(state.get(), intent);
        if (refused.isPresent()) {
            return "refused: " + refused.get();
        }
        sink.dispatch(intent);
        return "sent: " + intent.getClass().getSimpleName();
    }

    private static SessionState session(final AppState s, final JsonNode args) {
        final String name = text(args, "session");
        return s.session(name).orElseThrow(() -> new IllegalArgumentException("no session '" + name + "'; sessions: " + names(s)));
    }

    static PanelState panel(final AppState s, final JsonNode args, final boolean orClosed) {
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

    private static Placement placement(final JsonNode args) {
        return "side".equals(text(args, "placement")) ? Placement.SIDE : Placement.LOWER;
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
            final JsonNode root = json.readTree(Mapped.text(file));
            // a file of a later version grants nothing this comando could misread (record 133)
            if (Peers.later(root)) {
                return false;
            }
            final JsonNode grants = root.path("grants").path(caller);
            for (final JsonNode g : grants) {
                if (what.equals(g.asString(""))) {
                    return true;
                }
            }
            return false;
        } catch (final IOException | RuntimeException e) {
            return false;
        }
    }
}
