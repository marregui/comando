package dev.marregui.comando.frontear.alexander;

import java.security.PublicKey;
import java.time.Duration;
import java.time.InstantSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonException;
import dev.marregui.comando.core.json.ObjectNode;
import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.frontear.gateway.Broker;
import dev.marregui.comando.frontear.gateway.Gate;
import dev.marregui.comando.frontear.octopus.Direction;
import dev.marregui.comando.frontear.octopus.LinkSpec;
import dev.marregui.comando.frontear.octopus.Mesh;
import dev.marregui.comando.frontear.protocol.Names;
import dev.marregui.comando.frontear.protocol.Packet;

/**
 * A node's control plane: signed requests on {@code $frontear/control/<node>/<Method>} change its {@link Mesh}, and
 * its links' state is kept retained on {@code $frontear/state/<node>/links}.
 *
 * <p>Requests. A request is a JSON object, content type {@code application/json}, signed as {@link Seal} defines by a
 * key in the trusted set ({@link Signer}). Methods: {@code PutLink {id, host, port, out, in, hopLimit}},
 * {@code RemoveLink {id}}, {@code SetFilters {id, direction, filters}} and {@code GetState {}}. A request with a
 * response topic gets a reply there carrying its correlation data unchanged: {@code {"ok":true}}, for
 * {@code GetState} {@code {"ok":true,"links":[...]}}, or {@code {"ok":false,"reason":<code>,"detail":<text>}} with a
 * {@link Refusal#code()}. A response topic under {@code $frontear/} gets no reply, since the plane's own publishes
 * pass no gate. Every refusal is counted ({@link #refused}) and its PUBACK carries a failure reason code.
 *
 * <p>Delivery. A request is handled once per delivery: at most once for a QoS 0 message; at QoS 1 a duplicate the
 * client sends again carries the nonce already seen and is refused as {@link Refusal#REPLAYED}, so a request is
 * applied at most once. The PUBACK goes once the request is handled.
 *
 * <p>Threads. Requests are handled, and the state topic published, on one timed strand,
 * {@code frontear-control-<node>}. Requests wait for it, before their signature is checked, bounded in count
 * ({@link #PENDING_MAX}) and in bytes ({@link #PENDING_BYTES_MAX}), and each origin (the publishing client) to a
 * quarter of both ({@link #ORIGIN_PENDING_MAX}, {@link #ORIGIN_PENDING_BYTES_MAX}); past a bound a request is refused
 * as {@link Refusal#BUSY} without a reply, so one client's forged requests cannot refuse another client's genuine
 * one. {@link #stats} shows what waits. The state is looked at after each change and every {@link StateTopic#EVERY}.
 * The plane installs a {@link ControlGate} on the broker and leaves it there on close.
 */
public final class ControlPlane implements AutoCloseable {

    /**
     * How many requests may wait for the strand; past them a request is refused. Unmeasured: the control pod sends
     * requests one at a time, so more than this waiting is a client flooding the topic.
     */
    static final int PENDING_MAX = 1_000;
    /**
     * How many bytes of requests may wait for the strand, {@link Packet.Publish#weight()} counted (its user properties
     * and the objects around it included); past them a request is refused. Unmeasured: {@link #PENDING_MAX} requests
     * of 16 KiB, where a control request is a JSON object of a few hundred bytes (a {@code PutLink} with its filters)
     * and its four signature properties, so this many bytes waiting is a client flooding the topic.
     */
    static final long PENDING_BYTES_MAX = 16L * 1024 * 1024;
    /**
     * How many requests one origin may have waiting: a quarter of {@link #PENDING_MAX}, so a single client flooding
     * the topic leaves three quarters to the others. Unmeasured.
     */
    static final int ORIGIN_PENDING_MAX = PENDING_MAX / 4;
    /** How many bytes one origin may have waiting: a quarter of {@link #PENDING_BYTES_MAX}, as for the count. */
    static final long ORIGIN_PENDING_BYTES_MAX = PENDING_BYTES_MAX / 4;
    private static final System.Logger LOG = System.getLogger(ControlPlane.class.getName());
    /** How often a refusal is logged at warning level at most; the others go to debug. Unmeasured. */
    private static final Duration REFUSAL_LOG_EVERY = Duration.ofMinutes(1);

    private final Broker broker;
    private final Mesh mesh;
    private final String node;
    private final String id;
    private final String prefix;
    private final InstantSource clock;
    private final Seal seal;
    private final StateTopic state;
    /**
     * Handles the requests, bounded as admitted by {@link #PENDING_MAX} and {@link #PENDING_BYTES_MAX} before they
     * join its queue, and runs the state tick, one periodic task that holds one place in it.
     */
    private final ScheduledExecutorService strand;
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Guards {@link #pending}, {@link #pendingBytes} and {@link #byOrigin}; held for no call out of the class. */
    private final Object admission = new Object();
    /** The requests admitted and not yet handled; written by the broker's delivering threads and the strand. */
    private int pending;
    /** Their bytes, {@link Packet.Publish#weight()} counted. */
    private long pendingBytes;
    /** What each origin has waiting; an origin with nothing waiting has no entry. */
    private final Map<String, Share> byOrigin = new HashMap<>();
    /** The futures of the requests handed to the strand and not yet handled, failed by {@link #close}. */
    private final Set<CompletableFuture<Integer>> waiting = ConcurrentHashMap.newKeySet();
    private final AtomicLong accepted = new AtomicLong();
    /** Refusals by {@link Refusal#ordinal()}; in memory only. */
    private final AtomicLongArray refusals = new AtomicLongArray(Refusal.values().length);
    private final Gate gate = new ControlGate();
    /** When a refusal was last logged at warning level; touched by the strand only. */
    private long refusalLoggedMillis;
    private boolean refusalLogged;

    /**
     * Starts the control plane of {@code node} on {@code broker}, changing {@code mesh}, trusting the Ed25519 keys
     * {@code trusted} and reading the time from {@code clock}: it installs its gate, subscribes to its control topics
     * and publishes the state topic.
     *
     * @throws IllegalArgumentException when {@code node} is not one topic level or a key is not an Ed25519 key
     */
    public ControlPlane(final Broker broker, final Mesh mesh, final String node, final Set<PublicKey> trusted,
            final InstantSource clock) {
        this.broker = Objects.requireNonNull(broker, "broker");
        this.mesh = Objects.requireNonNull(mesh, "mesh");
        this.node = Objects.requireNonNull(node, "node");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (node.isEmpty() || node.indexOf('/') >= 0 || node.indexOf('+') >= 0 || node.indexOf('#') >= 0
                || node.indexOf((char) 0) >= 0) {
            throw new IllegalArgumentException("node is not one topic level [node=" + node + ']');
        }
        this.seal = new Seal(Objects.requireNonNull(trusted, "trusted"), clock);
        this.id = "$control/" + node;
        this.prefix = Names.CONTROL + node + '/';
        this.state = new StateTopic(mesh, node, clock, message -> publish(message, "state"));
        this.strand = Threads.timedStrand("frontear-control-" + node);
        try {
            broker.gate(gate);
            broker.subscribe(id, prefix + '+', 1, false, this::deliver);
            strand.scheduleWithFixedDelay(this::tick, 0, StateTopic.EVERY.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final RuntimeException | Error e) {
            try {
                broker.unsubscribeAll(id);
            } catch (final RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            ExecutorStop.now("control-" + node, strand);
            throw e;
        }
        LOG.log(System.Logger.Level.INFO, "control plane started [node=" + node + ", trustedKeys=" + trusted.size()
                + ']');
    }

    /** The gate the plane installed, for a host that wraps it ({@link RemoteGate}). */
    public Gate gate() {
        return gate;
    }

    /** How many requests were applied or answered. */
    public long accepted() {
        return accepted.get();
    }

    /** How many messages were refused for {@code reason}. */
    public long refused(final Refusal reason) {
        return refusals.get(reason.ordinal());
    }

    /** What waits for the strand now, beside its bounds. */
    public Stats stats() {
        synchronized (admission) {
            return new Stats(pending, PENDING_MAX, pendingBytes, PENDING_BYTES_MAX, byOrigin.size());
        }
    }

    /**
     * Stops handling requests: unsubscribes, lets the strand finish what it has for at most {@link ExecutorStop#WAIT},
     * and fails the requests left with {@link Packet#UNSPECIFIED}. A second call does nothing.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            broker.unsubscribeAll(id);
        } finally {
            ExecutorStop.drain("control-" + node, ExecutorStop.WAIT, strand);
            for (final CompletableFuture<Integer> left : waiting) {
                left.complete(Packet.UNSPECIFIED);
            }
            LOG.log(System.Logger.Level.INFO, "control plane stopped [node=" + node + ", accepted=" + accepted.get()
                    + ']');
        }
    }

    /** Hands {@code message} to the strand; completes with its PUBACK reason code once handled. */
    private CompletionStage<Integer> deliver(final Packet.Publish message, final String origin) {
        final long bytes = message.weight();
        if (!admit(origin, bytes)) {
            refusals.incrementAndGet(Refusal.BUSY.ordinal());
            LOG.log(System.Logger.Level.DEBUG, "refused control message [reason=" + Refusal.BUSY.code() + ", topic="
                    + message.topic() + ", origin=" + origin + ", bytes=" + bytes + ']');
            return CompletableFuture.completedFuture(Refusal.BUSY.reasonCode());
        }
        final CompletableFuture<Integer> done = new CompletableFuture<>();
        waiting.add(done);
        try {
            strand.execute(() -> {
                try {
                    done.complete(handle(message, origin));
                } catch (final RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "control message failed [topic=" + message.topic() + ']', e);
                    done.complete(Packet.UNSPECIFIED);
                } catch (final Error e) {
                    // the PUBACK still goes, so the client's later ones do not wait behind this one for good
                    LOG.log(System.Logger.Level.ERROR, "control message failed [topic=" + message.topic() + ']', e);
                    done.complete(Packet.UNSPECIFIED);
                    throw e;
                } finally {
                    release(origin, bytes);
                    waiting.remove(done);
                }
            });
        } catch (final RejectedExecutionException _) {
            // closing: the strand takes no more work
            release(origin, bytes);
            waiting.remove(done);
            done.complete(Packet.UNSPECIFIED);
        }
        return done;
    }

    /** Counts a request of {@code bytes} from {@code origin} as waiting, unless that would pass a bound. */
    private boolean admit(final String origin, final long bytes) {
        synchronized (admission) {
            final Share share = byOrigin.getOrDefault(origin, Share.NONE);
            if (pending >= PENDING_MAX || pendingBytes + bytes > PENDING_BYTES_MAX
                    || share.requests() >= ORIGIN_PENDING_MAX || share.bytes() + bytes > ORIGIN_PENDING_BYTES_MAX) {
                return false;
            }
            pending++;
            pendingBytes += bytes;
            byOrigin.put(origin, new Share(share.requests() + 1, share.bytes() + bytes));
            return true;
        }
    }

    /** Counts a request {@link #admit} admitted as no longer waiting. */
    private void release(final String origin, final long bytes) {
        synchronized (admission) {
            pending--;
            pendingBytes -= bytes;
            final Share share = byOrigin.get(origin);
            if (share.requests() == 1) {
                byOrigin.remove(origin);
            } else {
                byOrigin.put(origin, new Share(share.requests() - 1, share.bytes() - bytes));
            }
        }
    }

    /** Checks, applies and answers {@code message}; returns its PUBACK reason code. */
    private int handle(final Packet.Publish message, final String origin) {
        final Optional<Refusal> refusal = seal.check(message);
        if (refusal.isPresent()) {
            return refuse(message, origin, refusal.get(), "signature check failed");
        }
        final String method = message.topic().substring(prefix.length());
        final Request request;
        try {
            if (!Names.JSON.equals(message.meta().contentType())) {
                throw new Request.Invalid(Refusal.BAD_REQUEST, "content type is not " + Names.JSON);
            }
            request = Request.parse(method, Json.readTree(message.payload()));
        } catch (final Request.Invalid e) {
            return refuse(message, origin, e.refusal(), e.getMessage());
        } catch (final JsonException _) {
            return refuse(message, origin, Refusal.BAD_REQUEST, "body is not JSON");
        }
        final ObjectNode reply = Json.object().put("ok", true);
        try {
            switch (request) {
                case Request.PutLink(final LinkSpec spec) -> mesh.put(spec);
                case Request.RemoveLink(final String link) -> mesh.remove(link);
                case Request.SetFilters(final String link, final Direction direction, final Set<String> filters) ->
                        mesh.filters(link, direction, filters);
                case Request.GetState() -> reply.setAll(StateTopic.json(mesh.state()));
            }
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "mesh refused a control request [node=" + node + ", method="
                    + method + ", nonce=" + nonce(message) + ']', e);
            return refuse(message, origin, Refusal.FAILED, String.valueOf(e.getMessage()));
        }
        accepted.incrementAndGet();
        LOG.log(request instanceof Request.GetState ? System.Logger.Level.DEBUG : System.Logger.Level.INFO,
                "applied control request [node=" + node + ", method=" + method + ", key="
                        + property(message, Seal.KEY) + ", nonce=" + nonce(message) + ", origin=" + origin + ']');
        reply(message, reply);
        if (!(request instanceof Request.GetState)) {
            state.publishIfChanged();
        }
        return Packet.SUCCESS;
    }

    private int refuse(final Packet.Publish message, final String origin, final Refusal refusal, final String detail) {
        final long count = refusals.incrementAndGet(refusal.ordinal());
        final long nowMillis = clock.millis();
        final boolean warn = !refusalLogged || nowMillis - refusalLoggedMillis >= REFUSAL_LOG_EVERY.toMillis()
                || nowMillis < refusalLoggedMillis;
        if (warn) {
            refusalLogged = true;
            refusalLoggedMillis = nowMillis;
        }
        LOG.log(warn ? System.Logger.Level.WARNING : System.Logger.Level.DEBUG,
                "refused control message [reason=" + refusal.code() + ", detail=" + detail + ", topic="
                        + message.topic() + ", origin=" + origin + ", refusedForReason=" + count + ']');
        reply(message, Json.object().put("ok", false).put("reason", refusal.code()).put("detail", detail));
        return refusal.reasonCode();
    }

    /** Publishes {@code body} on the request's response topic with its correlation data, when there is one to use. */
    private void reply(final Packet.Publish request, final ObjectNode body) {
        final String to = request.responseTopic();
        if (to == null || to.startsWith(Names.RESERVED)) {
            return;
        }
        publish(new Packet.Publish(to, Json.writeBytes(body), 1, false, false, 0, null, request.correlation(),
                new Packet.Meta(1, -1, Names.JSON, List.of())), "reply");
    }

    private void publish(final Packet.Publish message, final String what) {
        broker.publish(message, id).whenComplete((code, failure) -> {
            if (failure != null) {
                LOG.log(System.Logger.Level.WARNING, "cannot publish control " + what + " [node=" + node + ", topic="
                        + message.topic() + ']', failure);
            }
        });
    }

    private void tick() {
        try {
            state.publishIfChanged();
        } catch (final RuntimeException e) {
            // a failed look at the mesh must not end the timer, which would stop the state topic for good
            LOG.log(System.Logger.Level.WARNING, "cannot publish link state [node=" + node + ']', e);
        }
    }

    private static String nonce(final Packet.Publish message) {
        return property(message, Seal.NONCE);
    }

    private static String property(final Packet.Publish message, final String name) {
        for (final Packet.UserProperty property : message.meta().userProperties()) {
            if (property.name().equals(name)) {
                return property.value();
            }
        }
        return "";
    }

    /**
     * What waits for the strand: {@code pending} requests of {@code pendingMax}, {@code pendingBytes} of
     * {@code pendingBytesMax}, from {@code origins} origins.
     */
    public record Stats(int pending, int pendingMax, long pendingBytes, long pendingBytesMax, int origins) {
    }

    /** What one origin has waiting. */
    private record Share(int requests, long bytes) {

        static final Share NONE = new Share(0, 0);
    }
}
