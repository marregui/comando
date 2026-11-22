package dev.marregui.comando.frontear.octopus;

import org.jetbrains.annotations.TestOnly;

/**
 * A link at one moment: whether the peer accepted its connection, the messages {@code queued} in its log between the
 * {@code cursor} (the next to deliver) and the {@code tail} (the next offset an append takes), and since the link
 * started the messages {@code sent} to the peer, {@code dropped} (refused by the peer for good, expired, past the hop
 * limit, past the backlog's bounds while the link was down, or a QoS 0 message, the gateway's or the peer's, over the
 * link's queue bound) and {@code reordered}: the messages the peer refused for now (0x97, 0x89) and took only after it
 * had accepted a later one of the same window, so the peer holds that later message before the refused one.
 * {@code queuedDeliveries} is how many of the gateway's messages wait for the link to append them, beside
 * {@code queueMax}, the count past which one is refused ({@link LinkOptions#queueMax}); the queue's weight and wait
 * bounds refuse earlier, and those refusals show in {@code refused} and {@code dropped}. {@code reason} says why a link
 * is down: empty while it is up or connecting, else its last failure, or that a TLS link waits for a TLS context.
 * {@code refused} counts the gateway's QoS 1 messages the link answered 0x97 since it started: its queue, its log or
 * its backlog was full. {@code coalesced} counts the retained messages never sent because a newer one on the same
 * topic was queued after them. {@code backlogBytes} is what the records between the cursor and the tail take in the
 * log, beside {@code maxBacklogBytes} ({@link LinkOptions#maxBacklogBytes}); {@code backlogGrowing} warns that the link
 * has been down for over five minutes and its queue grew meanwhile.
 */
public record LinkState(String id, boolean connected, long queued, long cursor, long tail, long sent, long dropped,
        long reordered, int queuedDeliveries, int queueMax, String reason, long refused, long coalesced,
        long backlogBytes, long maxBacklogBytes, boolean backlogGrowing) {

    /** A state with nothing refused, coalesced or in the backlog under the default bound, the shape before them. */
    public LinkState(final String id, final boolean connected, final long queued, final long cursor, final long tail,
            final long sent, final long dropped, final long reordered, final int queuedDeliveries, final int queueMax,
            final String reason) {
        this(id, connected, queued, cursor, tail, sent, dropped, reordered, queuedDeliveries, queueMax, reason, 0, 0,
                0, LinkOptions.defaults().maxBacklogBytes(), false);
    }

    /** A state with no reason given, the shape before the reason was shown. */
    @TestOnly
    public LinkState(final String id, final boolean connected, final long queued, final long cursor, final long tail,
            final long sent, final long dropped, final long reordered, final int queuedDeliveries, final int queueMax) {
        this(id, connected, queued, cursor, tail, sent, dropped, reordered, queuedDeliveries, queueMax, "");
    }

    /** A state with no deliveries waiting under the default bound, the shape before the deliveries were shown. */
    public LinkState(final String id, final boolean connected, final long queued, final long cursor, final long tail,
            final long sent, final long dropped, final long reordered) {
        this(id, connected, queued, cursor, tail, sent, dropped, reordered, 0, LinkOptions.defaults().queueMax());
    }

    /** A state with no reordering, the shape before {@code reordered} was counted. */
    public LinkState(final String id, final boolean connected, final long queued, final long cursor, final long tail,
            final long sent, final long dropped) {
        this(id, connected, queued, cursor, tail, sent, dropped, 0);
    }
}
