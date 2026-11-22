package dev.marregui.comando.frontear.protocol;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntFunction;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Queues;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.frontear.tls.Tls;

/**
 * An MQTT 5 client over a blocking socket, for the one gateway its {@link Options} name: QoS 0 and 1 both ways,
 * subscriptions, keep alive, a last will, and reconnection when its {@link Handler} asks for it.
 *
 * <p>Threads. One reader, a timed strand named {@code mqtt-<client id>}, opens each connection, reads it, keeps it
 * alive and runs the reconnects the handler schedules; it never writes once the connection is up, so a gateway that
 * stops reading cannot stall it. Every later write runs on the writer strand, {@code mqtt-writer-<client id>}, in the
 * order the client queued it, and takes the connection's write lock. A write that lasts longer than
 * {@link #WRITE_WITHIN} is cut by the reader, which closes the socket. The handler's {@code connected} and
 * {@code received} calls, and the completion of every future but {@link #connect}'s and {@link #disconnect}'s, run on
 * the {@code callbacks} executor in the order they happened; {@code disconnected} runs on the reader, and so does the
 * completion of {@link #connect}'s future, or on the thread that disconnects first.
 *
 * <p>TLS. A client made with an {@link SSLContext} layers TLS 1.3 over each TCP connection and finishes its handshake
 * before CONNECT. The handshake runs on the writer, idle until the gateway accepts the connection, while the reader
 * waits for it at most what {@link #CONNECT_WITHIN} leaves after the TCP connect and closes the socket when it is not
 * done: a read of the handshake would wait its full timeout per record, so a peer that dribbles could hold it longer.
 * A failed or late handshake reaches the handler's {@code disconnected} as any failed attempt does.
 *
 * <p>Delivery. Within one connection, at least once both ways at QoS 1: a message in is acknowledged once the stage
 * its handler returned has completed, with the reason code it gave, and in the order the messages arrived (§4.6); a
 * publish out completes when its PUBACK arrives. The client starts every session clean and lets it expire on
 * disconnect, so a message in flight when a connection ends is lost: its publish fails, and the gateway drops what it
 * had not delivered. Across connections delivery is therefore at most once.
 */
public final class MqttClient implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(MqttClient.class.getName());
    /**
     * How long a TCP connect, and the TLS handshake after it, may take: the HiveMQ client's default, which this client
     * replaced.
     */
    private static final Duration CONNECT_WITHIN = Duration.ofSeconds(10);
    /** How long the gateway may take to answer CONNECT: the HiveMQ client's default, which this client replaced. */
    private static final Duration CONNACK_WITHIN = Duration.ofSeconds(60);
    /**
     * How long one write may wait for the write lock or for the socket, after which the connection is dropped.
     * Unmeasured: a write to a gateway on the same machine that drains its socket takes microseconds, so this only
     * catches a gateway that stopped reading.
     */
    static final Duration WRITE_WITHIN = Duration.ofSeconds(10);
    /** How often the reader wakes to check the writes when no keep-alive deadline wakes it sooner. Unmeasured. */
    private static final Duration WATCH_EVERY = Duration.ofSeconds(1);
    /** How long before the keep alive ends a ping goes out: the HiveMQ client's margin, which this client replaced. */
    private static final Duration PING_EARLY = Duration.ofMillis(100);
    /** How long the rest of a packet may take once its first byte arrived, with no keep alive to bound it. */
    private static final Duration BODY_WITHIN = Duration.ofSeconds(60);
    /** How long {@link #close} waits for the disconnect: the wait the HiveMQ client's caller gave it. */
    private static final Duration CLOSE_WITHIN = Duration.ofSeconds(2);
    /**
     * How many QoS 1 publishes may wait for the gateway's Receive Maximum to free a slot; a publish past them fails
     * with {@link Packet#QUOTA_EXCEEDED}. Unmeasured: a peer publishes a handful of messages at a time.
     */
    public static final int QUEUE_MAX = 1_000;
    private static final int MAX_PACKET_ID = 65_535;
    /** A reason code is one byte (§2.4). */
    private static final int MAX_REASON_CODE = 0xFF;
    /** What a QoS 0 publish completes with once written. */
    private static final Ack WRITTEN = new Ack(Packet.SUCCESS, null);

    private final Options options;
    private final Handler handler;
    private final Executor callbacks;
    /** The TLS each connection layers over TCP, null for plain TCP. */
    private final SSLContext tls;
    /**
     * Runs one connection's task at a time, opening and reading it, and holds at most one more, the next connect the
     * handler scheduled ({@link #retry}): {@link #connect} queues the first only once, and each ended connection
     * schedules at most one next.
     */
    private final ScheduledExecutorService reader;
    /**
     * Runs the writes in the order queued; its queue has no bound of its own, what joins it is bounded where it comes
     * from: the QoS 1 publishes by the gateway's Receive Maximum (the rest wait in {@link #queued}, at most
     * {@link #QUEUE_MAX}), the SUBSCRIBEs and UNSUBSCRIBEs by the packet ids free, the PUBACKs by
     * {@link Options#receiveMaximum}, one ping at a time, one DISCONNECT. A QoS 0 publish is queued as it comes, so
     * its caller bounds how many it has unwritten, as a link does by its window; a write stuck past
     * {@link #WRITE_WITHIN} closes the connection and fails what waits.
     */
    private final ExecutorService writer;
    private final Object lock = new Object();
    /**
     * The QoS 0 messages in that wait for the handler on the callbacks executor, beside the bound
     * {@link Options#maxQueuedQos0}; added by the reader alone, removed once the handler returns.
     */
    private final Queues.Gauge waitingQos0;
    /** The QoS 0 messages in dropped past {@link Options#maxQueuedQos0}; written by the reader, read by any thread. */
    private final AtomicLong droppedQos0 = new AtomicLong();
    /** Completes once {@link #disconnect} has ended the connection and the client's strands were told to stop. */
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    /** Completes at the first accepted connect; fails when the client stops trying before one. */
    private final CompletableFuture<Void> firstConnect = new CompletableFuture<>();
    /** The QoS 1 publishes and the subscriptions waiting for their acknowledgement, by packet id; guarded by lock. */
    private final Map<Integer, Pending> pending = new HashMap<>();
    /** The QoS 1 publishes waiting for a slot under the gateway's Receive Maximum, in order; guarded by lock. */
    private final ArrayDeque<Queued> queued = new ArrayDeque<>();
    /** The connection the gateway accepted, null while there is none; guarded by lock. */
    private Connection current;
    /** The connection being opened or read, null between connections; guarded by lock. */
    private Connection opening;
    /** The next connect, scheduled; guarded by lock. */
    private ScheduledFuture<?> retry;
    /**
     * The number of the connect scheduled last; a connect that finds a later number returns without connecting, so
     * a wake that replaces a connect already begun leaves one chain of connects. Guarded by lock.
     */
    private long ticket;
    /**
     * Run by the reader at the start of each scheduled connect, before it takes the lock; written by a test's thread
     * through {@link #beforeSession}, read by the reader.
     */
    private volatile Runnable beforeSession = () -> { };
    /** Guarded by lock. */
    private boolean started;
    /** Set by {@link #disconnect}; guarded by lock. */
    private boolean stopping;
    /** Whether a connection's task runs on the reader; guarded by lock. */
    private boolean running;
    /** Connect attempts failed since the last accepted one; guarded by lock. */
    private int failures;
    /** Guarded by lock. */
    private int nextPacketId = 1;
    /** Whether the last QoS 0 message in was dropped, so a run of drops logs once; reader only. */
    private boolean droppingQos0;

    /**
     * A client for {@code options}, telling {@code handler} what happens, its {@code connected} and {@code message}
     * calls and its futures' completions on {@code callbacks}. Nothing connects before {@link #connect}.
     */
    public MqttClient(final Options options, final Handler handler, final Executor callbacks) {
        this(options, handler, callbacks, null);
    }

    /**
     * As {@link #MqttClient(Options, Handler, Executor)}, each connection over TLS 1.3 from {@code tls}, or over plain
     * TCP when {@code tls} is null. The peer is authenticated by the context's trust manager alone: no host name is
     * checked.
     */
    public MqttClient(final Options options, final Handler handler, final Executor callbacks, final SSLContext tls) {
        this.options = Objects.requireNonNull(options, "options");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        this.tls = tls;
        this.reader = Threads.timedStrand("mqtt-" + options.clientId());
        this.writer = Threads.strand("mqtt-writer-" + options.clientId());
        this.waitingQos0 = Queues.gauge(qos0Name());
    }

    /**
     * Starts connecting, at once; after a failed attempt or a lost connection the handler says when to try again.
     * Returns a future that completes when the gateway first accepts a connect, and fails when the client stops trying
     * before that. A second call returns the same future.
     */
    public CompletableFuture<Void> connect() {
        synchronized (lock) {
            if (!started && !stopping) {
                started = true;
                final long t = ++ticket;
                reader.execute(() -> session(t));
            }
        }
        return firstConnect;
    }

    /**
     * Brings the scheduled reconnect forward to now, when one waits: the handler learnt the gateway is back before its
     * backoff ran out. Returns false, changing nothing, while a connection runs, before {@link #connect} or after
     * {@link #disconnect}. A scheduled connect that has begun but not yet opened its connection gives way to the woken
     * one, so one chain of connects runs.
     */
    public boolean wake() {
        synchronized (lock) {
            if (stopping || running || retry == null || !retry.cancel(false)) {
                return false;
            }
            final long t = ++ticket;
            retry = reader.schedule(() -> session(t), 0L, TimeUnit.NANOSECONDS);
            return true;
        }
    }

    /** Whether the gateway has accepted the current connection and it has not ended. */
    public boolean connected() {
        synchronized (lock) {
            return current != null;
        }
    }

    /**
     * The Receive Maximum the gateway set for the current connection, how many QoS 1 publishes may await their PUBACK
     * at once; 0 while not connected.
     */
    public int receiveMaximum() {
        synchronized (lock) {
            return current == null ? 0 : current.receiveMaximum;
        }
    }

    /**
     * The QoS 0 messages in this client dropped since it was made, each because {@link Options#maxQueuedQos0} were
     * already waiting for the handler.
     */
    public long droppedQos0() {
        return droppedQos0.get();
    }

    /**
     * Sends {@code message} at its QoS, 0 or 1, with the packet id this client gives it. A QoS 0 publish completes
     * once written, a QoS 1 one when the gateway acknowledges it; past the gateway's Receive Maximum a QoS 1 publish
     * waits its turn, up to {@link #QUEUE_MAX} of them. It fails at once when the client is not connected, when the
     * packet is larger than the gateway accepts, or when the queue is full; later when the gateway refuses it or the
     * connection ends first.
     */
    public CompletableFuture<Void> publish(final Packet.Publish message) {
        return refusedFails(Kind.PUBLISH, out(message));
    }

    /**
     * Sends {@code message} as {@link #publish} does, but its future completes with the reason code of the PUBACK, a
     * refusal's (0x80 or above) included, and with {@link Packet#SUCCESS} for a QoS 0 publish once written. It fails
     * only when no PUBACK can come: in the cases {@link #publish} fails at once, and when the connection ends first.
     */
    public CompletableFuture<Integer> publishAcknowledged(final Packet.Publish message) {
        final CompletableFuture<Integer> result = new CompletableFuture<>();
        out(message).whenComplete((a, e) -> {
            if (e != null) {
                result.completeExceptionally(e);
            } else {
                result.complete(a.reasonCode());
            }
        });
        return result;
    }

    /** Sends {@code message}; its future completes with its acknowledgement, a refusal included. */
    private CompletableFuture<Ack> out(final Packet.Publish message) {
        if (message.qos() > 1) {
            throw new IllegalArgumentException("QoS 2 not supported [topic=" + message.topic() + ']');
        }
        final CompletableFuture<Ack> result = new CompletableFuture<>();
        try {
            synchronized (lock) {
                final Connection c = connectedOrFail();
                if (message.qos() > c.maximumQos) {
                    // [MQTT-3.2.2-11]: never above the Maximum QoS the gateway's CONNACK stated
                    throw new MqttException(Packet.QOS_NOT_SUPPORTED, "QoS above the gateway's maximum [qos="
                            + message.qos() + ", max=" + c.maximumQos + ", topic=" + message.topic() + ']');
                }
                if (message.retain() && !c.retainAvailable) {
                    // [MQTT-3.2.2-14]: no RETAIN 1 to a gateway whose CONNACK said Retain Available 0
                    throw new MqttException(Packet.RETAIN_NOT_SUPPORTED, "retain not available on the gateway [topic="
                            + message.topic() + ']');
                }
                if (message.qos() == 0) {
                    send(c, encode(c, message.withPacketId(0)), result);
                } else if (!queued.isEmpty() || c.inflight >= c.receiveMaximum) {
                    if (queued.size() >= QUEUE_MAX) {
                        throw new MqttException(Packet.QUOTA_EXCEEDED, "publish queue full [max=" + QUEUE_MAX
                                + ", topic=" + message.topic() + ']');
                    }
                    queued.add(new Queued(message, result));
                } else {
                    inflight(c, message, result);
                }
            }
        } catch (final MqttException e) {
            return CompletableFuture.failedFuture(e);
        }
        return result;
    }

    /**
     * Subscribes to {@code filter} with maximum QoS {@code qos}. The future completes when the gateway grants the
     * subscription, and fails when it refuses it, when the client is not connected, or when the connection ends first.
     */
    public CompletableFuture<Void> subscribe(final String filter, final int qos) {
        return subscribe(new Packet.Subscription(filter, qos));
    }

    /** Subscribes with {@code subscription}'s options (No Local, Retain As Published, Retain Handling), as above. */
    public CompletableFuture<Void> subscribe(final Packet.Subscription subscription) {
        return refusedFails(Kind.SUBSCRIBE, request(Kind.SUBSCRIBE,
                id -> new Packet.Subscribe(id, List.of(subscription))));
    }

    /**
     * Unsubscribes from {@code filter}. The future completes when the gateway acknowledges it, a filter it held no
     * subscription for included, and fails as {@link #subscribe} does.
     */
    public CompletableFuture<Void> unsubscribe(final String filter) {
        return refusedFails(Kind.UNSUBSCRIBE, request(Kind.UNSUBSCRIBE,
                id -> new Packet.Unsubscribe(id, List.of(filter))));
    }

    /** Sends the packet {@code build} makes under a new packet id; its future completes with the acknowledgement. */
    private CompletableFuture<Ack> request(final Kind kind, final IntFunction<Packet> build) {
        final CompletableFuture<Ack> result = new CompletableFuture<>();
        try {
            synchronized (lock) {
                final Connection c = connectedOrFail();
                final int id = packetId();
                send(c, encode(c, build.apply(id)), null);
                pending.put(id, new Pending(kind, result));
            }
        } catch (final MqttException e) {
            return CompletableFuture.failedFuture(e);
        }
        return result;
    }

    /**
     * {@code acknowledged}, failed with an {@link MqttException} carrying the reason code when the gateway refused the
     * packet (0x80 or above, §2.4).
     */
    private static CompletableFuture<Void> refusedFails(final Kind kind, final CompletableFuture<Ack> acknowledged) {
        final CompletableFuture<Void> result = new CompletableFuture<>();
        acknowledged.whenComplete((a, e) -> {
            if (e != null) {
                result.completeExceptionally(e);
            } else if (a.reasonCode() >= Packet.FAILURE) {
                result.completeExceptionally(new MqttException(a.reasonCode(), "the gateway refused the "
                        + kind.name().toLowerCase(Locale.ROOT) + " [reasonCode=" + Packet.hex(a.reasonCode())
                        + ", reason=" + a.reasonString() + ']'));
            } else {
                result.complete(null);
            }
        });
        return result;
    }

    /**
     * Stops: cancels a scheduled reconnect, sends DISCONNECT on an accepted connection, so the gateway drops the will,
     * and closes it. Returns a future that completes once the connection has ended and the client's strands were told
     * to stop; every later call returns it. A client disconnected does not connect again.
     */
    public CompletableFuture<Void> disconnect() {
        final Connection c;
        final ScheduledFuture<?> r;
        final boolean idle;
        synchronized (lock) {
            if (stopping) {
                return stopped;
            }
            stopping = true;
            r = retry;
            retry = null;
            c = opening;
            idle = !running;
        }
        if (r != null) {
            r.cancel(false);
        }
        firstConnect.completeExceptionally(new MqttException(Packet.UNSPECIFIED, "disconnected before connecting"));
        if (idle) {
            finish();
        } else if (c != null) {
            if (c.accepted) {
                try {
                    writer.execute(() -> goodbye(c, Packet.SUCCESS));
                } catch (final RejectedExecutionException _) {
                    c.close();
                }
            } else {
                c.close();
            }
        }
        return stopped;
    }

    /** Disconnects, waits at most {@link #CLOSE_WITHIN} for it, and stops the client's strands. */
    @Override
    public void close() {
        try {
            disconnect().get(CLOSE_WITHIN.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final ExecutionException | TimeoutException e) {
            LOG.log(System.Logger.Level.DEBUG, "disconnect incomplete [client=" + options.clientId() + ", cause="
                    + e + ']');
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ExecutorStop.now("mqtt-" + options.clientId(), reader, writer);
        // again here: a disconnect cut short by the wait may never reach finish
        Queues.drop(qos0Name(), waitingQos0);
    }

    /** Holds each scheduled connect at its start, before it takes the lock, while {@code hook} runs. */
    @TestOnly
    void beforeSession(final Runnable hook) {
        beforeSession = hook;
    }

    /**
     * One connection, on the reader: open, read until it ends, then tell the handler and maybe schedule the next.
     * Returns at once when {@code t} is no longer the last ticket: a wake scheduled another connect in its place.
     */
    private void session(final long t) {
        beforeSession.run();
        final Connection c;
        synchronized (lock) {
            if (t != ticket) {
                return;
            }
            retry = null;
            if (stopping) {
                return;
            }
            running = true;
            c = new Connection();
            opening = c;
        }
        ended(c, attempt(c));
    }

    /** Runs connection {@code c} to its end and closes it; returns why it ended, null when the client stopped it. */
    private Throwable attempt(final Connection c) {
        try {
            return run(c);
        } catch (final MqttException e) {
            if (answered(e.reasonCode())) {
                goodbye(c, e.reasonCode());
            }
            return e;
        } catch (final IOException e) {
            return e;
        } catch (final InterruptedException e) {
            // the reader is interrupted only when the client's strands are stopped
            Thread.currentThread().interrupt();
            return e;
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "connection failed [client=" + options.clientId() + ']', e);
            return e;
        } finally {
            c.close();
        }
    }

    /**
     * Opens {@code c}, sends CONNECT and reads until the connection ends. Returns the gateway's refusal or DISCONNECT
     * as the cause; throws when the connection broke or the gateway broke the protocol.
     */
    private MqttException run(final Connection c) throws IOException, InterruptedException {
        final long startNanos = System.nanoTime();
        c.socket.setTcpNoDelay(true);
        c.socket.connect(new InetSocketAddress(options.host(), options.port()), (int) CONNECT_WITHIN.toMillis());
        c.open(tls == null ? c.socket : handshake(c, startNanos));
        // the reader writes CONNECT itself: nothing else writes on a connection the gateway has not accepted, and the
        // send buffer of a new socket is empty, so the write does not wait for the gateway
        c.write(Codec.encode(new Packet.Connect(options.clientId(), true, options.keepAliveSeconds(), 0,
                options.receiveMaximum(), options.maximumPacketSize(), options.will())));
        c.socket.setSoTimeout((int) CONNACK_WITHIN.toMillis());
        final int first = c.in.read();
        if (first < 0) {
            throw new EOFException("the gateway closed the connection before acknowledging it");
        }
        final Packet answer = Codec.read(c.in, first, options.maximumPacketSize());
        if (!(answer instanceof final Packet.ConnAck ack)) {
            throw new MqttException(Packet.PROTOCOL_ERROR, "first packet not a connect acknowledgement [packet="
                    + answer.getClass().getSimpleName() + ']');
        }
        if (ack.reasonCode() != Packet.SUCCESS) {
            return new MqttException(ack.reasonCode(), "the gateway refused the connect [reasonCode="
                    + Packet.hex(ack.reasonCode()) + ", reason=" + ack.reasonString() + ']');
        }
        if (ack.sessionPresent()) {
            // [MQTT-3.2.2-4]: the client asked Clean Start, so it holds no session the gateway could have resumed
            throw new MqttException(Packet.PROTOCOL_ERROR, "session present after a clean start [client="
                    + options.clientId() + ']');
        }
        final int keepAliveSeconds = ack.serverKeepAliveSeconds() == Packet.NO_SERVER_KEEP_ALIVE
                ? options.keepAliveSeconds() : ack.serverKeepAliveSeconds();
        c.accept(ack, keepAliveSeconds);
        synchronized (lock) {
            if (stopping) {
                return null;
            }
            current = c;
            failures = 0;
        }
        firstConnect.complete(null);
        post(handler::connected);
        return readLoop(c);
    }

    /**
     * Layers TLS 1.3 over {@code c}'s connected socket and runs the handshake on the writer, for at most what
     * {@link #CONNECT_WITHIN} leaves since {@code startNanos}; a handshake not done by then is cut by closing the
     * socket. Throws the handshake's own failure.
     */
    private SSLSocket handshake(final Connection c, final long startNanos) throws IOException, InterruptedException {
        final SSLSocket s = (SSLSocket) tls.getSocketFactory().createSocket(c.socket, options.host(), options.port(),
                true);
        s.setUseClientMode(true);
        s.setEnabledProtocols(new String[] {Tls.PROTOCOL});
        final long leftNanos = CONNECT_WITHIN.toNanos() - (System.nanoTime() - startNanos);
        try {
            writer.submit(() -> {
                s.startHandshake();
                return null;
            }).get(leftNanos, TimeUnit.NANOSECONDS);
            return s;
        } catch (final TimeoutException _) {
            c.close();
            throw new SocketTimeoutException("TLS handshake not done in time [client=" + options.clientId()
                    + ", waitMillis=" + CONNECT_WITHIN.toMillis() + ']');
        } catch (final RejectedExecutionException _) {
            throw new IOException("client closing before the TLS handshake [client=" + options.clientId() + ']');
        } catch (final ExecutionException e) {
            switch (e.getCause()) {
                case final IOException io -> throw io;
                case final RuntimeException r -> throw r;
                case final Error err -> throw err;
                case null, default -> throw new IllegalStateException("TLS handshake failed [client="
                        + options.clientId() + ']', e.getCause());
            }
        }
    }

    /**
     * Reads packets until the gateway disconnects, whose DISCONNECT this returns; throws when the connection
     * breaks.
     */
    private MqttException readLoop(final Connection c) throws IOException {
        while (true) {
            final long now = System.nanoTime();
            watch(c, now);
            c.socket.setSoTimeout(wakeMillis(c, now));
            final int first;
            try {
                first = c.in.read();
            } catch (final SocketTimeoutException _) {
                continue;
            }
            if (first < 0) {
                throw new EOFException("the gateway closed the connection");
            }
            c.lastReadNanos = System.nanoTime();
            c.socket.setSoTimeout(c.bodyMillis);
            final MqttException told = handle(c, Codec.read(c.in, first, options.maximumPacketSize()));
            if (told != null) {
                return told;
            }
        }
    }

    /**
     * The reader's checks between reads: a write that lasts past {@link #WRITE_WITHIN} or a ping unanswered for the
     * keep alive ends the connection; a connection idle in either direction for the keep alive is pinged, as the
     * HiveMQ client did. Only a PINGRESP answers a ping (§3.12.4): a gateway that sends messages but never answers is
     * timed out all the same.
     */
    private void watch(final Connection c, final long now) throws MqttException {
        // c.writing is written after c.writeStartedNanos, both volatile, so a write seen in progress has its start
        if (c.writing && now - c.writeStartedNanos >= WRITE_WITHIN.toNanos()) {
            throw new MqttException(Packet.UNSPECIFIED, "write to the gateway stalled [waitMillis="
                    + WRITE_WITHIN.toMillis() + ']');
        }
        if (c.keepAliveNanos == 0) {
            return;
        }
        final long interval = c.keepAliveNanos - PING_EARLY.toNanos();
        if (c.pingOutstanding) {
            if (now - c.pingSentNanos >= interval) {
                throw new MqttException(Packet.KEEP_ALIVE_TIMEOUT, "no answer to a ping within the keep alive "
                        + "[keepAliveMillis=" + TimeUnit.NANOSECONDS.toMillis(c.keepAliveNanos) + ']');
            }
        } else if (now - idleSince(c) >= interval) {
            c.pingOutstanding = true;
            c.pingSentNanos = now;
            send(c, Codec.encode(new Packet.PingReq()), null);
        }
    }

    /** How long the reader may wait for the next byte before {@link #watch} must run again, in milliseconds. */
    private static int wakeMillis(final Connection c, final long now) {
        long wakeNanos = WATCH_EVERY.toNanos();
        if (c.keepAliveNanos > 0) {
            final long interval = c.keepAliveNanos - PING_EARLY.toNanos();
            final long from = c.pingOutstanding ? c.pingSentNanos : idleSince(c);
            wakeNanos = Math.min(wakeNanos, from + interval - now);
        }
        return (int) Math.max(1, Math.ceilDiv(wakeNanos, 1_000_000L));
    }

    /** The older of the last read and the last write: the connection has been idle in one direction since. */
    private static long idleSince(final Connection c) {
        final long read = c.lastReadNanos;
        final long wrote = c.lastWriteNanos;
        return read - wrote < 0 ? read : wrote;
    }

    /** Acts on a packet read; returns the cause when it is the gateway's DISCONNECT. */
    private MqttException handle(final Connection c, final Packet packet) throws MqttException {
        return switch (packet) {
            case Packet.Publish m -> {
                incoming(c, m);
                yield null;
            }
            case Packet.PubAck(final int id, final int code, final String reason) -> {
                acknowledged(c, id, Kind.PUBLISH, code, reason);
                yield null;
            }
            case Packet.SubAck(final int id, final List<Integer> codes, final String reason) -> {
                if (codes.size() != 1) {
                    throw new MqttException(Packet.PROTOCOL_ERROR, "subscribe acknowledgement codes not one per "
                            + "filter [id=" + id + ", codes=" + codes.size() + ']');
                }
                acknowledged(c, id, Kind.SUBSCRIBE, codes.getFirst(), reason);
                yield null;
            }
            case Packet.UnsubAck(final int id, final List<Integer> codes, final String reason) -> {
                if (codes.size() != 1) {
                    throw new MqttException(Packet.PROTOCOL_ERROR, "unsubscribe acknowledgement codes not one per "
                            + "filter [id=" + id + ", codes=" + codes.size() + ']');
                }
                acknowledged(c, id, Kind.UNSUBSCRIBE, codes.getFirst(), reason);
                yield null;
            }
            case Packet.PingResp _ -> {
                c.pingOutstanding = false;
                yield null;
            }
            case Packet.Disconnect(final int code, final String reason, _) -> new MqttException(code,
                    "the gateway disconnected [reasonCode=" + Packet.hex(code) + ", reason=" + reason + ']');
            case Packet.Connect _, Packet.ConnAck _, Packet.Subscribe _, Packet.Unsubscribe _, Packet.PingReq _ ->
                    throw new MqttException(Packet.PROTOCOL_ERROR, "packet a gateway does not send [packet="
                            + packet.getClass().getSimpleName() + ']');
        };
    }

    /**
     * A message in: to the handler; at QoS 1, a place in the connection's PUBACK order, filled when the stage the
     * handler returned completes.
     */
    private void incoming(final Connection c, final Packet.Publish m) throws MqttException {
        if (m.qos() == 2) {
            throw new MqttException(Packet.PROTOCOL_ERROR, "QoS 2 message on a QoS 1 subscription [topic="
                    + m.topic() + ']');
        }
        // the CONNECT sent options.receiveMaximum(), the most the gateway may have unacknowledged at once (§3.1.2.11.3)
        if (m.qos() == 1 && c.unacked.incrementAndGet() > options.receiveMaximum()) {
            throw new MqttException(Packet.RECEIVE_MAXIMUM_EXCEEDED, "more QoS 1 messages unacknowledged than "
                    + "allowed [max=" + options.receiveMaximum() + ']');
        }
        final InAck place;
        if (m.qos() == 1) {
            place = new InAck(m.packetId());
            synchronized (lock) {
                c.inAcks.add(place);
            }
        } else {
            place = null;
            // the reader alone adds, so the depth read here can only have fallen by the time the add runs
            if (waitingQos0.depth() >= options.maxQueuedQos0()) {
                final long dropped = droppedQos0.incrementAndGet();
                if (!droppingQos0) {
                    droppingQos0 = true;
                    LOG.log(System.Logger.Level.WARNING, "QoS 0 messages dropped: the handler is behind [client="
                            + options.clientId() + ", topic=" + m.topic() + ", maxQueuedQos0="
                            + options.maxQueuedQos0() + ", dropped=" + dropped + ']');
                }
                return;
            }
            droppingQos0 = false;
            waitingQos0.added();
        }
        final boolean posted = post(() -> {
            CompletionStage<Integer> answer;
            try {
                answer = handler.received(m);
            } catch (final RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "message handler failed [topic=" + m.topic() + ']', e);
                answer = CompletableFuture.completedFuture(Packet.UNSPECIFIED);
            } finally {
                if (place == null) {
                    waitingQos0.removed();
                }
            }
            if (place != null) {
                answer.whenComplete((code, e) -> handled(c, place, e != null || code == null || code < 0
                        || code > MAX_REASON_CODE ? Packet.UNSPECIFIED : code));
            }
        });
        if (!posted && place == null) {
            waitingQos0.removed();
        }
    }

    /**
     * The handler answered the message {@code place} holds with {@code reasonCode}: queues, in arrival order, the
     * PUBACK of every message answered up to the first not yet answered.
     */
    private void handled(final Connection c, final InAck place, final int reasonCode) {
        synchronized (lock) {
            place.reasonCode = reasonCode;
            while (!c.inAcks.isEmpty() && c.inAcks.peek().reasonCode >= 0) {
                final InAck head = c.inAcks.poll();
                acknowledge(c, head.packetId, head.reasonCode);
            }
        }
    }

    /** Queues the PUBACK of the message {@code packetId} on the writer. */
    private void acknowledge(final Connection c, final int packetId, final int reasonCode) {
        try {
            writer.execute(() -> {
                try {
                    c.write(Codec.encode(new Packet.PubAck(packetId, reasonCode, null)));
                    c.unacked.decrementAndGet();
                } catch (final IOException e) {
                    c.close();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    c.close();
                }
            });
        } catch (final RejectedExecutionException _) {
            c.close();
        }
    }

    /** A PUBACK or SUBACK: completes its packet's future, and lets the next queued publish use the freed slot. */
    private void acknowledged(final Connection c, final int packetId, final Kind kind, final int reasonCode,
            final String reasonString) throws MqttException {
        final Pending p;
        final List<Queued> refused = new ArrayList<>();
        final List<MqttException> refusals = new ArrayList<>();
        synchronized (lock) {
            p = pending.get(packetId);
            if (p == null || p.kind != kind) {
                throw new MqttException(Packet.PROTOCOL_ERROR, "acknowledgement of no packet in flight [id="
                        + packetId + ", kind=" + kind + ']');
            }
            pending.remove(packetId);
            if (kind == Kind.PUBLISH) {
                c.inflight--;
                while (!queued.isEmpty() && c.inflight < c.receiveMaximum) {
                    final Queued q = queued.poll();
                    try {
                        inflight(c, q.message, q.future);
                    } catch (final MqttException e) {
                        refused.add(q);
                        refusals.add(e);
                    }
                }
            }
        }
        settle(p.future, new Ack(reasonCode, reasonString), null);
        for (int i = 0; i < refused.size(); i++) {
            settle(refused.get(i).future, null, refusals.get(i));
        }
    }

    /** Sends QoS 1 {@code message} under a new packet id, counted in flight. Holds lock. */
    private void inflight(final Connection c, final Packet.Publish message, final CompletableFuture<Ack> result)
            throws MqttException {
        final int id = packetId();
        send(c, encode(c, message.withPacketId(id)), null);
        pending.put(id, new Pending(Kind.PUBLISH, result));
        c.inflight++;
    }

    /** The connection the gateway accepted, or a failure: the client is not connected. Holds lock. */
    private Connection connectedOrFail() throws MqttException {
        if (current == null) {
            throw new MqttException(Packet.UNSPECIFIED, "not connected [client=" + options.clientId() + ']');
        }
        return current;
    }

    /** A packet id no packet in flight holds. Holds lock. */
    private int packetId() throws MqttException {
        for (int i = 0; i < MAX_PACKET_ID; i++) {
            final int id = nextPacketId;
            nextPacketId = id == MAX_PACKET_ID ? 1 : id + 1;
            if (!pending.containsKey(id)) {
                return id;
            }
        }
        throw new MqttException(Packet.QUOTA_EXCEEDED, "no packet id free [inFlight=" + pending.size() + ']');
    }

    /** {@code packet}'s bytes, or a failure when the gateway's Maximum Packet Size cannot take them. */
    private static byte[] encode(final Connection c, final Packet packet) throws MqttException {
        final byte[] bytes = Codec.encode(packet);
        if (bytes.length > c.maximumPacketSize) {
            throw new MqttException(Packet.PACKET_TOO_LARGE, "packet larger than the gateway accepts [bytes="
                    + bytes.length + ", max=" + c.maximumPacketSize + ']');
        }
        return bytes;
    }

    /**
     * Queues {@code bytes} on the writer, in the order of the calls, and completes {@code written}, when given, once
     * they are written. Callers that order their packets call this under lock.
     */
    private void send(final Connection c, final byte[] bytes, final CompletableFuture<Ack> written)
            throws MqttException {
        try {
            writer.execute(() -> {
                try {
                    c.write(bytes);
                    if (written != null) {
                        settle(written, WRITTEN, null);
                    }
                } catch (final IOException e) {
                    c.close();
                    if (written != null) {
                        settle(written, null, e);
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    c.close();
                    if (written != null) {
                        settle(written, null, e);
                    }
                }
            });
        } catch (final RejectedExecutionException e) {
            throw new MqttException(Packet.UNSPECIFIED, "client closed [client=" + options.clientId() + ']', e);
        }
    }

    /**
     * Sends DISCONNECT with {@code reasonCode} and closes {@code c}, whichever thread asks, at most
     * {@link #WRITE_WITHIN} later: on the writer the write runs at once; elsewhere it queues behind the writes before
     * it.
     */
    private void goodbye(final Connection c, final int reasonCode) {
        if (c.out == null) {
            c.close();
            return;
        }
        final Runnable write = () -> {
            try {
                c.write(Codec.encode(new Packet.Disconnect(reasonCode, null)));
            } catch (final IOException e) {
                LOG.log(System.Logger.Level.DEBUG, "disconnect not sent [client=" + options.clientId() + ", cause="
                        + e + ']');
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                c.close();
            }
        };
        if (!c.onReader()) {
            write.run();
            return;
        }
        try {
            final Future<?> done = writer.submit(write);
            done.get(WRITE_WITHIN.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final RejectedExecutionException | ExecutionException | TimeoutException e) {
            LOG.log(System.Logger.Level.DEBUG, "disconnect not sent in time [client=" + options.clientId()
                    + ", cause=" + e + ']');
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            c.close();
        }
    }

    /** A connection ended: fails what waited on it, then stops, or asks the handler when to try again. */
    private void ended(final Connection c, final Throwable cause) {
        final List<CompletableFuture<Ack>> lost = new ArrayList<>();
        final boolean stop;
        final int attempts;
        synchronized (lock) {
            if (current == c) {
                current = null;
            }
            opening = null;
            running = false;
            for (final Pending p : pending.values()) {
                lost.add(p.future);
            }
            pending.clear();
            for (final Queued q : queued) {
                lost.add(q.future);
            }
            queued.clear();
            stop = stopping;
            attempts = c.accepted ? 0 : ++failures;
        }
        final MqttException lostCause = new MqttException(Packet.UNSPECIFIED, "connection ended [client="
                + options.clientId() + ']', cause);
        for (final CompletableFuture<Ack> f : lost) {
            settle(f, null, lostCause);
        }
        if (stop) {
            finish();
            return;
        }
        LOG.log(System.Logger.Level.DEBUG, "connection ended [client=" + options.clientId() + ", attempts="
                + attempts + ", cause=" + cause + ']');
        Duration delay;
        try {
            delay = handler.disconnected(attempts, cause);
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "disconnect handler failed [client=" + options.clientId() + ']', e);
            delay = null;
        }
        synchronized (lock) {
            if (delay != null && !stopping) {
                final long t = ++ticket;
                retry = reader.schedule(() -> session(t), delay.toNanos(), TimeUnit.NANOSECONDS);
                return;
            }
        }
        firstConnect.completeExceptionally(cause == null ? lostCause : cause);
    }

    /** The client stopped: its strands finish what they hold and end; {@link #close} bounds the wait. */
    private void finish() {
        reader.shutdown();
        writer.shutdown();
        Queues.drop(qos0Name(), waitingQos0);
        stopped.complete(null);
    }

    /** The name the QoS 0 messages waiting for the handler are read under. */
    private String qos0Name() {
        return "mqtt-qos0-" + options.clientId();
    }

    /** Runs {@code task} on the callbacks executor; once that executor has stopped, drops it and returns false. */
    private boolean post(final Runnable task) {
        try {
            callbacks.execute(task);
            return true;
        } catch (final RejectedExecutionException _) {
            LOG.log(System.Logger.Level.DEBUG, "callback dropped: its executor stopped [client=" + options.clientId()
                    + ']');
            return false;
        }
    }

    /**
     * Completes {@code f} with {@code value}, or failed with {@code error} when given, on the callbacks executor, or
     * here once it stopped.
     */
    private <T> void settle(final CompletableFuture<T> f, final T value, final Throwable error) {
        final Runnable complete = () -> {
            if (error == null) {
                f.complete(value);
            } else {
                f.completeExceptionally(error);
            }
        };
        try {
            callbacks.execute(complete);
        } catch (final RejectedExecutionException _) {
            complete.run();
        }
    }

    /** Whether this client answers its own failure {@code reasonCode} with a DISCONNECT before closing (§4.13). */
    private static boolean answered(final int reasonCode) {
        return reasonCode == Packet.MALFORMED || reasonCode == Packet.PROTOCOL_ERROR
                || reasonCode == Packet.RECEIVE_MAXIMUM_EXCEEDED || reasonCode == Packet.TOPIC_ALIAS_INVALID
                || reasonCode == Packet.PACKET_TOO_LARGE;
    }

    /**
     * Who to connect to and as whom: {@code keepAliveSeconds} 0 for no keep alive, the largest packet this client
     * reads in bytes (sent to the gateway as its Maximum Packet Size), the {@code will}, null for none, how many
     * QoS 1 messages the gateway may have unacknowledged at once (sent as its Receive Maximum), and how many QoS 0
     * messages may wait for the handler: one that arrives past them is dropped and counted in
     * {@link MqttClient#droppedQos0}.
     */
    public record Options(String host, int port, String clientId, int keepAliveSeconds, int maximumPacketSize,
            Packet.Publish will, int receiveMaximum, int maxQueuedQos0) {

        /**
         * Unmeasured: as {@link #QUEUE_MAX}, a peer publishes a handful of messages at a time, and each waiting holds
         * its payload in memory.
         */
        private static final int DEFAULT_MAX_QUEUED_QOS0 = 1_000;

        /** Refuses a value out of range. */
        public Options {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(clientId, "clientId");
            if (port < 1 || port > 65_535 || keepAliveSeconds < 0 || keepAliveSeconds > 65_535
                    || maximumPacketSize < 1 || maximumPacketSize > Packet.PROTOCOL_MAXIMUM_PACKET_SIZE
                    || receiveMaximum < 1 || receiveMaximum > Packet.DEFAULT_RECEIVE_MAXIMUM || maxQueuedQos0 < 1) {
                throw new IllegalArgumentException("option out of range [port=" + port + ", keepAliveSeconds="
                        + keepAliveSeconds + ", maximumPacketSize=" + maximumPacketSize + ", receiveMaximum="
                        + receiveMaximum + ", maxQueuedQos0=" + maxQueuedQos0 + ']');
            }
        }

        /** Options with the default bound on the QoS 0 messages waiting for the handler. */
        public Options(final String host, final int port, final String clientId, final int keepAliveSeconds,
                final int maximumPacketSize, final Packet.Publish will, final int receiveMaximum) {
            this(host, port, clientId, keepAliveSeconds, maximumPacketSize, will, receiveMaximum,
                    DEFAULT_MAX_QUEUED_QOS0);
        }

        /** Options with the protocol's default Receive Maximum. */
        public Options(final String host, final int port, final String clientId, final int keepAliveSeconds,
                final int maximumPacketSize, final Packet.Publish will) {
            this(host, port, clientId, keepAliveSeconds, maximumPacketSize, will, Packet.DEFAULT_RECEIVE_MAXIMUM);
        }
    }

    /** What a client tells its owner. */
    public interface Handler {

        /** The gateway accepted a connect, the first or a later one. On the callbacks executor. */
        void connected();

        /** A message arrived. On the callbacks executor, in the order of arrival; a QoS 1 one is acknowledged after. */
        void message(Packet.Publish message);

        /**
         * A message arrived; the reason code the returned stage completes with goes back in its PUBACK at QoS 1, and
         * {@link Packet#UNSPECIFIED} when the stage fails or its code is not one. On the callbacks executor, in the
         * order of arrival; PUBACKs leave in that order too, so one stage not yet complete holds back the PUBACKs of
         * the messages after it. The default hands the message to {@link #message} and acknowledges it at once.
         */
        default CompletionStage<Integer> received(final Packet.Publish message) {
            message(message);
            return CompletableFuture.completedFuture(Packet.SUCCESS);
        }

        /**
         * A connect attempt failed, or an accepted connection ended, because of {@code cause}, the gateway's refusal
         * or disconnect included. {@code attempts} counts the attempts failed in a row, 0 when an accepted
         * connection ended. Returns how long to wait before the next attempt, or null for none. On the reader.
         */
        Duration disconnected(int attempts, Throwable cause);
    }

    private enum Kind {
        PUBLISH,
        SUBSCRIBE,
        UNSUBSCRIBE
    }

    /** The reason code and string of an acknowledgement. */
    private record Ack(int reasonCode, String reasonString) {
    }

    /** A packet waiting for its acknowledgement. */
    private record Pending(Kind kind, CompletableFuture<Ack> future) {
    }

    /** A QoS 1 publish waiting for a slot under the gateway's Receive Maximum. */
    private record Queued(Packet.Publish message, CompletableFuture<Ack> future) {
    }

    /** A QoS 1 message in, in its connection's PUBACK order. */
    private static final class InAck {

        private final int packetId;
        /** The handler's answer, -1 until it gave one; guarded by the client's lock. */
        private int reasonCode = -1;

        InAck(final int packetId) {
            this.packetId = packetId;
        }
    }

    /** One TCP connection, with the TLS layered over it when the client has one, and what the client knows of it. */
    private static final class Connection {

        private final Socket socket = new Socket();
        /** Serialises the writes: CONNECT from the reader, everything after from the writer. */
        private final ReentrantLock writeLock = new ReentrantLock();
        /** QoS 1 messages in not acknowledged yet; incremented by the reader, decremented by the writer. */
        private final AtomicInteger unacked = new AtomicInteger();
        /**
         * The QoS 1 messages in whose PUBACK is not queued yet, in arrival order; guarded by the client's lock. At
         * most the client's Receive Maximum, which {@link #unacked} enforces.
         */
        private final ArrayDeque<InAck> inAcks = new ArrayDeque<>();
        /** The reader's thread, set when it opens this connection, to tell its calls from others'. */
        private Thread readerThread;
        /** Written by the reader before {@link #accepted}, read by the reader. */
        private InputStream in;
        /** Written by the reader before {@link #accepted}; read by the writer, which gets this connection under lock. */
        private OutputStream out;
        /** Written by the reader at CONNACK; read by {@link #disconnect} and the writer. */
        private volatile boolean accepted;
        /** The gateway's limits and the keep alive, written by the reader before {@link #accepted}. */
        private int receiveMaximum;
        private int maximumPacketSize = Packet.PROTOCOL_MAXIMUM_PACKET_SIZE;
        private int maximumQos = Packet.Capabilities.DEFAULT.maximumQos();
        private boolean retainAvailable = true;
        private long keepAliveNanos;
        private int bodyMillis;
        /** QoS 1 publishes out awaiting their PUBACK; guarded by the client's lock. */
        private int inflight;
        /** Written by the writer (the reader for CONNECT); read by the reader. */
        private volatile long lastWriteNanos;
        /** Written by the writer before {@link #writing} goes true; read by the reader. */
        private volatile long writeStartedNanos;
        /** Whether a write is under way; written by the writer, read by the reader. */
        private volatile boolean writing;
        /** Reader only. */
        private long lastReadNanos;
        /** Reader only. */
        private long pingSentNanos;
        /** Reader only. */
        private boolean pingOutstanding;

        /**
         * Takes the streams of {@code wire}, the socket itself or the TLS layered over it; on the reader, once
         * connected. Timeouts are set on the socket, which a layered TLS socket reads through; closing the socket
         * ends a read or write on either.
         */
        void open(final Socket wire) throws IOException {
            readerThread = Thread.currentThread();
            in = new BufferedInputStream(wire.getInputStream());
            out = wire.getOutputStream();
        }

        /** Takes the gateway's CONNACK: its limits, and the keep alive that holds. On the reader. */
        void accept(final Packet.ConnAck ack, final int keepAliveSeconds) {
            receiveMaximum = ack.receiveMaximum();
            maximumPacketSize = ack.maximumPacketSize();
            maximumQos = ack.capabilities().maximumQos();
            retainAvailable = ack.capabilities().retainAvailable();
            keepAliveNanos = TimeUnit.SECONDS.toNanos(keepAliveSeconds);
            bodyMillis = (int) (keepAliveSeconds > 0 ? TimeUnit.SECONDS.toMillis(keepAliveSeconds)
                    : BODY_WITHIN.toMillis());
            lastReadNanos = System.nanoTime();
            accepted = true;
        }

        /** Whether the calling thread is this connection's reader. */
        boolean onReader() {
            return Thread.currentThread() == readerThread;
        }

        /**
         * Writes {@code bytes} whole, after at most {@link #WRITE_WITHIN} for the write lock; a write that then stalls
         * is ended by the reader closing the socket.
         */
        void write(final byte[] bytes) throws IOException, InterruptedException {
            if (!writeLock.tryLock(WRITE_WITHIN.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new MqttException(Packet.UNSPECIFIED, "write lock not free in time [waitMillis="
                        + WRITE_WITHIN.toMillis() + ']');
            }
            try {
                writeStartedNanos = System.nanoTime();
                writing = true;
                out.write(bytes);
                lastWriteNanos = System.nanoTime();
            } finally {
                writing = false;
                writeLock.unlock();
            }
        }

        /** Closes the socket, which ends a read or a write blocked on it; idempotent. */
        void close() {
            try {
                socket.close();
            } catch (final IOException e) {
                LOG.log(System.Logger.Level.DEBUG, "socket close failed [cause=" + e + ']');
            }
        }
    }
}
