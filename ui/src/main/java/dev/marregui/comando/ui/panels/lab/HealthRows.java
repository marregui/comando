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
 * the step that resolves it, worst first; the node's faults in one line, each counter with its rate. A growing queue,
 * a recent drop and a counter's rate are read across successive nodes, so one instance follows one node, a node read
 * at a time. The windows are measured on the
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
    /**
     * A fault counter's rate is over this long: the window of a recent drop, so a burst stays in sight for as long
     * as the link's flag does.
     */
    static final long RATE_MILLIS = DROP_MILLIS;
    /** What the faults line says first while cdb refuses the lab's rows for a table it failed or suspended. */
    static final String SUSPENDED = "cdb table suspended";

    private static final long HOUR_MILLIS = 3_600_000L;
    private static final double MILLIS_PER_SECOND = 1_000.0;
    private static final double THOUSAND = 1_000.0;
    private static final double MILLION = 1_000_000.0;
    private static final double BILLION = 1_000_000_000.0;
    private static final long MINUTE_MILLIS = 60_000L;
    private static final Log NO_LOG = new Log("", 0L, 0L, 0L, 0, 0.0, 0, 0L, 0.0, 0.0, 0.0);
    /** A link's mark that no drop has been seen since its first read. */
    private static final long NEVER = Long.MIN_VALUE;

    /** Per peer, its dropped count at the last read and when it last grew, as {dropped, atMillis}. */
    private final Map<String, long[]> drops = new HashMap<>();
    /** Per peer, its queue at each read within {@link #GROWTH_MILLIS}, oldest first, as {atMillis, queued}. */
    private final Map<String, ArrayDeque<long[]>> queues = new HashMap<>();
    /** Per fault counter, its total at each read within {@link #RATE_MILLIS}, oldest first, as {atMillis, total}. */
    private final Map<String, ArrayDeque<long[]>> totals = new HashMap<>();

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
                .append(" · reordered ").append(l.reordered()).append(" · refused ").append(l.refused())
                .append(" · coalesced ").append(l.coalesced()).append('\n');
        b.append("       backlog ").append(l.backlogBytes()).append(" / ").append(l.maxBacklogBytes()).append(" bytes")
                .append(l.backlogGrowing() ? " · growing while down" : "").append('\n');
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
     * The node's faults in one line, only the counters that are not zero, or "" when there are none: the gateway's
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

    /**
     * The node's faults as {@link #faults(Node, long)} names them, each counter that is not zero with its rate over the
     * last {@link #RATE_MILLIS} and its total, {@code packets missed 1.2k/s · 7.8M total}, the counters apart by
     * {@code ;}; led by {@link #SUSPENDED} while {@code cdbFault}, the reason cdb gave for refusing the lab's rows, is
     * not "". A rate runs from the newest read at least the window old, or from the first read while the node has been
     * read for less; a total that went back, a node started again, starts its window again. {@code sampled} counts the
     * packets the database sink left out by design, so it is no fault: only its rate is kept, for the Traffic view.
     */
    public Faults faults(final Node node, final long missed, final long sampled, final String cdbFault) {
        final long nowMillis = node.atMillis();
        final Totals t = node.totals();
        final Counts c = node.counts();
        final List<String> out = new ArrayList<>();
        if (!cdbFault.isEmpty()) {
            out.add(SUSPENDED);
        }
        rated(out, nowMillis, "dropped full", t.droppedFull());
        rated(out, nowMillis, "dropped expired", t.droppedExpired());
        rated(out, nowMillis, "dropped oversize", t.droppedOversize());
        rated(out, nowMillis, "refused", t.refused());
        rated(out, nowMillis, "backlog disconnects", t.backlogDisconnects());
        final double missedPerSecond = rated(out, nowMillis, "packets missed", missed + c.ringMissed());
        rated(out, nowMillis, "watch window dropped", c.windowDropped());
        rated(out, nowMillis, "cdb dropped full", c.sinkDroppedFull());
        rated(out, nowMillis, "cdb dropped down", c.sinkDroppedDown());
        final double sampledPerSecond = perSecond("cdb sampled", nowMillis, sampled);
        return new Faults(String.join("; ", out), cdbFault, missedPerSecond, sampledPerSecond);
    }

    /**
     * {@code value} in at most four figures and a unit, as a faults line counts: {@code 7}, {@code 0.5}, {@code 120},
     * {@code 1.2k}, {@code 7.8M}, {@code 3.0G}.
     */
    static String compact(final double value) {
        // the thresholds sit where the figure, rounded, would read 1000 of the unit below
        if (value >= BILLION - MILLION / 20.0) {
            return fixed(value / BILLION, 1) + "G";
        }
        if (value >= MILLION - THOUSAND / 20.0) {
            return fixed(value / MILLION, 1) + "M";
        }
        if (value >= THOUSAND - 0.5) {
            return fixed(value / THOUSAND, 1) + "k";
        }
        return fixed(value, value < 10.0 && value != Math.rint(value) ? 1 : 0);
    }

    /** {@code millis} as the largest whole unit it holds: {@code 45s}, {@code 3m}, {@code 2h}. */
    static String duration(final long millis) {
        return millis >= HOUR_MILLIS ? millis / HOUR_MILLIS + "h"
                : millis >= MINUTE_MILLIS ? millis / MINUTE_MILLIS + "m" : millis / 1_000L + "s";
    }

    /**
     * Whether {@code l} dropped or refused a message within the last {@link #DROP_MILLIS}; its first read counts none.
     * A refusal counts: a link whose queue refuses by its wait or its weight is far under its count.
     */
    private boolean dropping(final Link l, final long nowMillis) {
        final long given = l.dropped() + l.refused();
        final long[] d = drops.get(l.id());
        if (d == null) {
            drops.put(l.id(), new long[]{given, NEVER});
            return false;
        }
        if (given > d[0]) {
            d[1] = nowMillis;
        }
        d[0] = given;
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

    /** Records {@code total} under {@code name} and adds it with its rate to {@code out} when not zero; the rate. */
    private double rated(final List<String> out, final long nowMillis, final String name, final long total) {
        final double rate = perSecond(name, nowMillis, total);
        if (total != 0L) {
            out.add(name + " " + compact(rate) + "/s · " + compact(total) + " total");
        }
        return rate;
    }

    /** Records {@code total} under {@code name} at {@code nowMillis}; how fast it grew over the window, per second. */
    private double perSecond(final String name, final long nowMillis, final long total) {
        final ArrayDeque<long[]> q = totals.computeIfAbsent(name, _ -> new ArrayDeque<>());
        if (!q.isEmpty() && (total < q.getLast()[1] || nowMillis < q.getLast()[0])) {
            // a counter that went back, or a clock that did, belongs to a node started again
            q.clear();
        }
        q.addLast(new long[]{nowMillis, total});
        // the reads before the newest one at least the window old are not needed again
        while (q.size() > 1 && nowMillis - secondAtMillis(q) >= RATE_MILLIS) {
            q.removeFirst();
        }
        final long[] first = q.getFirst();
        final long spanMillis = nowMillis - first[0];
        return spanMillis <= 0L ? 0.0 : (total - first[1]) * MILLIS_PER_SECOND / spanMillis;
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
            case LOG_FULL -> "the gateway writes faster than the log takes it: look for a client flooding a topic in "
                    + "Traffic";
            case BACKING_UP -> "the peer takes messages slower than they come: check its sessions are not stuck";
            case DROPPING -> "messages to this peer were given up: Traffic, kind Dropped, gives each reason";
            case SLOW_DISK -> "the log's forces to disk are slow: check the disk's free space and load";
            case OK -> "";
        };
    }

    /**
     * The node's faults line ("" for none) and its tooltip, the reason cdb gave while it refuses the lab's rows ("" for
     * none), with the rates of the packets the tab missed and the database sink sampled, per second, that the Traffic
     * view says it shows a sample by.
     */
    public record Faults(String text, String tip, double missedPerSecond, double sampledPerSecond) {
    }

    /** What is wrong with a link, worst first; the order sorts the rows. */
    public enum Status { DOWN, LOG_FULL, BACKING_UP, DROPPING, SLOW_DISK, OK }

    /**
     * One link's health: its figures, its log's ({@code log} all zero when the node keeps none for it), its status, how
     * long it has been down ({@code 0} when up or unknown), and the step that resolves it ("" when ok).
     */
    public record Health(Link link, Log log, Status status, long downMillis, String step) {

        /**
         * The status in a few words, as the table shows it: {@code down 3m}, {@code down 9m, backlog growing} (its
         * backlog grew for 5 minutes while down), {@code backing up}, {@code ok}.
         */
        public String label() {
            return switch (status) {
                case DOWN -> (downMillis > 0L ? "down " + duration(downMillis) : "down")
                        + (link.backlogGrowing() ? ", backlog growing" : "");
                case LOG_FULL -> "log full";
                case BACKING_UP -> "backing up";
                case DROPPING -> "dropping";
                case SLOW_DISK -> "slow disk";
                case OK -> "ok";
            };
        }
    }
}
