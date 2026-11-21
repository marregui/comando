package dev.marregui.comando.ui.panels.lab;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import dev.marregui.comando.core.port.LabPort.Kind;
import dev.marregui.comando.core.port.LabPort.Packet;

/**
 * The rows of the frontear tab's packet log, from the packets kept: those whose topic holds the filter's text and whose
 * kind is the one chosen, and, unless every presence is asked for, a run of presences one origin published in a row as
 * one row with its count. Plain values, so the rules are tested without Swing.
 */
public final class TrafficRows {

    /**
     * How long after a publish its steps on the links still count as its path: a link that is up sends within
     * milliseconds; the record's bound (record 187).
     */
    public static final long PATH_MILLIS = 5_000L;

    private TrafficRows() {
    }

    /**
     * One row: the latest packet of its run, how many the run holds (1 for any packet but a presence), and the
     * {@code seq} of its first, which names the row while the run grows.
     */
    public record Row(Packet packet, int count, long firstSeq) {
    }

    /**
     * The rows of {@code packets}, oldest first: {@code topic} a substring of the packet's topic ("" for any),
     * {@code kind} its kind (null for any), and the presences collapsed unless {@code everyPresence}.
     */
    public static List<Row> of(
            final Collection<Packet> packets,
            final String topic,
            final Kind kind,
            final boolean everyPresence
    ) {
        final List<Row> rows = new ArrayList<>();
        for (final Packet p : packets) {
            if ((kind != null && p.kind() != kind) || (!topic.isEmpty() && !p.topic().contains(topic))) {
                continue;
            }
            if (!everyPresence && presence(p) && !rows.isEmpty()) {
                final Row last = rows.getLast();
                if (presence(last.packet()) && last.packet().origin().equals(p.origin())) {
                    rows.set(rows.size() - 1, new Row(p, last.count() + 1, last.firstSeq()));
                    continue;
                }
            }
            rows.add(new Row(p, 1, p.seq()));
        }
        return rows;
    }

    /** Whether {@code p} is a presence: published on a topic whose level is {@code presence}. */
    public static boolean presence(final Packet p) {
        final String t = p.topic();
        return p.kind() == Kind.PUBLISHED && (t.endsWith("/presence") || t.contains("/presence/"));
    }

    /**
     * The rows of {@code rows} on the path of the publish at {@code selected}: the steps on the links with its topic,
     * within {@link #PATH_MILLIS} after it, and its correlation when the step carries one. Empty for any other row.
     */
    public static List<Integer> path(final List<Row> rows, final int selected) {
        final List<Integer> path = new ArrayList<>();
        if (selected < 0 || selected >= rows.size()) {
            return path;
        }
        final Packet from = rows.get(selected).packet();
        if (from.kind() != Kind.PUBLISHED) {
            return path;
        }
        for (int i = selected + 1; i < rows.size(); i++) {
            final Packet p = rows.get(i).packet();
            if (p.atMillis() - from.atMillis() > PATH_MILLIS) {
                break;
            }
            final boolean step = p.kind() != Kind.PUBLISHED;
            final boolean same = p.correlation().isEmpty() || p.correlation().equals(from.correlation());
            if (step && same && p.topic().equals(from.topic())) {
                path.add(i);
            }
        }
        return path;
    }
}
