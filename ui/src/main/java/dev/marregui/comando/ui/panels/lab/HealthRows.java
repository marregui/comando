package dev.marregui.comando.ui.panels.lab;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongFunction;

import dev.marregui.comando.core.port.LabPort.Counts;
import dev.marregui.comando.core.port.LabPort.Link;
import dev.marregui.comando.core.port.LabPort.Log;
import dev.marregui.comando.core.port.LabPort.Node;
import dev.marregui.comando.core.port.LabPort.Totals;

/**
 * The frontear tab's Health view as plain values: one row per link to a peer, each with the worst trouble it shows and
 * the step that resolves it, worst first; the node's faults in one line. A growing queue and a recent drop are read
 * across successive nodes, so one instance follows one node, a node read at a time. The windows are measured on the
 * node's own clock, the {@code atMillis} of each read, as the port gives no other. Plain values, so the rules are
 * tested without Swing.
 */
public final class HealthRows {

    /** A link that dropped a message within this long is flagged: long enough to be seen at four reads a second. */
    static final long DROP_MILLIS = 60_000L;
    /** A queue that grew over this long while its link was up is backing up: the window of the link's rates. */
    static final long GROWTH_MILLIS = 5_000L;
    /** A 99th percentile force over this is a slow disk: an SSD forces in single milliseconds, ten times under it. */
    static final double SLOW_FORCE_MILLIS = 50.0;

    private static final long HOUR_MILLIS = 3_600_000L;
    private static final long MINUTE_MILLIS = 60_000L;
    private static final Log NO_LOG = new Log("", 0L, 0L, 0L, 0, 0.0, 0, 0L, 0.0, 0.0, 0.0);
    /** A link's mark that no drop has been seen since its first read. */
    private static final long NEVER = Long.MIN_VALUE;

    /** Per peer, its dropped count at the last read and when it last grew, as {dropped, atMillis}. */
    private final Map<String, long[]> drops = new HashMap<>();
    /** Per peer, its queue at each read within {@link #GROWTH_MILLIS}, oldest first, as {atMillis, queued}. */
    private final Map<String, ArrayDeque<long[]>> queues = new HashMap<>();

    /** The rows of {@code node}, worst first, then by peer; reading it also records its queues and drops. */
    public List<Health> read(final Node node) {
        final long nowMillis = node.atMillis();
        final Map<String, Log> logs = new HashMap<>();
        for (final Log g : node.logs()) {
            logs.put(g.link(), g);
        }
        final List<Health> rows = new ArrayList<>();
        for (final Link l : node.links()) {
            final Log log = logs.getOrDefault(l.id(), NO_LOG);
            final boolean growing = growing(l, nowMillis);
            final boolean dropping = dropping(l, nowMillis);
            final Status status = !l.connected() ? Status.DOWN
                    : l.queueMax() > 0 && l.queuedDeliveries() >= l.queueMax() ? Status.LOG_FULL
                    : growing ? Status.BACKING_UP
                    : dropping ? Status.DROPPING
                    : log.p99Millis() > SLOW_FORCE_MILLIS ? Status.SLOW_DISK
                    : Status.OK;
            final long downMillis = status == Status.DOWN && l.lastDownMillis() > 0L
                    ? Math.max(0L, nowMillis - l.lastDownMillis()) : 0L;
            rows.add(new Health(l, log, status, downMillis, step(l, status)));
        }
        // a peer gone from the node takes its history with it
        final List<String> ids = ids(node);
        queues.keySet().retainAll(ids);
        drops.keySet().retainAll(ids);
        rows.sort(Comparator.comparing(Health::status).thenComparing(h -> h.link().id()));
        return rows;
    }

    /**
     * Everything the node shows of {@code h}'s link and log, as the detail pane prints it: its status and step, its
     * last connect or disconnect, then the link's and the log's figures; times through {@code time}.
     */
    public static String detail(final Health h, final LongFunction<String> time) {
        final Link l = h.link();
        final Log g = h.log();
        final StringBuilder b = new StringBuilder();
        b.append(l.id()).append(" · ").append(h.label()).append('\n');
        if (!h.step().isEmpty()) {
            b.append("To resolve: ").append(h.step()).append('\n');
        }
        if (!l.lastDetail().isEmpty()) {
            b.append(l.connected() ? "Last connect: " : "Last disconnect: ").append(l.lastDetail()).append('\n');
        }
        b.append('\n');
        b.append("Link   queued ").append(l.queued()).append(" (cursor ").append(l.cursor()).append(", tail ")
                .append(l.tail()).append(") · sent ").append(l.sent()).append(" · dropped ").append(l.dropped())
                .append(" · reordered ").append(l.reordered()).append('\n');
        b.append("       waiting to append ").append(l.queuedDeliveries()).append(" / ").append(l.queueMax())
                .append(" · ").append(fixed(l.messagesPerSecond(), 1)).append(" msg/s · ")
                .append(fixed(l.bytesPerSecond(), 1)).append(" bytes/s\n");
        b.append("       last up ").append(or(time.apply(l.lastUpMillis()))).append(" · last down ")
                .append(or(time.apply(l.lastDownMillis()))).append('\n');
        b.append("Log    delivered ").append(g.cursor()).append(" / written ").append(g.tail()).append(" · ")
                .append(g.bytes()).append(" bytes in ").append(g.segments()).append(" segments\n");
        b.append("       fsync ").append(fixed(g.roundsPerSecond(), 1)).append(" rounds/s · last ")
                .append(g.lastRecords()).append(" records, ").append(g.lastBytes()).append(" bytes, ")
                .append(fixed(g.lastMillis(), 2)).append(" ms · p50 ").append(fixed(g.p50Millis(), 2))
                .append(" ms · p99 ").append(fixed(g.p99Millis(), 2)).append(" ms");
        return b.toString();
    }

    /**
     * The node's faults in one line, only the counters that are not zero, or "" when there are none: the broker's
     * drops, refusals and backlog disconnects, the packets the watch lost to its window and its ring, and the rows cdb
     * dropped. {@code missed} counts the packets the tab's own reads found gone.
     */
    public static String faults(final Node node, final long missed) {
        final Totals t = node.totals();
        final Counts c = node.counts();
        final List<String> out = new ArrayList<>();
        add(out, "dropped full", t.droppedFull());
        add(out, "dropped expired", t.droppedExpired());
        add(out, "dropped oversize", t.droppedOversize());
        add(out, "refused", t.refused());
        add(out, "backlog disconnects", t.backlogDisconnects());
        add(out, "packets missed", missed + c.ringMissed());
        add(out, "watch window dropped", c.windowDropped());
        add(out, "cdb dropped full", c.sinkDroppedFull());
        add(out, "cdb dropped down", c.sinkDroppedDown());
        return String.join(" · ", out);
    }

    /** {@code millis} as the largest whole unit it holds: {@code 45s}, {@code 3m}, {@code 2h}. */
    static String duration(final long millis) {
        return millis >= HOUR_MILLIS ? millis / HOUR_MILLIS + "h"
                : millis >= MINUTE_MILLIS ? millis / MINUTE_MILLIS + "m" : millis / 1_000L + "s";
    }

    /** Whether {@code l} dropped a message within the last {@link #DROP_MILLIS}; its first read counts none. */
    private boolean dropping(final Link l, final long nowMillis) {
        final long[] d = drops.get(l.id());
        if (d == null) {
            drops.put(l.id(), new long[]{l.dropped(), NEVER});
            return false;
        }
        if (l.dropped() > d[0]) {
            d[1] = nowMillis;
        }
        d[0] = l.dropped();
        return d[1] != NEVER && nowMillis - d[1] < DROP_MILLIS;
    }

    /**
     * Whether {@code l}'s queue grew over the last {@link #GROWTH_MILLIS} while up: above 0 and above what it was at
     * the read that window ago. A link that is down starts its window again.
     */
    private boolean growing(final Link l, final long nowMillis) {
        final ArrayDeque<long[]> q = queues.computeIfAbsent(l.id(), _ -> new ArrayDeque<>());
        if (!l.connected()) {
            q.clear();
            return false;
        }
        q.addLast(new long[]{nowMillis, l.queued()});
        // the reads before the newest one at least the window old are not needed again
        while (q.size() > 1 && nowMillis - secondAtMillis(q) >= GROWTH_MILLIS) {
            q.removeFirst();
        }
        final long[] first = q.getFirst();
        return nowMillis - first[0] >= GROWTH_MILLIS && l.queued() > 0L && l.queued() > first[1];
    }

    private static void add(final List<String> out, final String name, final long count) {
        if (count != 0L) {
            out.add(name + " " + count);
        }
    }

    private static String fixed(final double value, final int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", value);
    }

    private static List<String> ids(final Node node) {
        final List<String> ids = new ArrayList<>();
        for (final Link l : node.links()) {
            ids.add(l.id());
        }
        return ids;
    }

    private static String or(final String time) {
        return time.isEmpty() ? "never" : time;
    }

    private static long secondAtMillis(final ArrayDeque<long[]> q) {
        final Iterator<long[]> it = q.iterator();
        it.next();
        return it.next()[0];
    }

    /** The step that resolves {@code status} on {@code l}: a peer named slug@machine is on another machine. */
    private static String step(final Link l, final Status status) {
        final boolean remote = l.id().contains("@");
        return switch (status) {
            case DOWN -> remote
                    ? "the machine is unreachable: check it runs comando, listens, and has this machine paired "
                    + "(Machines…)"
                    : "instance " + l.id() + " is not running, or its frontear is off: start it";
            case LOG_FULL -> "the broker writes faster than the log takes it: look for a client flooding a topic in "
                    + "Traffic";
            case BACKING_UP -> "the peer takes messages slower than they come: check its sessions are not stuck";
            case DROPPING -> "messages to this peer were given up: Traffic, kind Dropped, gives each reason";
            case SLOW_DISK -> "the log's forces to disk are slow: check the disk's free space and load";
            case OK -> "";
        };
    }

    /** What is wrong with a link, worst first; the order sorts the rows. */
    public enum Status { DOWN, LOG_FULL, BACKING_UP, DROPPING, SLOW_DISK, OK }

    /**
     * One link's health: its figures, its log's ({@code log} all zero when the node keeps none for it), its status, how
     * long it has been down ({@code 0} when up or unknown), and the step that resolves it ("" when ok).
     */
    public record Health(Link link, Log log, Status status, long downMillis, String step) {

        /** The status in a few words, as the table shows it: {@code down 3m}, {@code backing up}, {@code ok}. */
        public String label() {
            return switch (status) {
                case DOWN -> downMillis > 0L ? "down " + duration(downMillis) : "down";
                case LOG_FULL -> "log full";
                case BACKING_UP -> "backing up";
                case DROPPING -> "dropping";
                case SLOW_DISK -> "slow disk";
                case OK -> "ok";
            };
        }
    }
}
