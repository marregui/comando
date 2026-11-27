package dev.marregui.comando.claude.codex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.stream.Stream;

import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.json.ArrayNode;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.ObjectNode;
import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.Engine;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.StartMode;
import dev.marregui.comando.core.port.ClaudePort;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.McpGateway;
import dev.marregui.comando.core.state.SessionInfo;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;

/**
 * The Codex sessions (ADR 0197): one {@code codex app-server} per running session, its thread started or resumed with
 * comando's permission profile, its prompts sent as turns. Each session has a strand, the only writer of its state;
 * the server's messages and exit hop onto it, so the session's {@link CodexEvents} sees one call at a time.
 */
public final class CodexAdapter implements ClaudePort, AutoCloseable {

    /** The prompts a session holds while a turn runs; one more is refused with a note. Unmeasured, a choice. */
    static final int QUEUED_PROMPTS = 16;
    private static final System.Logger LOG = System.getLogger(CodexAdapter.class.getName());
    /** The version comando's client reports in {@code initialize}, as the plan-usage read does. */
    private static final String CLIENT_VERSION = "1";
    private static final String PERMISSION_PROFILE = "scratch-only";
    private static final String MCP_SERVERS_TABLE = "[mcp_servers.";
    /** The kit's guards a session's commands pass, each at {@code <kit>/hooks/<name>/<name>.py}. */
    private static final List<String> GUARD_HOOKS = List.of("kill-guard", "instance-guard");
    /** The tool names Codex gives its shell commands, as the probe of 2026-11-27 on 0.172.0 matched them. */
    private static final String SHELL_MATCHER = "Bash|shell|exec_command";
    /** Codex's limit on one guard run; the value the probe of 2026-11-27 ran kill-guard with. */
    private static final int HOOK_TIMEOUT_SECONDS = 5; // the kit's settings.json gives its guards 5 s

    private final IntentSink sink;
    private final TranscriptRegistry transcripts;
    private final McpGateway mcp;
    private final CodexSettings settings;
    private final Function<String, CodexEvents> mapperFactory;
    /** The user's Codex configuration, whose MCP servers each thread turns off; it may not exist. */
    private final Path codexConfig;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    /** Each run's session, from its start to its end; read by {@link #close} after the strands stop. */
    private final Map<Run, String> started = new ConcurrentHashMap<>();
    /** Written by {@link #close}; read by any thread that makes a session and by the strands' starts. */
    private volatile boolean closed;

    public CodexAdapter(
            final IntentSink sink,
            final TranscriptRegistry transcripts,
            final McpGateway mcp,
            final CodexSettings settings,
            final Function<String, CodexEvents> mapperFactory
    ) {
        this(sink, transcripts, mcp, settings, mapperFactory, userCodexConfig());
    }

    /** As the public constructor, with the Codex configuration at {@code codexConfig}; for tests. */
    CodexAdapter(
            final IntentSink sink,
            final TranscriptRegistry transcripts,
            final McpGateway mcp,
            final CodexSettings settings,
            final Function<String, CodexEvents> mapperFactory,
            final Path codexConfig
    ) {
        this.sink = sink;
        this.transcripts = transcripts;
        this.mcp = mcp;
        this.settings = settings;
        this.mapperFactory = mapperFactory;
        this.codexConfig = codexConfig;
    }

    @Override
    public void start(final SessionSpec spec) {
        final Session s = session(spec.name());
        s.run(() -> startServer(s, spec));
    }

    @Override
    public void stop(final String name) {
        final Session s = sessions.get(name);
        if (s == null) {
            sink.dispatch(new Intent.SessionExited(name, 0, ""));
            return;
        }
        s.run(() -> stopServer(s));
    }

    @Override
    public void send(final String name, final String text, final String origin, final String preface) {
        send(name, text, origin, preface, false);
    }

    @Override
    public void send(
            final String name,
            final String text,
            final String origin,
            final String preface,
            final boolean delivered
    ) {
        final Session s = session(name);
        s.run(() -> {
            s.events.prompt(text, origin, delivered);
            s.transcript.changed();
            if (s.current == null || !s.current.rpc.isAlive()) {
                note(s, "Session is not running; the prompt was not sent");
                sink.dispatch(new Intent.SessionTurnEnded(name, 0, -1.0, -1L, 0L, true, "session not running"));
                return;
            }
            if (command(s, text.strip())) {
                sink.dispatch(new Intent.SessionTurnEnded(name, pending(s), -1.0, -1L, 0L, false, ""));
                return;
            }
            final String prompt = (preface == null ? "" : preface) + text;
            if (!s.ready || s.turnRunning) {
                if (s.queued.size() >= QUEUED_PROMPTS) {
                    note(s, "Not sent: " + QUEUED_PROMPTS + " prompts already wait for the running turn");
                    sink.dispatch(new Intent.SessionTurnEnded(name, pending(s), -1.0, -1L, 0L, true,
                            "prompt queue full"));
                    return;
                }
                s.queued.add(prompt);
                return;
            }
            startTurn(s, s.current, prompt);
        });
    }

    @Override
    public void interrupt(final String name) {
        final Session s = sessions.get(name);
        if (s == null) {
            return;
        }
        s.run(() -> {
            if (s.current == null || !s.turnRunning) {
                return;
            }
            if (s.turnId.isEmpty()) {
                s.interruptAsked = true; // sent once the turn's id arrives
                return;
            }
            sendInterrupt(s, s.current);
        });
    }

    @Override
    public void clearTranscript(final String name) {
        final Session s = sessions.get(name);
        if (s == null) {
            return;
        }
        s.run(() -> {
            s.transcript.clear();
            s.transcript.changed();
        });
    }

    @Override
    public void loadHistory(final SessionSpec spec) {
        final Session s = session(spec.name());
        s.run(() -> note(s, "Codex sessions do not load the history of their thread"));
    }

    @Override
    public void forget(final String name) {
        final Session s = sessions.remove(name);
        if (s == null) {
            return;
        }
        s.run(() -> {
            final Run run = s.current;
            s.current = null;
            if (run != null) {
                end(s.name, run);
            }
        });
        s.strand.shutdown();
        transcripts.forget(name);
    }

    @Override
    public void showSubagent(final String name, final String claudeSessionId, final String toolUseId) {
        final Session s = session(name);
        s.run(() -> note(s, "Codex sessions have no sub-agent view"));
    }

    /** Stops every server and the strands. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        final Map<String, ExecutorService> strands = new LinkedHashMap<>();
        for (final Session s : sessions.values()) {
            strands.put(s.name, s.strand);
        }
        ExecutorStop.drain("codex", ExecutorStop.WAIT, strands);
        // what still runs after the strands stopped: no exit handler runs for it any more
        for (final Map.Entry<Run, String> e : Map.copyOf(started).entrySet()) {
            end(e.getValue(), e.getKey());
        }
    }

    /**
     * Why a Codex session must not start in {@code workDir}, empty when it may (ADR 0197): a configuration it cannot
     * read, whose MCP servers it then cannot turn off, or an execpolicy rule, which runs a command outside the sandbox
     * and which {@code codex app-server}, unlike {@code codex exec --ignore-rules}, cannot be told to skip.
     */
    static String unsafe(final Path config, final Path workDir) {
        for (final Path file : configLayers(config, workDir)) {
            if (!Files.isReadable(file) || Files.isDirectory(file)) {
                return "cannot read the Codex configuration " + file + ", so its MCP servers cannot be turned off";
            }
        }
        final List<Path> ruleDirs = new ArrayList<>();
        if (config != null && config.getParent() != null) {
            ruleDirs.add(config.getParent().resolve("rules"));
        }
        for (Path dir = workDir.toAbsolutePath(); dir != null; dir = dir.getParent()) {
            ruleDirs.add(dir.resolve(".codex").resolve("rules"));
        }
        for (final Path dir : ruleDirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (final Path file : files.filter(p -> p.toString().endsWith(".rules")).toList()) {
                    if (Mapped.lines(file).stream().anyMatch(l -> l.contains("prefix_rule("))) {
                        return "the execpolicy rules in " + file + " run commands outside the sandbox, and codex "
                                + "app-server cannot skip them; empty that file (Codex adds a rule each time a command "
                                + "is approved for always), then start the session again";
                    }
                }
            } catch (final IOException e) {
                return "cannot read the Codex rules in " + dir + ": " + e.getMessage();
            }
        }
        return "";
    }

    /**
     * The names of the MCP servers Codex would start in {@code workDir}: those {@code config} and every
     * {@code .codex/config.toml} from {@code workDir} up declare, comando's left out. A file that cannot be read
     * declares none here; {@link #unsafe} refuses the start before.
     */
    private static List<String> mcpServers(final Path config, final Path workDir) {
        final Set<String> names = new LinkedHashSet<>();
        for (final Path file : configLayers(config, workDir)) {
            try {
                for (final String raw : Mapped.lines(file)) {
                    final String name = serverName(raw.strip());
                    if (!name.isEmpty() && !"comando".equals(name)) {
                        names.add(name);
                    }
                }
            } catch (final IOException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot read a Codex configuration [file=" + file + ']', e);
            }
        }
        return List.copyOf(names);
    }

    /** The server a {@code [mcp_servers.<name>…]} table header names; empty for any other line. */
    private static String serverName(final String line) {
        if (!line.startsWith(MCP_SERVERS_TABLE) || !line.endsWith("]")) {
            return "";
        }
        final String rest = line.substring(MCP_SERVERS_TABLE.length(), line.length() - 1).strip();
        if (rest.startsWith("\"")) {
            final int close = rest.indexOf('"', 1);
            return close < 0 ? "" : rest.substring(1, close);
        }
        final int dot = rest.indexOf('.');
        return (dot < 0 ? rest : rest.substring(0, dot)).strip();
    }

    /** The configuration files Codex reads for {@code workDir}: the user's, then each project layer that exists. */
    private static List<Path> configLayers(final Path config, final Path workDir) {
        final List<Path> files = new ArrayList<>();
        if (config != null && Files.exists(config)) {
            files.add(config);
        }
        for (Path dir = workDir.toAbsolutePath(); dir != null; dir = dir.getParent()) {
            final Path project = dir.resolve(".codex").resolve("config.toml");
            if (Files.exists(project)) {
                files.add(project);
            }
        }
        return files;
    }

    /** {@code text} as one POSIX shell word: single-quoted, each {@code '} written {@code '\\''}. */
    private static String shellQuoted(final String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    /** The effort a turn runs at: empty is Codex's default, and Claude's {@code max} is Codex's {@code xhigh}. */
    private static String effortOf(final String effort) {
        final String e = effort == null ? "" : effort.strip();
        if (e.isEmpty()) {
            return Engine.CODEX_EFFORT;
        }
        return "max".equals(e) ? "xhigh" : e;
    }

    /** {@code $CODEX_HOME/config.toml}, else {@code ~/.codex/config.toml}: where the Codex CLI reads its settings. */
    private static Path userCodexConfig() {
        final String home = System.getenv("CODEX_HOME");
        if (home != null && !home.isBlank()) {
            return Path.of(home, "config.toml");
        }
        return Path.of(System.getProperty("user.home"), ".codex", "config.toml");
    }

    /** The Codex CLI's version in an {@code initialize} result's user agent ({@code name/version (os...)}). */
    private static String cliVersion(final ObjectNode result) {
        final String agent = result.path("userAgent").asString("");
        final int slash = agent.indexOf('/');
        if (slash < 0) {
            return "";
        }
        final int space = agent.indexOf(' ', slash);
        return agent.substring(slash + 1, space < 0 ? agent.length() : space);
    }

    private static String lastLine(final String text) {
        final List<String> lines = text.strip().lines().toList();
        return lines.isEmpty() ? "" : lines.getLast();
    }

    private static String messageOf(final Throwable t) {
        final Throwable cause = t.getCause() != null ? t.getCause() : t;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    /**
     * The session, made on first use; one made during or after {@link #close} takes no task, so no server starts then.
     */
    private Session session(final String name) {
        final Session known = sessions.get(name);
        if (known != null) {
            return known;
        }
        final boolean[] made = new boolean[1];
        final Session s = sessions.computeIfAbsent(name, n -> {
            made[0] = true;
            return new Session(n);
        });
        if (made[0] && closed) {
            s.strand.shutdown();
        }
        return s;
    }

    /** The parameters of {@code thread/start}, and of {@code thread/resume} beside its thread id. */
    private ObjectNode threadParams(final SessionSpec spec, final String model, final McpGateway.Ticket ticket) {
        final ObjectNode params = Json.object();
        params.put("model", model);
        params.put("cwd", spec.workDir());
        params.put("approvalPolicy", "never");
        params.put("developerInstructions", settings.developerInstructions());
        final ObjectNode config = params.putObject("config");
        config.put("model_reasoning_effort", effortOf(spec.effort()));
        config.put("default_permissions", PERMISSION_PROFILE);
        final ObjectNode profile = config.putObject("permissions").putObject(PERMISSION_PROFILE);
        final ObjectNode filesystem = profile.putObject("filesystem");
        filesystem.put(":root", "read");
        filesystem.put(settings.scratchpadDir().toString(), "write");
        profile.putObject("network").put("enabled", false);
        config.putObject("features").put("plugins", false).put("apps", false);
        final List<String> guards = guardCommands();
        if (!guards.isEmpty()) {
            // hooks given in the thread's config are untrusted to Codex until reviewed in its own app
            config.put("bypass_hook_trust", true);
            final ObjectNode shell = config.putObject("hooks").putArray("PreToolUse").addObject();
            shell.put("matcher", SHELL_MATCHER);
            final ArrayNode hooks = shell.putArray("hooks");
            for (final String command : guards) {
                hooks.addObject().put("type", "command").put("command", command).put("timeout", HOOK_TIMEOUT_SECONDS);
            }
        }
        final ObjectNode servers = config.putObject("mcp_servers");
        for (final String name : mcpServers(codexConfig, Path.of(spec.workDir()))) {
            servers.putObject(name).put("enabled", false);
        }
        if (ticket != null) {
            final ObjectNode comando = servers.putObject("comando");
            comando.put("url", ticket.url());
            comando.putObject("http_headers").put("Authorization", "Bearer " + ticket.token());
            // approval is never, so a tool that needs one fails; comando's write tools ask the user through its cards
            comando.put("default_tools_approval_mode", "approve");
        }
        return params;
    }

    /** The command of each guard hook whose script the kit has, run by the kit's Python as its own wiring does. */
    private List<String> guardCommands() {
        final Path kit = settings.kitRoot();
        final List<String> commands = new ArrayList<>();
        if (kit == null) {
            return commands;
        }
        final Path launcher = kit.resolve("bin").resolve("kit-python");
        // without the kit's launcher the guard still runs, on the PATH's python3, rather than failing open in Codex
        final String python = Files.isExecutable(launcher) ? shellQuoted(launcher.toString()) : "python3";
        for (final String name : GUARD_HOOKS) {
            final Path script = kit.resolve("hooks").resolve(name).resolve(name + ".py");
            if (Files.isRegularFile(script)) {
                commands.add(python + " " + shellQuoted(script.toString()));
            }
        }
        return commands;
    }

    /** The kit's skills folder, which Codex lists beside its own; null when the kit has none. */
    private Path skillsRoot() {
        final Path kit = settings.kitRoot();
        if (kit == null) {
            return null;
        }
        final Path skills = kit.resolve("skills");
        return Files.isDirectory(skills) ? skills : null;
    }

    // ---- strand work

    private void startServer(final Session s, final SessionSpec spec) {
        if (closed) {
            LOG.log(System.Logger.Level.DEBUG, "closing, start dropped [session=" + s.name + ']');
            return;
        }
        if (s.current != null && s.current.rpc.isAlive()) {
            sink.dispatch(new Intent.SessionStarted(s.name));
            return;
        }
        final Path workDir;
        try {
            workDir = Path.of(spec.workDir());
        } catch (final InvalidPathException e) {
            failStart(s, null, "working directory is not a path: " + e.getMessage());
            return;
        }
        if (!Files.isDirectory(workDir)) {
            failStart(s, null, "working directory does not exist: " + workDir);
            return;
        }
        final String unsafe = unsafe(codexConfig, workDir);
        if (!unsafe.isEmpty()) {
            failStart(s, null, unsafe);
            return;
        }
        final Run run = new Run(mcp.register(s.name));
        try {
            run.rpc = CodexRpc.start(s.name, List.of(settings.codexExecutable(), "app-server"), workDir,
                    listener(s, run));
        } catch (final IOException e) {
            failStart(s, run, "cannot start " + settings.codexExecutable() + ": " + e.getMessage()
                    + "; install the Codex CLI, or set COMANDO_CODEX to its path");
            return;
        }
        s.current = run;
        started.put(run, s.name);
        s.model = Engine.codexModelId(spec.model());
        s.effort = effortOf(spec.effort());
        s.ready = false;
        s.turnRunning = false;
        s.turnId = "";
        s.interruptAsked = false;
        s.queued.clear();
        LOG.log(System.Logger.Level.INFO, "codex started [session=" + s.name + ", pid=" + run.rpc.pid() + ", mode="
                + spec.startMode() + ']');
        sink.dispatch(new Intent.SessionStarted(s.name));
        final ObjectNode init = Json.object();
        init.putObject("clientInfo").put("name", "comando").put("version", CLIENT_VERSION);
        run.rpc.request("initialize", init)
                .whenComplete((result, error) -> s.run(() -> onInitialized(s, run, spec, result, error)));
    }

    private void onInitialized(
            final Session s,
            final Run run,
            final SessionSpec spec,
            final ObjectNode result,
            final Throwable error
    ) {
        if (s.current != run) {
            return;
        }
        if (error != null) {
            failRun(s, run, "Codex did not initialize: " + messageOf(error));
            return;
        }
        final String cli = cliVersion(result);
        run.rpc.notify("initialized", null);
        final Path skills = skillsRoot();
        if (skills == null) {
            requestThread(s, run, spec, cli);
            return;
        }
        final ObjectNode roots = Json.object();
        roots.putArray("extraRoots").add(skills.toString());
        run.rpc.request("skills/extraRoots/set", roots).whenComplete((_, failure) -> s.run(() -> {
            if (s.current != run) {
                return;
            }
            if (failure != null) {
                note(s, "Codex did not take the kit's skills (" + skills + "): " + messageOf(failure));
            }
            requestThread(s, run, spec, cli);
        }));
    }

    /** Starts, resumes or forks the session's thread, as its start mode says. */
    private void requestThread(final Session s, final Run run, final SessionSpec spec, final String cli) {
        final ObjectNode params = threadParams(spec, s.model, run.ticket);
        final String method;
        if (spec.startMode() == StartMode.NEW) {
            method = "thread/start";
        } else {
            // a copied session forks the thread, so the original's stays as it was
            method = spec.startMode() == StartMode.FORK ? "thread/fork" : "thread/resume";
            params.put("threadId", spec.claudeSessionId());
        }
        run.rpc.request(method, params)
                .whenComplete((thread, failure) -> s.run(() -> onThread(s, run, spec, cli, thread, failure)));
    }

    private void onThread(
            final Session s,
            final Run run,
            final SessionSpec spec,
            final String cli,
            final ObjectNode result,
            final Throwable error
    ) {
        if (s.current != run) {
            return;
        }
        if (error != null) {
            failRun(s, run, "Codex did not " + (spec.startMode() == StartMode.NEW ? "start" : "resume")
                    + " the thread: " + messageOf(error));
            return;
        }
        s.threadId = result.path("thread").path("id").asString("");
        final String model = result.path("model").asString(s.model);
        final String effort = result.path("reasoningEffort").asString(s.effort);
        LOG.log(System.Logger.Level.INFO, "codex thread ready [session=" + s.name + ", thread=" + s.threadId
                + ", model=" + model + ']');
        sink.dispatch(new Intent.SessionInit(s.name, new SessionInfo(s.threadId, model,
                result.path("cwd").asString(spec.workDir()), cli, result.path("approvalPolicy").asString("never"),
                List.of(), effort)));
        s.events.threadStarted(s.threadId, model, effort);
        s.transcript.changed();
        s.ready = true;
        startNext(s, run);
    }

    /** Handles {@code /model}, {@code /effort} and the Claude commands Codex lacks; false for a prompt. */
    private boolean command(final Session s, final String text) {
        if (!text.startsWith("/")) {
            return false;
        }
        final int space = text.indexOf(' ');
        final String word = space < 0 ? text : text.substring(0, space);
        if (word.length() < 2 || word.indexOf('/', 1) >= 0) {
            return false; // a path, not a command
        }
        final String arg = space < 0 ? "" : text.substring(space + 1).strip();
        switch (word) {
            case "/model" -> {
                if (!arg.isEmpty() && Engine.of(arg) != Engine.CODEX) {
                    note(s, arg + " is a Claude model; a Codex session runs Codex models (sol, astra, luna): make a "
                            + "Claude session for it");
                } else {
                    if (!arg.isEmpty()) {
                        s.model = Engine.codexModelId(arg);
                    }
                    note(s, "The next turn runs on " + s.model);
                }
            }
            case "/effort" -> {
                if (!arg.isEmpty()) {
                    s.effort = effortOf(arg);
                }
                note(s, "The next turn runs at effort " + s.effort);
            }
            default -> note(s, word + " is a Claude Code command; Codex sessions do not have it");
        }
        return true;
    }

    private void startTurn(final Session s, final Run run, final String prompt) {
        s.turnRunning = true;
        s.turnId = "";
        final ObjectNode params = Json.object();
        params.put("threadId", s.threadId);
        params.putArray("input").add(Json.object().put("type", "text").put("text", prompt));
        params.put("model", s.model);
        params.put("effort", s.effort);
        run.rpc.request("turn/start", params).whenComplete((result, error) -> s.run(() -> {
            if (s.current != run) {
                return;
            }
            if (error != null) {
                s.turnRunning = false;
                note(s, "Codex did not start the turn: " + messageOf(error));
                sink.dispatch(new Intent.SessionTurnEnded(s.name, s.queued.size(), -1.0, -1L, 0L, true,
                        "turn not started"));
                s.interruptAsked = false; // it was for this turn, not the next queued one
                startNext(s, run);
                return;
            }
            if (s.turnRunning && s.turnId.isEmpty()) {
                s.turnId = result.path("turn").path("id").asString("");
            }
            if (s.interruptAsked && !s.turnId.isEmpty()) {
                sendInterrupt(s, run);
            }
        }));
    }

    private void startNext(final Session s, final Run run) {
        final String next = s.queued.poll();
        if (next != null) {
            startTurn(s, run, next);
        }
    }

    private void sendInterrupt(final Session s, final Run run) {
        s.interruptAsked = false;
        final ObjectNode params = Json.object().put("threadId", s.threadId).put("turnId", s.turnId);
        run.rpc.request("turn/interrupt", params).whenComplete((_, error) -> {
            if (error != null) {
                LOG.log(System.Logger.Level.DEBUG, "codex interrupt failed [session=" + s.name + ", cause="
                        + messageOf(error) + ']');
            }
        });
    }

    private void onNotification(final Session s, final Run run, final String method, final ObjectNode params) {
        if (s.current != run) {
            return;
        }
        s.events.notification(method, params);
        s.transcript.changed();
        switch (method) {
            case "turn/started" -> {
                s.turnRunning = true;
                s.turnId = params.path("turn").path("id").asString(s.turnId);
                if (s.interruptAsked && !s.turnId.isEmpty()) {
                    sendInterrupt(s, run);
                }
            }
            case "turn/completed" -> {
                s.turnRunning = false;
                s.turnId = "";
                s.interruptAsked = false;
                startNext(s, run);
            }
            default -> {
                // the mapper alone reads it
            }
        }
    }

    private void onServerRequest(final Session s, final Run run, final String method, final ObjectNode params) {
        if (s.current != run) {
            return;
        }
        s.events.notification(method, params);
        note(s, "Codex asked " + method + "; comando declined");
    }

    /** Runs on the strand after the server's last stdout line, or after a close reported its exit. */
    private void onExit(final Session s, final Run run, final int code, final String stderr) {
        if (run.ended) {
            return;
        }
        run.ended = true;
        started.remove(run);
        mcp.unregister(s.name, run.ticket);
        run.rpc.close(); // the input and the reader of a server that exited on its own
        LOG.log(System.Logger.Level.INFO, "codex exited [session=" + s.name + ", code=" + code + ']');
        if (s.current != run) {
            return;
        }
        s.current = null;
        clearTurns(s);
        final boolean clean = code == 0;
        s.events.exited(clean ? "" : stderr.isBlank() ? "codex app-server exited [code=" + code + ']' : stderr);
        s.transcript.changed();
        sink.dispatch(new Intent.SessionExited(s.name, clean ? 0 : code, lastLine(stderr)));
    }

    private void stopServer(final Session s) {
        final Run run = s.current;
        if (run == null) {
            sink.dispatch(new Intent.SessionExited(s.name, 0, ""));
            return;
        }
        s.current = null;
        clearTurns(s);
        end(s.name, run);
        LOG.log(System.Logger.Level.INFO, "codex stopped [session=" + s.name + ']');
        s.events.exited("");
        s.transcript.changed();
        sink.dispatch(new Intent.SessionExited(s.name, 0, ""));
    }

    private void failStart(final Session s, final Run run, final String message) {
        if (run != null) {
            mcp.unregister(s.name, run.ticket);
        }
        note(s, message);
        sink.dispatch(new Intent.SessionExited(s.name, -1, message));
    }

    /** A started server that cannot serve the session: stopped, and the session exits with {@code message}. */
    private void failRun(final Session s, final Run run, final String message) {
        s.current = null;
        clearTurns(s);
        end(s.name, run);
        LOG.log(System.Logger.Level.WARNING, "codex session failed [session=" + s.name + ", cause=" + message + ']');
        s.events.exited(message);
        s.transcript.changed();
        sink.dispatch(new Intent.SessionExited(s.name, -1, message));
    }

    /** Closes the run's server and revokes its ticket; its exit, reported by the close, then changes nothing. */
    private void end(final String name, final Run run) {
        if (run.ended) {
            return;
        }
        run.ended = true;
        started.remove(run);
        if (run.rpc != null) {
            run.rpc.close();
        }
        mcp.unregister(name, run.ticket);
    }

    private void clearTurns(final Session s) {
        s.ready = false;
        s.turnRunning = false;
        s.turnId = "";
        s.interruptAsked = false;
        s.queued.clear();
    }

    /** The turns the session still holds: the running one and those queued behind it. */
    private int pending(final Session s) {
        return (s.turnRunning ? 1 : 0) + s.queued.size();
    }

    private void note(final Session s, final String text) {
        s.events.note(text);
        s.transcript.changed();
    }

    private CodexRpc.Listener listener(final Session s, final Run run) {
        return new CodexRpc.Listener() {
            @Override
            public void notification(final String method, final ObjectNode params) {
                s.run(() -> onNotification(s, run, method, params));
            }

            @Override
            public ObjectNode request(final String method, final ObjectNode params) {
                s.run(() -> onServerRequest(s, run, method, params));
                return null;
            }

            @Override
            public void exited(final int code, final String error) {
                s.run(() -> onExit(s, run, code, error));
            }
        };
    }

    /** One server of a session. Strand-confined, but for {@link #close}, which reads it after the strands stop. */
    private static final class Run {
        final McpGateway.Ticket ticket;
        CodexRpc rpc;
        boolean ended;

        Run(final McpGateway.Ticket ticket) {
            this.ticket = ticket;
        }
    }

    /** Per-session state; every field is confined to the strand. */
    private final class Session {
        final String name;
        final Transcript transcript;
        final CodexEvents events;
        final ExecutorService strand;
        /** Prompts sent while a turn runs or before the thread is ready, at most {@link #QUEUED_PROMPTS}. */
        final ArrayDeque<String> queued = new ArrayDeque<>();
        Run current;
        String threadId = "";
        String model = "";
        String effort = "";
        /** The thread started: a prompt can become a turn. */
        boolean ready;
        boolean turnRunning;
        /** The running turn's id; empty until {@code turn/start} answers or {@code turn/started} arrives. */
        String turnId = "";
        /** An interrupt came before the running turn's id did. */
        boolean interruptAsked;

        Session(final String name) {
            this.name = name;
            this.transcript = transcripts.of(name);
            this.events = mapperFactory.apply(name);
            this.strand = Threads.strand("codex-strand-" + name);
        }

        void run(final Runnable task) {
            try {
                strand.execute(() -> {
                    try {
                        task.run();
                    } catch (final RuntimeException e) {
                        LOG.log(System.Logger.Level.ERROR, "session task failed [session=" + name + ']', e);
                    }
                });
            } catch (final RejectedExecutionException e) {
                LOG.log(System.Logger.Level.DEBUG, "session closed, task dropped [session=" + name + ']');
            }
        }
    }
}
