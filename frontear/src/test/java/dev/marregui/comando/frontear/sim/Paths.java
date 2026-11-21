package dev.marregui.comando.frontear.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.marregui.comando.frontear.protocol.Topics;

/// The delivery paths of a mesh whose filters do not change: every walk a message on a topic can make from the node
/// that published it, one carrier at a time, as the link contract routes it. A carrier is a link's OUT side, from its
/// near node to its far node for a topic its OUT filters match, or its IN side, from its far node back to its near
/// node for a topic its IN filters match. A walk has at most the hop limit's carriers, since a link carries on only a
/// message that has made fewer hops than its limit, and never takes a link right after it took the same link the
/// other way: the IN subscription is No Local against the link's own client, which is what published the message
/// its OUT side carried, and the OUT subscription is No Local against the link's owner, which is what published the
/// message its IN side carried. Each walk is one delivery: every subscriber of the node it ends at whose filters match
/// the topic gets one copy, with the walk's length as its hop count; the walk of no carriers is the local delivery.
/// One thread uses an instance.
final class Paths {

    private final int hopLimit;
    private final Map<String, List<String>> in;
    private final List<Ledger.Link> links;
    private final Map<String, List<String>> out;
    private final Map<String, List<Walk>> walks = new HashMap<>();

    /// The paths over `links`, each with hop limit `hopLimit` and the OUT and IN filters `out` and `in` give it by
    /// id (none when absent).
    Paths(final int hopLimit, final List<Ledger.Link> links, final Map<String, List<String>> out,
            final Map<String, List<String>> in) {
        this.hopLimit = hopLimit;
        this.links = List.copyOf(links);
        this.out = Map.copyOf(out);
        this.in = Map.copyOf(in);
    }

    /// The hop limit every link has.
    int hopLimit() {
        return hopLimit;
    }

    /// Every walk of a message `source` publishes on `topic`, the local delivery first, shorter walks before longer.
    List<Walk> walks(final String source, final String topic) {
        return walks.computeIfAbsent(source + ' ' + topic, _ -> enumerate(source, topic));
    }

    private static boolean admits(final List<String> filters, final String topic) {
        for (final String f : filters) {
            if (Topics.matches(f, topic)) {
                return true;
            }
        }
        return false;
    }

    private List<Walk> enumerate(final String source, final String topic) {
        final List<Walk> all = new ArrayList<>();
        all.add(new Walk(List.of(), source));
        // breadth first: the walks of length h are extended into those of length h + 1
        for (int from = 0, to = all.size(); from < to; from = to, to = all.size()) {
            for (int i = from; i < to; i++) {
                final Walk w = all.get(i);
                if (w.hops() < hopLimit) {
                    extend(w, topic, all);
                }
            }
        }
        return List.copyOf(all);
    }

    private void extend(final Walk w, final String topic, final List<Walk> into) {
        final Carrier last = w.carriers().isEmpty() ? null : w.carriers().getLast();
        for (final Ledger.Link l : links) {
            if (last != null && last.link().equals(l.id())) {
                continue;
            }
            if (l.from().equals(w.end()) && admits(out.getOrDefault(l.id(), List.of()), topic)) {
                into.add(w.then(new Carrier(l.id(), false), l.to()));
            }
            if (l.to().equals(w.end()) && admits(in.getOrDefault(l.id(), List.of()), topic)) {
                into.add(w.then(new Carrier(l.id(), true), l.from()));
            }
        }
    }

    /// One carrier: link `link`'s OUT side, or its IN side when `in`.
    record Carrier(String link, boolean in) {

        @Override
        public String toString() {
            return link + (in ? "<" : ">");
        }
    }

    /// A walk of `carriers` from its source, ending at node `end`.
    record Walk(List<Carrier> carriers, String end) {

        @Override
        public String toString() {
            if (carriers.isEmpty()) {
                return "local";
            }
            final StringBuilder b = new StringBuilder();
            for (final Carrier c : carriers) {
                b.append(c);
            }
            return b.toString();
        }

        /// The hops a message has made at the end of this walk.
        int hops() {
            return carriers.size();
        }

        /// Whether every carrier is an OUT side: each hop then is a link's log, so nothing acknowledged is lost on
        /// the way, while an IN side is a client session that a cut or a restart ends.
        boolean outOnly() {
            for (final Carrier c : carriers) {
                if (c.in()) {
                    return false;
                }
            }
            return true;
        }

        /// Whether the walk takes link `id`, either side.
        boolean takes(final String id) {
            for (final Carrier c : carriers) {
                if (c.link().equals(id)) {
                    return true;
                }
            }
            return false;
        }

        private Walk then(final Carrier c, final String next) {
            final List<Carrier> longer = new ArrayList<>(carriers);
            longer.add(c);
            return new Walk(List.copyOf(longer), next);
        }
    }
}
