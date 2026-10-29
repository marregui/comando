package dev.marregui.comando.mcp;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.Errands;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.InstanceName;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.state.AppState;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 * publishes its presence, retained, when it connects and when it changes (at most every 2 s), and clears it on close; its
 * last will clears it when the instance dies. A question goes to the peer's inbox with a response topic and correlation
 * data; the peer prompts its session {@code consult-<asker>} through that session's panel, so its user sees the question,
 * and replies with Claude's text in the turn that answers it. Only the text a session chose to send and the reply's text
 * travel, never a brain's or a kit's files. A peer the user listed in the data root's {@code agents.json}
 * ({@code {"peers": {"deny": ["<id>"]}}}) is refused.
 */
public final class Peers implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Peers.class.getName());
    /** Every instance's topics are under this, then its id: {@code …/<id>/presence}, {@code …/<id>/inbox}, {@code …/<id>/reply}. */
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
    /** How long a new consult session may take to appear in the state after it was asked for. */
    private static final Duration MAKE_WITHIN = Duration.ofSeconds(5);
    static final String EDGE_DOWN = "this instance is not connected to the comando Edge, so it reaches no other instance: start the Edge with "
            + "its toggle at the top of the workspace, right of QuestDB's, in any comando instance; presence resumes within seconds once it runs";

    /** This instance: its name as the Dock shows it, its id in the topics, its version. */
    public record Me(String name, String slug, String version) {
        /** The instance named {@code instanceName} ({@link InstanceName}; empty for the main one), at {@code version}. */
        public static Me of(String instanceName, String version) {
            return new Me(InstanceName.display(instanceName), Peers.slug(instanceName), version);
        }

        /** Its MQTT client id: {@code comando-<slug>}, or the slug as it is when it already says comando ({@code comando-expert}). */
        public String clientId() {
            return slug.equals("comando") || slug.startsWith("comando-") ? slug : "comando-" + slug;
        }
    }

    /** Another instance, as its presence says. */
    record Peer(String slug, String name, String version, boolean busy, String started, List<Session> sessions) {
    }

    record Session(String name, boolean busy) {
    }

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
    }

    /** An instance's id in the topics: its name in lowercase letters, digits and dashes; {@code main} for the main instance. */
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

    /** Sets the last will, subscribes, and publishes presence on every connect and on every change; before the broker connects. */
    public void start() {
        broker.will(presenceTopic(me.slug()), new byte[0], true);
        broker.onConnected(this::connected);
        broker.subscribe(TOPICS + "+/presence", this::presence);
        broker.subscribe(inboxTopic(me.slug()), this::inbox);
        broker.subscribe(replyTopic(me.slug()), this::reply);
        ticks.scheduleWithFixedDelay(this::tick, PRESENCE_EVERY.toMillis(), PRESENCE_EVERY.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Connected, or again: the table is rebuilt from the retained presences the subscription brings, and this one's is sent. */
    private void connected() {
        peers.clear();
        synchronized (presenceLock) {
            lastPresence = null;
        }
        tick();
    }

    /** Publishes presence when it changed since it was last sent. */
    void tick() {
        // an exception escaping a periodic task cancels every later run
        try {
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
        p.put("started", started);
        AppState s = state.get();
        p.put("busy", s.sessions().stream().anyMatch(SessionState::isBusy));
        ArrayNode sessions = p.putArray("sessions");
        for (SessionState x : s.sessions()) {
            sessions.addObject().put("name", x.name()).put("busy", x.isBusy());
        }
        return p.toString();
    }

    // ---- presence

    private void presence(PeerBroker.Message m) {
        String slug = between(m.topic(), TOPICS, "/presence");
        if (slug.isEmpty()) {
            return;
        }
        if (m.payload() == null || m.payload().length == 0) {
            Peer gone = peers.remove(slug);
            String name = gone == null ? slug : gone.name();
            asked.forEach((id, a) -> {
                if (a.peer().equals(slug)) {
                    a.reply().complete(new Reply(ERROR, name + " left the comando Edge before it replied", name, ""));
                }
            });
            return;
        }
        try {
            JsonNode p = json.readTree(m.payload());
            List<Session> sessions = new ArrayList<>();
            p.path("sessions").forEach(x -> sessions.add(new Session(x.path("name").asString(""), x.path("busy").asBoolean(false))));
            peers.put(slug, new Peer(slug, p.path("name").asString(slug), p.path("version").asString(""), p.path("busy").asBoolean(false),
                    p.path("started").asString(""), List.copyOf(sessions)));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "unreadable presence [instance=" + slug + ", cause=" + e.getMessage() + ']');
        }
    }

    // ---- answering

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
        errands.start("peer-answer", () -> {
            Reply r;
            try {
                r = answer(from, q.path("name").asString(from), q.path("session").asString(""), q.path("text").asString(""));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "a question failed [instance=" + from + ']', e);
                r = new Reply(ERROR, me.name() + " could not answer: " + e.getMessage(), me.name(), CONSULT + from);
            }
            send(m.responseTopic(), m.correlation(), r);
        });
    }

    /**
     * Prompts this instance's session {@code consult-<from>} with the question, headed with who asks, in its panel; created
     * by {@code peer:<from>} in the default folder on the first question. Waits for the turn that answers it.
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

    /** Waits for the reducer to have made {@code session}, at most {@link #MAKE_WITHIN} and never past {@code deadline}. */
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

    /** The first top-level prompt block from {@code cursor} on with this text that no other answer waits on, claimed; -1 when none. */
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

    /** Why {@code from} is refused, from {@code agents.json}'s {@code peers.deny}; an unreadable file refuses, naming it. */
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

    // ---- asking

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
                            .map(x -> x.name() + (x.busy() ? " (busy)" : "")).toList())).append('\n');
        }
        return McpTools.Outcome.ok(out.append("this one is ").append(me.name()).append(" (instance ").append(me.slug())
                .append("); ask one with instance_ask {\"instance\": \"<instance>\", \"text\": \"…\"}").toString());
    }

    /** {@code instance_ask}: sends the question to the peer's inbox and waits up to {@code wait_seconds} for the reply. */
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
            return McpTools.Outcome.fail("the question did not reach " + p.name() + ": " + (e.getCause() == null ? e : e.getCause()).getMessage());
        } catch (InterruptedException e) {
            asked.remove(id);
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

    /** Clears this instance's presence, stops answering and fails the questions still waiting; before the broker closes. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        ticks.shutdownNow();
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
