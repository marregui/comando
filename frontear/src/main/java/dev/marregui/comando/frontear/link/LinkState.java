package dev.marregui.comando.frontear.link;

/**
 * A link at one moment: whether the peer accepted its connection, the messages {@code queued} in its log between the
 * {@code cursor} (the next to deliver) and the {@code tail} (the next offset an append takes), and since the link
 * started the messages {@code sent} to the peer, {@code dropped} (refused by the peer for good, expired, past the hop
 * limit, or a QoS 0 message, the broker's or the peer's, over the link's queue bound) and {@code reordered}: the
 * messages the peer refused for now (0x97, 0x89) and took only after it had accepted a later one of the same window,
 * so the peer holds that later message before the refused one. {@code queuedDeliveries} is how many of the broker's
 * messages wait for the link to append them, beside {@code queueMax}, the bound past which one is refused
 * ({@link LinkOptions#queueMax}).
 */
public record LinkState(String id, boolean connected, long queued, long cursor, long tail, long sent, long dropped,
        long reordered, int queuedDeliveries, int queueMax) {

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
