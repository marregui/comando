package dev.marregui.comando.frontear.broker;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;

import dev.marregui.comando.frontear.mqtt.Packet;

/**
 * Where a {@link Broker} listens and the limits it states in CONNACK and holds to. {@code receiveMaximum} is the QoS 1
 * publishes a client may leave unacknowledged (§3.2.2.3.3); {@code maximumPacketSize} the largest packet the broker
 * reads (§3.2.2.3.6); {@code maxSessions} the sessions it keeps, connected or not, before it refuses a new client
 * with 0x97; {@code maxQueued} the QoS 1 messages a session holds beyond its client's receive maximum, the newest
 * dropped and counted when full. {@code clock} is what message expiry, will delay and session expiry are measured
 * against: it must not step backwards, as wall-clock time can. The byte bounds count what holding a message costs,
 * {@link Packet.Publish#weight()}: its payload, topic, response topic, correlation data, content type and user
 * properties, and a fixed overhead for the message and for each property. {@code maxQueuedBytes} bounds that weight
 * for the messages a session holds for its client, queued and in flight, the newest message dropped and counted past
 * it; {@code maxBacklogBytes} the encoded bytes a connection's writer holds unsent, with a fixed overhead per packet,
 * past which the connection is ended with DISCONNECT 0x97; {@code maxRetained} and {@code maxRetainedBytes} the
 * retained messages and their weight, a retained PUBLISH past either refused with 0x97; {@code maxTopicLevels} the
 * levels of a topic name or filter, deeper ones refused with 0x90 or 0x8F. {@code maxFiltersPerSubscribe} and
 * {@code maxSubscriptions} bound the filters of one SUBSCRIBE and of one session: a filter past either is refused
 * with 0x97 (§3.9.3), counted.
 */
public record BrokerOptions(InetSocketAddress bind, int receiveMaximum, int maximumPacketSize, int maxSessions,
        InstantSource clock, int maxQueued, long maxQueuedBytes, long maxBacklogBytes, int maxRetained,
        long maxRetainedBytes, int maxTopicLevels, int maxFiltersPerSubscribe, int maxSubscriptions) {

    // frontear's choice for a node: a link sends up to its peer's receive maximum unacknowledged (one window per
    // link), and 1,024 small messages cover a LAN round trip without letting one client hold more of the broker
    private static final int DEFAULT_RECEIVE_MAXIMUM = 1_024;
    // BrokerInteropTest moves 5 MiB payloads; 16 MiB keeps room above that and bounds what one read allocates
    private static final int DEFAULT_MAXIMUM_PACKET_SIZE = 16 * 1024 * 1024;
    // a node serves its own comando instances and its links, tens at most; the bound keeps a client that connects
    // under ever new ids from growing the broker without limit
    private static final int DEFAULT_MAX_SESSIONS = 1_000;
    // an offline session's backlog, as many messages as a client's window; past it the newest is dropped and counted
    private static final int DEFAULT_MAX_QUEUED = 1_000;
    // three of the largest payloads frontear's interop test moves, 5 MiB each, and more; a client that stops reading
    // holds about this much of the heap per session, as the weight counts the objects around what a message carries
    private static final long DEFAULT_MAX_QUEUED_BYTES = 64L * 1024 * 1024;
    // the same budget for a connection's unsent bytes, each packet's queue overhead counted: a client that stops
    // reading is ended before it can hold more
    private static final long DEFAULT_MAX_BACKLOG_BYTES = 64L * 1024 * 1024;
    // frontear retains a presence per session and a state per component, a few thousand at most; the count keeps a
    // client that retains under ever new topics from growing the store without limit
    private static final int DEFAULT_MAX_RETAINED = 10_000;
    // the retained store's share of the heap, the same budget as one session's queue
    private static final long DEFAULT_MAX_RETAINED_BYTES = 64L * 1024 * 1024;
    // frontear's own topics are under 10 levels deep; 128 leaves room for any client's and bounds the work and the
    // recursion one topic costs the subscription tree
    private static final int DEFAULT_MAX_TOPIC_LEVELS = 128;
    // a client subscribes a few filters at a time, and a link its configured list; each granted filter costs a scan
    // of the retained store for its replay, so one SUBSCRIBE holds its reader for at most 256 scans
    private static final int DEFAULT_MAX_FILTERS_PER_SUBSCRIBE = 256;
    // the tree copies a subscriber's set of filters on each add, so its n-th costs n: one SUBSCRIBE of 5,000 new
    // filters took 928 ms on a laptop, growing with the square, so a session's 1,000 cost about a 25th of that
    private static final int DEFAULT_MAX_SUBSCRIPTIONS = 1_000;

    /** Options with the default {@code maxQueued} and byte bounds. */
    public BrokerOptions(final InetSocketAddress bind, final int receiveMaximum, final int maximumPacketSize,
            final int maxSessions, final InstantSource clock) {
        this(bind, receiveMaximum, maximumPacketSize, maxSessions, clock, DEFAULT_MAX_QUEUED);
    }

    /** Options with the default byte bounds and subscription caps. */
    public BrokerOptions(final InetSocketAddress bind, final int receiveMaximum, final int maximumPacketSize,
            final int maxSessions, final InstantSource clock, final int maxQueued) {
        this(bind, receiveMaximum, maximumPacketSize, maxSessions, clock, maxQueued, DEFAULT_MAX_QUEUED_BYTES,
                DEFAULT_MAX_BACKLOG_BYTES, DEFAULT_MAX_RETAINED, DEFAULT_MAX_RETAINED_BYTES, DEFAULT_MAX_TOPIC_LEVELS,
                DEFAULT_MAX_FILTERS_PER_SUBSCRIBE, DEFAULT_MAX_SUBSCRIPTIONS);
    }

    /** Refuses a limit MQTT cannot state or a broker cannot hold to. */
    public BrokerOptions {
        Objects.requireNonNull(bind, "bind");
        Objects.requireNonNull(clock, "clock");
        check(receiveMaximum >= 1 && receiveMaximum <= 65_535, "receive maximum out of range [value="
                + receiveMaximum + ']');
        check(maximumPacketSize >= 2 && maximumPacketSize <= Packet.PROTOCOL_MAXIMUM_PACKET_SIZE,
                "maximum packet size out of range [bytes=" + maximumPacketSize + ']');
        check(maxSessions >= 1, "max sessions must be positive [value=" + maxSessions + ']');
        check(maxQueued >= 0, "max queued must not be negative [value=" + maxQueued + ']');
        check(maxQueuedBytes >= 0, "max queued bytes must not be negative [bytes=" + maxQueuedBytes + ']');
        check(maxBacklogBytes >= 1, "max backlog bytes must be positive [bytes=" + maxBacklogBytes + ']');
        check(maxRetained >= 0, "max retained must not be negative [value=" + maxRetained + ']');
        check(maxRetainedBytes >= 0, "max retained bytes must not be negative [bytes=" + maxRetainedBytes + ']');
        check(maxTopicLevels >= 1, "max topic levels must be positive [value=" + maxTopicLevels + ']');
        check(maxFiltersPerSubscribe >= 1, "max filters per subscribe must be positive [value="
                + maxFiltersPerSubscribe + ']');
        check(maxSubscriptions >= 0, "max subscriptions must not be negative [value=" + maxSubscriptions + ']');
    }

    /** The defaults on {@code bind}, timed by a clock that does not step backwards. */
    public static BrokerOptions defaults(final InetSocketAddress bind) {
        return new BrokerOptions(bind, DEFAULT_RECEIVE_MAXIMUM, DEFAULT_MAXIMUM_PACKET_SIZE, DEFAULT_MAX_SESSIONS,
                new Monotonic(), DEFAULT_MAX_QUEUED);
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /** The wall-clock instant of its making, advanced by {@link System#nanoTime()}: it never steps backwards. */
    private static final class Monotonic implements InstantSource {

        private final Instant start = Instant.now();
        private final long startNanos = System.nanoTime();

        @Override
        public Instant instant() {
            return start.plusNanos(System.nanoTime() - startNanos);
        }
    }
}
