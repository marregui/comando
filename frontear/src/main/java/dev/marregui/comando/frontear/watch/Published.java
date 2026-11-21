package dev.marregui.comando.frontear.watch;

import java.nio.charset.StandardCharsets;

/**
 * A message the broker routed: published by {@code origin} (a client id, or an internal publisher's) on {@code topic},
 * its payload {@code bytes} long, reaching {@code matches} subscriptions, after {@code hops} hops between brokers (-1
 * when its count is not a number). {@code correlation} is a copy of at most {@link Watch#CORRELATION_BYTES} of its
 * correlation data, null when it has none; {@code payload} is the message's own payload, by reference, which nothing
 * writes into after the message is built, so the publisher's thread neither copies nor decodes it ({@link #head} and
 * {@link #text} decode it on the reader's); a payload over {@link Watch#PAYLOAD_BYTES} is kept as a copy of its first
 * that many bytes instead, the one copy, bounded, that keeps the ring's memory bounded.
 */
public record Published(long seq, long atMillis, String origin, String topic, int qos, boolean retain, int bytes,
        int matches, byte[] correlation, byte[] payload, int hops) implements Observation {

    /** The first {@link Watch#HEAD_BYTES} payload bytes decoded as UTF-8, a malformed or cut sequence replaced. */
    public String head() {
        return new String(payload, 0, Math.min(payload.length, Watch.HEAD_BYTES), StandardCharsets.UTF_8);
    }

    /**
     * The payload kept decoded as UTF-8, a malformed or cut sequence replaced: the whole payload when {@link #bytes} is
     * at most {@link Watch#PAYLOAD_BYTES}, else its first that many bytes.
     */
    public String text() {
        return new String(payload, StandardCharsets.UTF_8);
    }
}
