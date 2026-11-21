package dev.marregui.comando.frontear.octopus;

import dev.marregui.comando.frontear.protocol.MqttClient;

/**
 * The bounds every link of a mesh holds to. {@code queueMax} is the most messages the broker hands a link that wait
 * for its strand to append them to the log, and the most QoS 0 messages from its peer that wait for the strand to
 * publish them: past it a QoS 0 message is dropped and counted in {@link LinkState#dropped}, a QoS 1 one from the
 * broker answered 0x97. {@code windowMax} is the most messages a link keeps in flight
 * to its peer, whatever the peer's Receive Maximum; it is at most the client's {@link MqttClient#QUEUE_MAX}, so the
 * sends of a go-back fit in the client's publish queue.
 */
public record LinkOptions(int queueMax, int windowMax) {

    // unmeasured: a message waiting holds its payload in memory as one in flight does, so as many as the window
    private static final int DEFAULT_QUEUE_MAX = 1_000;
    // unmeasured: the client's publish queue bound, the most a go-back can send again at once without a refusal
    private static final int DEFAULT_WINDOW_MAX = MqttClient.QUEUE_MAX;

    /** Refuses an empty queue, and a window that is empty or larger than the client's publish queue. */
    public LinkOptions {
        if (queueMax < 1) {
            throw new IllegalArgumentException("refuse empty link queue [queueMax=" + queueMax + ']');
        }
        if (windowMax < 1 || windowMax > MqttClient.QUEUE_MAX) {
            throw new IllegalArgumentException("refuse link window out of range [windowMax=" + windowMax
                    + ", maximum=" + MqttClient.QUEUE_MAX + ']');
        }
    }

    /** The defaults. */
    public static LinkOptions defaults() {
        return new LinkOptions(DEFAULT_QUEUE_MAX, DEFAULT_WINDOW_MAX);
    }
}
