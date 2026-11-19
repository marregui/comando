package dev.marregui.comando.frontear.tree;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.marregui.comando.frontear.mqtt.Packet;
import dev.marregui.comando.frontear.mqtt.Topics;

/**
 * A broker's subscriptions: Topic Filters (MQTT 5 §4.7) in a tree of levels, each node holding the entries of the
 * filters that end there, and the entries that match a Topic Name merged to one {@link Match} per subscriber. A
 * subscriber's overlapping filters therefore deliver a message once, at the highest QoS among them.
 *
 * <p>Any thread may call any method. The tree is immutable and copied on write: a write copies the nodes on its
 * filter's path and publishes the new root with one volatile store; writes are serialised on a lock only writers
 * take. {@link #match} reads the root once and walks that snapshot without a lock, so it sees every write completed
 * before that read and none after it, and a {@link #removeAll} reaches it as one store, never half done. Copy on
 * write rather than a mutable tree read without locks: match runs on every PUBLISH while writes come with SUBSCRIBE
 * and UNSUBSCRIBE, and a walk over nodes that change under it is not linearizable (it can see a later write on one
 * branch and miss an earlier one on another). The price is on the write: a copy of each node on the path, its
 * children and entries included, so a write costs the width of the levels it passes. An {@link #add} of a new filter
 * and a {@link #remove} also copy the subscriber's set of filters, so they cost that set's size too: a subscriber's
 * n-th filter costs n, its first n cost n squared over two, and its owner bounds how many filters one subscriber
 * may hold (the broker's {@code maxSubscriptions} per client session).
 *
 * <p>A match allocates only for what it finds: levels are compared in place in the topic, and the map that merges
 * a subscriber's matches is made at the first entry that matches.
 *
 * <p>Writes and matches recurse once per level, so the tree holds no filter and matches no topic deeper than the
 * levels it was made with: that bound is the depth of its recursion.
 */
public final class TopicTree {

    private static final String[] NO_LEVELS = {};
    private static final Node[] NO_CHILDREN = {};
    private static final Entry[] NO_ENTRIES = {};

    private final Object writes = new Object();
    // subscriber id to its filters, each set immutable; written under writes, read by filters() without a lock
    private final Map<String, Set<String>> filtersById = new ConcurrentHashMap<>();
    // written by add, remove and removeAll under writes; read by match without a lock. The nodes it reaches are
    // never changed once built, and this volatile store publishes them to the reader that loads it.
    private volatile Node root = Node.EMPTY;
    private final int maxLevels;

    /** A tree of filters and topics at most {@code maxLevels} levels deep. */
    public TopicTree(final int maxLevels) {
        if (maxLevels < 1) {
            throw new IllegalArgumentException("max levels must be positive [value=" + maxLevels + ']');
        }
        this.maxLevels = maxLevels;
    }

    /**
     * Adds {@code entry} on {@code filter}, replacing the entry its subscriber already had there.
     *
     * @throws IllegalArgumentException when {@code filter} is not a Topic Filter (§4.7.1) or is deeper than the tree
     */
    public void add(final String filter, final Entry entry) {
        Objects.requireNonNull(entry, "entry");
        // a Subscription refuses a string that is not a Topic Filter, as Topics decides
        new Packet.Subscription(filter, entry.qos());
        if (Topics.levels(filter) > maxLevels) {
            throw new IllegalArgumentException("filter deeper than the tree [levels=" + Topics.levels(filter)
                    + ", max=" + maxLevels + ']');
        }
        final String id = entry.subscriberId();
        synchronized (writes) {
            root = insert(root, filter, 0, entry);
            final Set<String> filters = filtersById.get(id);
            if (filters == null) {
                filtersById.put(id, Set.of(filter));
            } else if (!filters.contains(filter)) {
                final Set<String> more = new HashSet<>(filters);
                more.add(filter);
                filtersById.put(id, Set.copyOf(more));
            }
        }
    }

    /**
     * Removes the entry of {@code subscriberId} on {@code filter}; false when it had none there, as for a filter
     * deeper than the tree, which {@link #add} never stores.
     */
    public boolean remove(final String filter, final String subscriberId) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(subscriberId, "subscriberId");
        if (Topics.levels(filter) > maxLevels) {
            return false;
        }
        synchronized (writes) {
            final Node next = remove(root, filter, 0, subscriberId);
            if (next == root) {
                return false;
            }
            root = next == null ? Node.EMPTY : next;
            final Set<String> filters = filtersById.get(subscriberId);
            if (filters.size() == 1) {
                filtersById.remove(subscriberId);
            } else {
                final Set<String> fewer = new HashSet<>(filters);
                fewer.remove(filter);
                filtersById.put(subscriberId, Set.copyOf(fewer));
            }
            return true;
        }
    }

    /** Removes every entry of {@code subscriberId}; a match sees all of them or none. */
    public void removeAll(final String subscriberId) {
        Objects.requireNonNull(subscriberId, "subscriberId");
        synchronized (writes) {
            final Set<String> filters = filtersById.get(subscriberId);
            if (filters == null) {
                return;
            }
            Node next = root;
            for (final String filter : filters) {
                final Node without = remove(next, filter, 0, subscriberId);
                next = without == null ? Node.EMPTY : without;
            }
            root = next;
            filtersById.remove(subscriberId);
        }
    }

    /**
     * One Match per subscriber whose filters match {@code topic}, a Topic Name, with No Local applied against
     * {@code publisherOwner} (an entry with No Local whose owner it is does not match); order unspecified. A topic
     * that starts with {@code $} is matched by no filter that starts with a wildcard (§4.7.2).
     *
     * @throws IllegalArgumentException when {@code topic} is deeper than the tree
     */
    public Collection<Match> match(final String topic, final String publisherOwner) {
        if (Topics.levels(topic) > maxLevels) {
            throw new IllegalArgumentException("topic deeper than the tree [levels=" + Topics.levels(topic)
                    + ", max=" + maxLevels + ']');
        }
        final Map<String, Match> found = walk(root, topic, 0, publisherOwner, null);
        return found == null ? List.of() : found.values();
    }

    /** The filters {@code subscriberId} has an entry on, an immutable set, empty when none. */
    public Set<String> filters(final String subscriberId) {
        return filtersById.getOrDefault(subscriberId, Set.of());
    }

    /**
     * Collects the entries of {@code node} and below that match {@code topic} from its level starting at
     * {@code from}; {@code from} past the topic's end means every level is matched. Returns {@code found}, or the map
     * made for the first match when it was null.
     */
    private static Map<String, Match> walk(final Node node, final String topic, final int from, final String owner,
            final Map<String, Match> found) {
        // §4.7.2: at the first level, a wildcard does not match a topic that starts with $
        final boolean wildcards = from > 0 || topic.isEmpty() || topic.charAt(0) != '$';
        // '#' matches the parent level and every level below it, so it matches wherever the walk reaches its node
        Map<String, Match> acc = wildcards ? collect(node.hash, owner, found) : found;
        if (from > topic.length()) {
            return collect(node.here, owner, acc);
        }
        final int end = Topics.levelEnd(topic, from);
        final int i = find(node.levels, topic, from, end);
        if (i >= 0) {
            acc = walk(node.children[i], topic, end + 1, owner, acc);
        }
        if (wildcards && node.plus != null) {
            acc = walk(node.plus, topic, end + 1, owner, acc);
        }
        return acc;
    }

    private static Map<String, Match> collect(final Entry[] entries, final String owner,
            final Map<String, Match> found) {
        Map<String, Match> acc = found;
        for (final Entry e : entries) {
            if (e.noLocal() && e.owner().equals(owner)) {
                continue;
            }
            if (acc == null) {
                acc = new HashMap<>();
            }
            acc.merge(e.subscriberId(), new Match(e.subscriberId(), e.qos(), e.retainAsPublished()), TopicTree::merged);
        }
        return acc;
    }

    private static Match merged(final Match a, final Match b) {
        return new Match(a.subscriberId(), Math.max(a.qos(), b.qos()), a.retainAsPublished() || b.retainAsPublished());
    }

    /** {@code node} with {@code entry} added on the filter's levels from {@code from} on, the path copied. */
    private static Node insert(final Node node, final String filter, final int from, final Entry entry) {
        if (from > filter.length()) {
            return new Node(node.levels, node.children, node.plus, node.hash, put(node.here, entry));
        }
        final int end = Topics.levelEnd(filter, from);
        if (wildcard(filter, from, end, '#')) {
            return new Node(node.levels, node.children, node.plus, put(node.hash, entry), node.here);
        }
        if (wildcard(filter, from, end, '+')) {
            final Node plus = insert(node.plus == null ? Node.EMPTY : node.plus, filter, end + 1, entry);
            return new Node(node.levels, node.children, plus, node.hash, node.here);
        }
        final int i = find(node.levels, filter, from, end);
        if (i >= 0) {
            final Node[] children = node.children.clone();
            children[i] = insert(children[i], filter, end + 1, entry);
            return new Node(node.levels, children, node.plus, node.hash, node.here);
        }
        final int at = -i - 1;
        final int n = node.levels.length;
        final String[] levels = new String[n + 1];
        final Node[] children = new Node[n + 1];
        System.arraycopy(node.levels, 0, levels, 0, at);
        System.arraycopy(node.children, 0, children, 0, at);
        levels[at] = filter.substring(from, end);
        children[at] = insert(Node.EMPTY, filter, end + 1, entry);
        System.arraycopy(node.levels, at, levels, at + 1, n - at);
        System.arraycopy(node.children, at, children, at + 1, n - at);
        return new Node(levels, children, node.plus, node.hash, node.here);
    }

    /**
     * {@code node} without the entry of {@code id} on the filter's levels from {@code from} on: the same node when it
     * has none there, null when nothing is left in it, else a copy with the emptied nodes below it cut off. A string
     * that is not a Topic Filter finds nothing, as {@link #add} never stores one.
     */
    private static Node remove(final Node node, final String filter, final int from, final String id) {
        if (from > filter.length()) {
            final Entry[] here = without(node.here, id);
            return here == node.here ? node : new Node(node.levels, node.children, node.plus, node.hash, here).live();
        }
        final int end = Topics.levelEnd(filter, from);
        if (end == filter.length() && wildcard(filter, from, end, '#')) {
            final Entry[] hash = without(node.hash, id);
            return hash == node.hash ? node : new Node(node.levels, node.children, node.plus, hash, node.here).live();
        }
        if (wildcard(filter, from, end, '+')) {
            if (node.plus == null) {
                return node;
            }
            final Node plus = remove(node.plus, filter, end + 1, id);
            return plus == node.plus ? node : new Node(node.levels, node.children, plus, node.hash, node.here).live();
        }
        final int i = find(node.levels, filter, from, end);
        if (i < 0) {
            return node;
        }
        final Node child = remove(node.children[i], filter, end + 1, id);
        if (child == node.children[i]) {
            return node;
        }
        if (child != null) {
            final Node[] children = node.children.clone();
            children[i] = child;
            return new Node(node.levels, children, node.plus, node.hash, node.here);
        }
        final int n = node.levels.length;
        final String[] levels = new String[n - 1];
        final Node[] children = new Node[n - 1];
        System.arraycopy(node.levels, 0, levels, 0, i);
        System.arraycopy(node.children, 0, children, 0, i);
        System.arraycopy(node.levels, i + 1, levels, i, n - i - 1);
        System.arraycopy(node.children, i + 1, children, i, n - i - 1);
        return new Node(levels, children, node.plus, node.hash, node.here).live();
    }

    /** {@code entries} with {@code entry} in place of its subscriber's, or added; a new array. */
    private static Entry[] put(final Entry[] entries, final Entry entry) {
        for (int i = 0; i < entries.length; i++) {
            if (entries[i].subscriberId().equals(entry.subscriberId())) {
                final Entry[] copy = entries.clone();
                copy[i] = entry;
                return copy;
            }
        }
        final Entry[] copy = Arrays.copyOf(entries, entries.length + 1);
        copy[entries.length] = entry;
        return copy;
    }

    /** {@code entries} without the entry of {@code id}: the same array when it has none. */
    private static Entry[] without(final Entry[] entries, final String id) {
        for (int i = 0; i < entries.length; i++) {
            if (entries[i].subscriberId().equals(id)) {
                if (entries.length == 1) {
                    return NO_ENTRIES;
                }
                final Entry[] copy = new Entry[entries.length - 1];
                System.arraycopy(entries, 0, copy, 0, i);
                System.arraycopy(entries, i + 1, copy, i, entries.length - i - 1);
                return copy;
            }
        }
        return entries;
    }

    private static boolean wildcard(final String s, final int from, final int end, final char wildcard) {
        return end - from == 1 && s.charAt(from) == wildcard;
    }

    /** The index of level {@code s[from, end)} in {@code levels}, sorted; else {@code -(insertion point) - 1}. */
    private static int find(final String[] levels, final String s, final int from, final int end) {
        int lo = 0;
        int hi = levels.length - 1;
        while (lo <= hi) {
            final int mid = lo + hi >>> 1;
            final int c = compare(levels[mid], s, from, end);
            if (c < 0) {
                lo = mid + 1;
            } else if (c > 0) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -(lo + 1);
    }

    /** {@code level} against {@code s[from, end)}, ordered as {@link String#compareTo} orders. */
    private static int compare(final String level, final String s, final int from, final int end) {
        final int length = end - from;
        final int n = Math.min(level.length(), length);
        for (int k = 0; k < n; k++) {
            final int d = level.charAt(k) - s.charAt(from + k);
            if (d != 0) {
                return d;
            }
        }
        return level.length() - length;
    }

    /**
     * One level of the tree, never changed once built: its literal children sorted by level, its {@code +} child,
     * the entries of the filters that end in {@code #} below it, and those of the filters that end at it.
     */
    private static final class Node {

        static final Node EMPTY = new Node(NO_LEVELS, NO_CHILDREN, null, NO_ENTRIES, NO_ENTRIES);

        final String[] levels;
        final Node[] children;
        final Node plus;
        final Entry[] hash;
        final Entry[] here;

        Node(final String[] levels, final Node[] children, final Node plus, final Entry[] hash, final Entry[] here) {
            this.levels = levels;
            this.children = children;
            this.plus = plus;
            this.hash = hash;
            this.here = here;
        }

        /** This node, or null when it holds nothing and leads to nothing. */
        Node live() {
            return levels.length == 0 && plus == null && hash.length == 0 && here.length == 0 ? null : this;
        }
    }
}
