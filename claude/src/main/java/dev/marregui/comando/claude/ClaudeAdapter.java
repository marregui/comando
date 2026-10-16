package dev.marregui.comando.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PlanUsage;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.StartMode;
import dev.marregui.comando.core.port.ClaudePort;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.McpGateway;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
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
            Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().name("claude-timer").daemon(true).unstarted(r));
    private volatile boolean closed;
    /** Reads the plan's usage limits; a thread of its own, as a read can take seconds. */
    private final ScheduledExecutorService planUsage =
            Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().name("plan-usage").daemon(true).unstarted(r));

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
            LOG.log(System.Logger.Level.DEBUG, "cannot clean " + runDir + ": " + e.getMessage());
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

        Session(String name) {
            this.name = name;
            this.transcript = transcripts.of(name);
            this.mapper = new StreamMapper(name, transcript, sink, json);
            this.strand = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("claude-strand-" + name).factory());
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

    private Session session(String name) {
        return sessions.computeIfAbsent(name, Session::new);
    }

    // ---- port

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
    public void send(String name, String text, String origin) {
        Session s = session(name);
        s.run(() -> {
            s.mapper.prompt(text, origin);
            s.transcript.changed();
            if (s.current == null || !s.current.isAlive()) {
                if (s.startFailed) {
                    // the failed start already reported why, in the transcript and on the status bar: one error surface;
                    // the prompt still says, plainly, that it went nowhere
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
            ObjectNode line = json.createObjectNode();
            line.put("type", "user");
            ObjectNode message = line.putObject("message");
            message.put("role", "user");
            message.put("content", text);
            s.current.process.send(line.toString());
        });
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
            s.mapper.endRunningTools(); // a call the stored conversation never answered will not be answered now
            s.mapper.note(Block.NoteKind.INFO, "Stored conversation " + spec.claudeSessionId() + " loaded; start the session to continue it");
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
        transcripts.forget(name);
    }

    // ---- strand work

    private void startProcess(Session s, SessionSpec spec) {
        if (s.current != null && s.current.isAlive()) {
            s.startFailed = false;
            sink.dispatch(new Intent.SessionStarted(s.name));
            return;
        }
        Path workDir = Path.of(spec.workDir());
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
            var argv = ClaudeCommand.of(settings.executable(), spec, newId, run.mcpConfig, settings.uiInstructions());
            s.workDir = workDir;
            s.mapper.resetStreams();
            run.process = ClaudeProcess.start(s.name, argv, workDir, Map.of(),
                    line -> s.run(() -> onLine(s, line)),
                    code -> s.run(() -> onExit(s, run, code)));
            s.current = run;
            s.startFailed = false;
            if (spec.startMode() == StartMode.NEW) {
                // the Session tab shows one conversation: the new one starts empty, the old stays in Claude Code's files
                s.transcript.clear();
                s.mapper.forgetBlocks();
            }
            LOG.log(System.Logger.Level.INFO, "claude started [session=" + s.name + ", pid=" + run.process.pid() + ", mode=" + spec.startMode() + ']');
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

    /** Runs on the strand after the process's last stdout line; ignores a process already replaced. */
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
        mcp.unregister(s.name, run.ticket);
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

    // ---- helpers

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
                LOG.log(System.Logger.Level.DEBUG, "cannot delete " + run.mcpConfig + ": " + e.getMessage());
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
            LOG.log(System.Logger.Level.WARNING, "cannot list " + dir + ": " + e.getMessage());
        }
        return null;
    }

    private static String lastLine(String text) {
        int i = text.lastIndexOf('\n');
        return i < 0 ? text : text.substring(i + 1);
    }

    /**
     * Reads the plan's usage limits now and then every {@code every}, and sends each read to the status bar. A read
     * runs Claude Code's local {@code /usage} command: no prompt, no tokens, no saved session.
     */
    public void watchPlanUsage(Duration every) {
        planUsage.scheduleWithFixedDelay(() -> {
            // an exception escaping a periodic task cancels every later run
            try {
                PlanUsage usage = PlanUsageReader.read(settings.executable(), settings.runDir(), json);
                if (!usage.problem().isEmpty()) {
                    LOG.log(System.Logger.Level.WARNING, "cannot read the plan's usage limits: " + usage.problem());
                }
                if (!closed) {
                    sink.dispatch(new Intent.PlanUsageRead(usage));
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "plan usage read failed", e);
            }
        }, 0, every.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Stops every process (SIGTERM, then kill) and the strands. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        planUsage.shutdownNow();
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
