package dev.marregui.comando.frontear.gateway;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.marregui.comando.frontear.protocol.Codec;
import dev.marregui.comando.frontear.protocol.MqttException;
import dev.marregui.comando.frontear.protocol.Packet;

/**
 * One client's session (§4.1), held in memory while it is connected and, offline, until its expiry: the QoS 1 messages
 * sent and not yet acknowledged, in the order sent and within the client's receive maximum, the QoS 1 messages queued
 * behind them, and the will its last connection left. Its subscriptions are in the broker's tree. What it holds is
 * bounded by the broker's {@code maxQueued} and {@code maxQueuedBytes}. Every method takes the session's lock; none
 * calls out of the broker under it, so a send only queues bytes on the connection's writer.
 */
final class Session {

    /** A session expiry of 0xFFFFFFFF never ends (§3.1.2.11.2). */
    static final long NEVER = 0xFFFF_FFFFL;

    final String clientId;
    private final Broker broker;
    private final Object lock = new Object();
    // the rest is guarded by lock
    // the QoS 1 messages sent and not acknowledged, by packet id in the order sent, as they were before their send
    private final Map<Integer, Queued> inflight = new LinkedHashMap<>();
    // the ids in inflight a new connection has not had again yet, in the order sent (§4.4)
    private final ArrayDeque<Integer> resend = new ArrayDeque<>();
    private final ArrayDeque<Queued> queue = new ArrayDeque<>();
    // the weight of the messages in inflight and queue (Packet.Publish.weight), against the broker's maxQueuedBytes
    private long heldBytes;
    private Connection connection;
    private int receiveMaximum = Packet.DEFAULT_RECEIVE_MAXIMUM;
    private int maximumPacketSize = Packet.PROTOCOL_MAXIMUM_PACKET_SIZE;
    private long expirySeconds;
    private long offlineSinceMillis;
    private Packet.Publish will;
    private long willDelaySeconds;
    private Packet.Publish pendingWill;
    private long pendingWillDueMillis;
    private int nextPacketId = 1;

    Session(final String clientId, final Broker broker) {
        this.clientId = clientId;
        this.broker = broker;
    }

    /**
     * Makes {@code c} this session's connection: queues {@code connAck} on it, then the messages sent and not
     * acknowledged again, as duplicates with their packet ids (§4.4), then the queue, both as far as the new receive
     * maximum lets them (§3.3.4). A delayed will waiting from the last connection is cancelled (§3.1.3.2.2).
     */
    void attach(final Connection c, final Packet.Connect connect, final byte[] connAck) {
        synchronized (lock) {
            connection = c;
            receiveMaximum = connect.receiveMaximum();
            maximumPacketSize = connect.maximumPacketSize();
            expirySeconds = connect.sessionExpirySeconds();
            will = connect.will();
            willDelaySeconds = connect.willDelaySeconds();
            pendingWill = null;
            c.send(connAck);
            resend.clear();
            resend.addAll(inflight.keySet());
            drain();
        }
    }

    /**
     * Ends {@code c}'s hold on this session, when it is still the session's connection; false when another connection
     * took the session over first. A will the connection's end publishes goes to {@code publish}: at once with no
     * delay, else when the delay passes, unless the client returns first (§3.1.3.2.2).
     */
    boolean detach(final Connection c, final boolean publishWill, final List<Packet.Publish> publish) {
        synchronized (lock) {
            if (connection != c) {
                return false;
            }
            connection = null;
            offlineSinceMillis = broker.nowMillis();
            final Packet.Publish w = will;
            will = null;
            if (publishWill && w != null) {
                if (willDelaySeconds == 0) {
                    publish.add(w);
                } else {
                    pendingWill = w;
                    pendingWillDueMillis = offlineSinceMillis + willDelaySeconds * 1_000;
                }
            }
            return true;
        }
    }

    /**
     * Takes the Session Expiry Interval of the client's DISCONNECT in place of its CONNECT's (§3.14.2.2.2); false,
     * the expiry kept, when the CONNECT's was 0 and this one is not, which is a Protocol Error.
     */
    boolean disconnectExpiry(final long seconds) {
        synchronized (lock) {
            if (expirySeconds == 0 && seconds != 0) {
                return false;
            }
            expirySeconds = seconds;
            return true;
        }
    }

    /** Whether the session ends now that its connection ended: a session expiry of 0 (§3.1.2.11.2). */
    boolean endsWithConnection() {
        synchronized (lock) {
            return connection == null && expirySeconds == 0;
        }
    }

    /** Whether the session is offline and its expiry has passed at {@code nowMillis}. */
    boolean expired(final long nowMillis) {
        synchronized (lock) {
            return connection == null && expirySeconds != NEVER
                    && nowMillis - offlineSinceMillis >= expirySeconds * 1_000;
        }
    }

    /** The delayed will due at {@code nowMillis}, taken from the session; null when none is due. */
    Packet.Publish dueWill(final long nowMillis) {
        synchronized (lock) {
            if (pendingWill == null || nowMillis - pendingWillDueMillis < 0) {
                return null;
            }
            final Packet.Publish w = pendingWill;
            pendingWill = null;
            return w;
        }
    }

    /** Ends the session: the delayed will it held, published at the session's end (§3.1.3.2.2), else null. */
    Packet.Publish end() {
        synchronized (lock) {
            final Packet.Publish w = pendingWill;
            pendingWill = null;
            inflight.clear();
            resend.clear();
            queue.clear();
            heldBytes = 0;
            return w;
        }
    }

    /** The connection the session is on, null when offline. */
    Connection connection() {
        synchronized (lock) {
            return connection;
        }
    }

    /** The bytes the session holds for its client, in flight and queued. */
    long heldBytes() {
        synchronized (lock) {
            return heldBytes;
        }
    }

    /**
     * Takes {@code message} for the client, at the QoS it carries. QoS 0 goes out at once when the client is connected,
     * else is dropped. QoS 1 goes out when the client's receive maximum leaves room and nothing waits before it, else
     * joins the queue; past the session's bounds, its expired messages deleted first (§3.3.2.3.3), it is dropped and
     * counted.
     */
    void offer(final Queued message) {
        synchronized (lock) {
            if (message.publish().qos() == 0) {
                if (connection != null) {
                    sendNow(message);
                }
                return;
            }
            final long bytes = message.publish().weight();
            final BrokerOptions options = broker.options();
            if (heldBytes + bytes > options.maxQueuedBytes() || queue.size() >= options.maxQueued()) {
                deleteExpired();
                if (heldBytes + bytes > options.maxQueuedBytes()) {
                    broker.droppedFull.increment();
                    return;
                }
            }
            if (connection != null && queue.isEmpty() && resend.isEmpty() && inFlight() < receiveMaximum) {
                heldBytes += bytes;
                sendNow(message);
            } else if (queue.size() >= options.maxQueued()) {
                broker.droppedFull.increment();
            } else {
                heldBytes += bytes;
                queue.add(message);
            }
        }
    }

    /** The client acknowledged {@code packetId}: its slot in the receive maximum goes to the next message waiting. */
    void acknowledged(final int packetId) {
        synchronized (lock) {
            final Queued acked = inflight.remove(packetId);
            if (acked != null) {
                heldBytes -= acked.publish().weight();
                if (!resend.isEmpty()) {
                    resend.remove(packetId);
                }
                drain();
            }
        }
    }

    /** The seconds {@code message} has left at {@code nowMillis}, 0 once expired; -1 when it does not expire. */
    private static long remainingExpiry(final Queued message, final long nowMillis) {
        final long expirySeconds = message.publish().meta().messageExpirySeconds();
        if (expirySeconds < 0) {
            return -1;
        }
        return Math.max(0, expirySeconds - (nowMillis - message.receivedMillis()) / 1_000);
    }

    /** The messages sent on the current connection and not acknowledged. Under lock. */
    private int inFlight() {
        return inflight.size() - resend.size();
    }

    /**
     * Sends what waits while the client's receive maximum leaves room: first the messages a previous connection did
     * not acknowledge, with their packet ids, then the queue. Under lock.
     */
    private void drain() {
        while (connection != null && !resend.isEmpty() && inFlight() < receiveMaximum) {
            final int id = resend.poll();
            final Queued message = inflight.get(id);
            final byte[] bytes = encode(message, id, true);
            if (bytes == null) {
                inflight.remove(id);
                heldBytes -= message.publish().weight();
            } else {
                connection.send(bytes);
            }
        }
        while (connection != null && resend.isEmpty() && !queue.isEmpty() && inFlight() < receiveMaximum) {
            sendNow(queue.poll());
        }
    }

    /** Deletes the queued messages past their expiry, counted (§3.3.2.3.3). Under lock. */
    private void deleteExpired() {
        final long now = broker.nowMillis();
        final Iterator<Queued> it = queue.iterator();
        while (it.hasNext()) {
            final Queued q = it.next();
            if (remainingExpiry(q, now) == 0) {
                it.remove();
                heldBytes -= q.publish().weight();
                broker.droppedExpired.increment();
            }
        }
    }

    /**
     * Sends {@code message} on the connection, a QoS 1 one under a new packet id kept until its acknowledgement;
     * dropped when {@link #encode(Queued, int, boolean)} refuses it. Under lock.
     */
    private void sendNow(final Queued message) {
        final int id = message.publish().qos() == 0 ? 0 : nextPacketId();
        final byte[] bytes = encode(message, id, false);
        if (bytes == null) {
            if (id != 0) {
                heldBytes -= message.publish().weight();
            }
            return;
        }
        if (id != 0) {
            inflight.put(id, message);
        }
        connection.send(bytes);
    }

    /**
     * {@code message}'s bytes under packet id {@code id}, its message expiry lowered by all the time it waited
     * (§3.3.2.3.3); null, counted, when it expired or is larger than the client's maximum packet size (§3.1.2.25).
     */
    private byte[] encode(final Queued message, final int id, final boolean dup) {
        final Packet.Publish p = message.publish();
        Packet.Meta meta = p.meta();
        if (meta.messageExpirySeconds() >= 0) {
            final long remaining = remainingExpiry(message, broker.nowMillis());
            if (remaining == 0) {
                broker.droppedExpired.increment();
                return null;
            }
            meta = meta.withMessageExpiry(remaining);
        }
        return encode(new Packet.Publish(p.topic(), p.payload(), p.qos(), p.retain(), dup, id, p.responseTopic(),
                p.correlation(), meta));
    }

    /** {@code p}'s bytes; null, counted, when the client's maximum packet size refuses them. */
    private byte[] encode(final Packet.Publish p) {
        try {
            final byte[] bytes = Codec.encode(p);
            if (bytes.length <= maximumPacketSize) {
                return bytes;
            }
        } catch (final MqttException _) {
            // a message the broker read fits a packet; one too long to write is dropped as one too large to send
        }
        broker.droppedOversize.increment();
        return null;
    }

    /** A packet id no message in flight holds; the receive maximum keeps one free. Under lock. */
    private int nextPacketId() {
        while (true) {
            final int id = nextPacketId;
            nextPacketId = id == 65_535 ? 1 : id + 1;
            if (!inflight.containsKey(id)) {
                return id;
            }
        }
    }

    /** A message for one client, as it is to be forwarded, and when the broker received it. */
    record Queued(Packet.Publish publish, long receivedMillis) {
    }
}
