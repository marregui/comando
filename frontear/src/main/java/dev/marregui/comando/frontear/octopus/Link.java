package dev.marregui.comando.frontear.octopus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Queues;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.frontear.gateway.Broker;
import dev.marregui.comando.frontear.gateway.InternalSubscriber;
import dev.marregui.comando.frontear.protocol.MqttClient;
import dev.marregui.comando.frontear.protocol.MqttException;
import dev.marregui.comando.frontear.protocol.Packet;
import dev.marregui.comando.frontear.protocol.PublishRecord;
import dev.marregui.comando.frontear.store.LinkLog;
import dev.marregui.comando.frontear.store.LogOptions;
import dev.marregui.comando.frontear.store.QuotaExceededException;
import dev.marregui.comando.frontear.store.Record;
import dev.marregui.comando.frontear.watch.LinkStep;
import dev.marregui.comando.frontear.watch.LogView;
import dev.marregui.comando.frontear.watch.Watch;

/**
 * One link to a peer broker. Its strand, {@code link-<id>}, runs every step of the link and is the only thread that
 * touches its log; it is also its client's callbacks executor, so the client's events reach it in order.
 *
 * <p>Out, at least once. Every message the local broker routes to the link's OUT filters, all under the one internal
 * subscriber {@code $link/<id>}, waits for the strand in a queue of {@link LinkOptions#queueMax} and joins the log at
 * its tail with its hop count incremented; the broker hears 0x00 once the record is durable, 0x97 when the log or the
 * queue is full (a QoS 0 message over the queue is dropped), 0x8B when the link closed first. The sender reads from
 * the log's cursor, at most the peer's Receive Maximum and {@link LinkOptions#windowMax} at a time, publishes each
 * record at QoS 1 and moves the cursor past the prefix the peer has answered. A peer that answers 0x97 or 0x89 holds
 * that message back: the link sends nothing new and sends the refused message again, alone, after a backoff that
 * grows with each refusal in a row, until the peer takes it; the window then opens again. A message the peer accepted
 * is not sent again on the same connection, so the messages of the window already sent after a refused one may reach
 * the peer before it ({@code reordered}). A lost connection sends again, in order, everything past the cursor, so the
 * peer may see a message twice.
 *
 * <p>In, at least once. The link subscribes to its IN filters on the peer with No Local, so what it sends does not
 * come back, and publishes what arrives on the local broker as {@code $link/<id>}, whose own OUT subscription, No
 * Local under that owner, leaves it; the peer's PUBACK waits for that local publish and carries its reason code.
 *
 * <p>Watch. While the node's {@link Watch} is on, the link keeps each message's steps there (appended, sent, arrived,
 * dropped and why) and each connection change; a connection the peer refused for the client id, a link aimed at a
 * node other than the one its id names, says so.
 */
final class Link implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Link.class.getName());
    /** The owner of a link's internal subscription and the origin of what it publishes locally, before its id. */
    static final String OWNER = "$link/";
    /** Server busy: a broker may answer a publish with it; the contract retries on it as on 0x97. */
    static final int SERVER_BUSY = 0x89;
    /**
     * The Receive Maximum the link asks of the peer: each message in holds a local publish open until it completes.
     * Unmeasured: a local publish completes within a force of the slowest link log it reaches.
     */
    static final int IN_RECEIVE_MAXIMUM = 100;
    /** The keep alive towards the peer: comando's peer client's. */
    private static final int KEEP_ALIVE_SECONDS = 60;

    private final String id;
    private final String owner;
    private final String localId;
    private final Broker broker;
    private final LinkLog log;
    private final InstantSource clock;
    private final LinkOptions options;
    private final Watch watch;
    /**
     * Runs every step of the link. Its queue has no bound of its own; what joins it is bounded where it comes from:
     * the broker's messages wait in {@link #deliveries} behind one task, the log's completions behind one pump, the
     * peer's answers by the window, its QoS 1 messages by {@link #IN_RECEIVE_MAXIMUM} and its QoS 0 messages by
     * {@link LinkOptions#queueMax}, the client's {@link MqttClient.Options#maxQueuedQos0}.
     */
    private final ScheduledExecutorService strand;
    /** The broker's messages waiting for the strand to append them, oldest first; any thread. */
    private final ArrayBlockingQueue<Delivery> deliveries;
    /** How many of {@link #deliveries} wait, as the queue readings report it. */
    private final Queues.Gauge waiting;
    /** Set by the broker's thread that posts the strand's append of the deliveries, cleared by that task. */
    private final AtomicBoolean appendPosted = new AtomicBoolean();
    /** Set by the log's completion that posts a pump, cleared by that pump. */
    private final AtomicBoolean pumpPosted = new AtomicBoolean();
    /** The QoS 0 messages dropped over the queue's bound; written by the broker's threads, read by {@link #state}. */
    private final AtomicLong shed = new AtomicLong();
    /** Guards {@link #shedIn} and the replacing of {@link #peer}, so {@link #state} counts a client's drops once. */
    private final Object peerSwap = new Object();
    private final InternalSubscriber subscriber = (m, _) -> deliver(m);
    /** The records sent and not yet acknowledged in order, oldest first; strand only. */
    private final ArrayDeque<Flight> flights = new ArrayDeque<>();
    /** The OUT filters registered with the broker; the mesh's caller only, under the mesh's lock. */
    private Set<String> out = Set.of();
    /** Written by the mesh's caller under the mesh's lock; read by the strand. */
    private volatile LinkSpec spec;
    /**
     * The connection to the peer; written by the mesh's caller under the mesh's lock, replaced under
     * {@link #peerSwap}; read by the strand and by {@link #state}.
     */
    private volatile Peer peer;
    /** The peer's QoS 0 messages dropped by the clients this link has closed; guarded by {@link #peerSwap}. */
    private long shedIn;
    /**
     * Set once by {@link #close}, on the mesh's caller, before it fails the deliveries and posts the strand's last
     * task; read by the strand, the broker's threads and the client's reader.
     */
    private volatile boolean closed;
    /** A failure of the log's close; written by the strand's last task, read by {@link #close} once it ended. */
    private volatile RuntimeException logFailure;
    /** The IN filters asked of the peer on the current connection; strand only. */
    private Set<String> subscribedIn = new HashSet<>();
    /** Whether the peer accepted the current connection; strand only. */
    private boolean up;
    /** How many records may be in flight on the current connection; strand only. */
    private int window;
    /** The offset of the next record to send; strand only. */
    private long next;
    /** Counts the rewinds: an answer to a send made before the last one is stale; strand only. */
    private int epoch;
    /**
     * Whether the last read reached the log's end: a link that is live sends each record as it becomes durable, one
     * behind refills only once half its window is free, so catching up reads in batches; strand only.
     */
    private boolean caughtUp = true;
    /** Retryable refusals in a row; strand only. */
    private int refusals;
    /** The flights the peer refused for now: while any is held nothing new is sent; strand only. */
    private int held;
    /** The send again after a retryable refusal's backoff, null when none waits; strand only. */
    private ScheduledFuture<?> resume;
    /** The link's state as {@link #state} reads it: written by the strand only, read from any thread. */
    private volatile boolean connected;
    private volatile long cursor;
    private volatile long tail;
    private volatile long sent;
    private volatile long dropped;
    private volatile long reordered;

    private Link(final String id, final String localId, final Broker broker, final LinkLog log,
            final LinkOptions options, final InstantSource clock, final Watch watch) {
        this.id = id;
        this.watch = watch;
        this.owner = OWNER + id;
        this.localId = localId;
        this.broker = broker;
        this.log = log;
        this.options = options;
        this.clock = clock;
        this.deliveries = new ArrayBlockingQueue<>(options.queueMax());
        this.cursor = log.cursor();
        this.tail = log.tail();
        this.strand = Threads.timedStrand("link-" + id);
        this.waiting = Queues.sampled(waitingName(), deliveries::size);
    }

    /**
     * Stops: the broker forgets the link's subscriptions, the client disconnects, every message still waiting for the
     * strand is answered 0x8B, the strand finishes what it holds and its last task saves the cursor and closes the
     * log. The strand is stopped by the closed flag and its shutdown, never interrupted, so a write in a segment's
     * channel is not cut; when it does not end within {@link ExecutorStop#WAIT}, its last task still closes the log
     * once that write returns. Idempotent; every step runs even when one before it failed.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        // written before the deliveries are failed and the last task posted: a broker's thread that queues a message
        // after the failing reads it and fails it itself, and every strand task after the last one reads it
        closed = true;
        RuntimeException failure = null;
        try {
            broker.unsubscribeAll(owner);
        } catch (final RuntimeException e) {
            failure = e;
        }
        final Peer p = peer;
        peer = null;
        if (p != null) {
            try {
                p.client.close();
            } catch (final RuntimeException e) {
                failure = kept(failure, e);
            }
            replace(p, null);
        }
        failDeliveries();
        if (!post(this::closeLog)) {
            closeLog();
        }
        strand.shutdown();
        if (ended()) {
            final RuntimeException e = logFailure;
            if (e != null) {
                failure = kept(failure, e);
            }
        } else {
            LOG.log(System.Logger.Level.WARNING, "cannot end link strand within its wait, its last task closes the log"
                    + " [link=" + id + ", waitMillis=" + ExecutorStop.WAIT.toMillis() + ']');
        }
        Queues.drop(waitingName(), waiting);
        LOG.log(System.Logger.Level.INFO, "link stopped [link=" + id + ", cursor=" + cursor + ", tail=" + tail
                + ", sent=" + sent + ", dropped=" + (dropped + shed.get() + droppedIn()) + ", reordered=" + reordered
                + ']');
        if (failure != null) {
            throw failure;
        }
    }

    /** The client id this link connects to its peer with: {@code <localId>~<link id>}. */
    String clientId() {
        return localId + '~' + id;
    }

    /** A link {@code id} on its log in {@code dir}, created when missing; nothing runs before {@link #start}. */
    static Link open(final String id, final String localId, final Broker broker, final Path dir,
            final LogOptions logOptions, final LinkOptions options, final InstantSource clock, final Watch watch)
            throws IOException {
        Files.createDirectories(dir);
        final LinkLog log = LinkLog.open(dir, logOptions, watch);
        try {
            return new Link(id, localId, broker, log, options, clock, watch);
        } catch (final RuntimeException e) {
            log.close();
            throw e;
        }
    }

    /** Starts the link as {@code s} says: registers its OUT filters and starts connecting to the peer. */
    void start(final LinkSpec s) {
        spec = s;
        out(s.out());
        connect(s);
    }

    /** Takes {@code s} in place of the link's spec: a new peer address reconnects, the filters are replaced. */
    void update(final LinkSpec s) {
        final LinkSpec old = spec;
        spec = s;
        out(s.out());
        if (!old.host().equals(s.host()) || old.port() != s.port()) {
            connect(s);
        } else if (!old.in().equals(s.in())) {
            post(this::syncIn);
        }
    }

    /** Replaces the OUT filters with {@code filters}, every new one registered before an old one goes. */
    void out(final Set<String> filters) {
        final LinkSpec s = spec;
        spec = new LinkSpec(s.id(), s.host(), s.port(), filters, s.in(), s.hopLimit());
        for (final String f : filters) {
            if (!out.contains(f)) {
                broker.subscribe(owner, f, 1, true, subscriber);
            }
        }
        for (final String f : out) {
            if (!filters.contains(f)) {
                broker.unsubscribe(owner, f);
            }
        }
        out = filters;
    }

    /** Replaces the IN filters with {@code filters}; on the peer, every new one is asked for before an old one goes. */
    void in(final Set<String> filters) {
        final LinkSpec s = spec;
        spec = new LinkSpec(s.id(), s.host(), s.port(), s.out(), filters, s.hopLimit());
        post(this::syncIn);
    }

    String id() {
        return id;
    }

    /** The spec the link runs by now, its filters as last replaced. */
    LinkSpec spec() {
        return spec;
    }

    /** The link's state; from any thread. */
    LinkState state() {
        // the cursor is read before the tail, and neither moves back, so the tail read is at least the cursor
        final long c = cursor;
        final long t = tail;
        return new LinkState(id, connected, Math.max(0, t - c), c, t, sent, dropped + shed.get() + droppedIn(),
                reordered, waiting.depth(), options.queueMax());
    }

    /** The link's log now; from any thread. */
    LogView logView() {
        return new LogView(id, log.cursor(), log.tail(), log.bytes(), log.segments());
    }

    private static Throwable cause(final Throwable e) {
        return e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
    }

    private static RuntimeException kept(final RuntimeException first, final RuntimeException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    /** The peer's QoS 0 messages dropped while the strand was behind, by every client of the link so far. */
    private long droppedIn() {
        synchronized (peerSwap) {
            final Peer p = peer;
            return shedIn + (p == null ? 0 : p.client.droppedQos0());
        }
    }

    /**
     * Puts {@code next} in place of {@code old}, whose client is closed, and adds that client's drops to
     * {@link #shedIn} in the same step: its reader stopped, so the count no longer moves. Mesh's caller.
     */
    private void replace(final Peer old, final Peer next) {
        synchronized (peerSwap) {
            if (old != null) {
                shedIn += old.client.droppedQos0();
            }
            peer = next;
        }
    }

    /** A new client for {@code s}'s peer in place of the current one, which is closed first. Mesh's caller. */
    private void connect(final LinkSpec s) {
        final Peer old = peer;
        if (old != null) {
            old.client.close();
        }
        final Peer p = new Peer(s);
        replace(old, p);
        post(() -> down(null));
        p.client.connect();
        LOG.log(System.Logger.Level.INFO, "link connecting [link=" + id + ", peer=" + s.host() + ':' + s.port()
                + ", clientId=" + clientId() + ']');
    }

    /**
     * A message the broker routed to the OUT filters: queued for the strand to append, refused at once when the queue
     * is full or the link closed. From the broker's threads.
     */
    private CompletionStage<Integer> deliver(final Packet.Publish m) {
        if (closed) {
            return CompletableFuture.completedFuture(Packet.SERVER_SHUTTING_DOWN);
        }
        final Delivery d = new Delivery(m, new CompletableFuture<>());
        if (!deliveries.offer(d)) {
            if (m.qos() == 0) {
                shed.incrementAndGet();
            }
            LOG.log(System.Logger.Level.DEBUG, () -> "message refused: the link's queue is full [link=" + id
                    + ", topic=" + m.topic() + ", qos=" + m.qos() + ", queueMax=" + options.queueMax() + ']');
            watch.step(id, LinkStep.Kind.DROPPED, -1, m.topic(), m.payload().length, "the link's queue is full");
            return CompletableFuture.completedFuture(Packet.QUOTA_EXCEEDED);
        }
        waiting.sample();
        if (closed) {
            // the close may have failed the queue before this message joined it
            failDeliveries();
        } else if (appendPosted.compareAndSet(false, true) && !post(this::appendDeliveries)) {
            failDeliveries();
        }
        return d.done();
    }

    /** Appends the waiting messages in the order they came, a queue's worth a task, so other steps run between. */
    private void appendDeliveries() {
        // cleared before the queue is read: a message queued after the last poll posts this task again
        appendPosted.set(false);
        for (int i = 0; i < options.queueMax(); i++) {
            final Delivery d = deliveries.poll();
            if (d == null) {
                return;
            }
            append(d.message(), d.done());
        }
        if (!deliveries.isEmpty() && appendPosted.compareAndSet(false, true) && !post(this::appendDeliveries)) {
            failDeliveries();
        }
    }

    /** Answers 0x8B to every message still waiting for the strand: the link closed before it was appended. */
    private void failDeliveries() {
        for (Delivery d = deliveries.poll(); d != null; d = deliveries.poll()) {
            d.done().complete(Packet.SERVER_SHUTTING_DOWN);
        }
    }

    /** Appends {@code m}, hop count incremented, unless it made its hops; completes {@code done} with the outcome. */
    private void append(final Packet.Publish m, final CompletableFuture<Integer> done) {
        if (closed) {
            done.complete(Packet.SERVER_SHUTTING_DOWN);
            return;
        }
        final int hops = Forwarding.hops(m);
        if (hops < 0 || hops >= spec.hopLimit()) {
            dropped++;
            LOG.log(System.Logger.Level.DEBUG, () -> "message not forwarded past the hop limit [link=" + id
                    + ", topic=" + m.topic() + ", hops=" + hops + ", hopLimit=" + spec.hopLimit() + ']');
            watch.step(id, LinkStep.Kind.DROPPED, -1, m.topic(), m.payload().length, "past the hop limit");
            done.complete(Packet.SUCCESS);
            return;
        }
        final CompletableFuture<Long> appended;
        try {
            appended = log.append(PublishRecord.encode(Forwarding.queued(m, hops + 1, clock.millis())));
        } catch (final MqttException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "message not queued [link=" + id + ", topic=" + m.topic() + ']', e);
            done.complete(Packet.UNSPECIFIED);
            return;
        }
        tail = log.tail();
        // handle, not whenComplete: on a failed future whenComplete wraps the failure in a new CompletionException,
        // one per message refused while the log is full
        appended.handle((offset, e) -> {
            if (e == null) {
                LOG.log(System.Logger.Level.DEBUG, () -> "message queued [link=" + id + ", offset=" + offset
                        + ", topic=" + m.topic() + ']');
                watch.step(id, LinkStep.Kind.APPENDED, offset, m.topic(), m.payload().length, "");
                done.complete(Packet.SUCCESS);
                schedulePump();
            } else if (cause(e) instanceof QuotaExceededException) {
                LOG.log(System.Logger.Level.DEBUG, () -> "message refused: the link's log is full [link=" + id
                        + ", topic=" + m.topic() + ']');
                watch.step(id, LinkStep.Kind.DROPPED, -1, m.topic(), m.payload().length, "the link's log is full");
                done.complete(Packet.QUOTA_EXCEEDED);
            } else if (closed) {
                LOG.log(System.Logger.Level.DEBUG, () -> "message not queued, the link closed first [link=" + id
                        + ", topic=" + m.topic() + ']');
                done.complete(Packet.SERVER_SHUTTING_DOWN);
            } else {
                LOG.log(System.Logger.Level.WARNING, "message not queued [link=" + id + ", topic=" + m.topic()
                        + ']', cause(e));
                done.complete(Packet.UNSPECIFIED);
            }
            return null;
        });
    }

    /** One pump on the strand for any number of appends made durable before it starts. From the log's completions. */
    private void schedulePump() {
        if (pumpPosted.compareAndSet(false, true)) {
            post(() -> {
                // cleared before the pump reads the log: an append durable after the read posts it again
                pumpPosted.set(false);
                pump();
            });
        }
    }

    /**
     * Sends records from {@link #next} on while at least half the window is free and the peer holds back no refusal,
     * each read filling the free slots: a link that fell behind reads its backlog half a window at a time, not one
     * record per answer. The answers to the flights that fill the rest pump again. Strand.
     */
    private void pump() {
        final Peer p = peer;
        if (closed || !up || held > 0 || p == null) {
            return;
        }
        while (flights.size() < window && (caughtUp || 2 * (window - flights.size()) >= window)) {
            final int free = window - flights.size();
            final List<Record> records;
            try {
                records = log.read(next, free);
            } catch (final IOException e) {
                // the next append or answer pumps again
                LOG.log(System.Logger.Level.WARNING, "link log not read [link=" + id + ", offset=" + next + ']', e);
                return;
            }
            caughtUp = records.size() < free;
            if (records.isEmpty()) {
                return;
            }
            for (final Record r : records) {
                send(p, r);
            }
            drain();
        }
    }

    /** Sends record {@code r} to the peer, or marks it dropped when it cannot be read or has expired. Strand. */
    private void send(final Peer p, final Record r) {
        next = r.offset() + 1;
        final Flight f = new Flight(r.offset());
        flights.add(f);
        if (!publish(p, f, r)) {
            f.outcome = Outcome.DROPPED;
        }
    }

    /** Publishes record {@code r} of flight {@code f}; false when it cannot be read or has expired. Strand. */
    private boolean publish(final Peer p, final Flight f, final Record r) {
        final Packet.Publish m;
        try {
            m = Forwarding.outgoing(PublishRecord.decode(r.bytes()), clock.millis());
        } catch (final MqttException e) {
            LOG.log(System.Logger.Level.WARNING, "link record unreadable, dropped [link=" + id + ", offset="
                    + r.offset() + ']', e);
            f.detail = "the record is unreadable";
            return false;
        }
        if (m == null) {
            LOG.log(System.Logger.Level.DEBUG, () -> "message expired in the link's log, dropped [link=" + id
                    + ", offset=" + r.offset() + ']');
            f.detail = "expired in the link's log";
            return false;
        }
        f.topic = m.topic();
        f.bytes = m.payload().length;
        final int sentIn = epoch;
        p.client.publishAcknowledged(m).whenComplete((code, e) -> post(() -> answered(sentIn, f, code, e)));
        return true;
    }

    /** The peer answered record {@code f} sent in epoch {@code sentIn}, or no answer can come. Strand. */
    private void answered(final int sentIn, final Flight f, final Integer code, final Throwable error) {
        if (sentIn != epoch || closed) {
            return;
        }
        if (error != null) {
            final int reason = cause(error) instanceof final MqttException e ? e.reasonCode() : Packet.UNSPECIFIED;
            if (reason == Packet.QUOTA_EXCEEDED) {
                refused(f, reason);
            } else if (reason == Packet.PACKET_TOO_LARGE) {
                LOG.log(System.Logger.Level.WARNING, "message larger than the peer accepts, dropped [link=" + id
                        + ", offset=" + f.offset + ']');
                f.detail = "larger than the peer accepts";
                settle(f, Outcome.DROPPED);
            }
            // otherwise the connection ended; its end rewinds to the cursor
        } else if (code < Packet.FAILURE) {
            settle(f, Outcome.SENT);
        } else if (code == Packet.QUOTA_EXCEEDED || code == SERVER_BUSY) {
            refused(f, code);
        } else {
            LOG.log(System.Logger.Level.INFO, "peer refused a message, dropped [link=" + id + ", offset=" + f.offset
                    + ", reasonCode=" + Packet.hex(code) + ']');
            f.detail = "the peer refused it [reasonCode=" + Packet.hex(code) + ']';
            settle(f, Outcome.DROPPED);
        }
    }

    /**
     * The peer refused flight {@code f} for now: it is held, nothing new is sent, and it goes again alone after a
     * backoff. A later flight the peer already accepted has overtaken it. Strand.
     */
    private void refused(final Flight f, final int code) {
        f.resent = false;
        if (!f.refused) {
            f.refused = true;
            held++;
        }
        if (!f.overtaken && acceptedAfter(f)) {
            overtaken(f);
        }
        if (resume != null) {
            // a resend is already due: the refusals of one window are one round, not one each
            return;
        }
        // rounds in a row, so the backoff grows with how long the peer stays busy, not with the window's size
        refusals++;
        final Duration delay = Forwarding.backoff(refusals, ThreadLocalRandom.current().nextDouble());
        LOG.log(System.Logger.Level.DEBUG, () -> "peer busy, sending the message again alone later [link=" + id
                + ", offset=" + f.offset + ", reasonCode=" + Packet.hex(code) + ", delayMillis=" + delay.toMillis()
                + ']');
        try {
            resume = strand.schedule(() -> {
                resume = null;
                resendHeld();
            }, delay.toNanos(), TimeUnit.NANOSECONDS);
        } catch (final RejectedExecutionException _) {
            // closing: the cursor holds the place for the next start
        }
    }

    /** Flight {@code f} has its final outcome; a held one is held no more. Strand. */
    private void settle(final Flight f, final Outcome outcome) {
        f.outcome = outcome;
        if (f.refused) {
            f.refused = false;
            f.resent = false;
            held--;
            if (outcome == Outcome.SENT) {
                refusals = 0;
            }
        }
        if (outcome == Outcome.SENT && held > 0) {
            for (final Flight g : flights) {
                if (g == f) {
                    break;
                }
                if (g.refused && !g.overtaken) {
                    overtaken(g);
                }
            }
        }
        drain();
        if (held == 0) {
            pump();
        } else if (resume == null) {
            resendHeld();
        }
    }

    /** Whether the peer accepted a flight sent after {@code f}. Strand. */
    private boolean acceptedAfter(final Flight f) {
        boolean after = false;
        for (final Flight g : flights) {
            if (after && g.outcome == Outcome.SENT) {
                return true;
            }
            after |= g == f;
        }
        return false;
    }

    private void overtaken(final Flight f) {
        f.overtaken = true;
        reordered++;
        LOG.log(System.Logger.Level.DEBUG, () -> "peer took a later message before a refused one [link=" + id
                + ", offset=" + f.offset + ']');
    }

    /**
     * Sends the oldest held flight again, alone, unless one sent again still waits for its answer: the window is one
     * until no flight is held. A held record that cannot be read or has expired is dropped and the next one goes.
     * Strand.
     */
    private void resendHeld() {
        final Peer p = peer;
        if (closed || !up || p == null) {
            return;
        }
        while (held > 0) {
            Flight f = null;
            for (final Flight g : flights) {
                if (g.refused) {
                    if (g.resent) {
                        return;
                    }
                    if (f == null) {
                        f = g;
                    }
                }
            }
            if (f == null) {
                return;
            }
            final List<Record> records;
            try {
                records = log.read(f.offset, 1);
            } catch (final IOException e) {
                LOG.log(System.Logger.Level.WARNING, "link log not read, sending again from the cursor [link=" + id
                        + ", offset=" + f.offset + ']', e);
                rewindToCursor();
                pump();
                return;
            }
            if (records.isEmpty() || records.getFirst().offset() != f.offset) {
                LOG.log(System.Logger.Level.WARNING, "held record not in the link log, sending again from the cursor"
                        + " [link=" + id + ", offset=" + f.offset + ']');
                rewindToCursor();
                pump();
                return;
            }
            f.resent = true;
            if (publish(p, f, records.getFirst())) {
                return;
            }
            f.refused = false;
            f.resent = false;
            f.outcome = Outcome.DROPPED;
            held--;
            drain();
        }
        pump();
    }

    /** Takes the answered prefix of the flights, in order, and acknowledges it in the log. Strand. */
    private void drain() {
        long done = -1;
        while (!flights.isEmpty()) {
            final Flight f = flights.peek();
            switch (f.outcome) {
                case PENDING -> {
                    ack(done);
                    return;
                }
                case SENT -> {
                    sent++;
                    LOG.log(System.Logger.Level.DEBUG, () -> "message sent [link=" + id + ", offset=" + f.offset
                            + ']');
                    watch.step(id, LinkStep.Kind.SENT, f.offset, f.topic, f.bytes, "");
                }
                case DROPPED -> {
                    dropped++;
                    watch.step(id, LinkStep.Kind.DROPPED, f.offset, f.topic, f.bytes, f.detail);
                }
            }
            flights.poll();
            done = f.offset;
        }
        ack(done);
    }

    /** Every record at or below {@code offset} is done, none when it is negative. Strand. */
    private void ack(final long offset) {
        if (offset < 0) {
            return;
        }
        try {
            log.ack(offset);
        } catch (final IOException | RuntimeException e) {
            // the cursor stays: what it covers goes again, at least once
            LOG.log(System.Logger.Level.WARNING, "link cursor not moved [link=" + id + ", offset=" + offset + ']', e);
        }
        cursor = log.cursor();
    }

    /** The peer accepted connection {@code p}: asks for the IN filters and sends from the cursor. Strand. */
    private void up(final Peer p) {
        up = true;
        connected = true;
        window = Math.max(1, Math.min(p.client.receiveMaximum(), options.windowMax()));
        subscribedIn = new HashSet<>();
        LOG.log(System.Logger.Level.INFO, "link connected [link=" + id + ", window=" + window + ", cursor="
                + log.cursor() + ", tail=" + log.tail() + ']');
        watch.step(id, LinkStep.Kind.CONNECTED, -1, null, 0, "");
        syncIn();
        rewindToCursor();
        pump();
    }

    /**
     * The connection ended, or is being replaced: nothing is in flight any more. A connection that was up, or one that
     * failed with {@code cause}, is a disconnect the watch sees. Strand.
     */
    private void down(final Throwable cause) {
        if (up) {
            LOG.log(System.Logger.Level.INFO, "link disconnected [link=" + id + ", inFlight=" + flights.size()
                    + ", cause=" + cause + ']');
        }
        if ((up || cause != null) && watch.on()) {
            watch.step(id, LinkStep.Kind.DISCONNECTED, -1, null, 0, disconnected(cause));
        }
        up = false;
        connected = false;
        rewindToCursor();
    }

    /**
     * Why the connection ended, for the watch: empty for a replacement, a refused client id named as a link aimed at
     * the wrong node (a node refuses a link client whose id does not end with its name), else the cause's message.
     */
    private String disconnected(final Throwable cause) {
        if (cause == null) {
            return "";
        }
        final Throwable c = cause(cause);
        if (c instanceof final MqttException e && e.reasonCode() == Packet.CLIENT_ID_NOT_VALID) {
            return "the peer refused the link's client id: it is not node " + id + " [clientId=" + clientId()
                    + ", reasonCode=" + Packet.hex(e.reasonCode()) + ']';
        }
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }

    private void rewindToCursor() {
        rewind(log.cursor());
    }

    /** Forgets what is in flight and held, and sends from {@code offset} next. Strand. */
    private void rewind(final long offset) {
        epoch++;
        flights.clear();
        held = 0;
        next = offset;
        if (resume != null) {
            resume.cancel(false);
            resume = null;
        }
    }

    /**
     * The strand's last task: a pending send again would hold the strand's shutdown for its whole delay; then the
     * log saves the cursor and closes. Strand, or the closer when the strand took no more.
     */
    private void closeLog() {
        rewindToCursor();
        try {
            log.close();
        } catch (final RuntimeException e) {
            logFailure = e;
        }
    }

    /** Whether the strand ended within {@link ExecutorStop#WAIT}; it is never interrupted. */
    private boolean ended() {
        try {
            return strand.awaitTermination(ExecutorStop.WAIT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (final InterruptedException _) {
            // the closing thread was told to stop: the strand is reported as running, not waited for
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String waitingName() {
        return "link-" + id + "-deliveries";
    }

    /** Asks the peer for the IN filters the link lacks, then drops those it no longer has. Strand. */
    private void syncIn() {
        final Peer p = peer;
        if (!up || p == null) {
            return;
        }
        final Set<String> wanted = spec.in();
        for (final String f : wanted) {
            if (subscribedIn.add(f)) {
                p.client.subscribe(new Packet.Subscription(f, 1, true, true, 0)).whenComplete((_, e) -> {
                    if (e != null) {
                        LOG.log(System.Logger.Level.WARNING, "peer refused a link subscription [link=" + id
                                + ", filter=" + f + ", cause=" + cause(e) + ']');
                        post(() -> subscribedIn.remove(f));
                    }
                });
            }
        }
        for (final String f : Set.copyOf(subscribedIn)) {
            if (!wanted.contains(f)) {
                subscribedIn.remove(f);
                p.client.unsubscribe(f).whenComplete((_, e) -> {
                    if (e != null) {
                        LOG.log(System.Logger.Level.DEBUG, "link unsubscribe failed [link=" + id + ", filter=" + f
                                + ", cause=" + cause(e) + ']');
                    }
                });
            }
        }
    }

    /** A message in from the peer: published locally as the link, unless it has made its hops. Strand. */
    private CompletionStage<Integer> arrive(final Packet.Publish m) {
        final int hops = Forwarding.hops(m);
        if (hops < 0 || hops >= spec.hopLimit()) {
            dropped++;
            LOG.log(System.Logger.Level.DEBUG, () -> "message in not published past the hop limit [link=" + id
                    + ", topic=" + m.topic() + ", hops=" + hops + ']');
            watch.step(id, LinkStep.Kind.DROPPED, -1, m.topic(), m.payload().length, "past the hop limit");
            return CompletableFuture.completedFuture(Packet.SUCCESS);
        }
        watch.step(id, LinkStep.Kind.ARRIVED, -1, m.topic(), m.payload().length, "");
        final CompletableFuture<Integer> published;
        try {
            published = broker.publish(Forwarding.arrived(m, hops + 1), owner);
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "message in not published [link=" + id + ", topic=" + m.topic()
                    + ']', e);
            return CompletableFuture.completedFuture(Packet.UNSPECIFIED);
        }
        return published.handle((code, e) -> {
            if (e != null) {
                LOG.log(System.Logger.Level.WARNING, "message in not published [link=" + id + ", topic="
                        + m.topic() + ']', cause(e));
                return Packet.UNSPECIFIED;
            }
            return code;
        });
    }

    /** Runs {@code task} on the strand; false once it has stopped. */
    private boolean post(final Runnable task) {
        try {
            strand.execute(task);
            return true;
        } catch (final RejectedExecutionException _) {
            return false;
        }
    }

    /** What became of a record sent. */
    private enum Outcome {
        PENDING,
        SENT,
        DROPPED
    }

    /** A message the broker handed the link and the future its outcome completes. */
    private record Delivery(Packet.Publish message, CompletableFuture<Integer> done) {
    }

    /** A record sent and its answer; strand only. */
    private static final class Flight {

        private final long offset;
        private Outcome outcome = Outcome.PENDING;
        /** Refused for now (0x97, 0x89) and still pending: it goes again alone. */
        private boolean refused;
        /** Refused, sent again, and that send's answer not in. */
        private boolean resent;
        /** Counted in {@code reordered}: the peer accepted a later flight while this one was refused. */
        private boolean overtaken;
        /** The record's topic and payload length, for the watch; null and 0 until it was read. */
        private String topic;
        private int bytes;
        /** Why it was dropped, for the watch; empty unless it was. */
        private String detail = "";

        Flight(final long offset) {
            this.offset = offset;
        }
    }

    /** One client to the peer; its events count only while it is the link's current one. */
    private final class Peer implements MqttClient.Handler {

        private final MqttClient client;

        Peer(final LinkSpec s) {
            // one client id per link, so two links to one peer do not take over each other's session; the peer's QoS 0
            // messages wait for the strand within the link's queue bound, as the broker's do
            this.client = new MqttClient(new MqttClient.Options(s.host(), s.port(), clientId(), KEEP_ALIVE_SECONDS,
                    Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, null, IN_RECEIVE_MAXIMUM, options.queueMax()), this, strand);
        }

        @Override
        public void connected() {
            if (peer == this) {
                up(this);
            }
        }

        @Override
        public void message(final Packet.Publish message) {
            received(message);
        }

        @Override
        public CompletionStage<Integer> received(final Packet.Publish message) {
            return peer == this ? arrive(message) : CompletableFuture.completedFuture(Packet.UNSPECIFIED);
        }

        @Override
        public Duration disconnected(final int attempts, final Throwable cause) {
            post(() -> {
                if (peer == this) {
                    down(cause);
                }
            });
            return closed || peer != this ? null : Forwarding.backoff(attempts,
                    ThreadLocalRandom.current().nextDouble());
        }
    }
}
