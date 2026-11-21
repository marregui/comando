package dev.marregui.comando.frontear.octopus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import dev.marregui.comando.frontear.protocol.Packet;

/**
 * What a link writes on a message it carries: the hops it has made, in the user property {@link #HOPS}, and, while it
 * waits in a link's log, when it was queued, in {@link #QUEUED}, so its expiry can be decremented by the time it
 * waited (§3.3.2.3.3) after a restart too.
 */
final class Forwarding {

    /** The hops a message has made between brokers, a decimal count; absent before its first. */
    static final String HOPS = "frontear-hops";
    /** When a message joined a link's log, epoch milliseconds; never sent on. */
    static final String QUEUED = "frontear-queued-ms";
    /** The first reconnect waits this long, and each failed attempt doubles it: the contract's backoff. */
    static final Duration BACKOFF_FIRST = Duration.ofSeconds(1);
    /** The longest a reconnect waits: the contract's backoff. */
    static final Duration BACKOFF_MAX = Duration.ofSeconds(30);
    /**
     * The share of a backoff that is random, taken off it, so links cut together do not reconnect together.
     * Unmeasured: the usual fifth.
     */
    private static final double JITTER = 0.2;
    /** The most digits a hop count may have: nine fit an int whatever they are. */
    private static final int HOPS_DIGITS = 9;

    private Forwarding() {
    }

    /** The hops {@code m} has made, 0 when it carries no count, -1 when its count is not one decimal number. */
    static int hops(final Packet.Publish m) {
        String value = null;
        for (final Packet.UserProperty p : m.meta().userProperties()) {
            if (HOPS.equals(p.name())) {
                if (value != null) {
                    return -1;
                }
                value = p.value();
            }
        }
        if (value == null) {
            return 0;
        }
        if (value.isEmpty() || value.length() > HOPS_DIGITS) {
            return -1;
        }
        int hops = 0;
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            hops = hops * 10 + (c - '0');
        }
        return hops;
    }

    /**
     * {@code m} as a link's log keeps it: at QoS 1 with packet id 1 (the log's offset identifies it), not a duplicate,
     * its hop count {@code hops} and its queue time {@code queuedMillis} in place of any it carried.
     */
    static Packet.Publish queued(final Packet.Publish m, final int hops, final long queuedMillis) {
        return stamped(m, 1, 1, hops, queuedMillis);
    }

    /**
     * {@code m} as it arrived over a link, to publish locally: at its QoS with no packet id, not a duplicate, with hop
     * count {@code hops}.
     */
    static Packet.Publish arrived(final Packet.Publish m, final int hops) {
        return stamped(m, m.qos(), 0, hops, -1);
    }

    /**
     * {@code record} as a link sends it at {@code nowMillis}: without its queue time, its message expiry decremented
     * by the whole seconds it waited; null when it expired while it waited.
     */
    static Packet.Publish outgoing(final Packet.Publish record, final long nowMillis) {
        final List<Packet.UserProperty> kept = new ArrayList<>();
        long queuedMillis = -1;
        for (final Packet.UserProperty p : record.meta().userProperties()) {
            if (QUEUED.equals(p.name())) {
                queuedMillis = millis(p.value());
            } else {
                kept.add(p);
            }
        }
        long expirySeconds = record.meta().messageExpirySeconds();
        if (expirySeconds >= 0 && queuedMillis >= 0) {
            final long waitedMillis = Math.max(0, nowMillis - queuedMillis);
            if (waitedMillis >= expirySeconds * 1_000) {
                return null;
            }
            expirySeconds -= waitedMillis / 1_000;
        }
        final Packet.Meta meta = record.meta();
        return record.withMeta(new Packet.Meta(meta.payloadFormat(), expirySeconds, meta.contentType(), kept));
    }

    /**
     * How long to wait before the next connect after {@code attempts} failed in a row, 0 when an accepted connection
     * ended: {@link #BACKOFF_FIRST} doubling per failure up to {@link #BACKOFF_MAX}, less up to {@link #JITTER} of it
     * as {@code random}, in [0, 1), says.
     */
    static Duration backoff(final int attempts, final double random) {
        // past 15 doublings the shift would only approach overflow; the cap has long been reached
        final int doublings = Math.min(Math.max(0, attempts - 1), 15);
        final long millis = Math.min(BACKOFF_MAX.toMillis(), BACKOFF_FIRST.toMillis() << doublings);
        return Duration.ofMillis(millis - (long) (millis * JITTER * random));
    }

    private static Packet.Publish stamped(final Packet.Publish m, final int qos, final int packetId, final int hops,
            final long queuedMillis) {
        final List<Packet.UserProperty> properties = new ArrayList<>();
        for (final Packet.UserProperty p : m.meta().userProperties()) {
            if (!HOPS.equals(p.name()) && !QUEUED.equals(p.name())) {
                properties.add(p);
            }
        }
        properties.add(new Packet.UserProperty(HOPS, Integer.toString(hops)));
        if (queuedMillis >= 0) {
            properties.add(new Packet.UserProperty(QUEUED, Long.toString(queuedMillis)));
        }
        final Packet.Meta meta = m.meta();
        return new Packet.Publish(m.topic(), m.payload(), qos, m.retain(), false, packetId, m.responseTopic(),
                m.correlation(), new Packet.Meta(meta.payloadFormat(), meta.messageExpirySeconds(),
                        meta.contentType(), properties));
    }

    /** {@code value} as epoch milliseconds, -1 when it is not a non-negative decimal number. */
    private static long millis(final String value) {
        try {
            final long millis = Long.parseLong(value);
            return millis < 0 ? -1 : millis;
        } catch (final NumberFormatException _) {
            return -1;
        }
    }
}
