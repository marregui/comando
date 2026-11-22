package dev.marregui.comando.frontear.gateway;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import javax.net.ssl.SSLServerSocket;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.InstanceName;
import dev.marregui.comando.frontear.channels.Entry;
import dev.marregui.comando.frontear.channels.Match;
import dev.marregui.comando.frontear.channels.TopicTree;
import dev.marregui.comando.frontear.protocol.Names;
import dev.marregui.comando.frontear.protocol.Packet;
import dev.marregui.comando.frontear.protocol.Topics;
import dev.marregui.comando.frontear.tls.Tls;
import dev.marregui.comando.frontear.watch.ConnectionView;
import dev.marregui.comando.frontear.watch.LinkStep;
import dev.marregui.comando.frontear.watch.Watch;

/**
 * frontear's MQTT 5 gateway: the clients that connect over TCP and the subscribers inside the process share one topic
 * tree, one retained store and the sessions, all in memory. It speaks MQTT 5 with QoS 0 and 1, and states in CONNACK
 * what it declines: QoS 2, subscription identifiers and shared subscriptions; it grants no topic alias. A client's
 * QoS 1 PUBLISH is acknowledged once every internal subscriber it reached completed, with the worst of their codes.
 * <p>
 * Delivery: QoS 0 is at-most-once. QoS 1 is at-least-once while the gateway runs and within a session's bounds: past
 * them a message is dropped and counted ({@code droppedFull} in {@link #stats()}), and the sessions are in memory, so a
 * restart loses what they held.
 * <p>
 * Presence: when a client's CONNACK goes out the gateway publishes {@code {"online":true}} retained on
 * {@code $frontear/presence/<client-id>}, and when the connection ends (a disconnect, a will, a takeover) an empty
 * retained message there. Presence and wills are published in the order the session lock saw their causes, so a
 * connection's late end never clears the presence of the one that took its session over. Until a {@link Gate} is
 * installed, no client may publish or subscribe under {@code $frontear/}.
 * <p>
 * Bounds: what a client can make the gateway hold is bounded by {@link GatewayOptions}, each bound's usage in
 * {@link #stats()}: a session's bytes, a connection's unsent bytes, the retained messages and their bytes, the levels
 * of a topic, and the filters of a SUBSCRIBE and of a session. A message's bytes are what holding it costs,
 * {@link Packet.Publish#weight()}, its user properties and the objects around it included.
 * <p>
 * Threads: one strand accepts, each connection has a reader and a writer strand, one timed strand runs the delayed
 * wills and the session expiries, and one strand publishes the presence and wills the session lock ordered. Locks: the
 * session lock, then a session's own; the retained store's lock alone; no lock is held while a message is routed, so
 * no internal subscriber and no gate is called under one.
 * <p>
 * Watch: every message routed is kept in the gateway's {@link Watch} while it is on, and one a link client published
 * (its client id {@code <node>~<link>}) as that link's arrival too. A gateway started as a node refuses a link client
 * aimed at another node, and one started holding its links refuses every link client until {@link #openLinks()}.
 * <p>
 * Other machines: {@link #listenRemote} opens a second listener, TLS 1.3 with both sides authenticated, whose
 * connections speak for the machine their certificate names (ADR 0189 §2.1). The gate decides what such a connection
 * may do, through the methods that take a principal, and the gateway stamps each message it publishes with that
 * machine ({@link Names#ORIGIN}). A remote link client {@code <slug>~<node>} holds the session
 * {@code <slug>@<machine>~<node>}, so two machines' links to one node, or a remote link and a local one, never take
 * each other's session over, and its arrivals are those of this node's link {@code <slug>@<machine>}.
 */
public final class Gateway implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Gateway.class.getName());

    /** The highest QoS the gateway grants and accepts (§3.2.2.3.4). */
    static final int MAXIMUM_QOS = 1;
    /** The prefix of a shared subscription's filter (§4.8.2), which the gateway refuses with 0x9E. */
    static final String SHARED = "$share/";
    private static final String GATEWAY_ORIGIN = "$frontear";
    private static final String CLIENT_KEY = "c/";
    private static final String INTERNAL_KEY = "i/";
    private static final String ASSIGNED_PREFIX = "frontear-";
    /** What separates a link client's node from its link id in its client id: {@code <node>~<link>}. */
    private static final char LINK_MARK = '~';
    private static final byte[] ONLINE = "{\"online\":true}".getBytes(StandardCharsets.UTF_8);
    private static final Packet.Meta ONLINE_META = new Packet.Meta(-1, -1, Names.JSON, List.of());
    // what CONNACK says the gateway declines: Maximum QoS 1, no subscription identifiers, no shared subscriptions
    private static final Packet.Capabilities CAPABILITIES = new Packet.Capabilities(MAXIMUM_QOS, true, true, false,
            false);
    // the accept queue the operating system holds for the listening socket; java.net.ServerSocket's own default
    private static final int ACCEPT_BACKLOG = 50;
    // how often delayed wills and expired sessions are looked for: both are stated in whole seconds (§3.1.2.11.2,
    // §3.1.3.2.2), and a quarter of one keeps them within the second they fall due
    private static final long SWEEP_MILLIS = 250;
    // how long a connection that ended may take to write its last bytes before its socket is closed under it: a
    // client that stopped reading would otherwise hold its writer strand for ever; ExecutorStop's own bound
    private static final long FORCE_CLOSE_NANOS = ExecutorStop.WAIT.toNanos();

    final LongAdder droppedFull = new LongAdder();
    final LongAdder droppedExpired = new LongAdder();
    final LongAdder droppedOversize = new LongAdder();
    final LongAdder refusedTopics = new LongAdder();
    final LongAdder refusedSubscriptions = new LongAdder();
    final LongAdder backlogDisconnects = new LongAdder();
    private final LongAdder subscriberFailures = new LongAdder();
    private final LongAdder refusedRetained = new LongAdder();
    private final Failures handshakeFailures = new Failures("remote handshake failed");
    private final Failures unpinnedPeers = new Failures("remote peer refused, no machine is paired with its key");
    private final Failures refusedRemote = new Failures("remote connection refused, the listener holds its most");
    // numbers the connections of both listeners, so the names of their strands stay apart; taken by the accept strands
    private final AtomicInteger numbered = new AtomicInteger();
    private final GatewayOptions options;
    private final ServerSocket server;
    private final int port;
    private final String owner;
    private final TopicTree tree;
    private final Watch watch;
    // the node's name, which a link client's id must end with; null for a gateway that is no node, which takes any
    private final String node;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, InternalSubscriber> internals = new ConcurrentHashMap<>();
    // changed under retainedLock, read by retained() without it
    private final Map<String, Retained> retained = new ConcurrentHashMap<>();
    // guards the retained store's changes and retainedBytes
    private final Object retainedLock = new Object();
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    // guards the sessions map's changes, a session's connection changes and the internal subscribers' registration
    private final Object sessionsLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    // guards remote's changes, and the remote accept strand's admission of a connection against them
    private final Object remoteLock = new Object();
    // runs the accept loop, its one task for the gateway's life: the operating system holds the clients waiting to be
    // accepted, ACCEPT_BACKLOG of them
    private final ExecutorService acceptor;
    // runs the sweep, one periodic task that holds one place in its queue; the ends offLocks hands it, at most one
    // per connection, from the first send past its maxBacklogBytes; and the handshake deadline of each remote
    // connection, one per connection accepted and removed from the queue once cancelled: so its depth is at most one
    // more than twice the connections alive
    private final ScheduledExecutorService sweeper;
    // runs, in the order the session lock handed them over, the publishes a lock held back. Its queue has no count
    // bound; what joins it is one task per cause the session lock saw: an accepted CONNECT (its presence, and the
    // will and expiry of a session it took over), a CONNECT refused at maxSessions after it took a session over, the
    // end of an accepted connection, and a sweep with a will or an expiry due, at most one per SWEEP_MILLIS. So a
    // connection adds at most two tasks over its life, and accepted connections live at once are at most maxSessions
    // (each holds a session, a takeover detaches the one before): the queue grows only while connections come and go
    // faster than the strand routes, so its depth is bounded by that rate, not by a count. A task never blocks (a
    // session's queue drops past its bound, an internal subscriber must not block). Its depth and peak are read
    // under the strand's name in the queue readings, frontear-gateway-publish-<port>.
    private final ExecutorService publisher;
    // told the client id of each CONNECT the gateway accepted, on that connection's reader, outside the sessions lock;
    // added and removed by any thread
    private final List<Consumer<String>> connectListeners = new CopyOnWriteArrayList<>();
    // the bytes of the retained messages, against options.maxRetainedBytes(); guarded by retainedLock
    private long retainedBytes;
    // written by gate(); read by every connection's reader when a client publishes, subscribes or connects with a
    // will. A reader sees the gate installed before its read or the one after; either is a whole gate.
    private volatile Gate gate = new Reserved();
    // set at construction for a gateway whose host lets the links in later, cleared by openLinks(); read by every
    // connection's reader at its CONNECT. A CONNECT read after the clear is let in; one read before it is refused and
    // its link comes again after its backoff, about a second for a frontear link refused 0x88.
    private volatile boolean linksHeld;
    // the remote listener while it is open, else null; written under remoteLock by listenRemote, closeRemote and
    // close, read under it by the remote accept strand and without it by remoteStats (any thread), which sees a whole
    // listener or none
    private volatile Remote remote;

    private Gateway(final GatewayOptions options, final ServerSocket server, final Watch watch, final String node,
            final boolean holdLinks) {
        this.options = options;
        this.watch = watch;
        this.node = node;
        this.linksHeld = holdLinks;
        this.tree = new TopicTree(options.maxTopicLevels());
        this.server = server;
        this.port = server.getLocalPort();
        this.owner = "frontear-gateway-" + port;
        this.acceptor = Threads.strand("frontear-gateway-accept-" + port);
        this.sweeper = Threads.timedStrand("frontear-gateway-sweep-" + port);
        this.publisher = Threads.strand("frontear-gateway-publish-" + port);
    }

    /** Binds {@code options.bind()} and starts accepting clients. */
    public static Gateway start(final GatewayOptions options) throws IOException {
        return start(options, new Watch(1));
    }

    /** As above, keeping what it routes in {@code watch} while that is on. */
    public static Gateway start(final GatewayOptions options, final Watch watch) throws IOException {
        return open(options, Objects.requireNonNull(watch, "watch"), null, false);
    }

    /**
     * As above, as node {@code node}: a CONNECT whose client id holds {@code ~} (a link's, {@code <localId>~<linkId>})
     * and whose part after the last {@code ~} is not {@code node} is refused 0x85, so a link aimed at another node's
     * port delivers nothing to this one.
     */
    public static Gateway start(final GatewayOptions options, final Watch watch, final String node) throws IOException {
        return start(options, watch, node, false);
    }

    /**
     * As above; with {@code holdLinks}, every link client is refused 0x88 (Server unavailable) at CONNECT until
     * {@link #openLinks()}, so a link that queued for this node while it was down drains only once the host's own
     * subscribers are in place: a message it carried before would reach none of them.
     */
    public static Gateway start(final GatewayOptions options, final Watch watch, final String node,
            final boolean holdLinks) throws IOException {
        return open(options, Objects.requireNonNull(watch, "watch"), Objects.requireNonNull(node, "node"), holdLinks);
    }

    private static Gateway open(final GatewayOptions options, final Watch watch, final String node,
            final boolean holdLinks) throws IOException {
        Objects.requireNonNull(options, "options");
        final ServerSocket server = new ServerSocket();
        try {
            server.bind(options.bind(), ACCEPT_BACKLOG);
        } catch (final IOException e) {
            server.close();
            throw e;
        }
        final Gateway gateway = new Gateway(options, server, watch, node, holdLinks);
        gateway.acceptor.execute(gateway::acceptLoop);
        gateway.sweeper.scheduleAtFixedRate(gateway::sweep, SWEEP_MILLIS, SWEEP_MILLIS, TimeUnit.MILLISECONDS);
        LOG.log(System.Logger.Level.INFO, "gateway started [port=" + gateway.port + ", receiveMaximum="
                + options.receiveMaximum() + ", maximumPacketSize=" + options.maximumPacketSize() + ", maxSessions="
                + options.maxSessions() + ", node=" + node + ", linksHeld=" + holdLinks + ']');
        return gateway;
    }

    /** The port the gateway listens on. */
    public int port() {
        return port;
    }

    /**
     * Opens the remote listener {@code r} beside the loopback one and returns its port: TLS 1.3 alone, each client
     * authenticated by its certificate and speaking for the machine {@code r.machineOf()} names, at most
     * {@code r.maxConnections()} of them at once. A client past that bound, one whose handshake fails or outlasts
     * {@code r.handshake()}, and one whose key names no machine, are closed unanswered and counted in
     * {@link #remoteStats()}, each kind logged at most once a minute.
     *
     * @throws IllegalStateException when the remote listener is open, or the gateway closed
     */
    public int listenRemote(final RemoteListen r) throws IOException {
        Objects.requireNonNull(r, "listen");
        final int remotePort;
        synchronized (remoteLock) {
            if (closed.get() || remote != null) {
                throw new IllegalStateException("remote listener not opened [port=" + port + ", closed="
                        + closed.get() + ", open=" + (remote != null) + ']');
            }
            final SSLServerSocket listening = (SSLServerSocket) r.ssl().getServerSocketFactory().createServerSocket();
            try {
                listening.setEnabledProtocols(new String[] {Tls.PROTOCOL});
                listening.setNeedClientAuth(true);
                listening.bind(r.bind(), ACCEPT_BACKLOG);
            } catch (final IOException | RuntimeException e) {
                try {
                    listening.close();
                } catch (final IOException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
                throw e;
            }
            remotePort = listening.getLocalPort();
            final Remote opened = new Remote(r, listening, Threads.strand("frontear-gateway-accept-remote-" + port));
            remote = opened;
            opened.acceptor().execute(() -> acceptRemote(opened));
        }
        LOG.log(System.Logger.Level.INFO, "remote listener opened [port=" + port + ", remotePort=" + remotePort
                + ", machine=" + r.machine() + ", maxConnections=" + r.maxConnections() + ", handshake="
                + r.handshake() + ']');
        return remotePort;
    }

    /**
     * Closes the remote listener, its port free once this returns, and ends every remote connection: DISCONNECT 0x8B
     * to a client past its CONNACK, its will published by its delay and its presence cleared. Nothing when the
     * listener is not open; the loopback listener and its clients go on.
     */
    public void closeRemote() {
        final Remote r;
        synchronized (remoteLock) {
            r = remote;
            remote = null;
        }
        if (r == null) {
            return;
        }
        stopListening(r);
        final List<Connection> ended = new ArrayList<>();
        final List<Deferred> deferred = new ArrayList<>();
        // the accept strand admits a connection under remoteLock only while the listener is open, so past the detach
        // above every remote connection is in connections
        synchronized (sessionsLock) {
            for (final Connection c : connections) {
                if (c.listen() == null) {
                    continue;
                }
                ended.add(c);
                final Session s = c.session();
                if (c.endHeld(c.clientId() == null ? Connection.NO_DISCONNECT : Packet.SERVER_SHUTTING_DOWN)
                        && s != null) {
                    detach(s, c, true, deferred);
                }
            }
            later(deferred);
        }
        stopConnections(ended);
        LOG.log(System.Logger.Level.INFO, "remote listener closed [port=" + port + ", remotePort="
                + r.server().getLocalPort() + ", connections=" + ended.size() + ']');
    }

    /**
     * Ends every remote connection whose peer's key is no longer pinned to the machine it speaks for, by the remote
     * listener's {@link RemoteListen#machineOf()} as it reads now: DISCONNECT 0x87 to a client past its CONNACK, its
     * will published by its delay and its presence cleared. For the host to call once the pairings changed; a
     * connection still in its handshake checks the pairings as its handshake ends.
     */
    public void recheckRemote() {
        final List<Connection> unpinned = new ArrayList<>();
        // machineOf is the host's: asked outside the session lock
        for (final Connection c : connections) {
            if (c.listen() != null && !c.stillPinned()) {
                unpinned.add(c);
            }
        }
        if (unpinned.isEmpty()) {
            return;
        }
        final List<Deferred> deferred = new ArrayList<>();
        synchronized (sessionsLock) {
            for (final Connection c : unpinned) {
                final Session s = c.session();
                if (c.endHeld(c.clientId() == null ? Connection.NO_DISCONNECT : Packet.NOT_AUTHORIZED) && s != null) {
                    detach(s, c, true, deferred);
                }
            }
            later(deferred);
        }
        stopConnections(unpinned);
        LOG.log(System.Logger.Level.INFO, "remote connections ended, their keys no longer paired [port=" + port
                + ", connections=" + unpinned.size() + ']');
    }

    /**
     * The remote listener: its port (0 while it is closed), the remote connections held now against its bound, and
     * since the gateway started the clients refused past that bound, the handshakes that failed or outlasted theirs,
     * and the peers whose key no machine is paired with.
     */
    @TestOnly
    public RemoteStats remoteStats() {
        final Remote r = remote;
        return new RemoteStats(r == null ? 0 : r.server().getLocalPort(), remoteConnections(),
                r == null ? 0 : r.listen().maxConnections(), refusedRemote.count(), handshakeFailures.count(),
                unpinnedPeers.count());
    }

    /** Installs {@code g} for every client PUBLISH, will and SUBSCRIBE from here on; never for {@link #publish}. */
    public void gate(final Gate g) {
        gate = Objects.requireNonNull(g, "gate");
    }

    /**
     * Lets link clients connect from here on, on a gateway started holding them; nothing on one that is not, or when
     * called again. A link refused before comes again after its own backoff: a frontear link retries a 0x88 refusal
     * after about a second, another client by its own policy.
     */
    public void openLinks() {
        if (linksHeld) {
            linksHeld = false;
            LOG.log(System.Logger.Level.INFO, "links let in [port=" + port + ", node=" + node + ']');
        }
    }

    /**
     * Tells {@code listener} the client id of every CONNECT accepted from here on, once the session is attached, on
     * the connection's reader: it must return at once, and a failure it throws is logged and dropped.
     */
    public void addConnectListener(final Consumer<String> listener) {
        connectListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Stops telling {@code listener} of CONNECTs; nothing when it was not added. */
    public void removeConnectListener(final Consumer<String> listener) {
        connectListeners.remove(listener);
    }

    /**
     * Registers subscriber {@code id} on {@code filter}, or replaces it: the gateway keeps one subscriber per id, the
     * last given, for all of the id's filters. Its owner is {@code id}: with {@code noLocal}, a message whose origin is
     * {@code id} does not reach it.
     *
     * @throws IllegalArgumentException when {@code filter} is not a Topic Filter (§4.7.1) or deeper than
     *         {@code maxTopicLevels}, or {@code qos} not 0 to 2
     */
    public void subscribe(final String id, final String filter, final int qos, final boolean noLocal,
            final InternalSubscriber subscriber) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(subscriber, "subscriber");
        if (!Topics.validFilter(filter)) {
            throw new IllegalArgumentException("not a topic filter [filter=" + filter + ']');
        }
        if (Topics.levels(filter) > options.maxTopicLevels()) {
            // refused before the subscriber is kept, so a refused filter leaves nothing behind
            throw new IllegalArgumentException("filter deeper than the gateway allows [levels=" + Topics.levels(filter)
                    + ", max=" + options.maxTopicLevels() + ']');
        }
        final Entry entry = new Entry(INTERNAL_KEY + id, id, qos, noLocal, true);
        synchronized (sessionsLock) {
            internals.put(id, subscriber);
            tree.add(filter, entry);
        }
    }

    /** Removes subscriber {@code id} from {@code filter}; with its last filter gone, the subscriber goes. */
    public void unsubscribe(final String id, final String filter) {
        final String key = INTERNAL_KEY + Objects.requireNonNull(id, "id");
        synchronized (sessionsLock) {
            tree.remove(filter, key);
            if (tree.filters(key).isEmpty()) {
                internals.remove(id);
            }
        }
    }

    /** Removes subscriber {@code id} from every filter it has. */
    public void unsubscribeAll(final String id) {
        final String key = INTERNAL_KEY + Objects.requireNonNull(id, "id");
        synchronized (sessionsLock) {
            tree.removeAll(key);
            internals.remove(id);
        }
    }

    /**
     * Routes {@code message} as if {@code origin} had published it, the gate not asked: retained when it says so, sent
     * to every client and internal subscriber it matches, the retained store's bounds not applied. Completes with the
     * worst code of the internal subscribers it reached, 0x10 when it reached nothing, 0x90 for a topic deeper than
     * {@code maxTopicLevels}, else 0x00; it never completes exceptionally.
     */
    public CompletableFuture<Integer> publish(final Packet.Publish message, final String origin) {
        return accept(Objects.requireNonNull(message, "message"), Objects.requireNonNull(origin, "origin"), false);
    }

    /** What the gateway holds and what it dropped since it started. */
    public Stats stats() {
        final int retainedCount;
        final long retainedHeld;
        synchronized (retainedLock) {
            retainedCount = retained.size();
            retainedHeld = retainedBytes;
        }
        long largestSession = 0;
        int largestSubscriptions = 0;
        for (final Session s : sessions.values()) {
            largestSession = Math.max(largestSession, s.heldBytes());
            largestSubscriptions = Math.max(largestSubscriptions, tree.filters(CLIENT_KEY + s.clientId).size());
        }
        long largestBacklog = 0;
        for (final Connection c : connections) {
            largestBacklog = Math.max(largestBacklog, c.pendingBytes());
        }
        return new Stats(sessions.size(), connections.size(), retainedCount, droppedFull.sum(), droppedExpired.sum(),
                droppedOversize.sum(), subscriberFailures.sum(), retainedHeld, refusedRetained.sum(),
                refusedTopics.sum(), refusedSubscriptions.sum(), largestSubscriptions, largestSession, largestBacklog,
                backlogDisconnects.sum());
    }

    /**
     * The clients connected now, by client id: each read under its session's lock for as long as a copy of two counts
     * takes, so no publisher waits on it longer than on another session's reader.
     */
    public List<ConnectionView> connections() {
        final List<ConnectionView> views = new ArrayList<>();
        for (final Connection c : connections) {
            final Session s = c.session();
            if (s != null && !c.ended()) {
                views.add(s.view(c));
            }
        }
        views.sort(Comparator.comparing(ConnectionView::clientId));
        return views;
    }

    /**
     * Sends DISCONNECT 0x8B to every connected client, closes every connection and stops the gateway's threads; no
     * will and no presence is published. Every later call does nothing.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        final Remote r;
        synchronized (remoteLock) {
            r = remote;
            remote = null;
        }
        if (r != null) {
            stopListening(r);
        }
        try {
            server.close();
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.WARNING, "listening socket close failed [port=" + port + ']', e);
        }
        ExecutorStop.now(owner, acceptor, sweeper);
        final List<Connection> all;
        // connect() reads the closed flag under this lock: past it, every connection either has its CONNACK out or
        // will be refused, so the DISCONNECT goes exactly to the clients that were told they are connected
        synchronized (sessionsLock) {
            all = List.copyOf(connections);
            for (final Connection c : all) {
                c.end(Connection.End.SHUTDOWN, c.clientId() == null ? Connection.NO_DISCONNECT
                        : Packet.SERVER_SHUTTING_DOWN);
            }
        }
        stopConnections(all);
        // after the readers: a connection that ended before the close may still hand its publishes over, refused here
        ExecutorStop.now(owner, publisher);
        connections.clear();
        LOG.log(System.Logger.Level.INFO, "gateway closed [port=" + port + ", connections=" + all.size() + ']');
    }

    GatewayOptions options() {
        return options;
    }

    long nowMillis() {
        return options.clock().millis();
    }

    boolean mayPublish(final String principal, final String clientId, final String topic) {
        return gate.mayPublish(principal, clientId, topic);
    }

    boolean maySubscribe(final String principal, final String clientId, final String filter) {
        return gate.maySubscribe(principal, clientId, filter);
    }

    boolean mayRetain(final String principal, final String clientId, final Packet.Publish message) {
        return gate.mayRetain(principal, clientId, message);
    }

    /** Remote connection {@code conn}'s handshake failed or outlasted its bound with {@code e}: counted. */
    void handshakeFailed(final Connection conn, final IOException e) {
        handshakeFailures.add("port=" + port + ", connection=" + conn.number, e.toString(), System.nanoTime());
    }

    /** Remote connection {@code conn}'s peer has a key no machine is paired with: counted. */
    void unpinned(final Connection conn) {
        unpinnedPeers.add("port=" + port + ", connection=" + conn.number, "not paired", System.nanoTime());
    }

    /**
     * Takes {@code c} for connection {@code conn}: 0x00 once its CONNACK is queued, else the code to refuse it with.
     * An empty client id gets one assigned (§3.1.3.1); one that starts with {@code $} or cannot name a presence topic
     * is refused 0x85. A live connection on the same session is taken over with DISCONNECT 0x8E (§3.1.4); Clean Start,
     * or a session past its expiry, starts a new session. A remote connection's CONNECT the gate refuses is refused
     * 0x87, and one it lets in holds the session its machine qualifies ({@link #remoteId}).
     */
    int connect(final Connection conn, final Packet.Connect c) {
        final String principal = conn.principal();
        if (principal != null && !gate.mayConnect(principal, c.clientId())) {
            LOG.log(System.Logger.Level.DEBUG, "remote client refused by the gate [client=" + c.clientId()
                    + ", machine=" + principal + ", connection=" + conn.number + ']');
            return Packet.NOT_AUTHORIZED;
        }
        final String assigned = c.clientId().isEmpty() ? ASSIGNED_PREFIX + UUID.randomUUID() : null;
        final String given = assigned == null ? c.clientId() : assigned;
        final String id = principal == null ? given : remoteId(given, principal, conn.listen().machine());
        if (id.startsWith("$") || !Topics.validName(Names.PRESENCE + id)) {
            return Packet.CLIENT_ID_NOT_VALID;
        }
        if (misdelivered(given, conn.listen())) {
            LOG.log(System.Logger.Level.DEBUG, "link client refused, it is aimed at another node [client=" + id
                    + ", node=" + node + ']');
            return Packet.CLIENT_ID_NOT_VALID;
        }
        if (linksHeld && id.indexOf(LINK_MARK) >= 0) {
            LOG.log(System.Logger.Level.DEBUG, "link client refused, the node's host has not let links in yet [client="
                    + id + ", node=" + node + ']');
            return Packet.SERVER_UNAVAILABLE;
        }
        if (c.will() != null) {
            if (c.will().qos() > MAXIMUM_QOS) {
                return Packet.QOS_NOT_SUPPORTED;
            }
            if (Topics.levels(c.will().topic()) > options.maxTopicLevels()) {
                refusedTopics.increment();
                return Packet.TOPIC_NAME_INVALID;
            }
            if (!mayPublish(principal, id, c.will().topic())) {
                return Packet.NOT_AUTHORIZED;
            }
            // an empty retained will clears its topic only once published, so it is not asked for as a clear
            if (c.will().retain() && c.will().payload().length > 0 && !mayRetain(principal, id, c.will())) {
                return Packet.NOT_AUTHORIZED;
            }
        }
        final List<Deferred> deferred = new ArrayList<>();
        final boolean present;
        synchronized (sessionsLock) {
            if (closed.get() || conn.ended()) {
                return Packet.UNSPECIFIED;
            }
            Session s = sessions.get(id);
            if (s != null) {
                final Connection previous = s.connection();
                if (previous != null) {
                    // a connection that ended on its own a moment ago has not detached yet: detach it here by its
                    // own cause, as its later call to ended() finds the session gone from it and does nothing;
                    // never SHUTDOWN, which close() sets under this lock after the closed flag checked above
                    previous.takenOver();
                    detach(s, previous, previous.endCause() != Connection.End.NORMAL, deferred);
                }
                if (sessions.get(id) != s) {
                    // the session ended with the connection taken over, its expiry 0
                    s = null;
                } else if (c.cleanStart() || s.expired(nowMillis())) {
                    endSession(s, deferred);
                    s = null;
                }
            }
            present = s != null;
            if (s == null) {
                if (sessions.size() >= options.maxSessions()) {
                    later(deferred);
                    return Packet.QUOTA_EXCEEDED;
                }
                s = new Session(id, this);
                sessions.put(id, s);
            }
            conn.accepted(s, id, c.keepAliveSeconds(), nowMillis());
            s.attach(conn, c, Connection.encode(new Packet.ConnAck(present, Packet.SUCCESS, options.receiveMaximum(),
                    options.maximumPacketSize(), 0, Packet.NO_SERVER_KEEP_ALIVE, assigned, null, CAPABILITIES)));
            deferred.add(new Deferred(new Packet.Publish(Names.PRESENCE + id, ONLINE, 1, true, false, 0, null, null,
                    ONLINE_META), GATEWAY_ORIGIN, false));
            later(deferred);
        }
        LOG.log(System.Logger.Level.DEBUG, "client connected [client=" + id + ", connection=" + conn.number
                + ", sessionPresent=" + present + ", cleanStart=" + c.cleanStart() + ']');
        for (final Consumer<String> listener : connectListeners) {
            try {
                listener.accept(id);
            } catch (final RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "connect listener failed [client=" + id + ']', e);
            }
        }
        return Packet.SUCCESS;
    }

    /**
     * Connection {@code conn} of session {@code s} ended: its will goes out by its delay when {@code publishWill},
     * a session with expiry 0 ends, and the client's presence is cleared. Nothing, when another connection took the
     * session over first.
     */
    void ended(final Connection conn, final Session s, final boolean publishWill) {
        final List<Deferred> deferred = new ArrayList<>();
        synchronized (sessionsLock) {
            if (detach(s, conn, publishWill, deferred)) {
                later(deferred);
            }
        }
    }

    /** Runs {@code task} on the gateway's timed strand, off every lock the caller holds; dropped once it closed. */
    void offLocks(final Runnable task) {
        try {
            sweeper.execute(task);
        } catch (final RejectedExecutionException _) {
            // the gateway closed, and its close ends every connection
        }
    }

    /** Connection {@code conn}'s socket closed: the gateway forgets it. */
    void closed(final Connection conn) {
        connections.remove(conn);
    }

    /**
     * A client's PUBLISH from {@code origin}: as {@link #publish}, with the retained store's bounds applied, a
     * retained message past them refused 0x97 and routed nowhere.
     */
    CompletableFuture<Integer> accept(final Packet.Publish p, final String origin) {
        return accept(p, origin, true);
    }

    /**
     * Subscribes client {@code clientId} to {@code sub}'s filter at {@code granted}; true when it was already
     * subscribed there, which Retain Handling 1 asks (§3.8.3.1).
     */
    boolean subscribeClient(final String clientId, final Packet.Subscription sub, final int granted) {
        final String key = CLIENT_KEY + clientId;
        final boolean existed = tree.filters(key).contains(sub.filter());
        tree.add(sub.filter(), new Entry(key, clientId, granted, sub.noLocal(), sub.retainAsPublished()));
        return existed;
    }

    /**
     * Whether client {@code clientId} holds {@code maxSubscriptions} filters and {@code filter} is not one of them, so
     * subscribing it would pass the bound; re-subscribing a filter it holds replaces it and passes nothing.
     */
    boolean subscriptionsFull(final String clientId, final String filter) {
        final Set<String> filters = tree.filters(CLIENT_KEY + clientId);
        return filters.size() >= options.maxSubscriptions() && !filters.contains(filter);
    }

    /** Unsubscribes client {@code clientId} from {@code filter}; false when it had no subscription there. */
    boolean unsubscribeClient(final String clientId, final String filter) {
        return tree.remove(filter, CLIENT_KEY + clientId);
    }

    /**
     * The retained messages {@code filter} matches, at most at {@code qos}, with their retain flag set (§3.3.1.3);
     * one past its message expiry is removed and counted instead.
     */
    List<Session.Queued> retained(final String filter, final int qos) {
        final long now = nowMillis();
        final List<Session.Queued> found = new ArrayList<>();
        for (final Map.Entry<String, Retained> e : retained.entrySet()) {
            final Retained r = e.getValue();
            if (!Topics.matches(filter, e.getKey())) {
                continue;
            }
            final long expirySeconds = r.message().meta().messageExpirySeconds();
            if (expirySeconds >= 0 && now - r.receivedMillis() >= expirySeconds * 1_000) {
                synchronized (retainedLock) {
                    if (retained.remove(e.getKey(), r)) {
                        retainedBytes -= r.message().weight();
                        droppedExpired.increment();
                    }
                }
                continue;
            }
            found.add(new Session.Queued(copy(r.message(), Math.min(qos, r.message().qos()), true),
                    r.receivedMillis()));
        }
        return found;
    }

    /** {@code p} as it is forwarded: at {@code qos}, with {@code retain}, no packet id, not a duplicate. */
    private static Packet.Publish copy(final Packet.Publish p, final int qos, final boolean retain) {
        return new Packet.Publish(p.topic(), p.payload(), qos, retain, false, 0, p.responseTopic(), p.correlation(),
                p.meta());
    }

    /** The accept strand's one task: every client that connects gets a connection, until the gateway closes. */
    private void acceptLoop() {
        while (!closed.get()) {
            final Socket socket;
            try {
                socket = server.accept();
            } catch (final IOException e) {
                if (!closed.get()) {
                    LOG.log(System.Logger.Level.ERROR, "accept failed, gateway takes no more clients [port=" + port
                            + ']', e);
                }
                return;
            }
            final int number = numbered.incrementAndGet();
            try {
                socket.setTcpNoDelay(true);
                final Connection c = new Connection(this, socket, number);
                connections.add(c);
                c.start();
            } catch (final IOException e) {
                LOG.log(System.Logger.Level.DEBUG, "connection not opened [port=" + port + ", connection=" + number
                        + ", cause=" + e + ']');
                try {
                    socket.close();
                } catch (final IOException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
            }
        }
    }

    /**
     * The remote accept strand's one task: every client that connects to {@code r} gets a connection while {@code r} is
     * open and holds fewer than its bound; one past the bound is closed unanswered and counted. The handshake runs on
     * the connection's reader, so a slow client holds no other.
     */
    private void acceptRemote(final Remote r) {
        while (true) {
            final Socket socket;
            try {
                socket = r.server().accept();
            } catch (final IOException e) {
                if (remote == r) {
                    LOG.log(System.Logger.Level.ERROR, "remote accept failed, the remote listener takes no more "
                            + "clients [port=" + port + ", remotePort=" + r.server().getLocalPort() + ']', e);
                }
                return;
            }
            final int number = numbered.incrementAndGet();
            Connection c = null;
            boolean open = true;
            boolean full = false;
            try {
                socket.setTcpNoDelay(true);
                synchronized (remoteLock) {
                    open = remote == r;
                    full = open && remoteConnections() >= r.listen().maxConnections();
                    if (open && !full) {
                        c = new Connection(this, socket, number, r.listen());
                        connections.add(c);
                        // close() clears remote under this lock before it stops the sweeper, so the sweeper of an
                        // open listener takes the deadline
                        c.start(sweeper.schedule(c::handshakeOverdue, r.listen().handshake().toNanos(),
                                TimeUnit.NANOSECONDS));
                    }
                }
            } catch (final IOException e) {
                LOG.log(System.Logger.Level.DEBUG, "remote connection not opened [port=" + port + ", connection="
                        + number + ", cause=" + e + ']');
            }
            if (c == null) {
                if (full) {
                    refusedRemote.add("port=" + port + ", connection=" + number + ", maxConnections="
                            + r.listen().maxConnections(), "full", System.nanoTime());
                }
                closeQuietly(socket, number);
            }
            if (!open) {
                return;
            }
        }
    }

    /** The remote connections the gateway holds, their sockets not yet closed, handshaking ones included. */
    private int remoteConnections() {
        int count = 0;
        for (final Connection c : connections) {
            if (c.listen() != null) {
                count++;
            }
        }
        return count;
    }

    /** Closes remote listener {@code r}'s socket, which frees its port, stops its accept strand, tells its counts. */
    private void stopListening(final Remote r) {
        try {
            r.server().close();
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.WARNING, "remote listening socket close failed [port=" + port + ']', e);
        }
        ExecutorStop.now(owner, r.acceptor());
        handshakeFailures.flush();
        unpinnedPeers.flush();
        refusedRemote.flush();
    }

    /**
     * Lets the connections {@code ended}, already ended, write their last bytes within ExecutorStop's bound, then
     * closes their sockets and stops their strands.
     */
    private void stopConnections(final List<Connection> ended) {
        final Map<String, ExecutorService> writers = new LinkedHashMap<>();
        final Map<String, ExecutorService> readers = new LinkedHashMap<>();
        for (final Connection c : ended) {
            writers.put("writer-" + c.number, c.writer());
            readers.put("reader-" + c.number, c.reader());
        }
        ExecutorStop.drain(owner, ExecutorStop.WAIT, writers);
        for (final Connection c : ended) {
            c.forceClose();
        }
        ExecutorStop.now(owner, readers);
    }

    private static void closeQuietly(final Socket socket, final int number) {
        try {
            socket.close();
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "socket close failed [connection=" + number + ", cause=" + e + ']');
        }
    }

    /**
     * The sweep strand's task: publishes the delayed wills now due, ends the sessions past their expiry (with the
     * delayed will each held), and closes the sockets of connections that ended and could not write their last bytes.
     */
    private void sweep() {
        try {
            final long now = nowMillis();
            final List<Deferred> deferred = new ArrayList<>();
            synchronized (sessionsLock) {
                for (final Session s : List.copyOf(sessions.values())) {
                    final Packet.Publish w = s.dueWill(now);
                    if (w != null) {
                        deferred.add(new Deferred(w, s.clientId, true));
                    }
                    if (s.expired(now)) {
                        endSession(s, deferred);
                    }
                }
                later(deferred);
            }
            final long nowNanos = System.nanoTime();
            for (final Connection c : connections) {
                if (c.ended() && nowNanos - c.endedAtNanos() > FORCE_CLOSE_NANOS) {
                    c.forceClose();
                    connections.remove(c);
                }
            }
        } catch (final RuntimeException e) {
            // a failed sweep must not cancel the ones after it, as a periodic task that throws is never run again
            LOG.log(System.Logger.Level.ERROR, "sweep failed [port=" + port + ']', e);
        }
    }

    /**
     * Detaches {@code conn} from {@code s}, under the session lock: the will goes to {@code deferred} by its delay, the
     * session ends when its expiry is 0, and the client's presence is cleared. False when {@code conn} no longer held
     * the session.
     */
    private boolean detach(final Session s, final Connection conn, final boolean publishWill,
            final List<Deferred> deferred) {
        final List<Packet.Publish> wills = new ArrayList<>(1);
        if (!s.detach(conn, publishWill, wills)) {
            return false;
        }
        for (final Packet.Publish w : wills) {
            deferred.add(new Deferred(w, s.clientId, true));
        }
        if (s.endsWithConnection()) {
            endSession(s, deferred);
        }
        deferred.add(new Deferred(new Packet.Publish(Names.PRESENCE + s.clientId, new byte[0], 1, true, false, 0, null,
                null), GATEWAY_ORIGIN, false));
        LOG.log(System.Logger.Level.DEBUG, "client disconnected [client=" + s.clientId + ", connection="
                + conn.number + ", will=" + !wills.isEmpty() + ']');
        return true;
    }

    /** Ends session {@code s}, under the session lock: its subscriptions go, and the delayed will it held is due. */
    private void endSession(final Session s, final List<Deferred> deferred) {
        sessions.remove(s.clientId, s);
        tree.removeAll(CLIENT_KEY + s.clientId);
        final Packet.Publish w = s.end();
        if (w != null) {
            deferred.add(new Deferred(w, s.clientId, true));
        }
        LOG.log(System.Logger.Level.DEBUG, "session ended [client=" + s.clientId + ", will=" + (w != null) + ']');
    }

    /**
     * Hands what the session lock held back to the publisher strand, under that lock: the strand publishes it after
     * everything handed over before, outside every lock. Dropped once the gateway closed, as its close publishes no
     * will and no presence.
     */
    private void later(final List<Deferred> deferred) {
        if (deferred.isEmpty()) {
            return;
        }
        final List<Deferred> held = List.copyOf(deferred);
        try {
            publisher.execute(() -> {
                for (final Deferred d : held) {
                    accept(d.message(), d.origin(), d.bounded());
                }
            });
        } catch (final RejectedExecutionException _) {
            LOG.log(System.Logger.Level.DEBUG, "publishes dropped at close [port=" + port + ", count=" + held.size()
                    + ']');
        }
    }

    /**
     * Retains {@code p} when it says so (an empty payload clears the topic, §3.3.1.3) and routes it from
     * {@code origin}; with {@code bounded}, a retained message past the store's bounds is refused 0x97 and routed
     * nowhere. A topic deeper than {@code maxTopicLevels} is refused 0x90 before both. Completes as {@link #publish}
     * does.
     */
    private CompletableFuture<Integer> accept(final Packet.Publish p, final String origin, final boolean bounded) {
        if (Topics.levels(p.topic()) > options.maxTopicLevels()) {
            refusedTopics.increment();
            return CompletableFuture.completedFuture(Packet.TOPIC_NAME_INVALID);
        }
        final long receivedMillis = nowMillis();
        if (p.retain() && !retain(p, receivedMillis, bounded)) {
            refusedRetained.increment();
            return CompletableFuture.completedFuture(Packet.QUOTA_EXCEEDED);
        }
        return route(p, origin, receivedMillis);
    }

    /** Retains {@code p}, or clears its topic when its payload is empty; false when {@code bounded} refuses it. */
    private boolean retain(final Packet.Publish p, final long receivedMillis, final boolean bounded) {
        synchronized (retainedLock) {
            if (p.payload().length == 0) {
                final Retained old = retained.remove(p.topic());
                if (old != null) {
                    retainedBytes -= old.message().weight();
                }
                return true;
            }
            final Retained old = retained.get(p.topic());
            final long held = retainedBytes - (old == null ? 0 : old.message().weight()) + p.weight();
            final int count = retained.size() + (old == null ? 1 : 0);
            if (bounded && (held > options.maxRetainedBytes() || count > options.maxRetained())) {
                return false;
            }
            retained.put(p.topic(), new Retained(copy(p, p.qos(), true), receivedMillis));
            retainedBytes = held;
            return true;
        }
    }

    /**
     * Sends {@code p} to every subscriber it matches, No Local applied against {@code origin}: each at the lower of
     * its QoS and the subscription's, the retain flag kept only for Retain As Published (§3.3.1.3).
     */
    private CompletableFuture<Integer> route(final Packet.Publish p, final String origin, final long receivedMillis) {
        final Collection<Match> matches = tree.match(p.topic(), origin);
        if (watch.on()) {
            observe(p, origin, matches.size());
        }
        if (matches.isEmpty()) {
            return CompletableFuture.completedFuture(Packet.NO_MATCHING_SUBSCRIBERS);
        }
        final List<CompletableFuture<Integer>> codes = new ArrayList<>();
        // a match whose subscriber left after the tree's snapshot reaches nothing
        boolean reached = false;
        for (final Match m : matches) {
            final int qos = Math.min(Math.min(p.qos(), m.qos()), MAXIMUM_QOS);
            final Packet.Publish forwarded = copy(p, qos, p.retain() && m.retainAsPublished());
            final String key = m.subscriberId();
            if (key.startsWith(INTERNAL_KEY)) {
                final InternalSubscriber sub = internals.get(key.substring(INTERNAL_KEY.length()));
                if (sub != null) {
                    codes.add(deliver(sub, forwarded, origin));
                }
            } else {
                final Session s = sessions.get(key.substring(CLIENT_KEY.length()));
                if (s != null) {
                    s.offer(new Session.Queued(forwarded, receivedMillis));
                    reached = true;
                }
            }
        }
        if (codes.isEmpty()) {
            return CompletableFuture.completedFuture(reached ? Packet.SUCCESS : Packet.NO_MATCHING_SUBSCRIBERS);
        }
        return CompletableFuture.allOf(codes.toArray(CompletableFuture[]::new)).thenApply(_ -> {
            int worst = Packet.SUCCESS;
            for (final CompletableFuture<Integer> code : codes) {
                worst = Math.max(worst, code.join());
            }
            return worst;
        });
    }

    /**
     * Whether {@code clientId} is a link client's whose link id is not this node's name, nor, for a client of remote
     * listener {@code listen} (null for the loopback one), {@code <node>@<machine>} with this machine's id.
     */
    private boolean misdelivered(final String clientId, final RemoteListen listen) {
        final int mark = clientId.lastIndexOf(LINK_MARK);
        if (node == null || mark < 0) {
            return false;
        }
        final String target = clientId.substring(mark + 1);
        return !target.equals(node)
                && (listen == null || !target.equals(InstanceName.onMachine(node, listen.machine())));
    }

    /**
     * The session id of machine {@code principal}'s client {@code given}, {@code <slug>~<node>}: the slug qualified
     * {@code <slug>@<principal>}, the node without {@code @<machine>} when it names this machine. An id without
     * {@code ~} is qualified whole.
     */
    private static String remoteId(final String given, final String principal, final String machine) {
        final int mark = given.lastIndexOf(LINK_MARK);
        if (mark < 0) {
            return InstanceName.onMachine(given, principal);
        }
        final String target = given.substring(mark + 1);
        final String aimed = machine.equals(InstanceName.machineOf(target)) ? InstanceName.slugOf(target) : target;
        return InstanceName.onMachine(given.substring(0, mark), principal) + LINK_MARK + aimed;
    }

    /**
     * Keeps {@code p}, routed to {@code matches} subscriptions, in the watch, and as an arrival of the link whose
     * client published it: on this node that link is named by the node it came from, the part before the {@code ~}.
     */
    private void observe(final Packet.Publish p, final String origin, final int matches) {
        watch.published(origin, p, matches);
        final int mark = origin.lastIndexOf(LINK_MARK);
        if (mark > 0) {
            watch.step(origin.substring(0, mark), LinkStep.Kind.ARRIVED, -1, p.topic(), p.payload().length, "");
        }
    }

    /** {@code sub}'s code for {@code message}: 0x80, counted, when it throws, fails or completes without one. */
    private CompletableFuture<Integer> deliver(final InternalSubscriber sub, final Packet.Publish message,
            final String origin) {
        final CompletableFuture<Integer> code = new CompletableFuture<>();
        try {
            final CompletionStage<Integer> stage = sub.deliver(message, origin);
            stage.whenComplete((c, failure) -> {
                if (failure != null || c == null) {
                    subscriberFailures.increment();
                    code.complete(Packet.UNSPECIFIED);
                } else {
                    code.complete(c);
                }
            });
        } catch (final RuntimeException e) {
            subscriberFailures.increment();
            LOG.log(System.Logger.Level.WARNING, "internal subscriber failed [topic=" + message.topic() + ']', e);
            code.complete(Packet.UNSPECIFIED);
        }
        return code;
    }

    /**
     * What the gateway holds and what it dropped: QoS 1 messages past a session's bounds ({@code droppedFull}),
     * messages past their expiry ({@code droppedExpired}), messages larger than a client's maximum packet size
     * ({@code droppedOversize}), and internal subscribers that failed a delivery. Beside each byte bound of
     * {@link GatewayOptions}, its usage: the retained messages' bytes and the retained publishes refused past the
     * store's bounds, the publishes, wills and subscriptions refused for their depth, the subscriptions refused past
     * the filters of one SUBSCRIBE or of one session, the most subscriptions a session holds now (against
     * {@code maxSubscriptions}), the most bytes a session holds and a connection has unsent now, and the connections
     * ended for their unsent bytes. A message's bytes are its {@link Packet.Publish#weight()}; a connection's, its
     * packets' encoded bytes and a fixed overhead each.
     */
    public record Stats(int sessions, int connections, int retained, long droppedFull, long droppedExpired,
            long droppedOversize, long subscriberFailures, long retainedBytes, long refusedRetained,
            long refusedTopics, long refusedSubscriptions, int largestSessionSubscriptions, long largestSessionBytes,
            long largestBacklogBytes, long backlogDisconnects) {
    }

    /**
     * The remote listener: its port, 0 while it is closed; the remote connections held now and their bound, 0 while
     * closed; and since the gateway started, the clients refused past the bound, the handshakes that failed or
     * outlasted theirs, and the peers whose key no machine is paired with.
     */
    @TestOnly
    public record RemoteStats(int port, int connections, int maxConnections, long refused, long handshakeFailures,
            long unpinned) {
    }

    /** A retained message and when the gateway received it, for its expiry. */
    private record Retained(Packet.Publish message, long receivedMillis) {
    }

    /** A publish a lock held back, the origin it goes out with, and whether the retained store's bounds apply. */
    private record Deferred(Packet.Publish message, String origin, boolean bounded) {
    }

    /** An open remote listener: what it was opened with, its socket and the strand that accepts on it. */
    private record Remote(RemoteListen listen, SSLServerSocket server, ExecutorService acceptor) {
    }

    /**
     * A remote failure that can recur per connection, so it is counted every time and logged the first time, then at
     * most once a {@link #TELL_EVERY_NANOS} with how many came since and the last one's cause.
     */
    static final class Failures {

        /** At most one line a minute per kind: a peer retrying every second would otherwise write 3,600 an hour. */
        static final long TELL_EVERY_NANOS = TimeUnit.MINUTES.toNanos(1);

        private final String what;
        private final LongAdder count = new LongAdder();
        // the failures since the last line, when that line was written, whether one was, the last cause; all guarded
        // by this
        private long untold;
        private long toldNanos;
        private boolean told;
        private String last;
        private String lastContext;

        Failures(final String what) {
            this.what = what;
        }

        /**
         * Counts a failure in {@code context} with {@code cause} at {@code nowNanos} and logs it when the class says:
         * how many failures that line told, 0 when it wrote none.
         */
        long add(final String context, final String cause, final long nowNanos) {
            count.increment();
            final long times;
            synchronized (this) {
                untold++;
                last = cause;
                lastContext = context;
                if (told && nowNanos - toldNanos < TELL_EVERY_NANOS) {
                    return 0L;
                }
                times = untold;
                untold = 0L;
                told = true;
                toldNanos = nowNanos;
            }
            tell(context, times, cause);
            return times;
        }

        /** Logs the failures not yet told, as their listener closes: how many, 0 when there were none. */
        long flush() {
            final long times;
            final String context;
            final String cause;
            synchronized (this) {
                times = untold;
                context = lastContext;
                cause = last;
                untold = 0L;
            }
            if (times > 0) {
                tell(context, times, cause);
            }
            return times;
        }

        long count() {
            return count.sum();
        }

        private void tell(final String context, final long times, final String cause) {
            LOG.log(System.Logger.Level.WARNING, what + "; repeats are counted [" + context + ", times=" + times
                    + ", last=" + cause + ']');
        }
    }

    /** The gate until one is installed: everything outside {@code $frontear/}. */
    private static final class Reserved implements Gate {

        @Override
        public boolean mayPublish(final String clientId, final String topic) {
            return !topic.startsWith(Names.RESERVED);
        }

        @Override
        public boolean maySubscribe(final String clientId, final String filter) {
            return !filter.startsWith(Names.RESERVED);
        }
    }
}
