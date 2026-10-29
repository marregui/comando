package dev.marregui.comando.claude;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.PlanUsage;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.StartMode;
import dev.marregui.comando.core.port.ClaudePort;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.McpGateway;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The Claude port. Each session has a strand, a single-thread executor that is the only writer of the
 * session's transcript and the only owner of its process; stdout lines, prompts, history loads and exits
 * all run there in order. Every port method returns at once.
 */
public final class ClaudeAdapter implements ClaudePort, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ClaudeAdapter.class.getName());

    private final IntentSink sink;
    private final TranscriptRegistry transcripts;
    private final McpGateway mcp;
    private final ClaudeSettings settings;
    private final ObjectMapper json = new ObjectMapper();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer =
            Threads.timedStrand("claude-timer");
    private volatile boolean closed;
    /** Reads the plans' usage limits; a thread of its own, as a read can take seconds. */
    private final ScheduledExecutorService planUsage =
            Threads.timedStrand("plan-usage");
    /** The last Codex read's problem, so that the same one is logged once; touched only on {@link #planUsage}. */
    private String lastCodexProblem = "";

    /** The most sub-agent views that follow their file at once; opening another stops the oldest. */
    static final int TAILS = 4;
    static final long TAIL_EVERY_MILLIS = 2_000;
    /** The follow period in force; tests shorten it. */
    long tailEveryMillis = TAIL_EVERY_MILLIS;
    /**
     * A sub-agent's file that has not grown for this long stops being followed, a backstop: a sub-agent writes nothing
     * while a tool runs or the model answers, often for minutes, so its end is read from the stream or its meta file.
     */
    static final long TAIL_IDLE_MILLIS = 10 * 60_000;
    /** The idle backstop in force; tests shorten it. */
    long tailIdleMillis = TAIL_IDLE_MILLIS;
    /** What a sub-agent's tab says of its following (record 109): its view's {@link ViewSpec.Agent#state()}. */
    static final String FOLLOWING = "Updating while the sub-agent runs.";
    static final String ENDED = "The sub-agent ended.";
    static final String STOPPED_SESSION = "Not updated any more: the session stopped.";
    static final String STOPPED_IDLE = "Not updated any more: the sub-agent wrote nothing for " + TAIL_IDLE_MILLIS / 60_000
            + " min. The transcript link on its card follows it again.";
    static final String STOPPED_TOO_MANY = "Not updated any more: " + TAILS
            + " newer sub-agent views follow theirs. The transcript link on its card follows it again.";
    /**
     * Where the turns', sub-agents' and plan usage's metrics go (record 110); none until {@link #metrics} sets them.
     */
    private volatile MetricsLog metrics = MetricsLog.NONE;

    /** Keeps the metrics in {@code log}; called before the first session starts. */
    public void metrics(MetricsLog log) {
        metrics = log;
    }

    /**
     * A sub-agent ended: its {@code agent} event gets what its own file says it spent (the models it ran on, its model
     * calls and tokens), read on the sub-agent thread, then is written. Without its file, it is written as it is.
     */
    private void agentSpend(ObjectNode event) {
        MetricsLog log = metrics;
        try {
            subagents.execute(() -> {
                try {
                    SubagentFiles.find(settings.projectsDir(), event.path("conversation").asString(""), event.path("tool_use_id").asString(""), json)
                            .ifPresent(f -> event.set("spend", SubagentFiles.spend(f.transcript(), json)));
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "cannot read a sub-agent's spend [toolUseId=" + event.path("tool_use_id").asString("") + ']', e);
                }
                log.write(event);
            });
        } catch (RejectedExecutionException e) {
            log.write(event);
        }
    }

    /** Reads sub-agent files and follows the open ones; a thread of its own, as a read can take a while. */
    private final ScheduledExecutorService subagents =
            Threads.timedStrand("subagent-tail");
    /** The sub-agent views following their file, by tool-use id, oldest first. Confined to {@link #subagents}. */
    private final LinkedHashMap<String, Tail> tails = new LinkedHashMap<>();
    /**
     * The sub-agent view titles given, title to tool-use id: a view is found by its title alone, so two sub-agents
     * with the same description get distinct ones. Confined to {@link #subagents}; the oldest go first.
     */
    private final Map<String, String> titles = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 256;
        }
    };

    public ClaudeAdapter(IntentSink sink, TranscriptRegistry transcripts, McpGateway mcp, ClaudeSettings settings) {
        this.sink = sink;
        this.transcripts = transcripts;
        this.mcp = mcp;
        this.settings = settings;
        deleteStaleConfigs(settings.runDir());
    }

    /** MCP configurations a previous run left behind when it was killed; their tokens are dead, the files go. */
    private static void deleteStaleConfigs(Path runDir) {
        if (runDir == null || !Files.isDirectory(runDir)) {
            return;
        }
        try (var files = Files.newDirectoryStream(runDir, "*.mcp.json")) {
            for (Path f : files) {
                Files.deleteIfExists(f);
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "cannot clean [dir=" + runDir + ']', e);
        }
    }

    /** One process of a session, with what belongs to it alone. Strand-confined. */
    private static final class Run {
        ClaudeProcess process;
        Path mcpConfig;
        McpGateway.Ticket ticket;
        boolean stopping;

        boolean isAlive() {
            return process != null && process.isAlive();
        }
    }

    /** Per-session state; every field is confined to the strand. */
    private final class Session {
        final String name;
        final Transcript transcript;
        final StreamMapper mapper;
        final ExecutorService strand;
        Run current;
        Path workDir;
        String branch = "";
        /** The last start failed and said so; a prompt sent after it adds no second error. Cleared by a start. */
        boolean startFailed;
        /** A process of the session runs; read by the sub-agent thread, which follows running sessions only. */
        volatile boolean live;

        Session(String name) {
            this.name = name;
            this.transcript = transcripts.of(name);
            this.mapper = new StreamMapper(name, transcript, sink, json);
            // the CLI's own settings, beside its projects folder: the base effort the stream does not report
            this.mapper.effortFrom(model -> BaseEffort.base(settings.projectsDir().resolveSibling("settings.json"), model, json));
            // what each message ran at, from the transcript the CLI writes a little after the stream's event
            this.mapper.ranFrom(new RunSettings(settings.projectsDir(), json)::of, retry -> {
                try {
                    timer.schedule(() -> run(retry), StreamMapper.RAN_RETRY_MILLIS, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    // closing: what the message ran at no longer matters
                }
            });
            this.mapper.metrics(metrics, ClaudeAdapter.this::agentSpend);
            this.strand = Threads.strand("claude-strand-" + name);
        }

        void run(Runnable task) {
            try {
                strand.execute(() -> {
                    try {
                        task.run();
                    } catch (RuntimeException e) {
                        LOG.log(System.Logger.Level.ERROR, "session task failed [session=" + name + ']', e);
                    }
                });
            } catch (RejectedExecutionException e) {
                LOG.log(System.Logger.Level.DEBUG, "session closed, task dropped [session=" + name + ']');
            }
        }
    }

    /**
     * The session, made on first use; one made during or after {@link #close} takes no task, so no process starts then.
     */
    private Session session(String name) {
        Session s = sessions.computeIfAbsent(name, Session::new);
        if (closed) {
            s.strand.shutdown();
        }
        return s;
    }

    @Override
    public void start(SessionSpec spec) {
        Session s = session(spec.name());
        s.run(() -> startProcess(s, spec));
    }

    @Override
    public void stop(String name) {
        Session s = sessions.get(name);
        if (s == null) {
            sink.dispatch(new Intent.SessionExited(name, 0, ""));
            return;
        }
        s.run(() -> stopProcess(s));
    }

    @Override
    public void send(String name, String text, String origin, String preface) {
        Session s = session(name);
        s.run(() -> {
            s.mapper.prompt(text, origin);
            s.transcript.changed();
            if (s.current == null || !s.current.isAlive()) {
                if (s.startFailed) {
                    // the failed start already said why, in the transcript and on the status bar; this note only says
                    // that the prompt went nowhere
                    s.mapper.note(Block.NoteKind.INFO, "Not sent: the session could not start (the reason is above)");
                    s.transcript.changed();
                    sink.dispatch(new Intent.SessionTurnEnded(name, 0, -1.0, -1L, 0L, true, "session could not start"));
                    return;
                }
                s.mapper.note(Block.NoteKind.ERROR, "Session is not running; the prompt was not sent");
                s.transcript.changed();
                sink.dispatch(new Intent.SessionTurnEnded(name, 0, -1.0, -1L, 0L, true, "session not running"));
                return;
            }
            sendLine(s, preface + text);
        });
    }

    /**
     * On the session's strand, so no line of the stream lands between the clear and the mapper forgetting where its
     * blocks were. The {@code /clear} goes after the transcript shows what the checkpoint carried; its own note and
     * turn end are not shown.
     */
    @Override
    public void clearConversation(String name, String carried) {
        Session s = sessions.get(name);
        if (s == null) {
            return;
        }
        s.run(() -> {
            s.transcript.clear();
            s.mapper.forgetBlocks();
            s.mapper.carried(carried);
            if (s.current == null || !s.current.isAlive()) {
                s.mapper.note(Block.NoteKind.ERROR, "Session is not running; the conversation was not cleared");
                s.transcript.changed();
                sink.dispatch(new Intent.SessionTurnEnded(name, 0, -1.0, -1L, 0L, true, "session not running"));
                return;
            }
            s.transcript.changed();
            s.mapper.clearQuietly();
            sendLine(s, SessionSpec.CLEAR_COMMAND);
        });
    }

    /** Writes one user message to the running process; on the session's strand. */
    private void sendLine(Session s, String text) {
        ObjectNode line = json.createObjectNode();
        line.put("type", "user");
        ObjectNode message = line.putObject("message");
        message.put("role", "user");
        message.put("content", text);
        s.current.process.send(line.toString());
    }

    @Override
    public void interrupt(String name) {
        Session s = sessions.get(name);
        if (s == null) {
            return;
        }
        s.run(() -> {
            if (s.current != null && s.current.isAlive()) {
                s.current.process.send(interruptRequest());
            }
        });
    }

    /**
     * On the session's strand, so no line of the stream lands between the clear and the mapper forgetting where its
     * blocks were: a result for a call made before the clear finds no block and is dropped.
     */
    @Override
    public void clearTranscript(String name) {
        Session s = sessions.get(name);
        if (s == null) {
            return;
        }
        s.run(() -> {
            s.transcript.clear();
            s.mapper.forgetBlocks();
            s.transcript.changed();
        });
    }

    @Override
    public void loadHistory(SessionSpec spec) {
        if (spec.claudeSessionId().isEmpty()) {
            return;
        }
        Session s = session(spec.name());
        s.run(() -> {
            if (s.current != null) {
                return; // a live session's transcript is already current
            }
            Path file = findTranscript(spec.claudeSessionId());
            if (file == null) {
                // a conversation a checkpoint cleared has no file until its first prompt; what it carries still shows
                if (!spec.carried().isEmpty()) {
                    s.transcript.clear();
                    s.mapper.carried(spec.carried());
                    s.transcript.changed();
                }
                return;
            }
            s.transcript.clear();
            try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (!line.isBlank()) {
                        s.mapper.acceptHistory(line);
                    }
                }
            } catch (IOException e) {
                s.mapper.note(Block.NoteKind.ERROR, "Cannot read the stored conversation: " + e.getMessage());
            }
            s.mapper.endHistory(); // its last turn's end, when it ended: its answer reads as the conclusion
            s.mapper.endRunningTools(); // a call the stored conversation never answered will not be answered now
            // the session bar's context from the start
            s.mapper.reportContext(metrics.lastContext(spec.claudeSessionId()));
            s.mapper.note(Block.NoteKind.INFO, "Stored conversation " + spec.claudeSessionId() + " loaded; start the session to continue it");
            if (!spec.carried().isEmpty()) {
                s.mapper.carried(spec.carried());
            }
            s.transcript.changed();
            String b = GitBranch.of(Path.of(spec.workDir()));
            if (!b.isEmpty()) {
                s.branch = b;
                sink.dispatch(new Intent.SessionBranch(spec.name(), b));
            }
        });
    }

    @Override
    public void forget(String name) {
        Session s = sessions.remove(name);
        if (s == null) {
            return;
        }
        s.run(() -> {
            if (s.current != null) {
                if (s.current.isAlive()) {
                    s.current.process.kill();
                }
                s.current.process.closeInput(); // ends its stdin writer; no exit handler runs after forget
                deleteConfig(s.current);
                mcp.unregister(name, s.current.ticket);
            }
        });
        s.strand.shutdown();
        stopTails(name, null);
        transcripts.forget(name);
    }

    /**
     * Shows the sub-agent's conversation as a lower view, then, while it runs, renders it again each
     * {@link #TAIL_EVERY_MILLIS} in which the file grew. It stops when the stream says the sub-agent ended (its Agent
     * call's result, or for a background one its task's notification), its meta file says it finished, the session
     * stops, or the file has not grown for {@link #TAIL_IDLE_MILLIS}; the view's last line says which.
     */
    @Override
    public void showSubagent(String name, String claudeSessionId, String toolUseId) {
        try {
            subagents.execute(() -> {
                try {
                    openSubagent(name, claudeSessionId, toolUseId);
                } catch (RuntimeException e) {
                    if (closed) {
                        return; // quitting: a read or a follow cut short is no failure
                    }
                    LOG.log(System.Logger.Level.ERROR, "cannot open a sub-agent [session=" + name + ", toolUseId=" + toolUseId + ']', e);
                    subagentSays(new Intent.Notify(Notice.Level.WARN, "Session " + name + ": cannot open the sub-agent's transcript", false));
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.log(System.Logger.Level.DEBUG, "adapter closed, sub-agent not shown [session=" + name + ']');
        }
    }

    // ---- sub-agents, on their own thread

    /**
     * A sub-agent's view, a session of its own (record 109): its file's lines, read as it grows, are mapped into its
     * transcript as a stored conversation's are; the mapper tells the window nothing, the sub-agent being no session.
     */
    private static final class Tail {
        final String session;
        final String toolUseId;
        final String title;
        final String description;
        final SubagentFiles.Found found;
        final SubagentFiles.Conversation conversation;
        final Transcript transcript;
        final StreamMapper mapper;
        long size;
        long grewAt;
        ScheduledFuture<?> future;

        Tail(String session, String toolUseId, String title, String description, SubagentFiles.Found found,
             SubagentFiles.Conversation conversation, Transcript transcript, StreamMapper mapper) {
            this.session = session;
            this.toolUseId = toolUseId;
            this.title = title;
            this.description = description;
            this.found = found;
            this.conversation = conversation;
            this.transcript = transcript;
            this.mapper = mapper;
            conversation.onLine(mapper::acceptHistory);
        }

        ViewSpec.Agent spec(String state) {
            return new ViewSpec.Agent(session, toolUseId, description, state);
        }
    }

    private void openSubagent(String name, String claudeSessionId, String toolUseId) {
        Optional<SubagentFiles.Found> found = SubagentFiles.find(settings.projectsDir(), claudeSessionId, toolUseId, json);
        if (found.isEmpty()) {
            subagentSays(new Intent.Notify(Notice.Level.WARN, "Session " + name + ": transcript not found for this sub-agent", false));
            return;
        }
        SubagentFiles.Found f = found.get();
        String description = f.description().isEmpty() ? toolUseId : f.description();
        // opened again, it is read again from its start, into the same transcript
        stopTail(toolUseId, null);
        Transcript transcript = transcripts.of(TranscriptRegistry.subagentKey(name, toolUseId));
        transcript.clear();
        Tail t = new Tail(name, toolUseId, title(description, toolUseId), description,
                f, new SubagentFiles.Conversation(f.transcript(), json, SubagentFiles.SCAN_BYTES),
                transcript, new StreamMapper(name, transcript, intent -> { }, json));
        t.size = SubagentFiles.size(f.transcript());
        Session s = sessions.get(name);
        // a stopped session's sub-agents write no more
        boolean follow = s != null && s.live && !s.mapper.agentEnded(toolUseId) && !SubagentFiles.finished(f.meta(), json);
        if (!readSubagent(t, !follow)) {
            return;
        }
        String state = follow ? FOLLOWING : s == null || !s.live ? STOPPED_SESSION : ENDED;
        subagentSays(new Intent.ShowView("", t.title, t.spec(state), Placement.LOWER));
        if (!follow) {
            return;
        }
        t.grewAt = System.nanoTime();
        tails.put(toolUseId, t);
        while (tails.size() > TAILS) {
            stopTail(tails.firstEntry().getKey(), STOPPED_TOO_MANY);
        }
        t.future = subagents.scheduleWithFixedDelay(() -> {
            // an exception escaping a periodic task cancels every later run
            try {
                follow(toolUseId, t);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "sub-agent follow failed [session=" + t.session + ", toolUseId=" + toolUseId + ']', e);
                stopTail(toolUseId, null);
            }
        }, tailEveryMillis, tailEveryMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * The view title of the sub-agent {@code toolUseId}: {@code agent · <description>}, numbered when another sub-agent
     * took that title; the same sub-agent keeps its title. The reducer clips longer titles, and a refresh finds its
     * view by the title as kept, so the description is clipped first.
     */
    private String title(String description, String toolUseId) {
        String base = "agent · " + Clip.keepStart(description, 60);
        String title = base;
        for (int n = 2; taken(title, toolUseId); n++) {
            title = base + " (" + n + ")";
        }
        titles.put(title, toolUseId);
        return title;
    }

    /** True when another sub-agent has {@code title}: given it lately, or followed under it now. */
    private boolean taken(String title, String toolUseId) {
        String owner = titles.get(title);
        if (owner != null && !owner.equals(toolUseId)) {
            return true;
        }
        for (Map.Entry<String, Tail> e : tails.entrySet()) {
            if (e.getValue().title.equals(title) && !e.getKey().equals(toolUseId)) {
                return true;
            }
        }
        return false;
    }

    /** Sends a sub-agent view's intent, unless the adapter closed: nothing is sent after {@link #close} returns. */
    private void subagentSays(Intent intent) {
        if (!closed) {
            sink.dispatch(intent);
        }
    }

    private void follow(String toolUseId, Tail t) {
        if (tails.get(toolUseId) != t) {
            return;
        }
        long now = System.nanoTime();
        Session s = sessions.get(t.session);
        if (s == null) {
            stopTail(toolUseId, null); // forgotten: its stop is queued behind
            return;
        }
        if (s.mapper.agentEnded(toolUseId) || SubagentFiles.finished(t.found.meta(), json)) {
            stopTail(toolUseId, ENDED); // read once more: its last lines may have landed since the last period
            return;
        }
        long size = SubagentFiles.size(t.found.transcript());
        if (size != t.size) {
            t.size = size;
            t.grewAt = now;
            readSubagent(t, false);
        } else if (now - t.grewAt >= TimeUnit.MILLISECONDS.toNanos(tailIdleMillis)) {
            stopTail(toolUseId, STOPPED_IDLE);
        }
    }

    /**
     * Reads the lines the sub-agent's file gained into its transcript; at the {@code end}, a tool call still running
     * gets no result any more. False, after a notice, when the file cannot be read.
     */
    private boolean readSubagent(Tail t, boolean end) {
        boolean cut = t.conversation.cut();
        try {
            t.conversation.read();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read the sub-agent transcript [file=" + t.found.transcript() + ']', e);
            subagentSays(new Intent.Notify(Notice.Level.WARN, "Session " + t.session + ": cannot read the sub-agent's transcript", false));
            return false;
        }
        if (!cut && t.conversation.cut()) {
            t.mapper.note(Block.NoteKind.INFO, "The conversation is longer than " + SubagentFiles.SCAN_BYTES / (1024 * 1024)
                    + " MB: only its last " + SubagentFiles.SCAN_BYTES / (1024 * 1024) + " MB are read");
        }
        if (end) {
            t.mapper.endRunningTools();
        }
        t.transcript.changed();
        return true;
    }

    /** Stops following {@code toolUseId}; with a {@code reason}, its view is rendered a last time ending with it. */
    private void stopTail(String toolUseId, String reason) {
        Tail t = tails.remove(toolUseId);
        if (t == null) {
            return;
        }
        if (t.future != null) {
            t.future.cancel(false);
        }
        if (reason != null && readSubagent(t, reason.equals(ENDED) || reason.equals(STOPPED_SESSION))) {
            subagentSays(new Intent.RefreshView(t.title, t.spec(reason)));
        }
    }

    /** The session stopped: its sub-agents write no more; a forgotten session's views are left as they are. */
    private void stopTails(String session, String reason) {
        try {
            subagents.execute(() -> {
                List<String> ids = new ArrayList<>();
                tails.forEach((id, t) -> {
                    if (t.session.equals(session)) {
                        ids.add(id);
                    }
                });
                ids.forEach(id -> stopTail(id, reason));
            });
        } catch (RejectedExecutionException e) {
            LOG.log(System.Logger.Level.DEBUG, "adapter closed [session=" + session + ']');
        }
    }

    /** For tests: how many sub-agent views follow their file; waits for the tail thread's queued work first. */
    int followedSubagents() throws Exception {
        return subagents.submit(tails::size).get();
    }

    // ---- strand work

    private void startProcess(Session s, SessionSpec spec) {
        if (s.current != null && s.current.isAlive()) {
            s.startFailed = false;
            sink.dispatch(new Intent.SessionStarted(s.name));
            return;
        }
        Path workDir;
        try {
            workDir = Path.of(spec.workDir());
        } catch (InvalidPathException e) {
            failStart(s, null, "working directory is not a path: " + e.getMessage());
            return;
        }
        if (!Files.isDirectory(workDir)) {
            failStart(s, null, "working directory does not exist: " + workDir);
            return;
        }
        String newId = spec.startMode() == StartMode.NEW ? UUID.randomUUID().toString() : "";
        Run run = new Run();
        try {
            run.mcpConfig = writeConfig(s.name, run);
        } catch (IOException e) {
            // the run directory, not the executable: COMANDO_CLAUDE would not help
            failStart(s, run, "cannot write the MCP configuration in " + settings.runDir() + ": " + e.getMessage());
            return;
        }
        try {
            RootLaunch launch = settings.launch().forSession(spec.name(), spec.workDir());
            var argv = launch.argv(ClaudeCommand.of(settings.executable(), spec, newId, run.mcpConfig, settings.uiInstructions()));
            s.workDir = workDir;
            s.mapper.resetStreams();
            run.process = ClaudeProcess.start(s.name, argv, workDir, launch,
                    line -> s.run(() -> onLine(s, line)),
                    code -> s.run(() -> onExit(s, run, code)));
            s.current = run;
            s.live = true;
            s.startFailed = false;
            if (spec.startMode() == StartMode.NEW) {
                // the Session tab shows one conversation: the new one starts empty, the old stays in its file
                s.transcript.clear();
                s.mapper.forgetBlocks();
            }
            LOG.log(System.Logger.Level.INFO, "claude started [session=" + s.name + ", pid=" + run.process.pid() + ", mode=" + spec.startMode()
                    + ", env=" + launch + ']');
            s.mapper.note(Block.NoteKind.INFO, switch (spec.startMode()) {
                case NEW -> "New conversation " + newId;
                case RESUME -> "Resumed conversation " + spec.claudeSessionId();
                case FORK -> "Forked conversation " + spec.claudeSessionId();
            });
            s.transcript.changed();
            sink.dispatch(new Intent.SessionStarted(s.name));
            refreshBranch(s);
        } catch (IOException e) {
            failStart(s, run, "cannot start " + settings.executable() + ": " + e.getMessage()
                    + "; install Claude Code, or set COMANDO_CLAUDE to its path");
        }
    }

    private void failStart(Session s, Run run, String message) {
        if (run != null) {
            deleteConfig(run);
            mcp.unregister(s.name, run.ticket);
        }
        s.startFailed = true;
        s.mapper.note(Block.NoteKind.ERROR, message);
        s.transcript.changed();
        sink.dispatch(new Intent.SessionExited(s.name, -1, message));
    }

    private void onLine(Session s, String line) {
        String type = s.mapper.accept(line);
        s.transcript.changed();
        if ("result".equals(type)) {
            refreshBranch(s);
        }
    }

    /** Runs on the strand after the process's last stdout line; a process already replaced only adds its note. */
    private void onExit(Session s, Run run, int code) {
        deleteConfig(run);
        run.process.closeInput(); // ends the stdin writer of a process that exited on its own
        boolean clean = code == 0 || run.stopping;
        String err = clean ? "" : run.process.stderrTail();
        LOG.log(System.Logger.Level.INFO, "claude exited [session=" + s.name + ", code=" + code + ']');
        s.mapper.note(clean ? Block.NoteKind.INFO : Block.NoteKind.ERROR,
                "Process exited [code=" + code + "]" + (err.isEmpty() ? "" : "\n" + err));
        s.transcript.changed();
        if (s.current != run) {
            return; // a newer process already runs for this session
        }
        if (s.mapper.endRunningTools() > 0) {
            s.transcript.changed();
        }
        s.current = null;
        s.live = false;
        mcp.unregister(s.name, run.ticket);
        stopTails(s.name, STOPPED_SESSION);
        sink.dispatch(new Intent.SessionExited(s.name, clean ? 0 : code, lastLine(err)));
    }

    private void stopProcess(Session s) {
        Run run = s.current;
        if (run == null || !run.isAlive()) {
            sink.dispatch(new Intent.SessionExited(s.name, 0, ""));
            return;
        }
        run.stopping = true;
        ClaudeProcess p = run.process;
        p.send(interruptRequest());
        p.closeInput();
        timer.schedule(() -> {
            if (p.isAlive()) {
                p.terminate();
                timer.schedule(() -> {
                    if (p.isAlive()) {
                        p.kill();
                    }
                }, 2, TimeUnit.SECONDS);
            }
        }, 3, TimeUnit.SECONDS);
    }

    private void refreshBranch(Session s) {
        if (s.workDir == null) {
            return;
        }
        String b = GitBranch.of(s.workDir);
        if (!b.equals(s.branch)) {
            s.branch = b;
            sink.dispatch(new Intent.SessionBranch(s.name, b));
        }
    }

    private String interruptRequest() {
        ObjectNode line = json.createObjectNode();
        line.put("type", "control_request");
        line.put("request_id", UUID.randomUUID().toString());
        line.putObject("request").put("subtype", "interrupt");
        return line.toString();
    }

    private Path writeConfig(String name, Run run) throws IOException {
        McpGateway.Ticket ticket = mcp.register(name);
        run.ticket = ticket;
        if (ticket == null) {
            return null;
        }
        ObjectNode root = json.createObjectNode();
        ObjectNode server = root.putObject("mcpServers").putObject("comando");
        server.put("type", "http");
        server.put("url", ticket.url());
        server.putObject("headers").put("Authorization", "Bearer " + ticket.token());
        // a per-server timeout also raises the idle window, so an approval card can wait for the user
        server.put("timeout", 3_600_000);
        Files.createDirectories(settings.runDir());
        Path file = settings.runDir().resolve(name.replaceAll("[^A-Za-z0-9._-]", "_") + '-' + UUID.randomUUID() + ".mcp.json");
        // the file holds the session's bearer token: owner-only from its creation, never readable in between
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            LOG.log(System.Logger.Level.DEBUG, "no POSIX permissions on this file system");
            Files.createFile(file);
        }
        Files.writeString(file, root.toString());
        return file;
    }

    private static void deleteConfig(Run run) {
        if (run.mcpConfig != null) {
            try {
                Files.deleteIfExists(run.mcpConfig);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG, "cannot delete [file=" + run.mcpConfig + ']', e);
            }
            run.mcpConfig = null;
        }
    }

    private Path findTranscript(String sessionId) {
        Path dir = settings.projectsDir();
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var projects = Files.newDirectoryStream(dir)) {
            for (Path project : projects) {
                Path candidate = project.resolve(sessionId + ".jsonl");
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot list [dir=" + dir + ']', e);
        }
        return null;
    }

    private static String lastLine(String text) {
        int i = text.lastIndexOf('\n');
        return i < 0 ? text : text.substring(i + 1);
    }

    /**
     * Reads the plans' usage limits now and then every {@code every}, and sends each read to the status bar: Claude's
     * through Claude Code's local {@code /usage} command (no prompt, no tokens, no saved session), then Codex's through
     * {@code codex app-server} (no prompt either), one after the other on the same strand.
     */
    public void watchPlanUsage(Duration every) {
        String codex = CodexUsageReader.findExecutable();
        planUsage.scheduleWithFixedDelay(() -> {
            // an exception escaping a periodic task cancels every later run
            try {
                PlanUsage usage = PlanUsageReader.read(settings.executable(), settings.launch(), settings.runDir(), json);
                if (!usage.problem().isEmpty()) {
                    LOG.log(System.Logger.Level.WARNING, "cannot read the plan's usage limits [problem=" + usage.problem() + ']');
                }
                if (!closed) {
                    sink.dispatch(new Intent.PlanUsageRead(usage));
                }
                usageMetrics(usage);
                PlanUsage codexUsage = CodexUsageReader.read(codex, json, CodexUsageReader.TIMEOUT, ZoneId.systemDefault());
                // logged when it changes: without Codex installed, every read fails the same way
                if (!codexUsage.problem().isEmpty() && !codexUsage.problem().equals(lastCodexProblem)) {
                    LOG.log(System.Logger.Level.INFO, "cannot read the Codex plan's usage limits [problem=" + codexUsage.problem() + ']');
                }
                lastCodexProblem = codexUsage.problem();
                if (!closed) {
                    sink.dispatch(new Intent.PlanUsageRead(codexUsage));
                }
                usageMetrics(codexUsage);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "cannot read the plan usage", e);
            }
        }, 0, every.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** A plan's usage read: a {@code usage} event per window (record 110); a read that failed says nothing. */
    private void usageMetrics(PlanUsage usage) {
        if (!usage.problem().isEmpty()) {
            return;
        }
        MetricsLog log = metrics;
        for (PlanUsage.Window w : usage.windows()) {
            ObjectNode e = log.event("usage");
            e.put("provider", usage.provider());
            e.put("window", w.name());
            e.put("pct", w.percent());
            e.put("resets", w.resets());
            log.write(e);
        }
    }

    /** Stops every process (SIGTERM, then kill) and the strands. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        planUsage.shutdownNow();
        subagents.shutdownNow();
        try {
            // a read that outlasts the wait sends nothing: it checks closed first
            subagents.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // SIGTERM to every process, collected on each strand; the strands then stop, so no exit handler runs and
        // this method cleans up after the processes itself: SIGKILL after the grace period, config files with
        // their bearer tokens deleted, tokens revoked
        Map<String, Run> runs = new ConcurrentHashMap<>();
        for (Session s : sessions.values()) {
            s.run(() -> {
                if (s.current != null) {
                    runs.put(s.name, s.current);
                    if (s.current.isAlive()) {
                        s.current.stopping = true;
                        s.current.process.terminate();
                    }
                }
            });
            s.strand.shutdown();
        }
        for (Session s : sessions.values()) {
            try {
                s.strand.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        for (Map.Entry<String, Run> e : runs.entrySet()) {
            Run run = e.getValue();
            if (run.process != null) {
                if (!run.process.awaitExit(Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())))) {
                    run.process.kill();
                }
                run.process.closeInput();
            }
            deleteConfig(run);
            mcp.unregister(e.getKey(), run.ticket);
        }
        timer.shutdownNow();
    }
}
