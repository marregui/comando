package dev.marregui.comando.frontear.watch;

/**
 * A message the broker routed: published by {@code origin} (a client id, or an internal publisher's) on {@code topic},
 * its payload {@code bytes} long, reaching {@code matches} subscriptions, after {@code hops} hops between brokers (-1
 * when its count is not a number). {@code correlation} is a copy of at most {@link Watch#CORRELATION_BYTES} of its
 * correlation data, null when it has none; {@code head} its first {@link Watch#HEAD_BYTES} payload bytes decoded as
 * UTF-8, a malformed or cut sequence replaced.
 */
public record Published(long seq, long atMillis, String origin, String topic, int qos, boolean retain, int bytes,
        int matches, byte[] correlation, String head, int hops) implements Observation {
}
