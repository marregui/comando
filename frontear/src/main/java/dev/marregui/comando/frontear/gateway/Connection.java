package dev.marregui.comando.frontear.gateway;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.cert.Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSocket;

import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.InstanceName;
import dev.marregui.comando.frontear.protocol.Codec;
import dev.marregui.comando.frontear.protocol.MqttException;
import dev.marregui.comando.frontear.protocol.Names;
import dev.marregui.comando.frontear.protocol.Packet;
import dev.marregui.comando.frontear.protocol.Topics;

/**
 * One client's network connection: a reader strand that reads its packets in order and answers them, and a writer
 * strand that writes what the broker sends it, in the order sent. The first packet must be a CONNECT, within
 * {@link #CONNECT_WAIT_MILLIS}; after it, a connection silent for one and a half times its keep alive is closed
 * (§3.1.2.10). The connection ends once, whichever side ends it: {@link #end} runs its effects a single time.
 * <p>
 * A connection of the remote listener first finishes its TLS handshake within its listener's bound, a deadline from
 * its accept that the broker's timed strand holds, however the client spreads its bytes, and speaks for the machine
 * its certificate names, its principal: every PUBLISH and will it sends carries that machine as its one
 * {@link Names#ORIGIN} user property, whatever the client set there. When the pairings change, the broker checks its
 * certificate again ({@link #stillPinned}) and ends it when its key is no longer pinned to that machine.
 */
final class Connection {

    private static final System.Logger LOG = System.getLogger(Connection.class.getName());

    /** No DISCONNECT is sent: before a CONNACK (§3.14.0), or when the client ended the connection. */
    static final int NO_DISCONNECT = -1;

    // §3.1.4 asks a server to close a connection that sends no CONNECT within a reasonable time; 10 s covers a
    // client's TLS-less handshake on any network frontear runs on, and bounds how long a silent socket holds a reader
    private static final int CONNECT_WAIT_MILLIS = 10_000;
    // §3.1.2.10: one and a half times the keep alive, in milliseconds per second of keep alive
    private static final int KEEP_ALIVE_GRACE_MILLIS = 1_500;
    // a sizing hint for the socket's streams, not a limit
    private static final int BUFFER_BYTES = 64 * 1024;
    // what a packet waiting on the writer holds besides its encoded bytes, on a 64-bit JVM with compressed references:
    // the write task (24), the writer queue's node (24) and the array's header (16), so a flood of 2-byte PINGRESPs
    // counts what it holds
    private static final long PACKET_OVERHEAD_BYTES = 64;
    private static final byte[] PING_RESPONSE = encode(new Packet.PingResp());

    final int number;
    private final Broker broker;
    private final Socket socket;
    // the remote listener that accepted the connection; null for one of the loopback listener
    private final RemoteListen listen;
    private final InputStream in;
    private final OutputStream out;
    // runs the read loop, its one task: what waits to be read waits in the socket's receive buffer, and a client past
    // the broker's Receive Maximum is refused once its PUBLISH is read
    private final ExecutorService reader;
    // runs the writes in the order sent; its queue is bounded in bytes, not in count: a send past maxBacklogBytes of
    // what waits (pendingBytes, PACKET_OVERHEAD_BYTES a packet included) is dropped and ends the connection, and the
    // end adds one last task, the DISCONNECT and the socket's close
    private final ExecutorService writer;
    // null while the connection lives; set once, by the first thread that ends it
    private final AtomicReference<End> endCause = new AtomicReference<>();
    private final AtomicLong pendingBytes = new AtomicLong();
    // set once, by the first send past the broker's maxBacklogBytes; every send after it is dropped
    private final AtomicBoolean overBacklog = new AtomicBoolean();
    // the client's QoS 1 publishes not yet acknowledged, in arrival order; guarded by itself
    private final ArrayDeque<InboundAck> acks = new ArrayDeque<>();
    // written by the reader under the broker's session lock when the CONNECT is accepted; read by the reader, and by
    // the threads that end the connection (a takeover under the same lock, the broker's close after the reader set it)
    private volatile Session session;
    private volatile String clientId;
    // written with session by the reader when the CONNECT is accepted; read by the broker's connections(), a snapshot
    // that pairs them with the session it read first, so at worst a moment's mismatch
    private volatile int keepAliveSeconds;
    private volatile long acceptedMillis;
    // written once by the thread that ends the connection; read by the broker's sweep to close a writer stuck on a
    // client that does not read
    private volatile long endedAtNanos;
    // written by the writer strand when a write fails; read by the threads that send, to stop queueing bytes
    private volatile boolean writeFailed;
    // a remote connection's handshake, settled once: by the reader when it ends in time, or by the broker's deadline
    // task when the bound passes first, which then closes the socket under it; set by whichever thread is first
    private final AtomicBoolean handshakeSettled = new AtomicBoolean();
    // the broker's deadline task for a remote connection's handshake, cancelled once the reader settles it; written by
    // the remote accept strand before start(), which hands it to the reader, and read by the reader alone
    private ScheduledFuture<?> handshakeDeadline;
    // the certificate a remote connection's peer presented, null for a local one and before its handshake ends;
    // written by the reader before it names the machine, read by the thread that checks the pairings again
    // (stillPinned), so a check that runs before the reader names the machine sees the certificate or the reader
    // sees the new pins
    private volatile Certificate peerCertificate;
    // the machine a remote connection's certificate names, null for a local one; written by the reader once its
    // handshake ends, before it reads the CONNECT, and read by the reader and by the thread that checks the pairings
    // again (stillPinned)
    private volatile String principal;

    Connection(final Broker broker, final Socket socket, final int number) throws IOException {
        this(broker, socket, number, null);
    }

    /** A connection of the remote listener {@code listen}, whose {@code socket} is a TLS one; null for loopback. */
    Connection(final Broker broker, final Socket socket, final int number, final RemoteListen listen)
            throws IOException {
        this.broker = broker;
        this.socket = socket;
        this.number = number;
        this.listen = listen;
        this.in = new BufferedInputStream(socket.getInputStream(), BUFFER_BYTES);
        this.out = new BufferedOutputStream(socket.getOutputStream(), BUFFER_BYTES);
        final String owner = broker.port() + "-" + number;
        this.reader = Threads.strand("frontear-broker-reader-" + owner);
        this.writer = Threads.strand("frontear-broker-writer-" + owner);
    }

    /** Starts reading the connection's packets. */
    void start() {
        reader.execute(this::read);
    }

    /**
     * Starts reading a remote connection's packets, {@code deadline} the broker's task that runs
     * {@link #handshakeOverdue} once its handshake bound has passed since its accept.
     */
    void start(final ScheduledFuture<?> deadline) {
        handshakeDeadline = deadline;
        start();
    }

    /**
     * The broker's deadline for a remote connection's handshake has passed: when the reader has not settled it yet,
     * the socket closes under the handshake, whose read then fails and ends the connection, counted. Broker's timed
     * strand.
     */
    void handshakeOverdue() {
        if (handshakeSettled.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.DEBUG, "remote handshake past its bound, socket closed [connection=" + number
                    + ", handshake=" + listen.handshake() + ']');
            closeSocket();
        }
    }

    /**
     * Whether a remote connection's peer certificate is still pinned to the machine it speaks for, by
     * {@code listen().machineOf()} as it reads now: true for a local connection and for one still in its handshake.
     */
    boolean stillPinned() {
        final Certificate peer = peerCertificate;
        if (listen == null || peer == null) {
            return true;
        }
        final String now = listen.machineOf().apply(peer);
        final String machine = principal;
        return now != null && (machine == null || machine.equals(now));
    }

    /** The client id the connection's CONNECT gave or the broker assigned; null before CONNECT. */
    String clientId() {
        return clientId;
    }

    /** The remote listener that accepted the connection; null for one of the loopback listener. */
    RemoteListen listen() {
        return listen;
    }

    /** The machine the connection speaks for, null for a local one; meaningful on the reader, past the handshake. */
    String principal() {
        return principal;
    }

    /** Whether the connection has ended, by either side. */
    boolean ended() {
        return endCause.get() != null;
    }

    /** Why the connection ended, a takeover being {@link End#WILL}; null while it lives. */
    End endCause() {
        return endCause.get();
    }

    /** When the connection ended, by {@link System#nanoTime()}; meaningful once {@link #ended()}. */
    long endedAtNanos() {
        return endedAtNanos;
    }

    /**
     * The CONNECT, which asked {@code keepAlive} seconds, was accepted for {@code s} at {@code atMillis}: from here on
     * the connection speaks for its client.
     */
    void accepted(final Session s, final String id, final int keepAlive, final long atMillis) {
        clientId = id;
        keepAliveSeconds = keepAlive;
        acceptedMillis = atMillis;
        session = s;
    }

    /** The session the connection speaks for; null before its CONNECT was accepted. */
    Session session() {
        return session;
    }

    /** The keep-alive its CONNECT asked; 0 before it was accepted. */
    int keepAliveSeconds() {
        return keepAliveSeconds;
    }

    /** When its CONNECT was accepted, by the broker's clock; 0 before. */
    long acceptedMillis() {
        return acceptedMillis;
    }

    /**
     * Queues {@code bytes} on the writer; dropped once a write failed or the connection ended its writer. Bytes that
     * would take what waits on the writer, each packet counted at its length and a fixed overhead, past the broker's
     * {@code maxBacklogBytes} are dropped, and the connection is ended with DISCONNECT 0x97, counted: off the caller's
     * locks, as a send may hold the session lock that an end takes.
     */
    void send(final byte[] bytes) {
        if (writeFailed || overBacklog.get()) {
            return;
        }
        final long held = held(bytes);
        if (pendingBytes.addAndGet(held) > broker.options().maxBacklogBytes()) {
            pendingBytes.addAndGet(-held);
            if (overBacklog.compareAndSet(false, true)) {
                broker.backlogDisconnects.increment();
                LOG.log(System.Logger.Level.DEBUG, "connection backlog full [client=" + clientId + ", connection="
                        + number + ", bytes=" + pendingBytes.get() + ", max=" + broker.options().maxBacklogBytes()
                        + ']');
                broker.offLocks(() -> end(End.WILL, Packet.QUOTA_EXCEEDED));
            }
            return;
        }
        try {
            writer.execute(() -> write(bytes));
        } catch (final RejectedExecutionException _) {
            // the writer stopped with the connection: what it would have sent goes nowhere
            pendingBytes.addAndGet(-held);
        }
    }

    /** The bytes waiting on the writer, each packet's overhead included. */
    long pendingBytes() {
        return pendingBytes.get();
    }

    /**
     * Ends the connection, once: {@code cause} decides whether its will is published, {@code disconnectCode} the
     * DISCONNECT sent before the socket closes ({@link #NO_DISCONNECT} for none). Every later call does nothing.
     */
    void end(final End cause, final int disconnectCode) {
        if (!endCause.compareAndSet(null, cause)) {
            return;
        }
        final Session s = session;
        finish(disconnectCode);
        LOG.log(System.Logger.Level.DEBUG, "connection ended [client=" + clientId + ", connection=" + number
                + ", cause=" + cause + ", disconnectCode=" + disconnectCode + ']');
        if (s != null && cause != End.SHUTDOWN) {
            broker.ended(this, s, cause != End.NORMAL);
        }
    }

    /**
     * Ends the connection because another took its session over (§3.1.4): DISCONNECT 0x8E, then the socket closes.
     * The caller, holding the broker's session lock, detaches it from the session. False when it had already ended.
     */
    boolean takenOver() {
        return endHeld(Packet.SESSION_TAKEN_OVER);
    }

    /**
     * Ends the connection, its will kept, with DISCONNECT {@code disconnectCode} ({@link #NO_DISCONNECT} for none),
     * then the socket closes. The caller, holding the broker's session lock, detaches it from its session. False when
     * it had already ended.
     */
    boolean endHeld(final int disconnectCode) {
        if (!endCause.compareAndSet(null, End.WILL)) {
            return false;
        }
        finish(disconnectCode);
        LOG.log(System.Logger.Level.DEBUG, "connection ended by the broker [client=" + clientId + ", connection="
                + number + ", disconnectCode=" + disconnectCode + ']');
        return true;
    }

    /** Closes the socket and stops both strands without waiting: for a writer stuck on a client that does not read. */
    void forceClose() {
        closeSocket();
        writer.shutdownNow();
        reader.shutdownNow();
    }

    ExecutorService reader() {
        return reader;
    }

    ExecutorService writer() {
        return writer;
    }

    /** A packet the broker builds: one it cannot write is a defect here, not the client's. */
    static byte[] encode(final Packet packet) {
        try {
            return Codec.encode(packet);
        } catch (final MqttException e) {
            throw new IllegalStateException("packet not writable [packet=" + packet + ']', e);
        }
    }

    /** {@code p} as a remote connection of {@code machine} sends it: each {@link Names#ORIGIN} dropped, one added. */
    static Packet.Publish stamped(final Packet.Publish p, final String machine) {
        final Packet.Meta meta = p.meta();
        final List<Packet.UserProperty> kept = new ArrayList<>(meta.userProperties().size() + 1);
        for (final Packet.UserProperty u : meta.userProperties()) {
            if (!u.name().equals(Names.ORIGIN)) {
                kept.add(u);
            }
        }
        kept.add(new Packet.UserProperty(Names.ORIGIN, InstanceName.origin(machine)));
        return p.withMeta(new Packet.Meta(meta.payloadFormat(), meta.messageExpirySeconds(), meta.contentType(),
                kept));
    }

    /** What {@code bytes} hold while they wait on the writer. */
    private static long held(final byte[] bytes) {
        return bytes.length + PACKET_OVERHEAD_BYTES;
    }

    /** A code the codec failed a packet with, as a DISCONNECT may carry it (§3.14.2.1); 0x82 when it may not. */
    private static int disconnectCode(final int code) {
        return switch (code) {
            case Packet.MALFORMED, Packet.PROTOCOL_ERROR, Packet.TOPIC_ALIAS_INVALID, Packet.PACKET_TOO_LARGE,
                    Packet.SUBSCRIPTION_IDS_NOT_SUPPORTED -> code;
            default -> Packet.PROTOCOL_ERROR;
        };
    }

    /** A code the codec failed a CONNECT with, as a CONNACK may carry it (§3.2.2.2); 0x80 when it may not. */
    private static int connectCode(final int code) {
        return switch (code) {
            case Packet.MALFORMED, Packet.PROTOCOL_ERROR, Packet.UNSUPPORTED_PROTOCOL_VERSION,
                    Packet.BAD_USER_NAME_OR_PASSWORD, Packet.BAD_AUTHENTICATION_METHOD, Packet.PACKET_TOO_LARGE -> code;
            default -> Packet.UNSPECIFIED;
        };
    }

    /** An internal subscriber's code as a PUBACK may carry it (§3.4.2.1); 0x80 when it may not. */
    private static int pubAckCode(final int code) {
        return switch (code) {
            case Packet.SUCCESS, Packet.NO_MATCHING_SUBSCRIBERS, Packet.UNSPECIFIED,
                    Packet.IMPLEMENTATION_SPECIFIC_ERROR, Packet.NOT_AUTHORIZED, Packet.TOPIC_NAME_INVALID,
                    Packet.PACKET_ID_IN_USE, Packet.QUOTA_EXCEEDED, Packet.PAYLOAD_FORMAT_INVALID -> code;
            default -> Packet.UNSPECIFIED;
        };
    }

    /** The reader strand's one task: the CONNECT, then every packet until the connection ends. */
    private void read() {
        boolean connected = false;
        try {
            if (listen != null && !handshake()) {
                return;
            }
            socket.setSoTimeout(CONNECT_WAIT_MILLIS);
            connected = connect();
            while (connected && !ended()) {
                final int header = in.read();
                if (header < 0) {
                    end(End.WILL, NO_DISCONNECT);
                    return;
                }
                if (!handle(Codec.readFromClient(in, header, broker.options().maximumPacketSize()))) {
                    return;
                }
            }
        } catch (final SocketTimeoutException _) {
            end(End.WILL, connected ? Packet.KEEP_ALIVE_TIMEOUT : NO_DISCONNECT);
        } catch (final MqttException e) {
            end(End.WILL, connected ? disconnectCode(e.reasonCode()) : NO_DISCONNECT);
        } catch (final IOException _) {
            end(End.WILL, NO_DISCONNECT);
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "connection failed [client=" + clientId + ", connection=" + number
                    + ']', e);
            end(End.WILL, connected ? Packet.UNSPECIFIED : NO_DISCONNECT);
        } catch (final Error e) {
            // the connection may be half way through a packet: it ends, so no client is left on a reader that stopped
            LOG.log(System.Logger.Level.ERROR, "connection reader failed [client=" + clientId + ", connection="
                    + number + ']', e);
            end(End.WILL, connected ? Packet.UNSPECIFIED : NO_DISCONNECT);
            throw e;
        } finally {
            reader.shutdown();
        }
    }

    /**
     * A remote connection's TLS handshake, within its listener's bound from the accept: true once the peer's
     * certificate names a paired machine, the connection's principal from then on. A handshake that fails or outlasts
     * the bound (the per-read timeout, or the broker's deadline closing the socket), and a peer whose key no machine is
     * paired with, are counted by the broker and end the connection unanswered.
     */
    private boolean handshake() throws IOException {
        if (!(socket instanceof final SSLSocket tls)) {
            throw new IllegalStateException("remote connection without TLS [connection=" + number + ']');
        }
        socket.setSoTimeout(listen.handshakeMillis());
        final Certificate[] chain;
        try {
            tls.startHandshake();
            chain = tls.getSession().getPeerCertificates();
            if (!handshakeSettled.compareAndSet(false, true)) {
                throw new SocketTimeoutException("handshake past its bound [handshake=" + listen.handshake() + ']');
            }
        } catch (final IOException e) {
            if (!ended()) {
                broker.handshakeFailed(this, e);
            }
            end(End.WILL, NO_DISCONNECT);
            return false;
        } finally {
            if (handshakeDeadline != null) {
                handshakeDeadline.cancel(false);
            }
        }
        if (chain.length > 0) {
            peerCertificate = chain[0];
        }
        final String machine = chain.length == 0 ? null : listen.machineOf().apply(chain[0]);
        if (machine == null) {
            broker.unpinned(this);
            end(End.WILL, NO_DISCONNECT);
            return false;
        }
        principal = machine;
        return true;
    }

    /**
     * Reads the first packet and hands a CONNECT to the broker: true when the broker accepted it and sent its CONNACK.
     * A CONNECT that cannot be read, or that the broker refuses, is answered with a CONNACK carrying the reason; any
     * other first packet closes the connection unanswered (§3.1.0).
     */
    private boolean connect() throws IOException {
        final int header = in.read();
        if (header < 0) {
            end(End.WILL, NO_DISCONNECT);
            return false;
        }
        final Packet first;
        try {
            first = Codec.readFromClient(in, header, broker.options().maximumPacketSize());
        } catch (final MqttException e) {
            if (header >>> 4 == 1) {
                refuse(connectCode(e.reasonCode()));
            } else {
                end(End.WILL, NO_DISCONNECT);
            }
            return false;
        }
        if (!(first instanceof final Packet.Connect asked)) {
            end(End.WILL, NO_DISCONNECT);
            return false;
        }
        final Packet.Connect c = principal == null || asked.will() == null ? asked : new Packet.Connect(
                asked.clientId(), asked.cleanStart(), asked.keepAliveSeconds(), asked.sessionExpirySeconds(),
                asked.receiveMaximum(), asked.maximumPacketSize(), stamped(asked.will(), principal),
                asked.willDelaySeconds());
        final int code = broker.connect(this, c);
        if (code != Packet.SUCCESS) {
            refuse(code);
            return false;
        }
        socket.setSoTimeout(c.keepAliveSeconds() * KEEP_ALIVE_GRACE_MILLIS);
        return true;
    }

    /** Answers a refused CONNECT with {@code code} (§3.2.2.2) and ends the connection. */
    private void refuse(final int code) {
        send(encode(new Packet.ConnAck(false, code, Packet.DEFAULT_RECEIVE_MAXIMUM,
                Packet.PROTOCOL_MAXIMUM_PACKET_SIZE, 0, Packet.NO_SERVER_KEEP_ALIVE, null, null)));
        LOG.log(System.Logger.Level.DEBUG, "connect refused [connection=" + number + ", code=" + code + ']');
        end(End.WILL, NO_DISCONNECT);
    }

    /** Answers one packet after CONNECT; false when the connection ended with it. */
    private boolean handle(final Packet packet) {
        switch (packet) {
            case Packet.Publish p -> {
                return publish(p);
            }
            case Packet.PubAck a -> session.acknowledged(a.packetId());
            case Packet.Subscribe s -> subscribe(s);
            case Packet.Unsubscribe u -> unsubscribe(u);
            case Packet.PingReq _ -> send(PING_RESPONSE);
            case Packet.Disconnect d -> {
                if (d.sessionExpirySeconds() >= 0 && !session.disconnectExpiry(d.sessionExpirySeconds())) {
                    // §3.14.2.2.2: a non-zero expiry after a CONNECT's 0
                    end(End.WILL, Packet.PROTOCOL_ERROR);
                    return false;
                }
                // 0x00 discards the will; any other code, 0x04 Disconnect with Will Message among them, keeps it
                end(d.reasonCode() == Packet.SUCCESS ? End.NORMAL : End.WILL, NO_DISCONNECT);
                return false;
            }
            case Packet.Connect _, Packet.ConnAck _, Packet.SubAck _, Packet.UnsubAck _, Packet.PingResp _ -> {
                // a second CONNECT (§3.1.0), or a packet only a server sends
                end(End.WILL, Packet.PROTOCOL_ERROR);
                return false;
            }
        }
        return true;
    }

    /**
     * A client PUBLISH: QoS 2 ends the connection with 0x9B, as CONNACK said Maximum QoS 1 (§3.3.1.4); a QoS 1 publish
     * past the broker's receive maximum with 0x93 (§3.3.4). A publish the gate refuses is acknowledged 0x87 and
     * routed nowhere; any other is retained when it asks, routed, and acknowledged once every internal subscriber it
     * reached completed, with the worst of their codes. Acknowledgements go out in the order the publishes came. A
     * remote connection's publish is routed {@link #stamped} with its machine.
     */
    private boolean publish(final Packet.Publish p) {
        if (p.qos() > 1) {
            end(End.WILL, Packet.QOS_NOT_SUPPORTED);
            return false;
        }
        if (p.qos() == 1) {
            synchronized (acks) {
                if (acks.size() >= broker.options().receiveMaximum()) {
                    end(End.WILL, Packet.RECEIVE_MAXIMUM_EXCEEDED);
                    return false;
                }
            }
        }
        final CompletableFuture<Integer> code = broker.mayPublish(principal, clientId, p.topic())
                && (!p.retain() || broker.mayRetain(principal, clientId, p))
                ? broker.accept(principal == null ? p : stamped(p, principal), clientId)
                : CompletableFuture.completedFuture(Packet.NOT_AUTHORIZED);
        if (p.qos() == 1) {
            synchronized (acks) {
                acks.add(new InboundAck(p.packetId(), code));
            }
            code.whenComplete((_, _) -> sendAcks());
        }
        return true;
    }

    /** Sends the PUBACKs at the head of the queue whose codes are known, in arrival order. */
    private void sendAcks() {
        synchronized (acks) {
            while (!acks.isEmpty() && acks.peek().code().isDone()) {
                final InboundAck a = acks.poll();
                send(encode(new Packet.PubAck(a.packetId(), pubAckCode(a.code().join()), null)));
            }
        }
    }

    /**
     * A SUBSCRIBE: a filter past the broker's {@code maxFiltersPerSubscribe} in the packet is refused with 0x97 before
     * anything else is asked of it, a {@code $share/} filter with 0x9E (Shared Subscription Available 0), one deeper
     * than the broker's {@code maxTopicLevels} with 0x8F, one the gate refuses with 0x87, a new one past the session's
     * {@code maxSubscriptions} with 0x97 (§3.9.3), and any other granted at the QoS asked, at most 1. The SUBACK goes
     * first, then the retained messages each granted filter asks for by its Retain Handling (§3.3.1.3): at every
     * subscribe (0), only when the subscription is new (1), or never (2). A session's SUBSCRIBEs are read by its
     * connection's one reader, so the count it checks is the one it adds to; only while a takeover's new connection
     * overlaps the old one's last SUBSCRIBE can the session pass its bound, by that one SUBSCRIBE's filters.
     */
    private void subscribe(final Packet.Subscribe s) {
        final List<Integer> codes = new ArrayList<>(s.subscriptions().size());
        final List<Packet.Subscription> replay = new ArrayList<>();
        final int maxFilters = broker.options().maxFiltersPerSubscribe();
        for (final Packet.Subscription sub : s.subscriptions()) {
            if (codes.size() >= maxFilters) {
                broker.refusedSubscriptions.increment();
                codes.add(Packet.QUOTA_EXCEEDED);
            } else if (sub.filter().startsWith(Broker.SHARED)) {
                codes.add(Packet.SHARED_SUBSCRIPTIONS_NOT_SUPPORTED);
            } else if (Topics.levels(sub.filter()) > broker.options().maxTopicLevels()) {
                broker.refusedTopics.increment();
                codes.add(Packet.TOPIC_FILTER_INVALID);
            } else if (!broker.maySubscribe(principal, clientId, sub.filter())) {
                codes.add(Packet.NOT_AUTHORIZED);
            } else if (broker.subscriptionsFull(clientId, sub.filter())) {
                broker.refusedSubscriptions.increment();
                codes.add(Packet.QUOTA_EXCEEDED);
            } else {
                final int granted = Math.min(sub.qos(), Broker.MAXIMUM_QOS);
                final boolean existed = broker.subscribeClient(clientId, sub, granted);
                codes.add(granted);
                if (sub.retainHandling() == 0 || sub.retainHandling() == 1 && !existed) {
                    replay.add(new Packet.Subscription(sub.filter(), granted));
                }
            }
        }
        send(encode(new Packet.SubAck(s.packetId(), codes, null)));
        for (final Packet.Subscription sub : replay) {
            for (final Session.Queued q : broker.retained(sub.filter(), sub.qos())) {
                session.offer(q);
            }
        }
    }

    /** An UNSUBSCRIBE: 0x00 for each filter the client had, 0x11 for each it had not (§3.11.3). */
    private void unsubscribe(final Packet.Unsubscribe u) {
        final List<Integer> codes = new ArrayList<>(u.filters().size());
        for (final String filter : u.filters()) {
            codes.add(broker.unsubscribeClient(clientId, filter) ? Packet.SUCCESS : Packet.NO_SUBSCRIPTION_EXISTED);
        }
        send(encode(new Packet.UnsubAck(u.packetId(), codes, null)));
    }

    /** The writer strand's task for one packet: written, and the stream flushed when nothing else waits. */
    private void write(final byte[] bytes) {
        try {
            out.write(bytes);
            if (pendingBytes.addAndGet(-held(bytes)) == 0) {
                out.flush();
            }
        } catch (final IOException e) {
            writeFailed = true;
            LOG.log(System.Logger.Level.DEBUG, "write failed [client=" + clientId + ", connection=" + number
                    + ", cause=" + e + ']');
            closeSocket();
        }
    }

    /** Queues the DISCONNECT, if any, and the socket's close behind everything queued, then stops the writer. */
    private void finish(final int disconnectCode) {
        endedAtNanos = System.nanoTime();
        try {
            writer.execute(() -> {
                try {
                    if (disconnectCode != NO_DISCONNECT && !writeFailed) {
                        out.write(encode(new Packet.Disconnect(disconnectCode, null)));
                    }
                    out.flush();
                } catch (final IOException e) {
                    LOG.log(System.Logger.Level.DEBUG, "disconnect not sent [client=" + clientId + ", connection="
                            + number + ", cause=" + e + ']');
                } finally {
                    closeSocket();
                    broker.closed(this);
                }
            });
        } catch (final RejectedExecutionException _) {
            closeSocket();
            broker.closed(this);
        }
        writer.shutdown();
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "socket close failed [connection=" + number + ", cause=" + e + ']');
        }
    }

    /** Why a connection ends, which decides its will: kept for every end but a clean one and the broker's close. */
    enum End {
        /** The client's DISCONNECT 0x00: the will is discarded. */
        NORMAL,
        /** Any other end the client or the network causes: the will is published. */
        WILL,
        /** The broker closes: no will, no presence. */
        SHUTDOWN
    }

    /** A client QoS 1 publish waiting for its PUBACK's code. */
    private record InboundAck(int packetId, CompletableFuture<Integer> code) {
    }
}
