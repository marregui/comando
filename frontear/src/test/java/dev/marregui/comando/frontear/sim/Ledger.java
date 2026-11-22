package dev.marregui.comando.frontear.sim;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.marregui.comando.frontear.protocol.Packet;
import dev.marregui.comando.frontear.protocol.Topics;

/// The simulation's oracle: what each node published, which links covered each message when it was published and the
/// PUBACK its publisher got, and what each subscriber received, in the order it received it. [#violations] checks the
/// octopus's contract against that record:
///
/// - lost: a message acknowledged 0x00 for a topic a link's OUT filters covered arrives at every checked subscriber
///   of the link's far node whose filters match it, a filter change after it was queued notwithstanding;
/// - order: over one link, the first arrivals of its near node's messages keep their publish order;
/// - overtaking: no message arrives over a link before every earlier one that must arrive there has;
/// - hops: no message arrives with more hops than the hop limit, and none arrives at another node without a count;
/// - phantom: every arrival is a message some node published.
///
/// Order and overtaking are not checked for a link whose `reordered` count is above zero: a peer refused one of its
/// messages for now and took a later one first, which the contract allows and counts. Only single-hop arrivals are
/// checked for loss and order, since whether a message goes on past its first hop depends on timing (a filter or link
/// change on the next node).
///
/// A ledger made with the octopus's [Paths], for an octopus whose filters do not change, checks every hop instead:
///
/// - path: every arrival has a walk that admits it, a walk of its hop count from its source to the subscriber's node
///   (so nothing loops past No Local or the hop limit); with `exact`, no arrival comes more often than the walks of
///   its hop count, once per delivery path;
/// - lost: a message acknowledged 0x00 arrives at a checked subscriber whose filters match it at least once for each
///   hop count an OUT-only walk reaches the subscriber's node with; with `exact`, once per walk, IN sides included;
/// - order and overtaking: per hop count, the first arrivals of the messages that the same walks carry keep their
///   publish order, unless one of those walks takes a link with a counted refusal; without `exact` only where every
///   such walk is OUT-only, since an IN side may lose a message to a cut.
///
/// Publishing is single-threaded per node: a node's messages carry one sequence, `"<node> <seq>"` at the head of the
/// payload. Arrivals are recorded from any thread; the rest is the test thread's.
final class Ledger {

    /// How many lines of one kind a violation report shows before it counts the rest.
    private static final int SHOWN = 12;
    /// The PUBACK code of a publish that got none: the connection or the node ended first.
    static final int NO_ACK = -1;

    private final int hopLimit;
    private final Paths paths;
    private final boolean exact;
    private final Map<String, Link> links = new LinkedHashMap<>();
    private final Map<String, List<Sent>> published = new HashMap<>();
    private final List<Sink> sinks = new ArrayList<>();
    private final Map<String, Long> reordered = new HashMap<>();

    /// A ledger for an octopus whose links all have hop limit `hopLimit`.
    Ledger(final int hopLimit) {
        this.hopLimit = hopLimit;
        this.paths = null;
        this.exact = false;
    }

    /// A ledger that checks every hop against `paths`, each walk once per delivery when `exact`.
    Ledger(final Paths paths, final boolean exact) {
        this.hopLimit = paths.hopLimit();
        this.paths = paths;
        this.exact = exact;
    }

    /// Adds link `id` from `from` to `to`.
    void link(final String id, final String from, final String to) {
        links.put(id, new Link(id, from, to));
    }

    /// Adds subscriber `name` on `node`.
    Sink sink(final String node, final String name, final List<String> filters) {
        final Sink s = new Sink(node, name, filters);
        sinks.add(s);
        return s;
    }

    /// Records that `source` publishes on `topic` while `covering` links carry it; returns its sequence, for the
    /// payload. Its code is [#NO_ACK] until [#acked].
    long publish(final String source, final String topic, final Set<String> covering) {
        final List<Sent> list = published.computeIfAbsent(source, _ -> new ArrayList<>());
        list.add(new Sent(topic, Set.copyOf(covering), NO_ACK));
        return list.size() - 1;
    }

    /// Records the PUBACK code `source`'s message `seq` got.
    void acked(final String source, final long seq, final int code) {
        final List<Sent> list = published.get(source);
        final Sent s = list.get((int) seq);
        list.set((int) seq, new Sent(s.topic(), s.links(), code));
    }

    /// Adds `count` to link `id`'s refusals the peer overtook, one incarnation of the link at a time.
    void reordered(final String id, final long count) {
        reordered.merge(id, count, Long::sum);
    }

    /// How many acknowledged single-hop messages have not yet arrived at a checked subscriber that must get them; with
    /// [Paths], how many copies owed per hop count, as the class says.
    long missing() {
        long n = 0;
        for (final Sink s : sinks) {
            if (!s.checked) {
                continue;
            }
            if (paths != null) {
                n += missingOnPaths(s, s.snapshot());
                continue;
            }
            final Map<String, BitSet> seen = seen(s.snapshot());
            for (final Link l : links.values()) {
                if (!l.to().equals(s.node)) {
                    continue;
                }
                final BitSet got = seen.getOrDefault(l.from(), new BitSet());
                for (final long seq : required(l, s)) {
                    if (!got.get((int) seq)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /// Every violation of the contract the record shows, one line each, at most [#SHOWN] of a kind and a count of
    /// the rest.
    List<String> violations() {
        final Map<String, List<String>> kinds = new LinkedHashMap<>();
        for (final String kind : List.of("lost", "order", "overtaking", "hops", "phantom", "path")) {
            kinds.put(kind, new ArrayList<>());
        }
        for (final Sink s : sinks) {
            if (s.checked) {
                check(s, s.snapshot(), kinds);
            }
        }
        final List<String> out = new ArrayList<>();
        kinds.forEach((kind, lines) -> {
            for (int i = 0; i < Math.min(SHOWN, lines.size()); i++) {
                out.add(kind + ": " + lines.get(i));
            }
            if (lines.size() > SHOWN) {
                out.add(kind + ": " + (lines.size() - SHOWN) + " more");
            }
        });
        return out;
    }

    /// The messages a schedule published and how many got PUBACK 0x00, per node, for its summary line.
    String published() {
        final StringBuilder b = new StringBuilder();
        published.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            final long ok = e.getValue().stream().filter(s -> s.code() == Packet.SUCCESS).count();
            b.append(b.isEmpty() ? "" : ", ").append(e.getKey()).append('=').append(ok).append('/')
                    .append(e.getValue().size());
        });
        return b.toString();
    }

    /// The sequences that arrived, per source.
    private static Map<String, BitSet> seen(final List<Arrival> arrivals) {
        final Map<String, BitSet> seen = new HashMap<>();
        for (final Arrival a : arrivals) {
            if (a.seq() >= 0) {
                seen.computeIfAbsent(a.source(), _ -> new BitSet()).set((int) a.seq());
            }
        }
        return seen;
    }

    private void check(final Sink s, final List<Arrival> arrivals, final Map<String, List<String>> kinds) {
        for (final Arrival a : arrivals) {
            final List<Sent> from = published.get(a.source());
            if (from == null || a.seq() < 0 || a.seq() >= from.size()) {
                kinds.get("phantom").add(a + " at " + s + " was never published");
            } else if (a.hops() > hopLimit) {
                kinds.get("hops").add(a + " at " + s + " made more hops than the limit [hopLimit=" + hopLimit + ']');
            } else if (a.hops() == 0 && !a.source().equals(s.node)) {
                kinds.get("hops").add(a + " at " + s + " came from another node without a hop count");
            }
        }
        if (paths != null) {
            checkPaths(s, arrivals, kinds);
            return;
        }
        final Map<String, BitSet> seen = seen(arrivals);
        for (final Link l : links.values()) {
            if (!l.to().equals(s.node)) {
                continue;
            }
            final List<Long> required = required(l, s);
            final BitSet got = seen.getOrDefault(l.from(), new BitSet());
            for (final long seq : required) {
                if (!got.get((int) seq)) {
                    final Sent sent = published.get(l.from()).get((int) seq);
                    kinds.get("lost").add(l.from() + ' ' + seq + " topic=" + sent.topic() + " link=" + l.id()
                            + " never arrived at " + s);
                }
            }
            if (reordered.getOrDefault(l.id(), 0L) == 0) {
                order(l, s, arrivals, required, kinds);
            }
        }
    }

    /// The first arrivals over `l` at `s` against publish order and the messages that must come before each.
    private void order(final Link l, final Sink s, final List<Arrival> arrivals, final List<Long> required,
            final Map<String, List<String>> kinds) {
        final BitSet first = new BitSet();
        long highest = -1;
        int next = 0;
        for (final Arrival a : arrivals) {
            if (!a.source().equals(l.from()) || a.hops() != 1 || a.seq() < 0 || first.get((int) a.seq())) {
                continue;
            }
            first.set((int) a.seq());
            if (a.seq() < highest) {
                kinds.get("order").add(l.from() + ' ' + a.seq() + " first arrived at " + s + " after " + l.from()
                        + ' ' + highest + " over " + l.id());
            }
            highest = Math.max(highest, a.seq());
            while (next < required.size() && required.get(next) < a.seq()) {
                final long earlier = required.get(next++);
                if (!first.get((int) earlier)) {
                    kinds.get("overtaking").add(l.from() + ' ' + a.seq() + " arrived at " + s + " before "
                            + l.from() + ' ' + earlier + ", published earlier, over " + l.id());
                }
            }
            if (next < required.size() && required.get(next) == a.seq()) {
                next++;
            }
        }
    }

    /// The sequences of `l`'s near node that must arrive at `s`, ascending.
    private List<Long> required(final Link l, final Sink s) {
        final List<Long> out = new ArrayList<>();
        final List<Sent> from = published.getOrDefault(l.from(), List.of());
        for (int seq = 0; seq < from.size(); seq++) {
            final Sent sent = from.get(seq);
            if (sent.code() == Packet.SUCCESS && sent.links().contains(l.id()) && s.matches(sent.topic())) {
                out.add((long) seq);
            }
        }
        return out;
    }

    /// How many copies of what `s` is owed have not arrived, per message and hop count.
    private long missingOnPaths(final Sink s, final List<Arrival> arrivals) {
        final Map<String, int[][]> got = counts(arrivals);
        long n = 0;
        for (final Map.Entry<String, List<Sent>> e : published.entrySet()) {
            final int[][] seen = got.get(e.getKey());
            for (int seq = 0; seq < e.getValue().size(); seq++) {
                final int[] owed = owed(e.getKey(), e.getValue().get(seq), s);
                for (int h = 0; h <= hopLimit; h++) {
                    n += Math.max(0, owed[h] - (seen == null ? 0 : seen[seq][h]));
                }
            }
        }
        return n;
    }

    /// Every message any node published against the walks that reach `s`: arrivals no walk admits, copies past the
    /// walks when exact, copies owed and missing; then order per group of walks.
    private void checkPaths(final Sink s, final List<Arrival> arrivals, final Map<String, List<String>> kinds) {
        final Map<String, int[][]> got = counts(arrivals);
        for (final Map.Entry<String, List<Sent>> e : published.entrySet()) {
            final String source = e.getKey();
            final int[][] seen = got.get(source);
            for (int seq = 0; seq < e.getValue().size(); seq++) {
                final Sent sent = e.getValue().get(seq);
                final int[] admitted = admitted(source, sent.topic(), s);
                final int[] owed = owed(source, sent, s);
                for (int h = 0; h <= hopLimit; h++) {
                    final int copies = seen == null ? 0 : seen[seq][h];
                    if (copies > 0 && admitted[h] == 0) {
                        kinds.get("path").add(source + ' ' + seq + " topic=" + sent.topic() + " arrived at " + s
                                + " with hops=" + h + ", which no path admits [paths=" + reaching(source,
                                sent.topic(), s) + ']');
                    } else if (exact && copies > admitted[h]) {
                        kinds.get("path").add(source + ' ' + seq + " topic=" + sent.topic() + " arrived at " + s
                                + ' ' + copies + " times with hops=" + h + ", more than its paths [paths="
                                + reaching(source, sent.topic(), s) + ']');
                    }
                    if (copies < owed[h]) {
                        kinds.get("lost").add(source + ' ' + seq + " topic=" + sent.topic() + " hops=" + h
                                + " arrived at " + s + ' ' + copies + " of " + owed[h] + " times [paths="
                                + reaching(source, sent.topic(), s) + ']');
                    }
                }
            }
        }
        orderOnPaths(s, arrivals, kinds);
    }

    /// The first arrivals at `s` per source and hop count, grouped by the walks that carry them, against publish
    /// order and the acknowledged messages of the same group that must come before each.
    private void orderOnPaths(final Sink s, final List<Arrival> arrivals, final Map<String, List<String>> kinds) {
        final Map<String, Group> groups = new HashMap<>();
        final Map<String, BitSet> first = new HashMap<>();
        for (final Arrival a : arrivals) {
            final List<Sent> from = published.get(a.source());
            if (from == null || a.seq() < 0 || a.seq() >= from.size() || a.hops() < 1 || a.hops() > hopLimit) {
                continue;
            }
            final BitSet once = first.computeIfAbsent(a.source() + ' ' + a.hops(), _ -> new BitSet());
            if (once.get((int) a.seq())) {
                continue;
            }
            once.set((int) a.seq());
            final String key = group(a.source(), from.get((int) a.seq()).topic(), s, a.hops());
            if (key == null) {
                continue;
            }
            groups.computeIfAbsent(key, k -> new Group(k, required(a.source(), s, a.hops(), k)))
                    .arrived(a.seq(), s, a.source(), kinds);
        }
    }

    /// The group of the walks of `hops` from `source` to `s` for `topic`, as their names; null when none, when one
    /// takes a link with a counted refusal, or, unless exact, when one is not OUT-only.
    private String group(final String source, final String topic, final Sink s, final int hops) {
        if (!s.matches(topic)) {
            return null;
        }
        final StringBuilder b = new StringBuilder().append(source).append(' ').append(hops);
        boolean any = false;
        for (final Paths.Walk w : paths.walks(source, topic)) {
            if (w.hops() != hops || !w.end().equals(s.node)) {
                continue;
            }
            if (!exact && !w.outOnly()) {
                return null;
            }
            for (final Map.Entry<String, Long> r : reordered.entrySet()) {
                if (r.getValue() > 0 && w.takes(r.getKey())) {
                    return null;
                }
            }
            b.append(' ').append(w);
            any = true;
        }
        return any ? b.toString() : null;
    }

    /// The acknowledged messages of `source` in group `key`, ascending.
    private List<Long> required(final String source, final Sink s, final int hops, final String key) {
        final List<Long> out = new ArrayList<>();
        final List<Sent> from = published.get(source);
        for (int seq = 0; seq < from.size(); seq++) {
            final Sent sent = from.get(seq);
            if (sent.code() == Packet.SUCCESS && key.equals(group(source, sent.topic(), s, hops))) {
                out.add((long) seq);
            }
        }
        return out;
    }

    /// How many walks of each hop count reach `s` for `topic` from `source`; none when its filters do not match.
    private int[] admitted(final String source, final String topic, final Sink s) {
        final int[] n = new int[hopLimit + 1];
        if (s.matches(topic)) {
            for (final Paths.Walk w : paths.walks(source, topic)) {
                if (w.end().equals(s.node)) {
                    n[w.hops()]++;
                }
            }
        }
        return n;
    }

    /// The copies of `sent` that `s` must get, per hop count: none unless acknowledged 0x00; when exact, one per
    /// walk; otherwise one per hop count an OUT-only walk reaches it with.
    private int[] owed(final String source, final Sent sent, final Sink s) {
        final int[] n = new int[hopLimit + 1];
        if (sent.code() != Packet.SUCCESS || !s.matches(sent.topic())) {
            return n;
        }
        for (final Paths.Walk w : paths.walks(source, sent.topic())) {
            if (w.end().equals(s.node) && (exact || w.outOnly())) {
                n[w.hops()] = exact ? n[w.hops()] + 1 : 1;
            }
        }
        return n;
    }

    /// The walks from `source` to `s`'s node for `topic`, for a report.
    private List<Paths.Walk> reaching(final String source, final String topic, final Sink s) {
        final List<Paths.Walk> out = new ArrayList<>();
        for (final Paths.Walk w : paths.walks(source, topic)) {
            if (w.end().equals(s.node)) {
                out.add(w);
            }
        }
        return out;
    }

    /// The copies per source, sequence and hop count; phantoms and counts past the hop limit left out, as the hops
    /// and phantom checks report them.
    private Map<String, int[][]> counts(final List<Arrival> arrivals) {
        final Map<String, int[][]> out = new HashMap<>();
        for (final Arrival a : arrivals) {
            final List<Sent> from = published.get(a.source());
            if (from == null || a.seq() < 0 || a.seq() >= from.size() || a.hops() < 0 || a.hops() > hopLimit) {
                continue;
            }
            out.computeIfAbsent(a.source(), _ -> new int[from.size()][hopLimit + 1])[(int) a.seq()][a.hops()]++;
        }
        return out;
    }

    /// One message as a subscriber received it.
    record Arrival(String source, long seq, int hops) {

        /// The arrival of `m`: its source and sequence from the head of its payload, its hops from `frontear-hops`
        /// (0 without one, the int maximum for a count that is not a number). A payload not of the simulation's shape
        /// reads as source `?`, which no node is.
        static Arrival of(final Packet.Publish m) {
            int hops = 0;
            for (final Packet.UserProperty p : m.meta().userProperties()) {
                if ("frontear-hops".equals(p.name())) {
                    try {
                        hops = Integer.parseInt(p.value());
                    } catch (final NumberFormatException _) {
                        hops = Integer.MAX_VALUE;
                    }
                }
            }
            final String text = new String(m.payload(), StandardCharsets.US_ASCII);
            final int space = text.indexOf(' ');
            final int end = text.indexOf(' ', space + 1);
            try {
                return new Arrival(text.substring(0, space), Long.parseLong(text.substring(space + 1,
                        end < 0 ? text.length() : end)), hops);
            } catch (final RuntimeException _) {
                return new Arrival("?", -1, hops);
            }
        }
    }

    /// A one-way link from node `from` to node `to`.
    record Link(String id, String from, String to) {
    }

    /// A message as published: its topic, the links whose OUT filters covered it then, and its PUBACK code.
    private record Sent(String topic, Set<String> links, int code) {
    }

    /// The first arrivals of one group of walks at one subscriber, in the order they came, against the acknowledged
    /// messages of the group, ascending.
    private static final class Group {

        private final String name;
        private final List<Long> required;
        private final BitSet first = new BitSet();
        private long highest = -1;
        private int next;

        private Group(final String name, final List<Long> required) {
            this.name = name;
            this.required = required;
        }

        private void arrived(final long seq, final Sink s, final String source, final Map<String, List<String>> kinds) {
            first.set((int) seq);
            if (seq < highest) {
                kinds.get("order").add(source + ' ' + seq + " first arrived at " + s + " after " + source + ' '
                        + highest + " over [" + name + ']');
            }
            highest = Math.max(highest, seq);
            while (next < required.size() && required.get(next) < seq) {
                final long earlier = required.get(next++);
                if (!first.get((int) earlier)) {
                    kinds.get("overtaking").add(source + ' ' + seq + " arrived at " + s + " before " + source + ' '
                            + earlier + ", published earlier, over [" + name + ']');
                }
            }
            if (next < required.size() && required.get(next) == seq) {
                next++;
            }
        }
    }

    /// A subscriber on node `node` with `filters`: its arrivals in the order they came. `checked` false leaves it out
    /// of every check, for a subscriber whose node may lose its messages by design (a client's session gone with a
    /// restart, or past its queue bound).
    static final class Sink {

        private final String node;
        private final String name;
        private final List<String> filters;
        private final List<Arrival> arrivals = new ArrayList<>();
        private boolean checked = true;

        private Sink(final String node, final String name, final List<String> filters) {
            this.node = node;
            this.name = name;
            this.filters = List.copyOf(filters);
        }

        /// Records `a`. From any thread.
        void arrived(final Arrival a) {
            synchronized (arrivals) {
                arrivals.add(a);
            }
        }

        /// Leaves this subscriber out of the checks.
        void unchecked() {
            checked = false;
        }

        List<String> filters() {
            return filters;
        }

        int size() {
            synchronized (arrivals) {
                return arrivals.size();
            }
        }

        private List<Arrival> snapshot() {
            synchronized (arrivals) {
                return List.copyOf(arrivals);
            }
        }

        private boolean matches(final String topic) {
            for (final String f : filters) {
                if (Topics.matches(f, topic)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return node + '/' + name;
        }
    }
}
