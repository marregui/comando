package dev.marregui.comando.mcp;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.Errands;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.AtomicFiles;
import dev.marregui.comando.core.model.InstanceName;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.InstancesPort;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.InstancesState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The comando instances on one machine consulting each other through the comando Edge (record 123). Each instance
 * publishes its presence, retained, when it connects and when it changes (at most every 2 s), and clears it on close;
 * its last will clears it when the instance dies. A question goes to the peer's inbox with a response topic and
 * correlation data; the peer prompts its session {@code consult-<asker>} through that session's panel, so its user sees
 * the question, and replies with Claude's text in the turn that answers it. Only the text a session chose to send and
 * the reply's text travel, never a brain's or a kit's files. A peer the user listed in the data root's
 * {@code agents.json} ({@code {"peers": {"deny": ["<id>"]}}}) is refused.
 * <p>
 * A presence says what the instance is doing, so another knows without asking: whether it started the Edge, and for
 * each session its state, what its turn is doing and for how long, its model, effort, context, folder and branch, its
 * background tasks, and the first line of its last prompt and of its last reply. Those two lines are the only words of a
 * conversation it carries; on a broker off this machine they leave it. The instances seen are remembered in the data
 * root's {@code instances-seen.json}, so one that left stays listed, as last seen, until the user forgets it.
 * <p>
 * The instances, this one's connection and the questions asked and answered reach the window as
 * {@link Intent.InstancesChanged} after each change (record 124), and the user asks and refuses from the Instances tab
 * through {@link InstancesPort}.
 */
public final class Peers implements AutoCloseable, InstancesPort {

    private static final System.Logger LOG = System.getLogger(Peers.class.getName());
    /**
     * Every instance's topics are under this, then its id: {@code …/<id>/presence}, {@code …/<id>/inbox},
     * {@code …/<id>/reply}.
     */
    static final String TOPICS = "comando/v1/local/instance/";
    static final Duration PRESENCE_EVERY = Duration.ofSeconds(2);
    /** How long an instance waits for its consult session's turn before it replies {@code timeout}. */
    static final Duration ANSWER_WITHIN = Duration.ofSeconds(McpTools.MAX_WAIT_SECONDS);
    static final int DEFAULT_WAIT_SECONDS = 300;
    static final String CONSULT = "consult-";
    /** Who a consult session is created and prompted by: {@code peer:<id>}. */
    static final String PEER = "peer:";
    static final String OK = "ok";
    static final String REFUSED = "refused";
    static final String TIMEOUT = "timeout";
    static final String ERROR = "error";
    private static final Duration SEND_WITHIN = Duration.ofSeconds(10);
    private static final Duration CLEAR_WITHIN = Duration.ofSeconds(2);
    private static final long POLL_MILLIS = 200;
    /** How much of a question the Instances tab keeps. */
    static final int QUESTION_CHARS = 4_000;
    /** How long a new consult session may take to appear in the state after it was asked for. */
    private static final Duration MAKE_WITHIN = Duration.ofSeconds(5);
    static final String EDGE_DOWN = "this instance is not connected to the comando Edge, so it reaches no other instance: start the Edge with "
            + "its toggle at the top of the workspace, right of QuestDB's, in any comando instance; presence resumes within seconds once it runs";

    /** This instance: its name as the Dock shows it, its id in the topics, its version, what it is for. */
    public record Me(String name, String slug, String version, String role) {

        public Me {
            role = role == null ? "" : role.strip();
        }

        public Me(String name, String slug, String version) {
            this(name, slug, version, "");
        }

        /**
         * The instance named {@code instanceName} ({@link InstanceName}; empty for the main one), at {@code version},
         * for {@code role} (its {@code instance.json}'s, or empty).
         */
        public static Me of(String instanceName, String version, String role) {
            return new Me(InstanceName.display(instanceName), Peers.slug(instanceName), version, role);
        }

        public static Me of(String instanceName, String version) {
            return of(instanceName, version, "");
        }

        /**
         * Its MQTT client id: {@code comando-<slug>}, or the slug as it is when it already says comando
         * ({@code comando-expert}).
         */
        public String clientId() {
            return slug.equals("comando") || slug.startsWith("comando-") ? slug : "comando-" + slug;
        }
    }

    /** Another instance, as its presence says. */
    record Peer(String slug, String name, String version, String role, String kit, boolean busy, String started,
                List<InstancesState.Member> sessions, boolean edge) {
    }

    /** An instance as this one last saw it: its presence, and when it was last seen present (0 while it is). */
    private record Seen(Peer peer, long lastSeen) {
    }

    /** How much of a prompt's or a reply's first line a presence carries. */
    static final int LINE_CHARS = 200;
    /** How far back in a conversation its last prompt and reply are looked for. */
    private static final int LOOK_BACK = 4_000;
    /** The data root's file of the instances seen. */
    static final String SEEN_FILE = "instances-seen.json";

    /** A reply to a question, as it travels. */
    record Reply(String status, String text, String instance, String session) {
    }

    /** A question this instance asked, waiting for its reply. */
    private record Asked(String peer, String peerName, CompletableFuture<Reply> reply) {
    }

    private final PeerBroker broker;
    private final Me me;
    private final IntentSink sink;
    private final Supplier<AppState> state;
    private final TranscriptRegistry transcripts;
    private final String workDir;
    private final Path agentsFile;
    private final Duration answerWithin;
    private final String started = Instant.now().toString();
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final Map<String, Asked> asked = new ConcurrentHashMap<>();
    /** The questions asked and answered, oldest first, at most {@link InstancesState#CONSULTS}; guarded by itself. */
    private final Deque<InstancesState.Consult> consults = new ArrayDeque<>();
    /** The state last sent to the window; guarded by {@link #consults}. */
    private InstancesState lastShown;
    /** The kit's commit, as the window last read it; part of the presence. */
    private volatile String kit = "";
    /** What this instance is for; part of the presence, changed from the Instances tab. */
    private volatile String role;
    /** Whether this instance started the comando Edge; part of the presence. */
    private volatile BooleanSupplier edge = () -> false;
    /** The data root's {@code instances-seen.json} and {@code instance.json}; null until {@link #dataRoot} names them. */
    private volatile Path seenFile;
    private volatile Path instanceFile;
    /** The other instances seen, present or not, by id; guarded by itself. */
    private final Map<String, Seen> seen = new java.util.LinkedHashMap<>();
    /** The prompt blocks answers are waiting on, so two equal questions in flight wait on a block each. */
    private final Set<Block> claimed = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private final ScheduledExecutorService ticks = Threads.timedStrand("peers");
    private final Errands errands = new Errands("peers");
    private final Object presenceLock = new Object();
    private final Object consultLock = new Object();
    private String lastPresence;
    private volatile boolean closed;

    /**
     * Peers through {@code broker} for instance {@code me}; consult sessions are made in {@code workDir}, and
     * {@code agentsFile} says which peers are refused.
     */
    public Peers(PeerBroker broker, Me me, IntentSink sink, Supplier<AppState> state, TranscriptRegistry transcripts, String workDir,
                 Path agentsFile) {
        this(broker, me, sink, state, transcripts, workDir, agentsFile, ANSWER_WITHIN);
    }

    /** As above, with the bound on a consult turn given; for tests. */
    Peers(PeerBroker broker, Me me, IntentSink sink, Supplier<AppState> state, TranscriptRegistry transcripts, String workDir, Path agentsFile,
          Duration answerWithin) {
        this.broker = broker;
        this.me = me;
        this.sink = sink;
        this.state = state;
        this.transcripts = transcripts;
        this.workDir = workDir;
        this.agentsFile = agentsFile;
        this.answerWithin = answerWithin;
        this.role = me.role();
    }

    /**
     * The data root whose {@code instances-seen.json} remembers the instances seen, read now, and whose
     * {@code instance.json} holds the role; call it before {@link #start}.
     */
    public void dataRoot(Path home) {
        seenFile = home.resolve(SEEN_FILE);
        instanceFile = home.resolve(InstanceName.FILE);
        synchronized (seen) {
            seen.clear();
            if (!Files.isRegularFile(seenFile)) {
                return;
            }
            try {
                for (JsonNode n : json.readTree(Files.readString(seenFile)).path("instances")) {
                    Peer p = peer(n.path("slug").asString(""), n);
                    if (!p.slug().isEmpty() && !p.slug().equals(me.slug())) {
                        seen.put(p.slug(), new Seen(p, n.path("lastSeen").asLong(0L)));
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot read " + seenFile + "; the instances seen before are not listed", e);
            }
        }
    }

    /** Whether this instance started the comando Edge, asked at each tick; part of the presence. */
    public void edge(BooleanSupplier startedHere) {
        edge = startedHere == null ? () -> false : startedHere;
    }

    /** An instance's id in the topics: its name in lowercase letters, digits and dashes; {@code main} when unnamed. */
    public static String slug(String instanceName) {
        String s = instanceName == null ? "" : instanceName.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "main" : s;
    }

    static String presenceTopic(String slug) {
        return TOPICS + slug + "/presence";
    }

    static String inboxTopic(String slug) {
        return TOPICS + slug + "/inbox";
    }

    static String replyTopic(String slug) {
        return TOPICS + slug + "/reply";
    }

    /**
     * Sets the last will, subscribes, and publishes presence on every connect and every change; call it before the
     * broker connects.
     */
    public void start() {
        broker.will(presenceTopic(me.slug()), new byte[0], true);
        broker.onConnected(this::connected);
        broker.subscribe(TOPICS + "+/presence", this::presence);
        broker.subscribe(inboxTopic(me.slug()), this::inbox);
        broker.subscribe(replyTopic(me.slug()), this::reply);
        ticks.scheduleWithFixedDelay(this::tick, PRESENCE_EVERY.toMillis(), PRESENCE_EVERY.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Connected, or again: the table is rebuilt from the retained presences that come, and this one's is sent. */
    private void connected() {
        peers.clear();
        synchronized (presenceLock) {
            lastPresence = null;
        }
        tick();
    }

    /** The kit's commit or description ({@code git describe}), sent in the presence from the next tick on. */
    public void kit(String described) {
        kit = described == null ? "" : described.strip();
    }

    /** Publishes presence when it changed since it was last sent, and tells the window what changed. */
    void tick() {
        // an exception escaping a periodic task cancels every later run
        try {
            show();
            if (closed || !broker.connected()) {
                return;
            }
            synchronized (presenceLock) {
                String now = presenceJson();
                if (now.equals(lastPresence)) {
                    return;
                }
                lastPresence = now;
                broker.publish(presenceTopic(me.slug()), now.getBytes(StandardCharsets.UTF_8), true, null, null)
                        .whenComplete((v, e) -> {
                            if (e != null) {
                                LOG.log(System.Logger.Level.DEBUG, "presence not sent [cause=" + e.getMessage() + ']');
                            }
                        });
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot send presence", e);
        }
    }

    String presenceJson() {
        ObjectNode p = json.createObjectNode();
        p.put("name", me.name());
        p.put("slug", me.slug());
        p.put("version", me.version());
        p.put("role", role);
        p.put("kit", kit);
        p.put("started", started);
        AppState s = state.get();
        p.put("edge", edge.getAsBoolean());
        p.put("busy", s.sessions().stream().anyMatch(SessionState::isBusy));
        ArrayNode sessions = p.putArray("sessions");
        for (InstancesState.Member m : members(s)) {
            ObjectNode o = sessions.addObject().put("name", m.name()).put("busy", m.busy()).put("status", m.status())
                    .put("activity", m.activity()).put("turnStarted", m.turnStarted()).put("model", m.model()).put("effort", m.effort())
                    .put("context", m.context()).put("folder", m.folder()).put("branch", m.branch());
            ArrayNode tasks = o.putArray("tasks");
            m.tasks().forEach(tasks::add);
            o.put("prompt", m.prompt()).put("reply", m.reply());
        }
        return p.toString();
    }

    /** This instance's sessions, each with what it is doing. */
    private List<InstancesState.Member> members(AppState s) {
        return s.sessions().stream().map(this::member).toList();
    }

    private InstancesState.Member member(SessionState x) {
        boolean usable = x.status().isUsable();
        String model = usable && !x.info().turnModel().isBlank() ? x.info().turnModel() : usable && !x.info().model().isBlank() ? x.info().model()
                : x.spec().model();
        String effort = usable && !x.info().turnEffort().isBlank() ? x.info().turnEffort()
                : usable && !x.info().reportedEffort().isBlank() ? x.info().reportedEffort() : x.spec().effort();
        int context = x.contextWindow() > 0 ? (int) Math.min(100, x.contextTokens() * 100 / x.contextWindow()) : -1;
        String[] ends = ends(x.name());
        return new InstancesState.Member(x.name(), x.isBusy(), x.status().name(), x.isBusy() ? x.activity() : "",
                x.isBusy() ? x.turnStartedMillis() : 0L, model, effort, context, home(x.spec().workDir()), x.branch(),
                x.tasks().stream().map(t -> t.description()).toList(), ends[0], ends[1]);
    }

    /** The first line of {@code session}'s last prompt and of its last reply, its own (not a subagent's), clipped. */
    private String[] ends(String session) {
        Transcript t = transcripts.of(session);
        String prompt = null;
        String reply = null;
        for (int i = t.size() - 1, last = Math.max(0, t.size() - LOOK_BACK); i >= last && (prompt == null || reply == null); i--) {
            Block b = t.get(i);
            if (prompt == null && b instanceof Block.UserPrompt u && u.parent().isEmpty()) {
                prompt = firstLine(u.text());
            } else if (reply == null && b instanceof Block.AssistantText a && a.parent().isEmpty() && !a.text().isBlank()) {
                reply = firstLine(a.text());
            }
        }
        return new String[]{prompt == null ? "" : prompt, reply == null ? "" : reply};
    }

    /** The first line that says something, clipped; a consult's {@code [from instance …]} header is skipped. */
    static String firstLine(String text) {
        return text.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("[from instance ")).findFirst()
                .map(l -> McpTools.clip(l, LINE_CHARS)).orElse("");
    }

    /** {@code path} with the home folder written {@code ~}. */
    private static String home(String path) {
        String home = System.getProperty("user.home");
        return path != null && home != null && !home.isEmpty() && path.startsWith(home) ? "~" + path.substring(home.length()) : path;
    }

    /** A presence, or a remembered one, read. */
    private static Peer peer(String slug, JsonNode p) {
        List<InstancesState.Member> sessions = new ArrayList<>();
        p.path("sessions").forEach(x -> {
            List<String> tasks = new ArrayList<>();
            x.path("tasks").forEach(t -> tasks.add(t.asString("")));
            sessions.add(new InstancesState.Member(x.path("name").asString(""), x.path("busy").asBoolean(false), x.path("status").asString(""),
                    x.path("activity").asString(""), x.path("turnStarted").asLong(0L), x.path("model").asString(""),
                    x.path("effort").asString(""), x.path("context").asInt(-1), x.path("folder").asString(""), x.path("branch").asString(""),
                    tasks, x.path("prompt").asString(""), x.path("reply").asString("")));
        });
        return new Peer(slug, p.path("name").asString(slug), p.path("version").asString(""), p.path("role").asString(""),
                p.path("kit").asString(""), p.path("busy").asBoolean(false), p.path("started").asString(""), List.copyOf(sessions),
                p.path("edge").asBoolean(false));
    }

    private void presence(PeerBroker.Message m) {
        String slug = between(m.topic(), TOPICS, "/presence");
        if (slug.isEmpty()) {
            return;
        }
        if (m.payload() == null || m.payload().length == 0) {
            Peer gone = peers.remove(slug);
            String name = gone == null ? slug : gone.name();
            if (gone != null) {
                remember(gone, System.currentTimeMillis());
            }
            asked.forEach((id, a) -> {
                if (a.peer().equals(slug)) {
                    a.reply().complete(new Reply(ERROR, name + " left the comando Edge before it replied", name, ""));
                }
            });
            show();
            return;
        }
        try {
            Peer p = peer(slug, json.readTree(m.payload()));
            Peer before = peers.put(slug, p);
            if (!slug.equals(me.slug()) && (before == null || !sameIdentity(before, p))) {
                remember(p, 0L);
            }
            show();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "unreadable presence [instance=" + slug + ", cause=" + e.getMessage() + ']');
        }
    }

    private void inbox(PeerBroker.Message m) {
        if (m.responseTopic() == null || m.correlation() == null) {
            LOG.log(System.Logger.Level.DEBUG, "a question without a response topic or correlation data, dropped [topic=" + m.topic() + ']');
            return;
        }
        JsonNode q;
        try {
            q = json.readTree(m.payload());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "unreadable question, dropped [cause=" + e.getMessage() + ']');
            return;
        }
        if (q.path("from").asString("").isBlank()) {
            LOG.log(System.Logger.Level.DEBUG, "a question that names no asker, dropped");
            return;
        }
        String from = slug(q.path("from").asString(""));
        String fromName = q.path("name").asString(from);
        String id = UUID.randomUUID().toString();
        record(new InstancesState.Consult(id, InstancesState.Direction.ANSWERED, from, fromName, CONSULT + from,
                McpTools.clip(q.path("text").asString(""), QUESTION_CHARS), InstancesState.Status.WAITING, System.currentTimeMillis(), 0, ""));
        errands.start("peer-answer", () -> {
            Reply r;
            try {
                r = answer(from, fromName, q.path("session").asString(""), q.path("text").asString(""));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "a question failed [instance=" + from + ']', e);
                r = new Reply(ERROR, me.name() + " could not answer: " + e.getMessage(), me.name(), CONSULT + from);
            }
            ended(id, r);
            send(m.responseTopic(), m.correlation(), r);
        });
    }

    /**
     * Prompts this instance's session {@code consult-<from>} with the question, headed with who asks, in its panel;
     * created by {@code peer:<from>} in the default folder on the first question. Waits for the turn that answers it.
     */
    private Reply answer(String from, String fromName, String fromSession, String text) {
        String session = CONSULT + from;
        Optional<String> refused = refused(from);
        if (refused.isPresent()) {
            sink.dispatch(new Intent.Notify(Notice.Level.INFO, "Refused a question from instance " + fromName + ": " + refused.get(), false));
            return new Reply(REFUSED, me.name() + " takes no questions from " + fromName + ": " + refused.get(), me.name(), "");
        }
        if (text.isBlank()) {
            return new Reply(ERROR, "the question was empty", me.name(), session);
        }
        String prompt = "[from instance " + fromName + (fromSession.isBlank() ? "" : " · session " + fromSession) + "]\n" + text.strip();
        long deadline = System.nanoTime() + answerWithin.toNanos();
        int cursor;
        // two questions arriving together make the session once, and reach it in the order they came
        synchronized (consultLock) {
            AppState s = state.get();
            Optional<SessionState> existing = s.session(session);
            cursor = existing.isPresent() ? transcripts.of(session).size() : 0;
            if (existing.isEmpty()) {
                // a session comes with its own panel (record 88), where the question shows
                sink.dispatch(new Intent.SaveSession("", SessionSpec.fresh(session, workDir).withCreatedBy(PEER + from)));
                if (!awaitSession(session, deadline)) {
                    return Thread.currentThread().isInterrupted() ? closing(session)
                            : new Reply(ERROR, "session " + session + " could not be made in " + me.name(), me.name(), session);
                }
            } else if (s.homePanel(session).isEmpty()) {
                sink.dispatch(new Intent.OpenSession(session, s.focused(), PanelId.random()));
            }
            sink.dispatch(new Intent.PromptSession(session, prompt, PEER + from));
        }
        Block block = null;
        try {
            int at = -1;
            while (at < 0) {
                Transcript t = transcripts.of(session);
                at = find(t, prompt, cursor);
                if (at >= 0) {
                    block = t.get(at);
                    break;
                }
                if (System.nanoTime() > deadline) {
                    return timeout(session);
                }
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return closing(session);
                }
            }
            Transcript t = transcripts.of(session);
            boolean ended = Turns.awaitTurnEnd(state, session, t, at, Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
            if (Thread.currentThread().isInterrupted()) {
                return closing(session);
            }
            if (!ended) {
                return timeout(session);
            }
            TranscriptText.Turn turn = TranscriptText.answer(t, at);
            if (!turn.ended()) {
                return new Reply(ERROR, "session " + session + " of " + me.name() + " stopped before it answered", me.name(), session);
            }
            String answer = McpTools.clip(turn.text().isBlank() ? "(the turn ended without text)" : turn.text(), McpTools.MAX_RESULT_CHARS);
            return turn.failed() ? new Reply(ERROR, "the turn failed: " + answer, me.name(), session) : new Reply(OK, answer, me.name(), session);
        } finally {
            if (block != null) {
                claimed.remove(block);
            }
        }
    }

    /**
     * Waits for the reducer to have made {@code session}, at most {@link #MAKE_WITHIN} and never past {@code deadline}.
     */
    private boolean awaitSession(String session, long deadline) {
        long until = Math.min(deadline, System.nanoTime() + MAKE_WITHIN.toNanos());
        while (state.get().session(session).isEmpty()) {
            if (System.nanoTime() > until) {
                return false;
            }
            try {
                Thread.sleep(POLL_MILLIS / 4);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * The first top-level prompt block from {@code cursor} on with this text that no other answer waits on, claimed; -1
     * when none.
     */
    private int find(Transcript t, String prompt, int cursor) {
        int size = t.size();
        String wanted = prompt.strip();
        for (int i = size < cursor ? 0 : cursor; i < size; i++) {
            Block b = t.get(i);
            if (b instanceof Block.UserPrompt p && p.parent().isEmpty() && p.text().strip().equals(wanted) && claimed.add(b)) {
                return i;
            }
        }
        return -1;
    }

    private Reply timeout(String session) {
        return new Reply(TIMEOUT, "session " + session + " of " + me.name() + " did not finish its turn within " + answerWithin.toSeconds()
                + " s; its user sees it in that session's panel", me.name(), session);
    }

    private Reply closing(String session) {
        return new Reply(ERROR, me.name() + " closed before it answered", me.name(), session);
    }

    private void send(String topic, byte[] correlation, Reply r) {
        ObjectNode p = json.createObjectNode();
        p.put("status", r.status());
        p.put("text", r.text());
        p.put("instance", r.instance());
        p.put("session", r.session());
        broker.publish(topic, p.toString().getBytes(StandardCharsets.UTF_8), false, null, correlation).whenComplete((v, e) -> {
            if (e != null) {
                LOG.log(System.Logger.Level.WARNING, "a reply was not sent [topic=" + topic + ']', e);
            }
        });
    }

    /** Why {@code from} is refused, from {@code agents.json}'s {@code peers.deny}; an unreadable file refuses. */
    private Optional<String> refused(String from) {
        Path file = agentsFile;
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            for (JsonNode d : json.readTree(Files.readString(file)).path("peers").path("deny")) {
                if (from.equals(slug(d.asString("")))) {
                    return Optional.of("its user listed " + from + " in agents.json peers.deny");
                }
            }
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.of(file + " cannot be read, so its deny list cannot be checked: " + e.getMessage());
        }
    }

    private void reply(PeerBroker.Message m) {
        if (m.correlation() == null) {
            return;
        }
        Asked a = asked.get(new String(m.correlation(), StandardCharsets.UTF_8));
        if (a == null) {
            return;
        }
        try {
            JsonNode r = json.readTree(m.payload());
            a.reply().complete(new Reply(r.path("status").asString(ERROR), r.path("text").asString(""), r.path("instance").asString(a.peerName()),
                    r.path("session").asString("")));
        } catch (RuntimeException e) {
            a.reply().complete(new Reply(ERROR, "an unreadable reply: " + e.getMessage(), a.peerName(), ""));
        }
    }

    /** {@code instances_list}: the other instances present on the comando Edge. */
    McpTools.Outcome list() {
        if (!broker.connected()) {
            return McpTools.Outcome.fail(EDGE_DOWN);
        }
        List<Peer> others = others();
        if (others.isEmpty()) {
            return McpTools.Outcome.ok("no other instance is connected to the comando Edge; this one is " + me.name() + " (instance " + me.slug() + ')');
        }
        StringBuilder out = new StringBuilder();
        for (Peer p : others) {
            out.append(p.name()).append(" (instance ").append(p.slug()).append(") · comando ").append(p.version()).append(" · ")
                    .append(p.busy() ? "busy" : "idle").append(" · sessions: ")
                    .append(p.sessions().isEmpty() ? "none" : String.join(", ", p.sessions().stream()
                            .map(x -> x.name() + (x.busy() ? " (busy" + (x.activity().isEmpty() ? "" : ": " + x.activity()) + ")" : "")
                                    + (x.prompt().isEmpty() ? "" : " last asked: " + x.prompt())).toList())).append('\n');
        }
        return McpTools.Outcome.ok(out.append("this one is ").append(me.name()).append(" (instance ").append(me.slug())
                .append("); ask one with instance_ask {\"instance\": \"<instance>\", \"text\": \"…\"}").toString());
    }

    /**
     * {@code instance_ask}: sends the question to the peer's inbox and waits up to {@code wait_seconds} for the reply.
     */
    McpTools.Outcome ask(String caller, JsonNode args) {
        String to = args.path("instance").asString("").strip();
        String text = args.path("text").asString("").strip();
        if (to.isEmpty() || text.isEmpty()) {
            return McpTools.Outcome.fail("instance and text are required");
        }
        if (!broker.connected()) {
            return McpTools.Outcome.fail(EDGE_DOWN);
        }
        if (to.equalsIgnoreCase(me.slug()) || to.equalsIgnoreCase(me.name())) {
            return McpTools.Outcome.fail(to + " is this instance; instance_ask asks another one");
        }
        Optional<Peer> peer = others().stream().filter(p -> p.slug().equalsIgnoreCase(to) || p.name().equalsIgnoreCase(to)).findFirst();
        if (peer.isEmpty()) {
            return McpTools.Outcome.fail("no instance '" + to + "' is connected to the comando Edge; instances: " + names());
        }
        Peer p = peer.get();
        String id = UUID.randomUUID().toString();
        Asked a = new Asked(p.slug(), p.name(), new CompletableFuture<>());
        asked.put(id, a);
        record(new InstancesState.Consult(id, InstancesState.Direction.ASKED, p.slug(), p.name(), caller, McpTools.clip(text, QUESTION_CHARS),
                InstancesState.Status.WAITING, System.currentTimeMillis(), 0, ""));
        a.reply().whenComplete((r, e) -> ended(id, r != null ? r : new Reply(ERROR, e == null ? "no reply" : String.valueOf(e.getMessage()),
                p.name(), "")));
        ObjectNode q = json.createObjectNode();
        q.put("from", me.slug());
        q.put("name", me.name());
        q.put("session", caller);
        q.put("text", text);
        try {
            broker.publish(inboxTopic(p.slug()), q.toString().getBytes(StandardCharsets.UTF_8), false, replyTopic(me.slug()),
                    id.getBytes(StandardCharsets.UTF_8)).get(SEND_WITHIN.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            asked.remove(id);
            String why = "the question did not reach " + p.name() + ": " + (e.getCause() == null ? e : e.getCause()).getMessage();
            a.reply().complete(new Reply(ERROR, why, p.name(), ""));
            return McpTools.Outcome.fail(why);
        } catch (InterruptedException e) {
            asked.remove(id);
            a.reply().complete(new Reply(ERROR, "interrupted before the question was sent", p.name(), ""));
            Thread.currentThread().interrupt();
            return McpTools.Outcome.fail("interrupted before the question was sent");
        }
        return await(id, a, waitSeconds(args));
    }

    /** {@code instance_reply}: the reply to an earlier question, waiting up to {@code wait_seconds} for it. */
    McpTools.Outcome replyOf(JsonNode args) {
        String id = args.path("id").asString("").strip();
        if (id.isEmpty()) {
            return McpTools.Outcome.fail("id is required: the one instance_ask returned");
        }
        Asked a = asked.get(id);
        if (a == null) {
            return McpTools.Outcome.fail("no question '" + id + "' waits for a reply" + (asked.isEmpty() ? "" : "; waiting: " + List.copyOf(asked.keySet()))
                    + "; a reply is read once");
        }
        return await(id, a, waitSeconds(args));
    }

    private McpTools.Outcome await(String id, Asked a, int seconds) {
        Reply r;
        try {
            r = a.reply().get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return McpTools.Outcome.ok("no reply from " + a.peerName() + " yet after " + seconds + " s; its session " + CONSULT + me.slug()
                    + " is on it. Read the reply with instance_reply {\"id\": \"" + id + "\", \"wait_seconds\": " + DEFAULT_WAIT_SECONDS + "}");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpTools.Outcome.fail("interrupted; read the reply later with instance_reply {\"id\": \"" + id + "\"}");
        } catch (ExecutionException e) {
            asked.remove(id);
            return McpTools.Outcome.fail(a.peerName() + " could not answer: " + e.getCause().getMessage());
        }
        asked.remove(id);
        return switch (r.status()) {
            case OK -> McpTools.Outcome.ok("reply from " + r.instance() + (r.session().isEmpty() ? "" : " (its session " + r.session() + ")") + ":\n" + r.text());
            case REFUSED -> McpTools.Outcome.fail("refused: " + r.text());
            case TIMEOUT -> McpTools.Outcome.fail("timeout: " + r.text());
            default -> McpTools.Outcome.fail(a.peerName() + " could not answer: " + r.text());
        };
    }

    private static int waitSeconds(JsonNode args) {
        return Math.max(0, Math.min(args.path("wait_seconds").asInt(DEFAULT_WAIT_SECONDS), McpTools.MAX_WAIT_SECONDS));
    }

    // ---- the window: the Instances tab (record 124)

    /** Asks from the Instances tab: as the user, from no session, on a thread of its own; a notice says how it ended. */
    @Override
    public void ask(String instance, String text) {
        ObjectNode args = json.createObjectNode();
        args.put("instance", instance == null ? "" : instance);
        args.put("text", text == null ? "" : text);
        args.put("wait_seconds", McpTools.MAX_WAIT_SECONDS);
        errands.start("peer-ask", () -> {
            McpTools.Outcome out = ask("", args);
            String first = out.text().lines().findFirst().orElse("");
            sink.dispatch(new Intent.Notify(out.error() ? Notice.Level.WARN : Notice.Level.INFO,
                    (out.error() ? "Question to " + instance + ": " : "") + McpTools.clip(first, 200) + " (the Instances tab has it)", false));
        });
    }

    /** Refuses, or stops refusing, {@code instance}: rewrites {@code agents.json}'s {@code peers.deny}, keeping the rest. */
    @Override
    public void deny(String instance, boolean denied) {
        String who = slug(instance);
        try {
            setDenied(who, denied);
            sink.dispatch(new Intent.Notify(Notice.Level.INFO, denied ? "Questions from " + who + " are refused now"
                    : "Questions from " + who + " are taken again", false));
        } catch (IOException | RuntimeException e) {
            sink.dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR, "Cannot change " + agentsFile + ": " + e.getMessage(), e));
        }
        show();
    }

    /** Forgets {@code instance}, seen before and not present now: it is listed no more, here or after a restart. */
    @Override
    public void forget(String instance) {
        String who = slug(instance);
        if (peers.containsKey(who)) {
            sink.dispatch(new Intent.Notify(Notice.Level.INFO, who + " is present; only an instance that left can be forgotten", false));
            return;
        }
        synchronized (seen) {
            if (seen.remove(who) == null) {
                return;
            }
            writeSeen();
        }
        show();
    }

    /** Sets this instance's role: {@code instance.json}'s {@code role}, its other keys kept, and the next presence. */
    @Override
    public void role(String newRole) {
        String r = newRole == null ? "" : newRole.strip();
        try {
            if (instanceFile == null) {
                throw new IOException("this instance has no data root");
            }
            ObjectNode root = Files.isRegularFile(instanceFile) && json.readTree(Files.readString(instanceFile)) instanceof ObjectNode o
                    ? o : json.createObjectNode();
            root.put("role", r);
            AtomicFiles.writeString(instanceFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
            role = r;
            sink.dispatch(new Intent.Notify(Notice.Level.INFO, r.isEmpty() ? "This instance has no role now" : "This instance is for: " + r, false));
        } catch (IOException | RuntimeException e) {
            sink.dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR, "Cannot change " + instanceFile + ": " + e.getMessage(), e));
        }
        tick();
    }

    /** Whether {@code a} and {@code b} name the same instance alike: what the remembered list keeps of it. */
    private static boolean sameIdentity(Peer a, Peer b) {
        return a.name().equals(b.name()) && a.version().equals(b.version()) && a.role().equals(b.role()) && a.kit().equals(b.kit());
    }

    /** Remembers {@code p}, last seen at {@code at} (0: present now), and writes the file. */
    private void remember(Peer p, long at) {
        if (p.slug().equals(me.slug())) {
            return;
        }
        synchronized (seen) {
            seen.put(p.slug(), new Seen(p, at));
            writeSeen();
        }
    }

    /** {@code instances-seen.json} as {@link #seen} has it: each instance's identity and when it was last seen. */
    private void writeSeen() {
        Path file = seenFile;
        if (file == null) {
            return;
        }
        ObjectNode root = json.createObjectNode();
        ArrayNode list = root.putArray("instances");
        seen.values().forEach(x -> list.addObject().put("slug", x.peer().slug()).put("name", x.peer().name()).put("version", x.peer().version())
                .put("role", x.peer().role()).put("kit", x.peer().kit()).put("started", x.peer().started()).put("lastSeen", x.lastSeen()));
        try {
            AtomicFiles.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot write " + file, e);
        }
    }

    /** {@code agents.json} with {@code who} in, or out of, {@code peers.deny}; the other keys as they were. */
    synchronized void setDenied(String who, boolean denied) throws IOException {
        if (agentsFile == null) {
            throw new IOException("this instance has no agents.json");
        }
        ObjectNode root = Files.isRegularFile(agentsFile) && json.readTree(Files.readString(agentsFile)) instanceof ObjectNode o
                ? o : json.createObjectNode();
        ObjectNode peersNode = root.path("peers") instanceof ObjectNode o ? o : root.putObject("peers");
        List<String> list = new ArrayList<>();
        peersNode.path("deny").forEach(d -> {
            String s = slug(d.asString(""));
            if (!s.equals(who) && !list.contains(s)) {
                list.add(s);
            }
        });
        if (denied) {
            list.add(who);
        }
        ArrayNode deny = peersNode.putArray("deny");
        list.forEach(deny::add);
        AtomicFiles.writeString(agentsFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
    }

    /** The instances {@code agents.json} refuses; none when it is missing or unreadable. */
    private List<String> denied() {
        if (agentsFile == null || !Files.isRegularFile(agentsFile)) {
            return List.of();
        }
        try {
            List<String> out = new ArrayList<>();
            json.readTree(Files.readString(agentsFile)).path("peers").path("deny").forEach(d -> out.add(slug(d.asString(""))));
            return out;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private void record(InstancesState.Consult c) {
        synchronized (consults) {
            consults.addLast(c);
            while (consults.size() > InstancesState.CONSULTS) {
                consults.removeFirst();
            }
        }
        show();
    }

    private void ended(String id, Reply r) {
        InstancesState.Status status = switch (r.status()) {
            case OK -> InstancesState.Status.OK;
            case REFUSED -> InstancesState.Status.REFUSED;
            case TIMEOUT -> InstancesState.Status.TIMEOUT;
            default -> InstancesState.Status.ERROR;
        };
        synchronized (consults) {
            List<InstancesState.Consult> all = new ArrayList<>(consults);
            consults.clear();
            for (InstancesState.Consult c : all) {
                consults.addLast(c.id().equals(id) && c.status() == InstancesState.Status.WAITING
                        ? c.ended(status, McpTools.clip(r.text(), McpTools.MAX_RESULT_CHARS), System.currentTimeMillis()) : c);
            }
        }
        show();
    }

    /** The instances and questions as the window shows them now. */
    InstancesState instances() {
        boolean connected = !closed && broker.connected();
        // with the link down, no presence is current: every instance is shown as last seen
        List<Peer> present = connected ? others() : List.of();
        Set<String> here = new java.util.HashSet<>(present.stream().map(Peer::slug).toList());
        List<InstancesState.Instance> others = new ArrayList<>(present.stream().map(p -> new InstancesState.Instance(p.slug(), p.name(),
                p.version(), p.role(), p.kit(), p.busy(), p.started(), p.sessions(), p.edge(), true, 0L)).toList());
        synchronized (seen) {
            seen.values().stream().filter(x -> !here.contains(x.peer().slug())).sorted(Comparator.comparingLong(Seen::lastSeen).reversed())
                    .forEach(x -> others.add(new InstancesState.Instance(x.peer().slug(), x.peer().name(), x.peer().version(), x.peer().role(),
                            x.peer().kit(), false, x.peer().started(), List.of(), false, false, x.lastSeen())));
        }
        AppState s = state.get();
        InstancesState.Instance self = new InstancesState.Instance(me.slug(), me.name(), me.version(), role, kit,
                s.sessions().stream().anyMatch(SessionState::isBusy), started, members(s), edge.getAsBoolean(), connected, 0L);
        List<InstancesState.Consult> list;
        synchronized (consults) {
            list = List.copyOf(consults);
        }
        return new InstancesState(connected, self, others, list, denied());
    }

    /** Tells the window, when what it shows of the instances changed since it was last told. */
    void show() {
        if (closed) {
            return;
        }
        synchronized (consults) {
            InstancesState now = instances();
            if (now.equals(lastShown)) {
                return;
            }
            lastShown = now;
            sink.dispatch(new Intent.InstancesChanged(now));
        }
    }

    private List<Peer> others() {
        return peers.values().stream().filter(p -> !p.slug().equals(me.slug())).sorted(Comparator.comparing(Peer::name)).toList();
    }

    private String names() {
        List<Peer> others = others();
        return others.isEmpty() ? "none" : String.join(", ", others.stream().map(p -> p.name() + " (" + p.slug() + ')').toList());
    }

    private static String between(String topic, String prefix, String suffix) {
        if (!topic.startsWith(prefix) || !topic.endsWith(suffix) || topic.length() <= prefix.length() + suffix.length()) {
            return "";
        }
        return topic.substring(prefix.length(), topic.length() - suffix.length());
    }

    /**
     * Clears this instance's presence, stops answering and fails the questions still waiting; call it before the broker
     * closes.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        ticks.shutdownNow();
        // the instances present now were last seen now
        long now = System.currentTimeMillis();
        synchronized (seen) {
            others().forEach(p -> seen.put(p.slug(), new Seen(p, now)));
            if (!seen.isEmpty()) {
                writeSeen();
            }
        }
        if (broker.connected()) {
            try {
                broker.publish(presenceTopic(me.slug()), new byte[0], true, null, null).get(CLEAR_WITHIN.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException e) {
                LOG.log(System.Logger.Level.WARNING, "presence not cleared at close", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        errands.close();
        asked.values().forEach(a -> a.reply().complete(new Reply(ERROR, "comando is closing", a.peerName(), "")));
    }
}
