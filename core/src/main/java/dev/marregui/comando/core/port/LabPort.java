package dev.marregui.comando.core.port;

import java.util.List;

/**
 * What this instance's frontear node shows of itself (record 187): its links, their logs, and the packets that went
 * through it, read by the frontear tab and by the {@code frontear_read} tool. Watching costs the node an event per
 * packet; nothing is watched until {@link #open} is first called, which the application does at launch, and from then
 * on it stays on, for each node this instance hosts.
 */
public interface LabPort {

    /** Starts watching the node, when not yet; idempotent, never blocks: the first frame comes through the listener. */
    void open();

    /**
     * Sets what runs on the EDT after the packets or the node's figures changed; at most one run waits at a time, so a
     * burst of changes is one run. Replaces the one set before.
     */
    void listen(Runnable changed);

    /**
     * The node now, and the packets after {@code after} (a {@link Packet#seq}), oldest first, at most {@code max}; with
     * {@code max} 0 the node alone. Never blocks on the node: it reads what the watch already copied.
     */
    Frame read(long after, int max);

    /** For a window without frontear (the replay tests): no node, no packets. */
    LabPort NONE = new LabPort() {
        @Override
        public void open() {
        }

        @Override
        public void listen(final Runnable changed) {
        }

        @Override
        public Frame read(final long after, final int max) {
            return new Frame(Node.NONE, List.of(), 0L, after);
        }
    };

    /** What a packet event says happened: published on this node, or one step on a link to a peer. */
    enum Kind { PUBLISHED, APPENDED, SENT, ARRIVED, DROPPED, CONNECTED, DISCONNECTED }

    /**
     * One event. {@code seq} numbers the events this instance kept, from 1, without gaps. A publish has an
     * {@code origin} and no {@code link}; a step has a {@code link} (the peer's id) and no {@code origin}. {@code qos}
     * and {@code hops} are -1 on a step, {@code offset} is -1 where the step has none, {@code topic} is "" on a
     * connect or disconnect; {@code correlation} is hex, "" when none; {@code head} is the start of the payload as
     * text, "" on a step; {@code detail} is "" or the reason a step gives; {@code payload} is the payload as text
     * (UTF-8, a malformed or cut sequence replaced), whole when {@code bytes} is at most 8 KiB, else its first 8 KiB,
     * "" on a step.
     */
    record Packet(
            long seq,
            long atMillis,
            Kind kind,
            String origin,
            String link,
            String topic,
            int qos,
            boolean retain,
            int bytes,
            int hops,
            long offset,
            String correlation,
            String head,
            String detail,
            String payload
    ) {

        /** A packet without its payload's text: a step, which carries none. */
        public Packet(final long seq, final long atMillis, final Kind kind, final String origin, final String link,
                final String topic, final int qos, final boolean retain, final int bytes, final int hops,
                final long offset, final String correlation, final String head, final String detail) {
            this(seq, atMillis, kind, origin, link, topic, qos, retain, bytes, hops, offset, correlation, head, detail,
                    "");
        }
    }

    /**
     * The node and the packets of one read. {@code missed} counts the packets after the asked {@code after} that are no
     * longer kept; {@code last} is the highest {@code seq} returned, else the {@code after} asked.
     */
    record Frame(Node node, List<Packet> packets, long missed, long last) {
    }

    /**
     * The node at {@code atMillis}: {@code up} false when this instance runs none, the rest then empty. Its links and
     * logs are one per peer, by the peer's id.
     */
    record Node(
            String name,
            int port,
            long atMillis,
            boolean up,
            Totals totals,
            List<Link> links,
            List<Log> logs,
            Counts counts
    ) {
        public static final Node NONE = new Node("", 0, 0L, false, Totals.NONE, List.of(), List.of(), Counts.NONE);
    }

    /** The gateway's own figures; {@code refused} sums the retained messages, topics and subscriptions it refused. */
    record Totals(
            int sessions,
            int connections,
            int retained,
            long retainedBytes,
            long droppedFull,
            long droppedExpired,
            long droppedOversize,
            long refused,
            long backlogDisconnects
    ) {
        public static final Totals NONE = new Totals(0, 0, 0, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    /**
     * One link to a peer: the octopus's figures, the rates of its SENT packets over the last five seconds, and its last
     * connect and disconnect ({@code 0} for none) with the reason the last of them gave.
     */
    record Link(
            String id,
            boolean connected,
            long queued,
            long cursor,
            long tail,
            long sent,
            long dropped,
            long reordered,
            int queuedDeliveries,
            int queueMax,
            double messagesPerSecond,
            double bytesPerSecond,
            long lastUpMillis,
            long lastDownMillis,
            String lastDetail
    ) {
    }

    /**
     * One link's log: how far the peer has read ({@code cursor}) of what was written ({@code tail}), its size, and its
     * force rounds: per second over the last five seconds, the last one's records, bytes and time, and the median and
     * 99th percentile time of the last 200.
     */
    record Log(
            String link,
            long cursor,
            long tail,
            long bytes,
            int segments,
            double roundsPerSecond,
            int lastRecords,
            long lastBytes,
            double lastMillis,
            double p50Millis,
            double p99Millis
    ) {
    }

    /**
     * What this instance lost of the watch: packets the window let go of ({@code windowDropped}), packets the node's
     * ring overwrote before they were read ({@code ringMissed}), and the database's rows written and dropped, at a full
     * queue or while it was down.
     */
    record Counts(long windowDropped, long ringMissed, long sinkWritten, long sinkDroppedFull, long sinkDroppedDown) {
        public static final Counts NONE = new Counts(0L, 0L, 0L, 0L, 0L);
    }
}
