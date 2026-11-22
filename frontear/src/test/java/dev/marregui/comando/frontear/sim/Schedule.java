package dev.marregui.comando.frontear.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// One simulated run, drawn whole from a seed before it starts: the nodes linked as `topology`, every link with hop
/// limit `hopLimit`, its first OUT filters and its IN filters, then the steps, faults among them, and how the run is
/// checked. Each step carries what it needs (its topics, its counts), so a run with steps left out runs the rest as
/// they were.
record Schedule(Topology topology, int hopLimit, Map<String, List<String>> filters, Map<String, List<String>> in,
        List<Step> steps, Check check) {

    /// The topics the clients publish on: the subscribers' clients take `sim/#`.
    static final List<String> TOPICS = List.of("sim/0", "sim/1", "sim/2", "sim/3");
    /// The topics bursts go on, outside `sim/#`, so a burst fills a link's queue and no client's session.
    static final List<String> BURST_TOPICS = List.of("simb/0", "simb/1");
    /// The filters a link's OUT set is drawn from; `sim/+/x` matches no topic the simulation publishes.
    private static final List<String> FILTERS = List.of("sim/#", "sim/+", "sim/0", "sim/1", "sim/2", "sim/3",
            "simb/#", "simb/0", "simb/1", "sim/+/x");
    /// A batch is at most this many messages: well under the client's publish queue of 1,000, so none is refused
    /// there, and a node's backlog after a reconnect stays under a client session's queue.
    private static final int BATCH_MAX = 150;
    /// A burst is more than one append batch of the link's strand (1,000 deliveries), so many wait at once when the
    /// strand lags, and the queue refuses once the oldest has waited past its wait bound; at most 2,500, to keep a run
    /// short. The queue's count bound, 16,384, is out of a burst's reach: `LinksTest` covers it.
    private static final int BURST_MIN = 1_200;
    private static final int BURST_MAX = 2_500;
    /// A burst of a run checked on every hop is at most one and a half append batches: past the far node, a refused
    /// resend waits a backoff that grows to 30 s, and a chain of them would hold a run past its minute.
    private static final int PATH_BURST_MAX = 1_500;
    /// The longest walk a run checked on every hop may make: 4 hops go round the ring with a shortcut and back.
    private static final int PATH_HOPS_MAX = 4;

    /// A schedule for `topology` from `r`: 6 to 10 steps, OUT filters only, checked on first hops.
    static Schedule generate(final SimRandom r, final Topology topology) {
        final int hopLimit = r.between(1, 3);
        final Map<String, List<String>> filters = new LinkedHashMap<>();
        for (final Ledger.Link l : topology.links()) {
            filters.put(l.id(), filters(r));
        }
        final Set<String> removed = new LinkedHashSet<>();
        final List<Step> steps = new ArrayList<>();
        final int count = r.between(6, 10);
        while (steps.size() < count) {
            final Ledger.Link link = topology.links().get(r.nextInt(topology.links().size()));
            final String node = topology.nodes().get(r.nextInt(topology.nodes().size()));
            final int kind = r.nextInt(100);
            if (kind < 30) {
                steps.add(new Batch(node, topics(r, r.between(10, BATCH_MAX), TOPICS), r.percent(40) ? link.id()
                        : null));
            } else if (kind < 40) {
                steps.add(new Burst(node, r.between(BURST_MIN, BURST_MAX), BURST_TOPICS.get(r.nextInt(2))));
            } else if (kind < 52) {
                steps.add(new Cut(link.id()));
            } else if (kind < 62) {
                steps.add(new Restart(node));
            } else if (kind < 72 && !removed.isEmpty()) {
                final String back = removed.iterator().next();
                removed.remove(back);
                steps.add(new PutBack(back));
            } else if (kind < 80 && removed.add(link.id())) {
                steps.add(new Remove(link.id()));
            } else if (kind >= 80 && !removed.contains(link.id())) {
                steps.add(new Refilter(link.id(), filters(r), topics(r, r.between(10, 80), TOPICS)));
            }
        }
        return new Schedule(topology, hopLimit, filters, none(topology), steps, Check.FIRST_HOP);
    }

    /// A schedule for `topology` from `r` whose filters never change, checked on every hop: OUT and IN filters per
    /// link, hop limit 2 to 4, then 6 to 10 steps of batches, bursts, cuts and restarts.
    static Schedule paths(final SimRandom r, final Topology topology) {
        final int hopLimit = r.between(2, PATH_HOPS_MAX);
        final Map<String, List<String>> out = new LinkedHashMap<>();
        final Map<String, List<String>> in = new LinkedHashMap<>();
        for (final Ledger.Link l : topology.links()) {
            out.put(l.id(), filters(r));
            in.put(l.id(), inFilters(r));
        }
        final List<Step> steps = new ArrayList<>();
        final int count = r.between(6, 10);
        while (steps.size() < count) {
            final Ledger.Link link = topology.links().get(r.nextInt(topology.links().size()));
            final String node = topology.nodes().get(r.nextInt(topology.nodes().size()));
            final int kind = r.nextInt(100);
            if (kind < 50) {
                steps.add(new Batch(node, topics(r, r.between(10, BATCH_MAX), TOPICS), r.percent(40) ? link.id()
                        : null));
            } else if (kind < 58) {
                steps.add(new Burst(node, r.between(BURST_MIN, PATH_BURST_MAX), BURST_TOPICS.get(r.nextInt(2))));
            } else if (kind < 80) {
                steps.add(new Cut(link.id()));
            } else {
                steps.add(new Restart(node));
            }
        }
        return new Schedule(topology, hopLimit, out, in, steps, Check.PATHS);
    }

    /// A schedule for `topology` from `r` without faults, checked on every walk once: OUT and IN filters per link, hop
    /// limit 1 to 4, then 2 to 4 batches.
    static Schedule exact(final SimRandom r, final Topology topology) {
        final int hopLimit = r.between(1, PATH_HOPS_MAX);
        final Map<String, List<String>> out = new LinkedHashMap<>();
        final Map<String, List<String>> in = new LinkedHashMap<>();
        for (final Ledger.Link l : topology.links()) {
            out.put(l.id(), filters(r));
            in.put(l.id(), inFilters(r));
        }
        final List<Step> steps = new ArrayList<>();
        final int count = r.between(2, 4);
        while (steps.size() < count) {
            final String node = topology.nodes().get(r.nextInt(topology.nodes().size()));
            steps.add(new Batch(node, topics(r, r.between(10, 60), TOPICS), null));
        }
        return new Schedule(topology, hopLimit, out, in, steps, Check.EXACT);
    }

    /// No IN filter on any link of `topology`.
    private static Map<String, List<String>> none(final Topology topology) {
        final Map<String, List<String>> out = new LinkedHashMap<>();
        for (final Ledger.Link l : topology.links()) {
            out.put(l.id(), List.of());
        }
        return out;
    }

    /// No IN filter on a third of the links, else one to three distinct filters.
    private static List<String> inFilters(final SimRandom r) {
        return r.percent(33) ? List.of() : filters(r);
    }

    /// One to three distinct filters.
    private static List<String> filters(final SimRandom r) {
        final Set<String> out = new LinkedHashSet<>();
        final int n = r.between(1, 3);
        while (out.size() < n) {
            out.add(FILTERS.get(r.nextInt(FILTERS.size())));
        }
        return List.copyOf(out);
    }

    private static List<String> topics(final SimRandom r, final int n, final List<String> from) {
        final List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(from.get(r.nextInt(from.size())));
        }
        return List.copyOf(out);
    }

    /// How a run is checked, as [Ledger] describes it.
    enum Check {
        /// Loss and order over first hops, for runs whose filters and links change.
        FIRST_HOP,
        /// Every hop against the octopus's paths, OUT-only walks owed, for runs with faults and fixed filters.
        PATHS,
        /// Every walk once, IN sides included, for runs without faults.
        EXACT
    }

    /// Nodes and their one-way links.
    record Topology(String name, List<String> nodes, List<Ledger.Link> links) {

        /// a to b to c to a.
        static final Topology RING = new Topology("ring", List.of("a", "b", "c"), List.of(
                new Ledger.Link("ab", "a", "b"), new Ledger.Link("bc", "b", "c"), new Ledger.Link("ca", "c", "a")));
        /// a and b and c in a row, a link each way between neighbours.
        static final Topology LINE = new Topology("line", List.of("a", "b", "c"), List.of(
                new Ledger.Link("ab", "a", "b"), new Ledger.Link("ba", "b", "a"), new Ledger.Link("bc", "b", "c"),
                new Ledger.Link("cb", "c", "b")));
        /// a to b to c to d, one way: d is three hops from a.
        static final Topology CHAIN = new Topology("chain", List.of("a", "b", "c", "d"), List.of(
                new Ledger.Link("ab", "a", "b"), new Ledger.Link("bc", "b", "c"), new Ledger.Link("cd", "c", "d")));
        /// a to b to c to d to a, and a shortcut from a to c: c is one hop and two hops from a.
        static final Topology SHORTCUT = new Topology("shortcut", List.of("a", "b", "c", "d"), List.of(
                new Ledger.Link("ab", "a", "b"), new Ledger.Link("bc", "b", "c"), new Ledger.Link("cd", "c", "d"),
                new Ledger.Link("da", "d", "a"), new Ledger.Link("ac", "a", "c")));
    }

    /// One thing a schedule does.
    sealed interface Step {
    }

    /// `node`'s client publishes one QoS 1 message per topic in `topics` without waiting between them, the relay of
    /// link `cut` (null for none) cutting its connections once they are all sent.
    record Batch(String node, List<String> topics, String cut) implements Step {

        @Override
        public String toString() {
            return "batch " + node + " count=" + topics.size() + (cut == null ? "" : " cut=" + cut);
        }
    }

    /// `count` QoS 1 messages on `topic` handed to `node`'s gateway in a loop, faster than a link's strand appends
    /// them.
    record Burst(String node, int count, String topic) implements Step {

        @Override
        public String toString() {
            return "burst " + node + " count=" + count + " topic=" + topic;
        }
    }

    /// The relay in front of link `link`'s peer closes every connection it carries, as a failed network would.
    record Cut(String link) implements Step {

        @Override
        public String toString() {
            return "cut " + link;
        }
    }

    /// Node `node` closes and starts again on the same data folder; its links are put again.
    record Restart(String node) implements Step {

        @Override
        public String toString() {
            return "restart " + node;
        }
    }

    /// Link `link` is removed; its log stays.
    record Remove(String link) implements Step {

        @Override
        public String toString() {
            return "remove " + link;
        }
    }

    /// Link `link`, removed, is put again with the filters it had.
    record PutBack(String link) implements Step {

        @Override
        public String toString() {
            return "put back " + link;
        }
    }

    /// Link `link`'s connection is cut, its near node's client publishes `topics` while it is down, and its OUT
    /// filters are replaced by `filters` while those messages wait in its log.
    record Refilter(String link, List<String> filters, List<String> topics) implements Step {

        @Override
        public String toString() {
            return "refilter " + link + " out=" + filters + " queuedFirst=" + topics.size();
        }
    }
}
