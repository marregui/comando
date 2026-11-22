package dev.marregui.comando.frontear.octopus;

import java.time.Duration;
import java.util.Objects;

import dev.marregui.comando.frontear.protocol.MqttClient;

/**
 * The bounds every link of an octopus holds to. {@code queueMax} is the most messages the gateway hands a link that
 * wait for its strand to append them to the log, and the most QoS 0 messages from its peer that wait for the strand to
 * publish them: past it a QoS 0 message is dropped and counted in {@link LinkState#dropped}, a QoS 1 one from the
 * gateway answered 0x97 and counted in {@link LinkState#refused}. {@code windowMax} is the most messages a link keeps
 * in flight to its peer, whatever the peer's Receive Maximum; it is at most the client's {@link MqttClient#QUEUE_MAX},
 * so the sends of a go-back fit in the client's publish queue. {@code maxBacklogBytes} is the most record bytes the
 * link's log holds between its cursor and its tail: past it a link that is down drops its oldest records, counted in
 * {@link LinkState#dropped}, and one that is up answers the new message 0x97. {@code maxBacklogAge} is how long a
 * record may wait while the link is down: an older one is dropped and counted the same way.
 */
public record LinkOptions(int queueMax, int windowMax, long maxBacklogBytes, Duration maxBacklogAge) {

    // unmeasured: a message waiting holds its payload in memory as one in flight does, so as many as the window
    private static final int DEFAULT_QUEUE_MAX = 1_000;
    // unmeasured: the client's publish queue bound, the most a go-back can send again at once without a refusal
    private static final int DEFAULT_WINDOW_MAX = MqttClient.QUEUE_MAX;
    // a day of a peer's presence and replies at the lab's rates fits many times over; chosen in task 232
    private static final long DEFAULT_MAX_BACKLOG_BYTES = 64L << 20;
    // a peer down for a day comes back to state newer than anything queued that long ago; chosen in task 232
    private static final Duration DEFAULT_MAX_BACKLOG_AGE = Duration.ofHours(24);

    /**
     * Refuses an empty queue, a window that is empty or larger than the client's publish queue, an empty backlog and
     * an age bound that is not positive.
     */
    public LinkOptions {
        Objects.requireNonNull(maxBacklogAge, "maxBacklogAge");
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
