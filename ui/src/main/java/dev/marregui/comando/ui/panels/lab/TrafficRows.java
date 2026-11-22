package dev.marregui.comando.ui.panels.lab;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.marregui.comando.core.port.LabPort.Kind;
import dev.marregui.comando.core.port.LabPort.Packet;

/**
 * The rows of the frontear tab's packet log, from the packets kept: those whose topic holds the filter's text and whose
 * kind is the one chosen, frontear's own control traffic only when asked for, and, unless every presence is asked for,
 * a run of presences one origin published in a row as one row with its count. Plain values, so the rules are tested
 * without Swing.
 */
public final class TrafficRows {

    /**
     * How long after a publish its steps on the links still count as its path: a link that is up sends within
     * milliseconds; the record's bound (record 187).
     */
    public static final long PATH_MILLIS = 5_000L;

    /** A link's mark that a DROPPED without an offset ended its path. */
    private static final long NO_OFFSET = Long.MIN_VALUE;
    /** The levels of a comando instance topic: {@code comando/v1/<machine>/instance/<slug>/<kind>}. */
    private static final int INSTANCE_LEVELS = 6;
    /** The machine level of this machine's own instance topics. */
    private static final String LOCAL = "local";

    /**
     * The topics of frontear's own traffic, by their start: the links' checks every 5 s and their replies, and the
     * nodes' state and presence. In a live run they are most of the packets.
     */
    public static final List<String> CONTROL_TOPICS = List.of("$frontear/", "comando/v1/local/fabric/");

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
     * {@code kind} its kind (null for any), the {@link #control} packets left out unless {@code control}, and the
     * presences collapsed unless {@code everyPresence}.
     */
    public static List<Row> of(
            final Collection<Packet> packets,
            final String topic,
            final Kind kind,
            final boolean control,
            final boolean everyPresence
    ) {
        final List<Row> rows = new ArrayList<>();
        for (final Packet p : packets) {
            if ((kind != null && p.kind() != kind) || (!topic.isEmpty() && !p.topic().contains(topic))
                    || (!control && control(p))) {
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

    /** Whether {@code p} is frontear's own control traffic: its topic starts with one of {@link #CONTROL_TOPICS}. */
    public static boolean control(final Packet p) {
        for (final String start : CONTROL_TOPICS) {
            if (p.topic().startsWith(start)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code p} is a presence: published on a topic whose level is {@code presence}. */
    public static boolean presence(final Packet p) {
        final String t = p.topic();
        return p.kind() == Kind.PUBLISHED && (t.endsWith("/presence") || t.contains("/presence/"));
    }

    /**
     * The rows of {@code rows} on the path of the publish at {@code selected}, in order: per link, the first APPENDED
     * within {@link #PATH_MILLIS} after it with its topic and size, and the SENT and DROPPED of that link at that log
     * offset, however much later (a peer in backoff takes it when it is back); a DROPPED with no offset joins by topic
     * and size as an APPENDED does. Steps carry no correlation, so the
     * first join is by topic and size; the second, by link and offset, is exact. Empty for any row but a publish.
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
        // per link, the log offset its APPENDED took, or NO_OFFSET once a DROPPED without one ended it
        final Map<String, Long> appended = new HashMap<>();
        for (int i = selected + 1; i < rows.size(); i++) {
            final Packet p = rows.get(i).packet();
            // only the joins by topic and size are bounded in time; a step at a joined offset is this message's
            final boolean soon = p.atMillis() - from.atMillis() <= PATH_MILLIS;
            final boolean like = soon && p.topic().equals(from.topic()) && p.bytes() == from.bytes();
            final Long at = appended.get(p.link());
            final boolean joins = switch (p.kind()) {
                case APPENDED -> like && at == null;
                case SENT -> at != null && at == p.offset();
                case DROPPED -> at == null ? like && p.offset() < 0L : at == p.offset();
                case PUBLISHED, ARRIVED, CONNECTED, DISCONNECTED -> false;
            };
            if (joins) {
                path.add(i);
                if (at == null) {
                    appended.put(p.link(), p.kind() == Kind.APPENDED ? p.offset() : NO_OFFSET);
                }
            }
        }
        return path;
    }

    /**
     * What {@code p} is, in one line, as the detail pane heads it: a comando presence, question or reply by the
     * instance its topic names, else the publish or step by its kind, origin or link, and topic.
     */
    public static String describe(final Packet p) {
        final String topic = p.topic();
        return switch (p.kind()) {
            case PUBLISHED -> published(p);
            case APPENDED -> "Appended to the log for " + p.link() + " at offset " + p.offset() + ": " + topic;
            case SENT -> "Sent to " + p.link() + ", which acknowledged it: " + topic;
            case ARRIVED -> "Arrived from " + p.link() + ", to be published here: " + topic;
            case DROPPED -> "Dropped for " + p.link() + ": " + topic;
            case CONNECTED -> "The link to " + p.link() + " connected";
            case DISCONNECTED -> "The link to " + p.link() + " disconnected";
        };
    }

    /**
     * One step of {@code from}'s path as the detail pane lists it: how long after the publish, then what happened on
     * which link; a drop with its reason.
     */
    public static String step(final Packet from, final Packet p) {
        final String after = "+" + (p.atMillis() - from.atMillis()) + " ms  ";
        return after + switch (p.kind()) {
            case APPENDED -> "appended to the log for " + p.link() + " at offset " + p.offset();
            case SENT -> "sent to " + p.link() + ", acknowledged";
            case DROPPED -> "dropped for " + p.link() + (p.detail().isEmpty() ? "" : ": " + p.detail());
            case PUBLISHED, ARRIVED, CONNECTED, DISCONNECTED -> p.kind().name().toLowerCase(Locale.ROOT) + " "
                    + p.link();
        };
    }

    /** A publish named by what its comando topic says, {@code comando/v1/<machine>/instance/<slug>/<kind>}. */
    private static String published(final Packet p) {
        final String[] level = p.topic().split("/", -1);
        final boolean comando = level.length == INSTANCE_LEVELS && "comando".equals(level[0])
                && "instance".equals(level[3]);
        // this machine's instances publish under the machine level local, and go by their slug alone
        final String instance = !comando ? "" : LOCAL.equals(level[2]) ? level[4] : level[4] + "@" + level[2];
        final String by = p.origin().isEmpty() ? "" : ", published by " + p.origin();
        final String hops = p.hops() > 0 ? ", arrived over a link" : "";
        if (comando) {
            switch (level[5]) {
                case "presence" -> {
                    return "Presence of " + instance + ": the instance, its sessions and their state" + hops;
                }
                case "inbox" -> {
                    return "Question to " + instance + by + hops;
                }
                case "reply" -> {
                    return "Reply to " + instance + by + hops;
                }
                default -> {
                    // another kind of comando topic is named by its topic below
                }
            }
        }
        return "Published on " + p.topic() + by + hops;
    }
}
