package dev.marregui.comando.mcp;

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
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.json.ArrayNode;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.json.ObjectNode;
import dev.marregui.comando.core.loop.Errands;
import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.AtomicFiles;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.model.InstanceName;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.InstancesPort;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.MachinesPort;
import dev.marregui.comando.core.reduce.Reducer;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.InstancesState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;

/**
 * The comando instances on one machine consulting each other through frontear (records 123 and 186). Each instance
 * publishes its presence, retained, when it connects and when it changes (at most every 2 s), and clears it on close;
 * its last will clears it when the instance dies. A question goes to the peer's inbox with a response topic and
 * correlation data; the peer prompts the session the question names, made when missing, or else its session
 * {@code consult-<asker>}, through that session's panel, so its user sees the question, and replies with Claude's text
 * in the turn that answers it. Only the text a session chose to send and the reply's text travel, never a brain's or a
 * kit's files. A peer the user listed in the data root's {@code agents.json} ({@code {"peers": {"deny": ["<id>"]}}}) is
 * refused.
 * <p>
 * A presence says what the instance is doing, so another knows without asking: whether it hosts frontear, and for
 * each session its state, what its turn is doing and for how long, its model, effort, context, folder and branch, its
 * background tasks, and the first line of its last prompt and of its last reply. Those two lines are the only words of a
 * conversation it carries; on a broker off this machine they leave it. The instances seen are remembered in the data
 * root's {@code instances-seen.json}, so one that left stays listed, as last seen, until the user forgets it.
 * <p>
 * The instances, this one's connection and the questions asked and answered reach the window as
 * {@link Intent.InstancesChanged} after each change (record 124), and the user asks and refuses from the Instances tab
 * through {@link InstancesPort}.
 * <p>
 * With this machine's id set ({@link #machine}), the instances of the machines paired with it are reached too (record
 * 189), as {@code <slug>@<machine>}, under {@code comando/v1/<machine>/instance/}. This instance sends them a cut
 * presence, its name, slug, version, role, busy and its sessions' names, retained with a 30 s expiry; one of theirs
 * shows its instance present for 30 s from its arrival. A question from another machine, as the broker stamped its
 * origin, is taken only from a paired machine, with its payload's {@code from} and its response topic of that machine;
 * it is headed {@code · peer} and answered by a session in plan mode, unless the machine is granted the overseer's
 * authority and the asker is its {@code main}.
 */
public final class Peers implements AutoCloseable, InstancesPort {

    private static final System.Logger LOG = System.getLogger(Peers.class.getName());
    static final Duration PRESENCE_EVERY = Duration.ofSeconds(2);
    /** How long an instance waits for its consult session's turn before it replies {@code timeout}. */
    static final Duration ANSWER_WITHIN = Duration.ofSeconds(McpTools.MAX_WAIT_SECONDS);
    static final int DEFAULT_WAIT_SECONDS = 300;
    static final String CONSULT = "consult-";
    /** Who a consult session is created and prompted by: {@code peer:<id>}. */
    static final String PEER = "peer:";
    /** The main instance's id: the user's own, unnamed, the overseer of the others (record 186). */
    static final String MAIN = "main";
    /** The header's mark after the asker's name when the asker is {@link #MAIN}. */
    static final String OVERSEER = " · overseer";
    /** The header's mark after a remote asker's id when its machine does not grant it the overseer's (record 189). */
    static final String PEER_MARK = " · peer";
    /** The topics between machines are under this, then the machine's id, then {@code /instance/<slug>/…}. */
    static final String MACHINES = "comando/v1/";
    /** The machine id of the topics of one machine's own traffic. */
    static final String LOCAL = "local";
    /**
     * Every instance's topics are under this, then its id: {@code …/<id>/presence}, {@code …/<id>/inbox},
     * {@code …/<id>/reply}.
     */
    static final String TOPICS = MACHINES + LOCAL + "/instance/";
    /** The mode a session reports while it only plans, as Claude Code names it. */
    private static final String PLAN_MODE = "plan";
    /**
     * How long a presence sent to other machines lives, and how long one received shows its instance present, from its
     * arrival (record 189 §2.2).
     */
    static final Duration REMOTE_EXPIRY = Duration.ofSeconds(30);
    /**
     * At most this long between two presences sent to other machines, changed or not, so one lives on there while this
     * instance does: a third of {@link #REMOTE_EXPIRY}, so two may be lost before it expires (record 189 §2.2).
     */
    static final Duration REMOTE_EVERY = REMOTE_EXPIRY.dividedBy(3);
    /**
     * The instances of one other machine kept at once: the instances one machine runs, with room, the bound the
     * gateway puts on the presences it retains for one machine (record 189 §2.1). A presence of a new instance past it
     * is dropped and counted once the machine's instances no longer present are gone to make room.
     */
    static final int MAX_REMOTES = 64;
    /**
     * The consult sessions an instance makes for the instances of one other machine: the instances one user runs on a
     * laptop, with room (record 189 §2.1). A question that would make one more is refused, with a reply that says so.
     */
    static final int MAX_CONSULTS = 8;
    static final String OK = "ok";
    static final String REFUSED = "refused";
    static final String TIMEOUT = "timeout";
    static final String ERROR = "error";
    private static final Duration SEND_WITHIN = Duration.ofSeconds(10);
    /**
     * How long past the answer's bound a question still waits for its reply before it expires: a margin for a reply in
     * flight over a broker that reconnects (a few seconds), taken wide so a slow answer is not cut.
     */
    private static final Duration EXPIRE_AFTER = Duration.ofSeconds(30);
    /**
     * How many questions' correlation data the inbox remembers, so a question delivered twice is answered once: a
     * redelivery comes within seconds, and an instance is asked a handful of questions a minute, so 256 covers hours.
     */
    static final int INBOX_SEEN = 256;
    private static final Duration CLEAR_WITHIN = Duration.ofSeconds(2);
    private static final long POLL_MILLIS = 200;
    /** How much of a question the Instances tab keeps. */
    static final int QUESTION_CHARS = 4_000;
    /** How long a new consult session may take to appear in the state after it was asked for. */
    private static final Duration MAKE_WITHIN = Duration.ofSeconds(5);
    static final String OCTOPUS_DOWN = "this instance is not connected to frontear, so it reaches no other instance: "
            + "start frontear with its toggle at the top of the workspace, right of cdb's; "
            + "presence resumes within seconds once it runs";
    /**
     * This process's start, as the presence's {@code started} says it and the fabric's registry records it, so a
     * registry entry whose process is gone names the presence it left ({@link #gone}).
     */
    public static final String PROCESS_STARTED = ProcessHandle.current().info().startInstant().orElseGet(Instant::now)
            .toString();

    /** This instance: its name as the Dock shows it, its id in the topics, its version, what it is for. */
    public record Me(String name, String slug, String version, String role) {

        public Me {
            role = role == null ? "" : role.strip();
        }

        public Me(final String name, final String slug, final String version) {
            this(name, slug, version, "");
        }

        /**
         * The instance named {@code instanceName} ({@link InstanceName}; empty for the main one), at {@code version},
         * for {@code role} (its {@code instance.json}'s, or empty).
         */
        public static Me of(final String instanceName, final String version, final String role) {
            return new Me(InstanceName.display(instanceName), Peers.slug(instanceName), version, role);
        }

        public static Me of(final String instanceName, final String version) {
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
                List<InstancesState.Member> sessions, boolean host) {
    }

    /** An instance as this one last saw it: its presence, and when it was last seen present (0 while it is). */
    private record Seen(Peer peer, long lastSeen) {
    }

    /**
     * An instance of another machine, as its last presence said: its slug in {@code peer} is its id,
     * {@code <slug>@<machine>}; it came at {@code arrivedNanos} ({@link System#nanoTime}) and {@code arrivedMillis}.
     */
    private record Remote(Peer peer, String machine, long arrivedNanos, long arrivedMillis) {
    }

    /**
     * Who asks a question, as its header names it: its id, its name, its session or empty, the header's mark after the
     * name, and whether the session that answers it runs in plan mode.
     */
    private record Asker(String id, String name, String session, String mark, boolean plans) {
    }

    /** The instance a question goes to and its machine, empty for this one; or none, and {@code why}. */
    private record Target(Peer peer, String machine, String why) {

        static Target none(final String why) {
            return new Target(null, "", why);
        }
    }

    /** How much of a prompt's or a reply's first line a presence carries. */
    static final int LINE_CHARS = 200;
    /** How far back in a conversation its last prompt and reply are looked for. */
    private static final int LOOK_BACK = 4_000;
    /** The data root's file of the instances seen. */
    static final String SEEN_FILE = "instances-seen.json";
    /**
     * The schema version of the files this class writes, {@code instances-seen.json}, {@code instance.json} and
     * {@code agents.json}, in their {@code version} key (record 133; a file without it is version 1). A file of a later
     * version is kept as it is and never written.
     */
    static final int FILE_VERSION = 1;
    /**
     * The schema of the messages between instances, presence, question and reply, in their {@code schema} key (record
     * 133; the presence's {@code version} is comando's release). A question of a later schema is refused; a presence or
     * reply of a later schema is read for the keys this schema has, as it is shown and never stored.
     */
    static final int PEER_SCHEMA = 1;
    /** The presence's key saying whether the instance hosts frontear. */
    static final String HOST_KEY = "host";
    /**
     * The key a comando before record 186 writes in place of {@link #HOST_KEY}, read when that one is missing; dropped
     * one release after it.
     */
    static final String OLD_HOST_KEY = "edge";
    /**
     * The question's key naming the peer's session that answers it (record 186); a peer before it ignores the key and
     * answers from its {@code consult-<asker>}, so the schema stays {@link #PEER_SCHEMA}.
     */
    static final String TO = "to";

    /** A reply to a question, as it travels. */
    record Reply(String status, String text, String instance, String session) {
    }

    /**
     * A question this instance asked, waiting for its reply.
     *
     * @param target    the peer's session the question names; empty for the peer's {@code consult-<this instance>}
     * @param sentNanos when it was asked, by {@link System#nanoTime}: it expires {@link #EXPIRE_AFTER} past the answer's
     *                  bound, so a reply lost with its peer (gone while this instance was off frontear) is not waited
     *                  for until comando quits
     */
    private record Asked(String peer, String peerName, String target, CompletableFuture<Reply> reply, long sentNanos) {
    }

    private final PeerGateway gateway;
    private final Me me;
    private final IntentSink sink;
    private final Supplier<AppState> state;
    private final TranscriptRegistry transcripts;
    private final String workDir;
    private final Path agentsFile;
    private final Duration answerWithin;
    /** How long a question this instance asked waits for its reply before it expires: the answer's bound and margins. */
    private final Duration expireWithin;
    private final String started = PROCESS_STARTED;
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
    /** Whether this instance's frontear node runs; part of the presence. Set at wiring, read by the ticks' strand. */
    private volatile BooleanSupplier host = () -> false;
    /** Told each instance forgotten, so its fabric link and registry entry go too; set at wiring, read by forget. */
    private volatile Consumer<String> forgotten = _ -> { };
    /**
     * Puts this instance's frontear link to an instance now, so a question to it finds its subscriber; set at wiring,
     * read by the asks and by the errands of the presences first seen.
     */
    private volatile Consumer<String> linker = _ -> { };
    /**
     * This instance's sessions blocked now in an {@code instance_ask} or {@code instance_reply} wait, with how many
     * waits each: a question to one of them could not be taken before both waits ran out, so it is refused at once.
     */
    private final Map<String, Integer> waiting = new ConcurrentHashMap<>();
    /** The data root's {@code instances-seen.json} and {@code instance.json}; null until {@link #dataRoot} names them. */
    private volatile Path seenFile;
    private volatile Path instanceFile;
    /** Whether {@code instances-seen.json} is of a later version: kept as it is, never written. Guarded by {@link #seen}. */
    private boolean seenRefused;
    /** The other instances seen, present or not, by id; guarded by itself. */
    private final Map<String, Seen> seen = new LinkedHashMap<>();
    /** The prompt blocks answers are waiting on, so two equal questions in flight wait on a block each. */
    private final Set<Block> claimed = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private final ScheduledExecutorService ticks = Threads.timedStrand("peers");
    private final Errands errands = new Errands("peers");
    private final Object presenceLock = new Object();
    /**
     * The asker and correlation data of the latest questions, oldest first, at most {@link #INBOX_SEEN}: MQTT delivers a
     * question at least once, and one delivered again is not answered again. Guarded by itself.
     */
    private final Set<String> inboxSeen = Collections.newSetFromMap(new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, Boolean> eldest) {
            return size() > INBOX_SEEN;
        }
    });
    private final Object consultLock = new Object();
    private String lastPresence;
    private volatile boolean closed;
    /** How long a presence from another machine shows its instance present after it came: {@link #REMOTE_EXPIRY}. */
    private final Duration presentFor;
    /** At most this long between two cut presences, changed or not: {@link #REMOTE_EVERY}. */
    private final Duration resendEvery;
    /**
     * This machine's id, empty while none is set: written by the application thread through {@link #machine}, read by
     * the ticks' strand, the broker's delivery threads and the callers of the tools.
     */
    private volatile String machine = "";
    /**
     * The grant of each paired machine, null for one not paired: written by the application thread through
     * {@link #grants}, read by the broker's delivery threads for each question from another machine.
     */
    private volatile Function<String, MachinesPort.Grant> grants = _ -> null;
    /** Whether {@link #start} ran, and the machines whose topics are subscribed; guarded by {@link #machineLock}. */
    private final Object machineLock = new Object();
    private boolean running;
    private final Set<String> subscribed = new HashSet<>();
    /** The instances of other machines, by {@code <slug>@<machine>}, as their last presence said and when it came. */
    private final Map<String, Remote> remotes = new ConcurrentHashMap<>();
    /** Makes a new entry of {@link #remotes} and its machine's count one step, so no machine passes its bound. */
    private final Object remoteLock = new Object();
    /** The machines a presence was dropped of since they last had room, each logged once; guarded by remoteLock. */
    private final Set<String> fullMachines = new HashSet<>();
    /** The presences of new instances of other machines dropped since start, past their machine's bound. */
    private final AtomicLong droppedPresences = new AtomicLong();
    /** The questions from other machines dropped since start: not paired, or naming an asker the origin is not. */
    private final AtomicLong dropped = new AtomicLong();
    /** The cut presence last sent, its topic and when it was sent; guarded by {@link #presenceLock}. */
    private String lastCut;
    private String cutTopic = "";
    private long cutSentNanos;

    /**
     * Peers through {@code gateway} for instance {@code me}; consult sessions are made in {@code workDir}, and
     * {@code agentsFile} says which peers are refused.
     */
    public Peers(
            final PeerGateway gateway,
            final Me me,
            final IntentSink sink,
            final Supplier<AppState> state,
            final TranscriptRegistry transcripts,
            final String workDir,
            final Path agentsFile
    ) {
        this(gateway, me, sink, state, transcripts, workDir, agentsFile, ANSWER_WITHIN);
    }

    /** As above, with the bound on a consult turn given; for tests. */
    Peers(
            final PeerGateway gateway,
            final Me me,
            final IntentSink sink,
            final Supplier<AppState> state,
            final TranscriptRegistry transcripts,
            final String workDir,
            final Path agentsFile,
            final Duration answerWithin
    ) {
        this(gateway, me, sink, state, transcripts, workDir, agentsFile, answerWithin,
                answerWithin.plus(SEND_WITHIN).plus(EXPIRE_AFTER));
    }

    /** As above, with how long a question asked waits for its reply given too; for tests. */
    Peers(
            final PeerGateway gateway,
            final Me me,
            final IntentSink sink,
            final Supplier<AppState> state,
            final TranscriptRegistry transcripts,
            final String workDir,
            final Path agentsFile,
            final Duration answerWithin,
            final Duration expireWithin
    ) {
        this(gateway, me, sink, state, transcripts, workDir, agentsFile, answerWithin, expireWithin, REMOTE_EXPIRY,
                REMOTE_EVERY);
    }

    /**
     * As above, with how long a presence from another machine shows its instance present, and how long at most
     * between two cut presences, given too.
     */
    @TestOnly
    Peers(
            final PeerGateway gateway,
            final Me me,
            final IntentSink sink,
            final Supplier<AppState> state,
            final TranscriptRegistry transcripts,
            final String workDir,
            final Path agentsFile,
            final Duration answerWithin,
            final Duration expireWithin,
            final Duration presentFor,
            final Duration resendEvery
    ) {
        this.gateway = gateway;
        this.me = me;
        this.sink = sink;
        this.state = state;
        this.transcripts = transcripts;
        this.workDir = workDir;
        this.agentsFile = agentsFile;
        this.answerWithin = answerWithin;
        this.expireWithin = expireWithin;
        this.presentFor = presentFor;
        this.resendEvery = resendEvery;
        this.role = me.role();
    }

    /**
     * This machine's id (record 189), empty for none: with one, this instance's topics between machines are subscribed
     * and its cut presence is sent from the next tick. Refused with an {@link IllegalArgumentException} when it is not
     * a machine id or is {@code local}.
     */
    public void machine(final String id) {
        final String m = id == null ? "" : id.strip();
        if (!m.isEmpty() && (!MachinesPort.MACHINE_ID.matcher(m).matches() || LOCAL.equals(m))) {
            throw new IllegalArgumentException("refused: not a machine id [machine=" + m + ", pattern="
                    + MachinesPort.MACHINE_ID.pattern() + ']');
        }
        machine = m;
        final boolean now;
        synchronized (machineLock) {
            now = running;
        }
        if (now) {
            subscribeMachine(m);
        }
        onTicks();
    }

    /**
     * What each paired machine may do here, by its id: null for a machine not paired, whose questions are dropped
     * (record 189); asked for each question from another machine, on the broker's thread, so it must not block.
     */
    public void grants(final Function<String, MachinesPort.Grant> grantOf) {
        grants = grantOf == null ? _ -> null : grantOf;
    }

    /**
     * Subscribes machine {@code m}'s topics of this instance once, and the presences of every machine with the first;
     * the broker is called outside the lock, as it may deliver retained messages on this thread. Each is delivered at
     * least once (QoS 1), with the consumers of {@link #start} making a repeat harmless; a message whose expiry passed
     * on a link is dropped, so a question or a reply may not arrive at all and its asker's wait ends it.
     */
    private void subscribeMachine(final String m) {
        final boolean first;
        synchronized (machineLock) {
            if (m.isEmpty() || !subscribed.add(m)) {
                return;
            }
            first = subscribed.size() == 1;
        }
        if (first) {
            gateway.subscribe(MACHINES + "+/instance/+/presence", this::remotePresence);
        }
        gateway.subscribe(inboxTopic(m, me.slug()), this::inbox);
        gateway.subscribe(replyTopic(m, me.slug()), this::reply);
    }

    /** The questions from other machines dropped since start; the {@code machines} tool shows them. */
    long dropped() {
        return dropped.get();
    }

    /** The presences of new instances of other machines dropped since start, past {@link #MAX_REMOTES}. */
    @TestOnly
    long droppedPresences() {
        return droppedPresences.get();
    }

    /**
     * The data root whose {@code instances-seen.json} remembers the instances seen, read now, and whose
     * {@code instance.json} holds the role; call it before {@link #start}.
     */
    public void dataRoot(final Path home) {
        seenFile = home.resolve(SEEN_FILE);
        instanceFile = home.resolve(InstanceName.FILE);
        synchronized (seen) {
            seen.clear();
            if (!Files.isRegularFile(seenFile)) {
                return;
            }
            try {
                final JsonNode root = Json.readTree(Mapped.text(seenFile));
                if (later(root)) {
                    seenRefused = true;
                    LOG.log(System.Logger.Level.WARNING, "kept the instances seen as they are, not written and not "
                            + "listed: the file is of a later version [file=" + seenFile + ", version="
                            + root.path("version").asInt() + ", supported=" + FILE_VERSION + ']');
                    // the user is told once, naming the file, both versions and that nothing is saved to it (record 133)
                    sink.dispatch(new Intent.Notify(Notice.Level.WARN, SEEN_FILE + " is of version " + root.path("version").asInt() + ", written by a "
                            + "later comando; this one reads version " + FILE_VERSION + ". It is kept as it is and nothing is saved to it; the instances "
                            + "seen before are not listed", false));
                    return;
                }
                for (final JsonNode n : root.path("instances")) {
                    final Peer p = peer(n.path("slug").asString(""), n);
                    if (!p.slug().isEmpty() && !p.slug().equals(me.slug())) {
                        seen.put(p.slug(), new Seen(p, n.path("lastSeen").asLong(0L)));
                    }
                }
            } catch (final IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot read the instances seen; they are not listed [file="
                        + seenFile + ']', e);
            }
        }
    }

    /** Whether this instance's frontear node runs, asked at each tick; part of the presence. */
    public void host(final BooleanSupplier hostedHere) {
        host = hostedHere == null ? () -> false : hostedHere;
    }

    /** Who is told the id of each instance {@link #forget} forgets, on the caller's thread; it must not block. */
    public void onForget(final Consumer<String> to) {
        forgotten = to == null ? _ -> { } : to;
    }

    /**
     * Who puts this instance's frontear link to an instance by its id, when it has none yet: the link is the only
     * subscriber of that instance's inbox on this node. Run on an errand for each other instance whose presence comes
     * while it is not present here, and on the asker's thread before each question to it, so a question asked in the
     * seconds after it appeared does not reach no one; it may block, bounded.
     */
    public void linkTo(final Consumer<String> link) {
        linker = link == null ? _ -> { } : link;
    }

    /** An instance's id in the topics: its name in lowercase letters, digits and dashes; {@code main} when unnamed. */
    public static String slug(final String instanceName) {
        return InstanceName.slug(instanceName);
    }

    /**
     * An instance's id as the deny list and the Instances tab hold it: {@code <slug>@<machine>} for one of another
     * machine (record 189), its {@link #slug} otherwise; a part after {@code @} that is no machine id is a slug's.
     */
    static String id(final String instance) {
        final String x = instance == null ? "" : instance.strip();
        final String m = InstanceName.machineOf(x).toLowerCase(Locale.ROOT);
        return MachinesPort.MACHINE_ID.matcher(m).matches() && !LOCAL.equals(m)
                ? InstanceName.onMachine(slug(InstanceName.slugOf(x)), m) : slug(x);
    }

    /** The topic of instance {@code slug}'s presence, retained. */
    public static String presenceTopic(final String slug) {
        return TOPICS + slug + "/presence";
    }

    /** The topic instance {@code slug} takes questions on. */
    public static String inboxTopic(final String slug) {
        return TOPICS + slug + "/inbox";
    }

    /** The topic instance {@code slug} takes the replies to its questions on. */
    public static String replyTopic(final String slug) {
        return TOPICS + slug + "/reply";
    }

    /** The topic of instance {@code slug}'s presence for other machines, of machine {@code machine} (record 189). */
    public static String presenceTopic(final String machine, final String slug) {
        return MACHINES + machine + "/instance/" + slug + "/presence";
    }

    /** The topic instance {@code slug} of machine {@code machine} takes questions from other machines on. */
    public static String inboxTopic(final String machine, final String slug) {
        return MACHINES + machine + "/instance/" + slug + "/inbox";
    }

    /** The topic instance {@code slug} of machine {@code machine} takes replies from other machines on. */
    public static String replyTopic(final String machine, final String slug) {
        return MACHINES + machine + "/instance/" + slug + "/reply";
    }

    /**
     * Sets the last will, subscribes, and publishes presence on every connect and every change; call it before the
     * broker connects. Every subscription is at least once (QoS 1), each consumer idempotent: a question delivered
     * again (its asker and correlation data) is answered once, a reply completes its waiting ask once and a later one
     * changes nothing, and a presence is retained state that the next one replaces.
     */
    public void start() {
        gateway.will(presenceTopic(me.slug()), new byte[0], true);
        gateway.onConnected(this::connected);
        gateway.subscribe(TOPICS + "+/presence", this::presence);
        gateway.subscribe(inboxTopic(me.slug()), this::inbox);
        gateway.subscribe(replyTopic(me.slug()), this::reply);
        synchronized (machineLock) {
            running = true;
        }
        subscribeMachine(machine);
        ticks.scheduleWithFixedDelay(this::tick, PRESENCE_EVERY.toMillis(), PRESENCE_EVERY.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Connected, or again: the table is rebuilt from the retained presences that come, and this one's is sent from the
     * ticks' strand, so presences leave in the order they were made.
     */
    private void connected() {
        peers.clear();
        resendPresence();
    }

    /**
     * Sends this instance's presence again at the next tick, changed or not: a peer's node that restarted holds no
     * retained copy of it, so the fabric calls this when its link to a peer connects.
     */
    public void resendPresence() {
        synchronized (presenceLock) {
            lastPresence = null;
            lastCut = null;
        }
        onTicks();
    }

    /**
     * Instance {@code slug}'s process, which started at {@code started}, is gone: the presence it left retained is
     * dropped, and the instance remembered as last seen now, when that presence is of the same process. A presence of
     * another process, one that started since, stays. The questions asked of it wait: its peer link queues them.
     */
    public void gone(final String slug, final String started) {
        final Peer p = peers.get(slug);
        if (p == null || slug.equals(me.slug()) || !p.started().equals(started) || !peers.remove(slug, p)) {
            return;
        }
        remember(p, System.currentTimeMillis());
        LOG.log(System.Logger.Level.INFO, "peer dropped: its process is gone [instance=" + slug + ", started="
                + started + ']');
        show();
    }

    /** Runs a {@link #tick} on the ticks' strand, unless this has closed. */
    private void onTicks() {
        if (closed) {
            return;
        }
        try {
            ticks.execute(this::tick);
        } catch (final RejectedExecutionException e) {
            LOG.log(System.Logger.Level.DEBUG, "presence not sent: closing");
        }
    }

    /** The kit's commit or description ({@code git describe}), sent in the presence from the next tick on. */
    public void kit(final String described) {
        kit = described == null ? "" : described.strip();
    }

    /**
     * Publishes presence when it changed since it was last sent, expires the questions whose reply cannot come any more,
     * and tells the window what changed. The application runs it on the ticks' strand only, so presences are published
     * in order, outside the lock.
     */
    void tick() {
        // an exception escaping a periodic task cancels every later run
        try {
            expire();
            show();
            if (closed || !gateway.connected()) {
                return;
            }
            // the presence is built, compared and handed to the broker under one lock, so two ticks on different
            // threads (a test's, the strand's) cannot hand the broker an older presence after the newer one it keeps
            synchronized (presenceLock) {
                final String now = presenceJson();
                if (!now.equals(lastPresence)) {
                    lastPresence = now;
                    sent(gateway.publish(presenceTopic(me.slug()), now.getBytes(StandardCharsets.UTF_8), true, null,
                            null));
                }
                cutTick();
            }
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot send presence", e);
        }
    }

    /**
     * Sends the cut presence for other machines, under {@link #presenceLock}, when this machine has an id: when it
     * changed, and unchanged once {@link #resendEvery} less a tick has passed, so no two are further apart than
     * {@link #resendEvery}; each expires {@link #REMOTE_EXPIRY} after it was sent. The one sent under an id this
     * machine no longer has is cleared.
     */
    private void cutTick() {
        final String m = machine;
        final String topic = m.isEmpty() ? "" : presenceTopic(m, me.slug());
        if (!cutTopic.isEmpty() && !cutTopic.equals(topic)) {
            sent(gateway.publish(cutTopic, new byte[0], true, null, null));
            cutTopic = "";
            lastCut = null;
        }
        if (topic.isEmpty()) {
            return;
        }
        final String now = cutPresenceJson();
        final long at = System.nanoTime();
        if (now.equals(lastCut) && at - cutSentNanos < resendEvery.minus(PRESENCE_EVERY).toNanos()) {
            return;
        }
        lastCut = now;
        cutTopic = topic;
        cutSentNanos = at;
        sent(gateway.publish(topic, now.getBytes(StandardCharsets.UTF_8), true, null, null,
                REMOTE_EXPIRY.toSeconds()));
    }

    /** Logs a presence the broker did not take. */
    private static void sent(final CompletableFuture<Void> publish) {
        publish.whenComplete((v, e) -> {
            if (e != null) {
                LOG.log(System.Logger.Level.DEBUG, "presence not sent [cause=" + e.getMessage() + ']');
            }
        });
    }

    /**
     * The presence other machines see (record 189 §2.1): name, slug, version, role, whether it is busy and its
     * sessions' names; no folder, branch, prompt, reply or task leaves this machine.
     */
    String cutPresenceJson() {
        final ObjectNode p = Json.object();
        p.put("schema", PEER_SCHEMA);
        p.put("name", me.name());
        p.put("slug", me.slug());
        p.put("version", me.version());
        p.put("role", role);
        final AppState s = state.get();
        p.put("busy", s.sessions().stream().anyMatch(SessionState::isBusy));
        final ArrayNode sessions = p.putArray("sessions");
        for (final SessionState x : s.sessions()) {
            sessions.addObject().put("name", x.name());
        }
        return p.toString();
    }

    /**
     * Ends the questions asked longer ago than the answer's bound, the send's and {@link #EXPIRE_AFTER}: the peer
     * answers within its bound or not at all, so a reply that has not come by then was lost (the peer left while this
     * instance was off frontear, and no presence of it comes again).
     */
    private void expire() {
        final long now = System.nanoTime();
        final long within = expireWithin.toNanos();
        asked.forEach((id, a) -> {
            if (now - a.sentNanos() > within) {
                a.reply().complete(new Reply(ERROR, a.peerName() + " sent no reply within " + expireWithin.toSeconds()
                        + " s; it may have left frontear", a.peerName(), ""));
            }
        });
    }

    String presenceJson() {
        final ObjectNode p = Json.object();
        p.put("schema", PEER_SCHEMA);
        p.put("name", me.name());
        p.put("slug", me.slug());
        p.put("version", me.version());
        p.put("role", role);
        p.put("kit", kit);
        p.put("started", started);
        final AppState s = state.get();
        final boolean hosts = host.getAsBoolean();
        p.put(HOST_KEY, hosts);
        // a comando before record 186 reads only this key; written beside HOST_KEY for one release after record 186,
        // then dropped with OLD_HOST_KEY
        p.put(OLD_HOST_KEY, hosts);
        p.put("busy", s.sessions().stream().anyMatch(SessionState::isBusy));
        final ArrayNode sessions = p.putArray("sessions");
        for (final InstancesState.Member m : members(s)) {
            final ObjectNode o = sessions.addObject().put("name", m.name()).put("busy", m.busy()).put("status", m.status())
                    .put("activity", m.activity()).put("turnStarted", m.turnStarted()).put("model", m.model()).put("effort", m.effort())
                    .put("context", m.context()).put("folder", m.folder()).put("branch", m.branch());
            final ArrayNode tasks = o.putArray("tasks");
            m.tasks().forEach(tasks::add);
            o.put("prompt", m.prompt()).put("reply", m.reply());
        }
        return p.toString();
    }

    /** This instance's sessions, each with what it is doing. */
    private List<InstancesState.Member> members(final AppState s) {
        return s.sessions().stream().map(this::member).toList();
    }

    private InstancesState.Member member(final SessionState x) {
        final boolean usable = x.status().isUsable();
        final String model = usable && !x.info().turnModel().isBlank() ? x.info().turnModel() : usable && !x.info().model().isBlank() ? x.info().model()
                : x.spec().model();
        final String effort = usable && !x.info().turnEffort().isBlank() ? x.info().turnEffort()
                : usable && !x.info().reportedEffort().isBlank() ? x.info().reportedEffort() : x.spec().effort();
        final int context = x.contextWindow() > 0 ? (int) Math.min(100, x.contextTokens() * 100 / x.contextWindow()) : -1;
        final String[] ends = ends(x.name());
        return new InstancesState.Member(x.name(), x.isBusy(), x.status().name(), x.isBusy() ? x.activity() : "",
                x.isBusy() ? x.turnStartedMillis() : 0L, model, effort, context, HomeDir.shorten(x.spec().workDir()), x.branch(),
                x.tasks().stream().map(t -> t.description()).toList(), ends[0], ends[1]);
    }

    /** The first line of {@code session}'s last prompt and of its last reply, its own (not a subagent's), clipped. */
    private String[] ends(final String session) {
        final Transcript t = transcripts.of(session);
        String prompt = null;
        String reply = null;
        for (int i = t.size() - 1, last = Math.max(0, t.size() - LOOK_BACK); i >= last && (prompt == null || reply == null); i--) {
            final Block b = t.get(i);
            if (prompt == null && b instanceof Block.UserPrompt u && u.parent().isEmpty()) {
                prompt = firstLine(u.text());
            } else if (reply == null && b instanceof Block.AssistantText a && a.parent().isEmpty() && !a.text().isBlank()) {
                reply = firstLine(a.text());
            }
        }
        return new String[]{prompt == null ? "" : prompt, reply == null ? "" : reply};
    }

    /** The first line that says something, clipped; a consult's {@code [from instance …]} header is skipped. */
    static String firstLine(final String text) {
        return text.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("[from instance ")).findFirst()
                .map(l -> McpTools.clip(l, LINE_CHARS)).orElse("");
    }

    /** A presence, or a remembered one, read. */
    private static Peer peer(final String slug, final JsonNode p) {
        final List<InstancesState.Member> sessions = new ArrayList<>();
        p.path("sessions").forEach(x -> {
            final List<String> tasks = new ArrayList<>();
            x.path("tasks").forEach(t -> tasks.add(t.asString("")));
            sessions.add(new InstancesState.Member(x.path("name").asString(""), x.path("busy").asBoolean(false), x.path("status").asString(""),
                    x.path("activity").asString(""), x.path("turnStarted").asLong(0L), x.path("model").asString(""),
                    x.path("effort").asString(""), x.path("context").asInt(-1), x.path("folder").asString(""), x.path("branch").asString(""),
                    tasks, x.path("prompt").asString(""), x.path("reply").asString("")));
        });
        return new Peer(slug, p.path("name").asString(slug), p.path("version").asString(""), p.path("role").asString(""),
                p.path("kit").asString(""), p.path("busy").asBoolean(false), p.path("started").asString(""), List.copyOf(sessions),
                p.path(HOST_KEY).asBoolean(p.path(OLD_HOST_KEY).asBoolean(false)));
    }

    private void presence(final PeerGateway.Message m) {
        final String slug = between(m.topic(), TOPICS, "/presence");
        if (slug.isEmpty()) {
            return;
        }
        if (m.payload() == null || m.payload().length == 0) {
            final Peer gone = peers.remove(slug);
            final String name = gone == null ? slug : gone.name();
            if (gone != null) {
                remember(gone, System.currentTimeMillis());
            }
            asked.forEach((id, a) -> {
                if (a.peer().equals(slug)) {
                    a.reply().complete(new Reply(ERROR, name + " left frontear before it replied", name, ""));
                }
            });
            show();
            return;
        }
        try {
            final Peer p = peer(slug, Json.readTree(m.payload()));
            final Peer before = peers.put(slug, p);
            if (!slug.equals(me.slug()) && (before == null || !sameIdentity(before, p))) {
                remember(p, 0L);
            }
            if (before == null && !slug.equals(me.slug())
                    && errands.start("peer-link", () -> link(slug)) == null) {
                LOG.log(System.Logger.Level.DEBUG, "no link put: closing [instance=" + slug + ']');
            }
            show();
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "unreadable presence [instance=" + slug + ", cause=" + e.getMessage() + ']');
        }
    }

    /**
     * A presence of an instance of another machine, {@code comando/v1/<machine>/instance/<slug>/presence}: kept with
     * when it came, as {@link #keepRemote} bounds it, or dropped with an empty payload, the questions asked of it
     * failed. This machine's and {@code local}'s are not another machine's; one whose origin, as the broker stamped
     * it, is not its topic's machine is dropped. It is neither remembered nor linked to: the gateway's link to its
     * machine carries it.
     */
    private void remotePresence(final PeerGateway.Message m) {
        final String[] t = m.topic().split("/", -1);
        if (t.length != 6 || !"instance".equals(t[3]) || !"presence".equals(t[5])) {
            return;
        }
        final String from = t[2];
        final String slug = t[4];
        if (LOCAL.equals(from) || from.equals(machine) || !MachinesPort.MACHINE_ID.matcher(from).matches()
                || !slug.equals(slug(slug))) {
            return;
        }
        if (!InstanceName.origin(from).equals(m.origin())) {
            LOG.log(System.Logger.Level.DEBUG, "a presence from another machine than its topic's, dropped [topic="
                    + m.topic() + ", origin=" + m.origin() + ']');
            return;
        }
        final String id = InstanceName.onMachine(slug, from);
        if (m.payload() == null || m.payload().length == 0) {
            remotes.remove(id);
            asked.forEach((_, a) -> {
                if (a.peer().equals(id)) {
                    a.reply().complete(new Reply(ERROR, id + " left frontear before it replied", id, ""));
                }
            });
            show();
            return;
        }
        try {
            final Peer p = peer(id, Json.readTree(m.payload()));
            final Peer remote = new Peer(id, p.name(), p.version(), p.role(), "", p.busy(), "", p.sessions(), false);
            if (keepRemote(id, new Remote(remote, from, System.nanoTime(), System.currentTimeMillis()))) {
                show();
            }
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "unreadable presence [instance=" + id + ", cause=" + e.getMessage()
                    + ']');
        }
    }

    /**
     * Keeps {@code r} as instance {@code id}, or drops it and counts it when it is a new instance of a machine that has
     * {@link #MAX_REMOTES} kept: that machine's instances no longer present are forgotten first to make room.
     */
    private boolean keepRemote(final String id, final Remote r) {
        final String of = r.machine();
        final boolean logged;
        synchronized (remoteLock) {
            if (!remotes.containsKey(id) && remotesOf(of) >= MAX_REMOTES) {
                remotes.values().removeIf(x -> x.machine().equals(of) && !present(x));
            }
            if (remotes.containsKey(id) || remotesOf(of) < MAX_REMOTES) {
                remotes.put(id, r);
                fullMachines.remove(of);
                return true;
            }
            droppedPresences.incrementAndGet();
            logged = fullMachines.add(of);
        }
        if (logged) {
            LOG.log(System.Logger.Level.WARNING, "a presence of another machine's instance dropped, the machine has "
                    + "its most instances kept, the next not logged until it has room [machine=" + of + ", instance="
                    + id + ", maxRemotes=" + MAX_REMOTES + ']');
        }
        return false;
    }

    /** How many instances of machine {@code m} {@link #remotes} holds, present or not. */
    private long remotesOf(final String m) {
        return remotes.values().stream().filter(x -> x.machine().equals(m)).count();
    }

    /** Whether {@code r}'s last presence came less than {@link #presentFor} ago. */
    private boolean present(final Remote r) {
        return System.nanoTime() - r.arrivedNanos() < presentFor.toNanos();
    }

    /** {@link #linkTo}'s listener for instance {@code slug}, on the caller's thread; a failure is logged. */
    private void link(final String slug) {
        try {
            linker.accept(slug);
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot put the link to an instance [instance=" + slug + ']', e);
        }
    }

    private void inbox(final PeerGateway.Message m) {
        if (m.responseTopic() == null || m.correlation() == null) {
            LOG.log(System.Logger.Level.DEBUG, "a question without a response topic or correlation data, dropped [topic=" + m.topic() + ']');
            return;
        }
        JsonNode q;
        try {
            q = Json.readTree(m.payload());
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "unreadable question, dropped [cause=" + e.getMessage() + ']');
            return;
        }
        if (q.path("from").asString("").isBlank()) {
            LOG.log(System.Logger.Level.DEBUG, "a question that names no asker, dropped");
            return;
        }
        final Asker asker = m.origin() == null ? localAsker(q) : remoteAsker(m, q);
        if (asker == null) {
            return;
        }
        final String from = asker.id();
        final String fromName = asker.name();
        // the asker's correlation data keys the question's log lines here and in the asker's
        final String correlation = new String(m.correlation(), StandardCharsets.UTF_8);
        // delivered at least once: the same question again (its asker and correlation data) is answered once
        final String key = from + ' ' + correlation;
        synchronized (inboxSeen) {
            if (!inboxSeen.add(key)) {
                LOG.log(System.Logger.Level.DEBUG, "a question delivered again, dropped [id=" + correlation
                        + ", instance=" + from + ']');
                return;
            }
        }
        if (q.path("schema").asInt(1) > PEER_SCHEMA) {
            LOG.log(System.Logger.Level.INFO, "question refused: of a later schema [id=" + correlation + ", instance="
                    + from + ", schema=" + q.path("schema") + ", supported=" + PEER_SCHEMA + ']');
            send(m.responseTopic(), m.correlation(), new Reply(REFUSED, me.name() + " reads questions of schema " + PEER_SCHEMA + " and this one is of "
                    + q.path("schema") + ": upgrade comando on " + me.name(), me.name(), ""));
            return;
        }
        final String id = UUID.randomUUID().toString();
        // the session the question names answers it; none named, this instance's consult session for the asker. One
        // from another machine always goes to its consult session: a name would reach another asker's (record 189)
        final String named = q.path(TO).asString("").strip();
        if (m.origin() != null && !named.isEmpty()) {
            LOG.log(System.Logger.Level.DEBUG, "the session a question from another machine names, ignored [id="
                    + correlation + ", instance=" + from + ", named=" + named + ']');
        }
        final String session = named.isEmpty() || m.origin() != null ? CONSULT + from : named;
        LOG.log(System.Logger.Level.INFO, "question received [id=" + correlation + ", instance=" + from + ", session="
                + session + ']');
        record(new InstancesState.Consult(id, InstancesState.Direction.ANSWERED, from, fromName, session,
                McpTools.clip(q.path("text").asString(""), QUESTION_CHARS), InstancesState.Status.WAITING, System.currentTimeMillis(), 0, ""));
        errands.start("peer-answer", () -> {
            Reply r;
            try {
                r = answer(correlation, asker, session, q.path("text").asString(""));
            } catch (final RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "a question failed [id=" + correlation + ", instance=" + from
                        + ']', e);
                r = new Reply(ERROR, me.name() + " could not answer: " + e.getMessage(), me.name(), session);
            }
            LOG.log(System.Logger.Level.INFO, "question ended [id=" + correlation + ", instance=" + from + ", status="
                    + r.status() + ", session=" + r.session() + ']');
            ended(id, r);
            send(m.responseTopic(), m.correlation(), r);
        });
    }

    /** The asker of a question of this machine, as its payload names it; its main is the overseer (record 186). */
    private static Asker localAsker(final JsonNode q) {
        final String from = slug(q.path("from").asString(""));
        return new Asker(from, q.path("name").asString(from), q.path("session").asString(""),
                MAIN.equals(from) ? OVERSEER : "", false);
    }

    /**
     * The asker of a question from machine {@code m.origin()} (record 189), or null when the question is dropped,
     * logged and counted: this machine has an id and the question came on its inbox here, the machine is paired, the
     * payload's {@code from} is {@code <slug>@<that machine>} and the response topic is that instance's reply topic
     * there. It is named by its id, as its payload's name is not checked; its main is the overseer only when its
     * machine is granted the overseer's authority, and every other asker's question is answered in plan mode.
     */
    private Asker remoteAsker(final PeerGateway.Message m, final JsonNode q) {
        final String here = machine;
        final String origin = m.origin();
        final String there = InstanceName.machineOfOrigin(origin);
        final String from = q.path("from").asString("").strip();
        final String fromMachine = InstanceName.machineOf(from);
        final String slug = fromMachine.isEmpty() ? "" : InstanceName.slugOf(from);
        MachinesPort.Grant grant = null;
        final String why;
        if (here.isEmpty() || !m.topic().equals(inboxTopic(here, me.slug()))) {
            why = "not on this machine's inbox of this instance";
        } else if (!MachinesPort.MACHINE_ID.matcher(there).matches() || LOCAL.equals(there) || there.equals(here)) {
            why = "its origin names no other machine";
        } else if ((grant = grantOf(there)) == null) {
            why = "its machine is not paired";
        } else if (slug.isEmpty() || !slug.equals(slug(slug)) || !fromMachine.equals(there)) {
            why = "its asker is not an instance of its machine";
        } else if (!replyTopic(there, slug).equals(m.responseTopic())) {
            why = "its response topic is not its asker's";
        } else {
            why = "";
        }
        if (!why.isEmpty()) {
            dropped.incrementAndGet();
            LOG.log(System.Logger.Level.DEBUG, "a question from another machine, dropped: " + why + " [origin="
                    + origin + ", from=" + from + ", topic=" + m.topic() + ", responseTopic=" + m.responseTopic()
                    + ']');
            return null;
        }
        final boolean overseer = grant == MachinesPort.Grant.OVERSEER && MAIN.equals(slug);
        final String id = InstanceName.onMachine(slug, there);
        return new Asker(id, id, headerSafe(q.path("session").asString("")), overseer ? OVERSEER : PEER_MARK,
                !overseer);
    }

    /** How many consult sessions for the instances of machine {@code m}, {@code consult-<slug>@<m>}, {@code s} has. */
    private static long consultsOf(final AppState s, final String m) {
        return s.sessions().stream().map(SessionState::name)
                .filter(n -> n.startsWith(CONSULT) && m.equals(InstanceName.machineOf(n))).count();
    }

    /** {@link #grants}'s answer for machine {@code m}; a failure of it is no grant. */
    private MachinesPort.Grant grantOf(final String m) {
        try {
            return grants.apply(m);
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read a machine's grant; taken as not paired [machine=" + m
                    + ']', e);
            return null;
        }
    }

    /** {@code text} when a header may name it, empty when it holds a header's marks or a line break. */
    private static String headerSafe(final String text) {
        return text.chars().anyMatch(c -> c == '[' || c == ']' || c == '·' || c == '\n' || c == '\r') ? ""
                : text.strip();
    }

    /**
     * Prompts this instance's {@code session}, the one the question names or {@code consult-<from>}, with the
     * question, headed with who asks, in its panel; created by {@code peer:<from>} in the default folder when missing,
     * in plan mode when the asker's questions are answered so. Waits for the turn that answers it. A session blocked in
     * a wait of its own for a reply is refused at once, and so is one that does not plan for an asker whose questions
     * are answered in plan mode, and one that would be made past its machine's {@link #MAX_CONSULTS}.
     */
    private Reply answer(final String correlation, final Asker asker, final String session, final String text) {
        final String from = asker.id();
        final String fromName = asker.name();
        final Optional<String> refused = refused(from);
        if (refused.isPresent()) {
            sink.dispatch(new Intent.Notify(Notice.Level.INFO, "Refused a question from instance " + fromName + ": " + refused.get(), false));
            return new Reply(REFUSED, me.name() + " takes no questions from " + fromName + ": " + refused.get(), me.name(), "");
        }
        if (text.isBlank()) {
            return new Reply(ERROR, "the question was empty", me.name(), session);
        }
        if (waiting.containsKey(session)) {
            // the asker may be the very session that waits for this one's reply: both would wait until their bounds
            return new Reply(ERROR, "session " + session + " of " + me.name() + " is waiting for a reply of its own "
                    + "(instance_ask), so it cannot take this question now: put the question in your reply to it, ask "
                    + "again once its wait ended, or name another session", me.name(), session);
        }
        final String prompt = header(asker) + '\n' + text.strip();
        final long deadline = System.nanoTime() + answerWithin.toNanos();
        int cursor;
        // two questions arriving together make the session once, and reach it in the order they came
        synchronized (consultLock) {
            final AppState s = state.get();
            final Optional<SessionState> existing = s.session(session);
            cursor = existing.isPresent() ? transcripts.of(session).size() : 0;
            final String there = InstanceName.machineOf(from);
            if (existing.isEmpty() && !there.isEmpty() && consultsOf(s, there) >= MAX_CONSULTS) {
                LOG.log(System.Logger.Level.INFO, "question refused, its machine has its most consult sessions "
                        + "here [id=" + correlation + ", session=" + session + ", machine=" + there + ", maxConsults="
                        + MAX_CONSULTS + ']');
                return new Reply(REFUSED, me.name() + " has " + MAX_CONSULTS + " consult sessions for the instances of "
                        + "machine " + there + ", its most, so it makes no session " + session + ": ask from an "
                        + "instance that has one, or have its user delete one", me.name(), session);
            }
            if (existing.isEmpty()) {
                // a session comes with its own panel (record 88), where the question shows; a name the reducer
                // refuses (a panel of another session's has that title) is refused here, with the reducer's reason
                final Intent.SaveSession save =
                        new Intent.SaveSession("", SessionSpec.fresh(session, workDir).withCreatedBy(PEER + from)
                                .withPlanMode(asker.plans()));
                final Optional<String> why = Reducer.refusal(s, save);
                if (why.isPresent()) {
                    return new Reply(ERROR, "session " + session + " cannot be made in " + me.name() + ": " + why.get(),
                            me.name(), session);
                }
                sink.dispatch(save);
                if (!awaitSession(session, deadline)) {
                    return Thread.currentThread().isInterrupted() ? closing(session)
                            : new Reply(ERROR, "session " + session + " could not be made in " + me.name(), me.name(), session);
                }
            } else if (asker.plans() && !existing.get().spec().planMode()) {
                // a session that may act would act for an asker whose questions only plan (record 189)
                return new Reply(REFUSED, "session " + session + " of " + me.name() + " does not run in plan mode, "
                        + "and a question from another machine is answered only in plan mode", me.name(), session);
            } else if (existing.get().spec().planMode() && leftPlanMode(existing.get())) {
                // its spec plans, so its process must too: one that reports another mode takes no question
                final String mode = existing.get().info().permissionMode();
                LOG.log(System.Logger.Level.INFO, "question refused, the session left plan mode [id=" + correlation
                        + ", session=" + session + ", mode=" + mode + ']');
                return new Reply(REFUSED, "session " + session + " of " + me.name() + " was made to only plan but "
                        + "reports mode " + mode + ", so it takes no question until it is started again in plan mode",
                        me.name(), session);
            } else if (s.homePanel(session).isEmpty()) {
                sink.dispatch(new Intent.OpenSession(session, s.focused(), PanelId.random()));
            }
            sink.dispatch(new Intent.PromptSession(session, prompt, PEER + from));
            LOG.log(System.Logger.Level.INFO, "question prompted [id=" + correlation + ", session=" + session + ']');
        }
        Block block = null;
        try {
            int at = -1;
            while (at < 0) {
                final Transcript t = transcripts.of(session);
                at = find(t, prompt, cursor);
                if (at >= 0) {
                    block = t.get(at);
                    break;
                }
                if (System.nanoTime() - deadline > 0) {
                    return timeout(session);
                }
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return closing(session);
                }
            }
            final Transcript t = transcripts.of(session);
            final boolean ended = Turns.awaitTurnEnd(state, session, t, at, Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
            if (Thread.currentThread().isInterrupted()) {
                return closing(session);
            }
            if (!ended) {
                return timeout(session);
            }
            final TranscriptText.Turn turn = TranscriptText.answer(t, at);
            if (!turn.ended()) {
                return new Reply(ERROR, "session " + session + " of " + me.name() + " stopped before it answered", me.name(), session);
            }
            final String answer = McpTools.clip(turn.text().isBlank() ? "(the turn ended without text)" : turn.text(), McpTools.MAX_RESULT_CHARS);
            return turn.failed() ? new Reply(ERROR, "the turn failed: " + answer, me.name(), session) : new Reply(OK, answer, me.name(), session);
        } finally {
            if (block != null) {
                claimed.remove(block);
            }
        }
    }

    /** Whether {@code s}, made to only plan, reported a mode other than plan; one that reported none yet has not. */
    private static boolean leftPlanMode(final SessionState s) {
        final String mode = s.info().permissionMode();
        return !mode.isEmpty() && !PLAN_MODE.equals(mode);
    }

    /**
     * Waits for the reducer to have made {@code session}, at most {@link #MAKE_WITHIN} and never past {@code deadline}.
     */
    private boolean awaitSession(final String session, final long deadline) {
        // nanoTime values compare by their difference: they may wrap
        final long now = System.nanoTime();
        final long until = deadline - now < MAKE_WITHIN.toNanos() ? deadline : now + MAKE_WITHIN.toNanos();
        while (state.get().session(session).isEmpty()) {
            if (System.nanoTime() - until > 0) {
                return false;
            }
            try {
                Thread.sleep(POLL_MILLIS / 4);
            } catch (final InterruptedException e) {
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
    private int find(final Transcript t, final String prompt, final int cursor) {
        final int size = t.size();
        final String wanted = prompt.strip();
        for (int i = size < cursor ? 0 : cursor; i < size; i++) {
            final Block b = t.get(i);
            if (b instanceof Block.UserPrompt p && p.parent().isEmpty() && p.text().strip().equals(wanted) && claimed.add(b)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The question's first line: {@code [from instance <name>[ · overseer| · peer][ · session <s>]]}, the overseer's
     * mark when the asker is {@link #MAIN} of this machine or of one granted its authority, the peer's for any other
     * asker of another machine; transcripts draw it as a label, so its shape stays.
     */
    private static String header(final Asker asker) {
        return "[from instance " + asker.name() + asker.mark()
                + (asker.session().isBlank() ? "" : " · session " + asker.session()) + ']';
    }

    private Reply timeout(final String session) {
        return new Reply(TIMEOUT, "session " + session + " of " + me.name() + " did not finish its turn within " + answerWithin.toSeconds()
                + " s; its user sees it in that session's panel", me.name(), session);
    }

    private Reply closing(final String session) {
        return new Reply(ERROR, me.name() + " closed before it answered", me.name(), session);
    }

    private void send(final String topic, final byte[] correlation, final Reply r) {
        final ObjectNode p = Json.object();
        p.put("schema", PEER_SCHEMA);
        p.put("status", r.status());
        p.put("text", r.text());
        p.put("instance", r.instance());
        p.put("session", r.session());
        gateway.publish(topic, p.toString().getBytes(StandardCharsets.UTF_8), false, null, correlation)
                .whenComplete((v, e) -> {
                    if (e != null) {
                        LOG.log(System.Logger.Level.WARNING, "a reply was not sent [id="
                                + new String(correlation, StandardCharsets.UTF_8) + ", topic=" + topic + ']', e);
                    }
                });
    }

    /**
     * Why {@code from} is refused, from {@code agents.json}'s {@code peers.deny}; an unreadable file refuses. The asker
     * is the one the question names: the broker does not say who published it, so the list refuses a cooperating
     * instance, not a local process that names another (record 123 accepts any local publisher until the peer channel
     * has credentials).
     */
    private Optional<String> refused(final String from) {
        final Path file = agentsFile;
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            final JsonNode root = Json.readTree(Mapped.text(file));
            if (later(root)) {
                return Optional.of(file + " is of a later version, so its deny list cannot be checked");
            }
            for (final JsonNode d : root.path("peers").path("deny")) {
                if (from.equals(id(d.asString("")))) {
                    return Optional.of("its user listed " + from + " in agents.json peers.deny");
                }
            }
            return Optional.empty();
        } catch (final IOException | RuntimeException e) {
            return Optional.of(file + " cannot be read, so its deny list cannot be checked: " + e.getMessage());
        }
    }

    private void reply(final PeerGateway.Message m) {
        if (m.correlation() == null) {
            return;
        }
        final Asked a = asked.get(new String(m.correlation(), StandardCharsets.UTF_8));
        if (a == null) {
            return;
        }
        // a reply comes from the machine asked, as the broker stamped it: none for this machine's (record 189)
        final String asked = InstanceName.machineOf(a.peer());
        final String from = asked.isEmpty() ? null : InstanceName.origin(asked);
        if (from == null ? m.origin() != null : !from.equals(m.origin())) {
            LOG.log(System.Logger.Level.DEBUG, "a reply from another machine than the one asked, dropped [instance="
                    + a.peer() + ", origin=" + m.origin() + ']');
            return;
        }
        try {
            final JsonNode r = Json.readTree(m.payload());
            a.reply().complete(new Reply(r.path("status").asString(ERROR), r.path("text").asString(""), r.path("instance").asString(a.peerName()),
                    r.path("session").asString("")));
        } catch (final RuntimeException e) {
            a.reply().complete(new Reply(ERROR, "an unreadable reply: " + e.getMessage(), a.peerName(), ""));
        }
    }

    /** {@code instances_list}: the other instances present on frontear, of this machine and of the paired ones. */
    McpTools.Outcome list() {
        if (!gateway.connected()) {
            return McpTools.Outcome.fail(OCTOPUS_DOWN);
        }
        final List<Peer> others = new ArrayList<>(others());
        final List<Peer> away = remotesPresent();
        others.addAll(away);
        if (others.isEmpty()) {
            return McpTools.Outcome.ok("no other instance is connected to frontear; this one is " + me.name()
                    + " (instance " + me.slug() + ')');
        }
        final StringBuilder out = new StringBuilder();
        for (final Peer p : others) {
            out.append(p.name()).append(" (instance ").append(p.slug()).append(") · comando ").append(p.version()).append(" · ")
                    .append(p.busy() ? "busy" : "idle").append(" · sessions: ")
                    .append(p.sessions().isEmpty() ? "none" : String.join(", ", p.sessions().stream()
                            .map(x -> x.name() + (x.busy() ? " (busy" + (x.activity().isEmpty() ? "" : ": " + x.activity()) + ")" : "")
                                    + (x.prompt().isEmpty() ? "" : " last asked: " + x.prompt())).toList())).append('\n');
        }
        return McpTools.Outcome.ok(out.append("this one is ").append(me.name()).append(" (instance ").append(me.slug())
                .append("); ask one with instance_ask {\"instance\": \"<instance>\", \"text\": \"…\"}")
                .append(away.isEmpty() ? "" : "; one of another machine (<slug>@<machine>) answers as a peer, planning"
                        + " only, unless its machine granted this one the overseer's authority")
                .toString());
    }

    /** The instances of other machines whose presence is current, by id. */
    private List<Peer> remotesPresent() {
        return remotes.values().stream().filter(this::present).map(Remote::peer)
                .sorted(Comparator.comparing(Peer::slug)).toList();
    }

    /**
     * The instance {@code to} names, and its machine (empty for this one), or why none: {@code <slug>@<machine>} names
     * one of that machine, or of this one; a bare slug or name names this machine's first, else the one instance of
     * another machine with that slug or name, and is refused when several are.
     */
    private Target target(final String to) {
        final String name = InstanceName.slugOf(to).strip();
        final String m = InstanceName.machineOf(to).strip().toLowerCase(Locale.ROOT);
        final String here = machine;
        if (!m.isEmpty() && (here.isEmpty() || !m.equals(here))) {
            final Remote r = remotes.get(InstanceName.onMachine(slug(name), m));
            return r == null || !present(r)
                    ? Target.none("no instance '" + to + "' is present on frontear; instances: " + names())
                    : new Target(r.peer(), r.machine(), "");
        }
        if (name.equalsIgnoreCase(me.slug()) || name.equalsIgnoreCase(me.name())) {
            return Target.none(to + " is this instance; instance_ask asks another one");
        }
        final Optional<Peer> local = others().stream()
                .filter(p -> p.slug().equalsIgnoreCase(name) || p.name().equalsIgnoreCase(name)).findFirst();
        final String none = "no instance '" + to + "' is connected to frontear; instances: " + names();
        if (local.isPresent() || !m.isEmpty()) {
            return local.map(p -> new Target(p, "", "")).orElseGet(() -> Target.none(none));
        }
        final String s = slug(name);
        final List<Remote> matches = remotes.values().stream().filter(this::present)
                .filter(r -> r.peer().slug().equals(InstanceName.onMachine(s, r.machine()))
                        || r.peer().name().equalsIgnoreCase(name))
                .sorted(Comparator.comparing((final Remote r) -> r.peer().slug())).toList();
        if (matches.size() > 1) {
            return Target.none("'" + to + "' names instances on several machines: "
                    + String.join(", ", matches.stream().map(r -> r.peer().slug()).toList())
                    + "; name one as <slug>@<machine>");
        }
        return matches.isEmpty() ? Target.none(none)
                : new Target(matches.getFirst().peer(), matches.getFirst().machine(), "");
    }

    /**
     * {@code instance_ask}: sends the question to the peer's inbox and waits up to {@code wait_seconds} for the reply.
     */
    McpTools.Outcome ask(final String caller, final JsonNode args) {
        final String to = args.path("instance").asString("").strip();
        final String text = args.path("text").asString("").strip();
        if (to.isEmpty() || text.isEmpty()) {
            return McpTools.Outcome.fail("instance and text are required");
        }
        if (!gateway.connected()) {
            return McpTools.Outcome.fail(OCTOPUS_DOWN);
        }
        final Target found = target(to);
        if (found.peer() == null) {
            return McpTools.Outcome.fail(found.why());
        }
        final Peer p = found.peer();
        final String away = found.machine();
        final String here = machine;
        if (!away.isEmpty() && here.isEmpty()) {
            return McpTools.Outcome.fail("this machine has no id, so " + p.slug() + " cannot reply to it: set one "
                    + "with the machines tool");
        }
        if (away.isEmpty()) {
            // the link to it subscribes its inbox here: put now when the presence's errand has not put it yet
            link(p.slug());
        }
        final String id = UUID.randomUUID().toString();
        final String target = args.path("session").asString("").strip();
        final Asked a = new Asked(p.slug(), p.name(), target, new CompletableFuture<>(), System.nanoTime());
        asked.put(id, a);
        record(new InstancesState.Consult(id, InstancesState.Direction.ASKED, p.slug(), p.name(), caller, McpTools.clip(text, QUESTION_CHARS),
                InstancesState.Status.WAITING, System.currentTimeMillis(), 0, ""));
        a.reply().whenComplete((r, e) -> {
            final Reply done = r != null ? r : new Reply(ERROR, e == null ? "no reply" : String.valueOf(e.getMessage()),
                    p.name(), "");
            // every end of a question asked comes here once: its reply, a failed send, an expiry, the peer gone, close
            LOG.log(System.Logger.Level.INFO, "question ended [id=" + id + ", instance=" + p.slug() + ", status="
                    + done.status() + ", session=" + done.session() + ']');
            ended(id, done);
        });
        hold(caller);
        try {
            final ObjectNode q = Json.object();
            q.put("schema", PEER_SCHEMA);
            q.put("from", away.isEmpty() ? me.slug() : InstanceName.onMachine(me.slug(), here));
            q.put("name", me.name());
            q.put("session", caller);
            if (!target.isEmpty()) {
                q.put(TO, target);
            }
            q.put("text", text);
            final String inbox = away.isEmpty() ? inboxTopic(p.slug())
                    : inboxTopic(away, InstanceName.slugOf(p.slug()));
            final String replyTo = away.isEmpty() ? replyTopic(me.slug()) : replyTopic(here, me.slug());
            // the question lives as long as this instance waits for its reply, so none is answered after it gave up
            final long expirySeconds = Math.max(1L, (expireWithin.toMillis() + 999L) / 1000L);
            try {
                gateway.publish(inbox, q.toString().getBytes(StandardCharsets.UTF_8), false, replyTo,
                        id.getBytes(StandardCharsets.UTF_8), expirySeconds)
                        .get(SEND_WITHIN.toMillis(), TimeUnit.MILLISECONDS);
                LOG.log(System.Logger.Level.INFO, "question sent [id=" + id + ", instance=" + p.slug() + ", session="
                        + caller + ", to=" + target + ']');
            } catch (final ExecutionException | TimeoutException e) {
                asked.remove(id);
                final String why = "the question did not reach " + p.name() + ": "
                        + (e.getCause() == null ? e : e.getCause()).getMessage();
                a.reply().complete(new Reply(ERROR, why, p.name(), ""));
                return McpTools.Outcome.fail(why);
            } catch (final InterruptedException e) {
                asked.remove(id);
                a.reply().complete(new Reply(ERROR, "interrupted before the question was sent", p.name(), ""));
                Thread.currentThread().interrupt();
                return McpTools.Outcome.fail("interrupted before the question was sent");
            }
            return await(id, a, waitSeconds(args));
        } finally {
            release(caller);
        }
    }

    /** {@code instance_reply}: the reply to an earlier question, waiting up to {@code wait_seconds} for it. */
    McpTools.Outcome replyOf(final String caller, final JsonNode args) {
        final String id = args.path("id").asString("").strip();
        if (id.isEmpty()) {
            return McpTools.Outcome.fail("id is required: the one instance_ask returned");
        }
        final Asked a = asked.get(id);
        if (a == null) {
            return McpTools.Outcome.fail("no question '" + id + "' waits for a reply" + (asked.isEmpty() ? "" : "; waiting: " + List.copyOf(asked.keySet()))
                    + "; a reply is read once");
        }
        hold(caller);
        try {
            return await(id, a, waitSeconds(args));
        } finally {
            release(caller);
        }
    }

    /**
     * Marks {@code caller}, a session of this instance (empty for the Instances tab, which marks nothing), as waiting
     * for a reply, from before its question goes out: a peer's question to it is refused until {@link #release}, since
     * that peer may be the one it waits for.
     */
    private void hold(final String caller) {
        if (!caller.isEmpty()) {
            waiting.merge(caller, 1, Integer::sum);
        }
    }

    private void release(final String caller) {
        if (!caller.isEmpty()) {
            waiting.computeIfPresent(caller, (_, n) -> n > 1 ? n - 1 : null);
        }
    }

    private McpTools.Outcome await(final String id, final Asked a, final int seconds) {
        Reply r;
        try {
            r = a.reply().get(seconds, TimeUnit.SECONDS);
        } catch (final TimeoutException _) {
            LOG.log(System.Logger.Level.INFO, "question still waiting [id=" + id + ", instance=" + a.peer()
                    + ", waitSeconds=" + seconds + ']');
            // another machine answers in its consult session for this instance, whatever session was named
            final boolean away = !InstanceName.machineOf(a.peer()).isEmpty();
            return McpTools.Outcome.ok("no reply from " + a.peerName() + " yet after " + seconds + " s; its session "
                    + (away ? CONSULT + InstanceName.onMachine(me.slug(), machine)
                            : !a.target().isEmpty() ? a.target() : CONSULT + me.slug())
                    + " is on it. Read the reply with "
                    + "instance_reply {\"id\": \"" + id + "\", \"wait_seconds\": " + DEFAULT_WAIT_SECONDS + "}");
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpTools.Outcome.fail("interrupted; read the reply later with instance_reply {\"id\": \"" + id + "\"}");
        } catch (final ExecutionException e) {
            asked.remove(id);
            return McpTools.Outcome.fail(a.peerName() + " could not answer: " + e.getCause().getMessage());
        }
        asked.remove(id);
        // a peer from before record 186 drops the session named and answers from the asker's consult session
        final String older = !a.target().isEmpty() && !r.session().isEmpty() && !r.session().equals(a.target())
                ? " (" + r.instance() + " runs a comando that does not take a session name, so its session "
                        + r.session() + " answered, not " + a.target() + ")"
                : "";
        return switch (r.status()) {
            case OK -> McpTools.Outcome.ok("reply from " + r.instance()
                    + (r.session().isEmpty() ? "" : " (its session " + r.session() + ")") + older + ":\n" + r.text());
            case REFUSED -> McpTools.Outcome.fail("refused: " + r.text());
            case TIMEOUT -> McpTools.Outcome.fail("timeout: " + r.text());
            default -> McpTools.Outcome.fail(a.peerName() + " could not answer: " + r.text());
        };
    }

    private static int waitSeconds(final JsonNode args) {
        return Math.max(0, Math.min(args.path("wait_seconds").asInt(DEFAULT_WAIT_SECONDS), McpTools.MAX_WAIT_SECONDS));
    }

    // ---- the window: the Instances tab (record 124)

    /** Asks from the Instances tab: as the user, from no session, on a thread of its own; a notice says how it ended. */
    @Override
    public void ask(final String instance, final String text) {
        final ObjectNode args = Json.object();
        args.put("instance", instance == null ? "" : instance);
        args.put("text", text == null ? "" : text);
        args.put("wait_seconds", McpTools.MAX_WAIT_SECONDS);
        errands.start("peer-ask", () -> {
            final McpTools.Outcome out = ask("", args);
            final String first = out.text().lines().findFirst().orElse("");
            sink.dispatch(new Intent.Notify(out.error() ? Notice.Level.WARN : Notice.Level.INFO,
                    (out.error() ? "Question to " + instance + ": " : "") + McpTools.clip(first, 200) + " (the Instances tab has it)", false));
        });
    }

    /** Refuses, or stops refusing, {@code instance}: rewrites {@code agents.json}'s {@code peers.deny}, keeping the rest. */
    @Override
    public void deny(final String instance, final boolean denied) {
        final String who = id(instance);
        try {
            setDenied(who, denied);
            sink.dispatch(new Intent.Notify(Notice.Level.INFO, denied ? "Questions from " + who + " are refused now"
                    : "Questions from " + who + " are taken again", false));
        } catch (final IOException | RuntimeException e) {
            sink.dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR, "Cannot change the refused instances [file="
                    + agentsFile + ", cause=" + e.getMessage() + ']', e));
        }
        show();
    }

    /**
     * Forgets {@code instance}, seen before and not present now: it is listed no more, here or after a restart. One of
     * another machine is listed until its presence comes again, or until a restart.
     */
    @Override
    public void forget(final String instance) {
        final String who = id(instance);
        final Remote remote = remotes.get(who);
        if (remote != null) {
            if (present(remote)) {
                sink.dispatch(new Intent.Notify(Notice.Level.INFO, who + " is present; only an instance that left can "
                        + "be forgotten", false));
            } else if (remotes.remove(who, remote)) {
                show();
            }
            return;
        }
        if (peers.containsKey(who)) {
            sink.dispatch(new Intent.Notify(Notice.Level.INFO, who + " is present; only an instance that left can be forgotten", false));
            return;
        }
        forgotten.accept(who);
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
    public void role(final String newRole) {
        final String r = newRole == null ? "" : newRole.strip();
        try {
            if (instanceFile == null) {
                throw new IOException("this instance has no data root");
            }
            final ObjectNode root = Files.isRegularFile(instanceFile)
                    && Json.readTree(Mapped.text(instanceFile)) instanceof ObjectNode o
                    ? o : Json.object();
            if (later(root)) {
                throw new IOException("it is of a later version, written by a later comando; it is kept as it is");
            }
            root.put("version", FILE_VERSION);
            root.put("role", r);
            AtomicFiles.writeString(instanceFile, Json.pretty(root) + "\n");
            role = r;
            sink.dispatch(new Intent.Notify(Notice.Level.INFO, r.isEmpty() ? "This instance has no role now" : "This instance is for: " + r, false));
        } catch (final IOException | RuntimeException e) {
            sink.dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR, "Cannot change the role [file=" + instanceFile
                    + ", cause=" + e.getMessage() + ']', e));
        }
        onTicks();
    }

    /** Whether a file's {@code version} is later than {@link #FILE_VERSION}; a file without one is version 1. */
    static boolean later(final JsonNode root) {
        return root != null && root.path("version").asInt(1) > FILE_VERSION;
    }

    /** Whether {@code a} and {@code b} name the same instance alike: what the remembered list keeps of it. */
    private static boolean sameIdentity(final Peer a, final Peer b) {
        return a.name().equals(b.name()) && a.version().equals(b.version()) && a.role().equals(b.role()) && a.kit().equals(b.kit());
    }

    /** Remembers {@code p}, last seen at {@code at} (0: present now), and writes the file. */
    private void remember(final Peer p, final long at) {
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
        final Path file = seenFile;
        if (file == null || seenRefused) {
            return;
        }
        final ObjectNode root = Json.object();
        root.put("version", FILE_VERSION);
        final ArrayNode list = root.putArray("instances");
        seen.values().forEach(x -> list.addObject().put("slug", x.peer().slug()).put("name", x.peer().name()).put("version", x.peer().version())
                .put("role", x.peer().role()).put("kit", x.peer().kit()).put("started", x.peer().started()).put("lastSeen", x.lastSeen()));
        try {
            AtomicFiles.writeString(file, Json.pretty(root) + "\n");
        } catch (final IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot write the instances seen [file=" + file + ']', e);
        }
    }

    /** {@code agents.json} with {@code who} in, or out of, {@code peers.deny}; the other keys as they were. */
    synchronized void setDenied(final String who, final boolean denied) throws IOException {
        if (agentsFile == null) {
            throw new IOException("this instance has no agents.json");
        }
        final ObjectNode root = Files.isRegularFile(agentsFile)
                && Json.readTree(Mapped.text(agentsFile)) instanceof ObjectNode o
                ? o : Json.object();
        if (later(root)) {
            throw new IOException("it is of a later version, written by a later comando; it is kept as it is");
        }
        root.put("version", FILE_VERSION);
        final ObjectNode peersNode = root.path("peers") instanceof ObjectNode o ? o : root.putObject("peers");
        final List<String> list = new ArrayList<>();
        peersNode.path("deny").forEach(d -> {
            final String s = id(d.asString(""));
            if (!s.equals(who) && !list.contains(s)) {
                list.add(s);
            }
        });
        if (denied) {
            list.add(who);
        }
        final ArrayNode deny = peersNode.putArray("deny");
        list.forEach(deny::add);
        AtomicFiles.writeString(agentsFile, Json.pretty(root) + "\n");
    }

    /** The instances {@code agents.json} refuses; none when it is missing or unreadable. */
    private List<String> denied() {
        if (agentsFile == null || !Files.isRegularFile(agentsFile)) {
            return List.of();
        }
        try {
            final List<String> out = new ArrayList<>();
            Json.readTree(Mapped.text(agentsFile)).path("peers").path("deny").forEach(d
                    -> out.add(id(d.asString(""))));
            return out;
        } catch (final IOException | RuntimeException e) {
            return List.of();
        }
    }

    private void record(final InstancesState.Consult c) {
        synchronized (consults) {
            consults.addLast(c);
            while (consults.size() > InstancesState.CONSULTS) {
                consults.removeFirst();
            }
        }
        show();
    }

    private void ended(final String id, final Reply r) {
        final InstancesState.Status status = switch (r.status()) {
            case OK -> InstancesState.Status.OK;
            case REFUSED -> InstancesState.Status.REFUSED;
            case TIMEOUT -> InstancesState.Status.TIMEOUT;
            default -> InstancesState.Status.ERROR;
        };
        synchronized (consults) {
            final List<InstancesState.Consult> all = new ArrayList<>(consults);
            consults.clear();
            for (final InstancesState.Consult c : all) {
                consults.addLast(c.id().equals(id) && c.status() == InstancesState.Status.WAITING
                        ? c.ended(status, McpTools.clip(r.text(), McpTools.MAX_RESULT_CHARS), System.currentTimeMillis()) : c);
            }
        }
        show();
    }

    /** The instances and questions as the window shows them now. */
    InstancesState instances() {
        final boolean connected = !closed && gateway.connected();
        // with the link down, no presence is current: every instance is shown as last seen
        final List<Peer> present = connected ? others() : List.of();
        final Set<String> here = new HashSet<>(present.stream().map(Peer::slug).toList());
        final List<InstancesState.Instance> others = new ArrayList<>(present.stream().map(p -> new InstancesState.Instance(p.slug(), p.name(),
                p.version(), p.role(), p.kit(), p.busy(), p.started(), p.sessions(), p.host(), true, 0L)).toList());
        synchronized (seen) {
            seen.values().stream().filter(x -> !here.contains(x.peer().slug())).sorted(Comparator.comparingLong(Seen::lastSeen).reversed())
                    .forEach(x -> others.add(new InstancesState.Instance(x.peer().slug(), x.peer().name(), x.peer().version(), x.peer().role(),
                            x.peer().kit(), false, x.peer().started(), List.of(), false, false, x.lastSeen())));
        }
        // another machine's instance, by <slug>@<machine>, is present while its last presence is current
        remotes.values().stream().sorted(Comparator.comparing((final Remote r) -> r.peer().slug())).forEach(r -> {
            final Peer p = r.peer();
            final boolean now = connected && present(r);
            others.add(new InstancesState.Instance(p.slug(), p.name(), p.version(), p.role(), "", now && p.busy(), "",
                    now ? p.sessions() : List.of(), false, now, now ? 0L : r.arrivedMillis()));
        });
        final AppState s = state.get();
        final InstancesState.Instance self = new InstancesState.Instance(me.slug(), me.name(), me.version(), role, kit,
                s.sessions().stream().anyMatch(SessionState::isBusy), started, members(s), host.getAsBoolean(),
                connected, 0L);
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
            final InstancesState now = instances();
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
        final List<Peer> others = new ArrayList<>(others());
        others.addAll(remotesPresent());
        return others.isEmpty() ? "none" : String.join(", ", others.stream().map(p -> p.name() + " (" + p.slug() + ')').toList());
    }

    private static String between(final String topic, final String prefix, final String suffix) {
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
        ExecutorStop.now("peers", ticks);
        // the instances present now were last seen now
        final long now = System.currentTimeMillis();
        synchronized (seen) {
            others().forEach(p -> seen.put(p.slug(), new Seen(p, now)));
            if (!seen.isEmpty()) {
                writeSeen();
            }
        }
        if (gateway.connected()) {
            final String cut;
            synchronized (presenceLock) {
                cut = cutTopic;
            }
            try {
                if (!cut.isEmpty()) {
                    gateway.publish(cut, new byte[0], true, null, null)
                            .get(CLEAR_WITHIN.toMillis(), TimeUnit.MILLISECONDS);
                }
                gateway.publish(presenceTopic(me.slug()), new byte[0], true, null, null)
                        .get(CLEAR_WITHIN.toMillis(), TimeUnit.MILLISECONDS);
            } catch (final ExecutionException | TimeoutException e) {
                LOG.log(System.Logger.Level.WARNING, "presence not cleared at close", e);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        errands.close();
        asked.values().forEach(a -> a.reply().complete(new Reply(ERROR, "comando is closing", a.peerName(), "")));
    }
}
