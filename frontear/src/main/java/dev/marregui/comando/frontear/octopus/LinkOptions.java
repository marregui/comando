package dev.marregui.comando.frontear.octopus;

import java.time.Duration;
import java.util.Objects;

import dev.marregui.comando.frontear.protocol.MqttClient;
import dev.marregui.comando.frontear.protocol.Packet;
import dev.marregui.comando.frontear.store.LogOptions;

/**
 * The bounds every link of an octopus holds to. The gateway's messages wait for the link's strand to append them to
 * the log within three bounds: at most {@code queueMax} of them, at most {@code maxQueueBytes} of their weight
 * ({@link Packet.Publish#weight}), and none while the oldest waiting has waited past {@code maxQueueWait}; past any of
 * them a QoS 0 message is dropped and counted in {@link LinkState#dropped}, a QoS 1 one answered 0x97 and counted in
 * {@link LinkState#refused}. The wait bound sizes the queue by the load: it holds what arrives while the strand is
 * {@code maxQueueWait} behind, whatever the rate, so a burst the strand takes in time is never refused for its count
 * and a strand that falls behind refuses within that wait. {@code queueMax} is also the most QoS 0 messages from the
 * peer that wait for the strand to publish them. {@code windowMax} is the most messages a link keeps
 * in flight to its peer, whatever the peer's Receive Maximum; it is at most the client's {@link MqttClient#QUEUE_MAX},
 * so the sends of a go-back fit in the client's publish queue. {@code maxBacklogBytes} is the most record bytes the
 * link's log holds between its cursor and its tail: past it a link that is down drops its oldest records, counted in
 * {@link LinkState#dropped}, and one that is up answers the new message 0x97. {@code maxBacklogAge} is how long a
 * record may wait while the link is down: an older one is dropped and counted the same way.
 */
public record LinkOptions(int queueMax, int windowMax, long maxBacklogBytes, Duration maxBacklogAge,
        long maxQueueBytes, Duration maxQueueWait) {

    // a memory bound behind the two below: 200 ms of 80,000 messages a second, past the highest rate measured on a
    // link (task 232); at 1,000, under one publisher's Receive Maximum of 1,024, 80,000 a second drew 283,080 0x97
    private static final int DEFAULT_QUEUE_MAX = 16_384;
    // unmeasured: the client's publish queue bound, the most a go-back can send again at once without a refusal
    private static final int DEFAULT_WINDOW_MAX = MqttClient.QUEUE_MAX;
    // a day of a peer's presence and replies at the lab's rates fits many times over; chosen in task 232
    private static final long DEFAULT_MAX_BACKLOG_BYTES = 64L << 20;
    // a peer down for a day comes back to state newer than anything queued that long ago; chosen in task 232
    private static final Duration DEFAULT_MAX_BACKLOG_AGE = Duration.ofHours(24);
    // one round of the link log, LogOptions.MAX_ROUND_BYTES: what waits is at most what one force takes
    private static final long DEFAULT_MAX_QUEUE_BYTES = LogOptions.MAX_ROUND_BYTES;
    // chosen in task 234: the p99 at 60,000 messages a second was 30.7 ms (task 232), so a strand 50 ms behind is
    // past what it does at the highest rate it keeps up with
    private static final Duration DEFAULT_MAX_QUEUE_WAIT = Duration.ofMillis(50);

    /**
     * Refuses an empty queue, a window that is empty or larger than the client's publish queue, an empty backlog or
     * queue weight, and an age or wait bound that is not positive.
     */
    public LinkOptions {
        Objects.requireNonNull(maxBacklogAge, "maxBacklogAge");
        Objects.requireNonNull(maxQueueWait, "maxQueueWait");
        if (queueMax < 1) {
            throw new IllegalArgumentException("refuse empty link queue [queueMax=" + queueMax + ']');
        }
        if (windowMax < 1 || windowMax > MqttClient.QUEUE_MAX) {
            throw new IllegalArgumentException("refuse link window out of range [windowMax=" + windowMax
                    + ", maximum=" + MqttClient.QUEUE_MAX + ']');
        }
        if (maxBacklogBytes < 1) {
            throw new IllegalArgumentException("refuse empty link backlog [maxBacklogBytes=" + maxBacklogBytes + ']');
        }
        if (maxBacklogAge.isNegative() || maxBacklogAge.isZero()) {
            throw new IllegalArgumentException("refuse link backlog age that is not positive [maxBacklogAge="
                    + maxBacklogAge + ']');
        }
        if (maxQueueBytes < 1) {
            throw new IllegalArgumentException("refuse empty link queue weight [maxQueueBytes=" + maxQueueBytes + ']');
        }
        if (maxQueueWait.isNegative() || maxQueueWait.isZero()) {
            throw new IllegalArgumentException("refuse link queue wait that is not positive [maxQueueWait="
                    + maxQueueWait + ']');
        }
    }

    /** As the canonical constructor, the queue's weight and wait bounded by the defaults. */
    public LinkOptions(final int queueMax, final int windowMax, final long maxBacklogBytes,
            final Duration maxBacklogAge) {
        this(queueMax, windowMax, maxBacklogBytes, maxBacklogAge, DEFAULT_MAX_QUEUE_BYTES, DEFAULT_MAX_QUEUE_WAIT);
    }

    /** As the canonical constructor, the backlog bounded by the defaults. */
    public LinkOptions(final int queueMax, final int windowMax) {
        this(queueMax, windowMax, DEFAULT_MAX_BACKLOG_BYTES, DEFAULT_MAX_BACKLOG_AGE);
    }

    /** The defaults. */
    public static LinkOptions defaults() {
        return new LinkOptions(DEFAULT_QUEUE_MAX, DEFAULT_WINDOW_MAX);
    }
}
