package dev.marregui.comando.frontear.tree;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.frontear.mqtt.Topics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The topic tree: wildcard matching on the MQTT 5 specification's examples (§4.7.1.2, §4.7.1.3, §4.7.2) and against
/// `Topics.matches` on random filters, one merged match per subscriber, No Local, the writes, and matching while other
/// threads write. Seed in the output; replay with `-Dfrontear.tree.seed=<seed>`.
class TopicTreeTest {

    private static final long SEED = Long.getLong("frontear.tree.seed", System.nanoTime());
    private static final String PUBLISHER = "publisher";
    // the threads of a concurrent test, and how long the test waits for them to end
    private static final int WRITERS = 4;
    private static final int READERS = 4;
    private static final Duration JOIN = Duration.ofSeconds(60);
    // the broker's default depth; the depth tests below build their own trees
    private static final int LEVELS = 128;

    private Random random;
    private long seed;

    /// A generator per test, from the run's seed and the test's name, so one test replays alone.
    @BeforeEach
    void seed(final TestInfo info) {
        System.out.println(info.getDisplayName() + " seed=" + SEED);
        seed = SEED ^ info.getDisplayName().hashCode();
        random = new Random(seed);
    }

    /// The filters of §4.7.1.2 and §4.7.1.3, each its own subscriber, match the names the specification says: `#` the
    /// parent level and every level below it, `+` exactly one level, an empty one included.
    @Test
    void wildcardsMatchAsTheSpecificationsExamplesSay() {
        final TopicTree tree = new TopicTree(LEVELS);
        for (final String filter : new String[] {"sport/tennis/player1/#", "sport/#", "#", "sport/tennis/+", "sport/+",
            "+/+", "/+", "+", "sport/tennis", "+/tennis/#", "sport/+/player1"}) {
            tree.add(filter, entry(filter, 0));
        }
        assertThat(ids(tree.match("sport/tennis/player1", PUBLISHER))).containsExactlyInAnyOrder(
                "sport/tennis/player1/#", "sport/#", "#", "sport/tennis/+", "+/tennis/#", "sport/+/player1");
        assertThat(ids(tree.match("sport/tennis/player1/ranking", PUBLISHER))).containsExactlyInAnyOrder(
                "sport/tennis/player1/#", "sport/#", "#", "+/tennis/#");
        assertThat(ids(tree.match("sport/tennis/player1/score/wimbledon", PUBLISHER))).containsExactlyInAnyOrder(
                "sport/tennis/player1/#", "sport/#", "#", "+/tennis/#");
        assertThat(ids(tree.match("sport", PUBLISHER))).containsExactlyInAnyOrder("sport/#", "#", "+");
        assertThat(ids(tree.match("sport/", PUBLISHER))).containsExactlyInAnyOrder("sport/#", "#", "sport/+", "+/+");
        assertThat(ids(tree.match("/finance", PUBLISHER))).containsExactlyInAnyOrder("#", "+/+", "/+");
        assertThat(ids(tree.match("sport/tennis", PUBLISHER))).containsExactlyInAnyOrder("sport/#", "#", "sport/+",
                "+/+", "sport/tennis", "+/tennis/#");
        assertThat(ids(tree.match("sport/Tennis", PUBLISHER))).containsExactlyInAnyOrder("sport/#", "#", "sport/+",
                "+/+");
        assertThat(ids(tree.match("sport/tennis/", PUBLISHER))).containsExactlyInAnyOrder("sport/#", "#",
                "sport/tennis/+", "+/tennis/#");
    }

    /// A name that starts with `$` is matched by no filter that starts with a wildcard (§4.7.2), and is matched by a
    /// filter that names its first level, wildcards after it included.
    @Test
    void aDollarTopicIsMatchedOnlyByAFilterThatNamesItsFirstLevel() {
        final TopicTree tree = new TopicTree(LEVELS);
        for (final String filter : new String[] {"#", "+/monitor/Clients", "$SYS/#", "$SYS/monitor/+", "+/#"}) {
            tree.add(filter, entry(filter, 0));
        }
        assertThat(ids(tree.match("$SYS/monitor/Clients", PUBLISHER))).containsExactlyInAnyOrder("$SYS/#",
                "$SYS/monitor/+");
        assertThat(ids(tree.match("SYS/monitor/Clients", PUBLISHER))).containsExactlyInAnyOrder("#",
                "+/monitor/Clients", "+/#");
        assertThat(ids(tree.match("a/$SYS", PUBLISHER))).containsExactlyInAnyOrder("#", "+/#");
    }

    /// A subscriber whose filters overlap gets one match for a topic they all match, at the highest QoS among them and
    /// retaining as published when any of them does: a message reaches it once.
    @Test
    void overlappingFiltersOfOneSubscriberMergeToOneMatchAtTheHighestQos() {
        final TopicTree tree = new TopicTree(LEVELS);
        tree.add("a/#", new Entry("s", "s", 0, false, false));
        tree.add("a/+", new Entry("s", "s", 1, false, false));
        tree.add("a/b", new Entry("s", "s", 0, false, true));
        tree.add("#", new Entry("other", "other", 0, false, false));

        assertThat(tree.match("a/b", PUBLISHER)).containsExactlyInAnyOrder(new Match("s", 1, true),
                new Match("other", 0, false));
        assertThat(tree.match("a/c", PUBLISHER)).containsExactlyInAnyOrder(new Match("s", 1, false),
                new Match("other", 0, false));
        assertThat(tree.match("a", PUBLISHER)).containsExactlyInAnyOrder(new Match("s", 0, false),
                new Match("other", 0, false));
    }

    /// No Local drops an entry for a message its own owner published, and only that entry: the subscriber still
    /// matches through a filter without No Local, at that filter's QoS. A link's entries are owned by the link, so a
    /// message the link brought in is not sent back on it.
    @Test
    void noLocalDropsOnlyTheEntriesThePublisherOwns() {
        final TopicTree tree = new TopicTree(LEVELS);
        tree.add("t/#", new Entry("c1", "c1", 1, true, false));
        tree.add("t/x", new Entry("c1", "c1", 0, false, false));
        tree.add("t/#", new Entry("$link/peer", "$link/peer", 1, true, false));
        tree.add("t/+", new Entry("c2", "c2", 1, false, false));

        assertThat(tree.match("t/x", "c1")).containsExactlyInAnyOrder(new Match("c1", 0, false),
                new Match("$link/peer", 1, false), new Match("c2", 1, false));
        assertThat(tree.match("t/y", "c1")).containsExactlyInAnyOrder(new Match("$link/peer", 1, false),
                new Match("c2", 1, false));
        assertThat(tree.match("t/y", "$link/peer")).containsExactlyInAnyOrder(new Match("c1", 1, false),
                new Match("c2", 1, false));
        assertThat(tree.match("t/y", "c2")).containsExactlyInAnyOrder(new Match("c1", 1, false),
                new Match("$link/peer", 1, false), new Match("c2", 1, false));
    }

    /// An entry added again on the same filter for the same subscriber replaces the first: one filter, the new QoS
    /// and options.
    @Test
    void addingOnTheSameFilterReplacesTheSubscribersEntry() {
        final TopicTree tree = new TopicTree(LEVELS);
        tree.add("a/+", entry("s", 0));
        tree.add("a/+", new Entry("s", "s", 1, false, true));

        assertThat(tree.match("a/b", PUBLISHER)).containsExactly(new Match("s", 1, true));
        assertThat(tree.filters("s")).containsExactly("a/+");
    }

    /// A remove takes away one subscriber's entry on one filter and answers whether there was one; the other
    /// subscribers on the filter and the subscriber's other filters stay. A string that is not a filter finds nothing.
    @Test
    void removeTakesOneEntryAndSaysWhetherItWasThere() {
        final TopicTree tree = new TopicTree(LEVELS);
        tree.add("a/+/c", entry("s", 0));
        tree.add("a/+/c", entry("t", 0));
        tree.add("a/#", entry("s", 1));

        assertThat(tree.remove("a/+/c", "s")).isTrue();
        assertThat(tree.remove("a/+/c", "s")).isFalse();
        assertThat(tree.remove("a/+", "t")).isFalse();
        assertThat(tree.remove("a/#/c", "s")).isFalse();
        assertThat(tree.remove("nothing", "nobody")).isFalse();
        assertThat(tree.match("a/b/c", PUBLISHER)).containsExactlyInAnyOrder(new Match("s", 1, false),
                new Match("t", 0, false));
        assertThat(tree.filters("s")).containsExactly("a/#");

        assertThat(tree.remove("a/#", "s")).isTrue();
        assertThat(tree.remove("a/+/c", "t")).isTrue();
        assertThat(tree.match("a/b/c", PUBLISHER)).isEmpty();
        assertThat(tree.filters("s")).isEmpty();
        assertThat(tree.filters("t")).isEmpty();
    }

    /// `removeAll` takes every entry of one subscriber and none of another's, on shared filters too; on a subscriber
    /// with no entries it does nothing.
    @Test
    void removeAllTakesEveryEntryOfOneSubscriber() {
        final TopicTree tree = new TopicTree(LEVELS);
        for (final String filter : new String[] {"a", "a/+", "a/#", "#", "+/b"}) {
            tree.add(filter, entry("gone", 1));
        }
        tree.add("a/#", entry("kept", 0));

        tree.removeAll("gone");
        tree.removeAll("never");

        assertThat(tree.filters("gone")).isEmpty();
        assertThat(tree.filters("kept")).containsExactly("a/#");
        assertThat(tree.match("a/b", PUBLISHER)).containsExactly(new Match("kept", 0, false));
        assertThat(tree.match("x/b", PUBLISHER)).isEmpty();
    }

    /// `filters` answers a subscriber's filters as an immutable set that later writes do not change.
    @Test
    void filtersAreTheSubscribersFiltersAsAnImmutableSnapshot() {
        final TopicTree tree = new TopicTree(LEVELS);
        tree.add("a/#", entry("s", 0));
        tree.add("b/+", entry("s", 0));
        tree.add("c", entry("other", 0));

        final Set<String> filters = tree.filters("s");
        tree.add("d", entry("s", 0));

        assertThat(filters).containsExactlyInAnyOrder("a/#", "b/+");
        assertThat(tree.filters("s")).containsExactlyInAnyOrder("a/#", "b/+", "d");
        assertThat(tree.filters("nobody")).isEmpty();
        assertThatThrownBy(() -> filters.add("e")).isInstanceOf(UnsupportedOperationException.class);
    }

    /// A string that is not a Topic Filter, and an entry with a QoS above 2, are refused on add.
    @Test
    void addRefusesWhatIsNotATopicFilter() {
        final TopicTree tree = new TopicTree(LEVELS);
        for (final String invalid : new String[] {"", "a/#/b", "a+", "#a", "a\u0000"}) {
            assertThatThrownBy(() -> tree.add(invalid, entry("s", 0))).as(invalid)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new Entry("s", "s", 3, false, false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(tree.filters("s")).isEmpty();
    }

    /// On random filters over a few levels, `+`, `#` and a `$` level among them, spread over a few subscribers with
    /// random QoS and options, and after random removes, the tree matches what `Topics.matches` decides filter by
    /// filter, merged per subscriber with No Local applied.
    @Test
    void matchesAgreeWithTopicsOnRandomFilters() {
        final String[] levels = {"a", "b", "", "$s"};
        for (int trial = 0; trial < 200; trial++) {
            final TopicTree tree = new TopicTree(LEVELS);
            final Map<String, Map<String, Entry>> model = new HashMap<>();
            for (int k = 0; k < 40; k++) {
                final String filter = randomFilter(levels);
                final String id = "s" + random.nextInt(8);
                final Entry entry = new Entry(id, id, random.nextInt(3), random.nextBoolean(), random.nextBoolean());
                tree.add(filter, entry);
                model.computeIfAbsent(id, _ -> new HashMap<>()).put(filter, entry);
            }
            for (int k = 0; k < 15; k++) {
                final String id = "s" + random.nextInt(8);
                final Map<String, Entry> filters = model.getOrDefault(id, Map.of());
                if (!filters.isEmpty()) {
                    final String filter = new ArrayList<>(filters.keySet()).get(random.nextInt(filters.size()));
                    assertThat(tree.remove(filter, id)).isTrue();
                    filters.remove(filter);
                }
            }
            for (final Map.Entry<String, Map<String, Entry>> e : model.entrySet()) {
                assertThat(tree.filters(e.getKey())).isEqualTo(e.getValue().keySet());
            }
            for (int k = 0; k < 40; k++) {
                final String topic = randomTopic(levels);
                final String publisher = "s" + random.nextInt(10);
                assertThat(tree.match(topic, publisher)).as("seed=%d trial=%d topic=%s", seed, trial, topic)
                        .containsExactlyInAnyOrderElementsOf(expected(model, topic, publisher));
            }
        }
    }

    /// A write completed on one thread is seen by every match another thread starts after it: at each step a writer
    /// adds a filter and removes one added before the run, then announces the step; a reader that read the
    /// announcement finds the step's added filter and not its removed one. Neither is written again, so what the
    /// reader sees cannot depend on how far the writer got since.
    @Test
    void aMatchSeesEveryWriteCompletedBeforeIt() throws InterruptedException {
        final int steps = 20_000;
        final TopicTree tree = new TopicTree(LEVELS);
        for (int i = 1; i <= steps; i++) {
            tree.add(stepTopic("r", i), entry("r" + i, 0));
        }
        final AtomicInteger step = new AtomicInteger();
        final AtomicReference<String> failure = new AtomicReference<>();
        final AtomicBoolean stop = new AtomicBoolean();
        final CountDownLatch start = new CountDownLatch(1);
        final List<Thread> threads = new ArrayList<>();
        threads.add(Threads.fork("topic-tree-test-writer", () -> {
            try {
                await(start);
                for (int i = 1; i <= steps; i++) {
                    tree.add(stepTopic("w", i), entry("w" + i, 0));
                    tree.remove(stepTopic("r", i), "r" + i);
                    step.set(i);
                    Thread.yield();
                }
            } finally {
                stop.set(true);
            }
        }));
        for (int r = 0; r < READERS; r++) {
            threads.add(Threads.fork("topic-tree-test-reader-" + r, () -> {
                await(start);
                while (!stop.get()) {
                    final int i = step.get();
                    if (i > 0 && !ids(tree.match(stepTopic("w", i), PUBLISHER)).equals(Set.of("w" + i))) {
                        failure.compareAndSet(null, "add at step " + i + " not seen");
                    }
                    if (i > 0 && !tree.match(stepTopic("r", i), PUBLISHER).isEmpty()) {
                        failure.compareAndSet(null, "removal at step " + i + " not seen");
                    }
                    // a virtual thread is not preempted: without a yield, spinning readers can hold every carrier
                    Thread.yield();
                }
            }));
        }
        start.countDown();
        join(threads);

        assertThat(failure.get()).isNull();
    }

    /// While writers add and remove overlapping filters of other subscribers, and of the same subscriber on other
    /// filters, every match of a topic finds the subscriptions present throughout, each subscriber once and at no
    /// less than its standing QoS: no write loses a match it did not remove.
    @Test
    void concurrentWritesLoseNoMatchOfASubscriptionPresentThroughout() throws InterruptedException {
        final TopicTree tree = new TopicTree(LEVELS);
        tree.add("s/+/x", new Entry("keeper", "keeper", 1, false, false));
        tree.add("s/#", new Entry("keeper2", "keeper2", 0, false, false));
        final String[] filters = {"s/#", "s/+/x", "s/a/x", "+/a/x", "#", "s/a/+", "s/a", "+/+/+", "s/b/x"};
        final String[] keeperExtras = {"s/a/+", "s/#", "#", "+/a/x"};
        final AtomicReference<String> failure = new AtomicReference<>();
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicLong matches = new AtomicLong();
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch writersDone = new CountDownLatch(WRITERS);
        final List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < WRITERS; w++) {
            final Random r = new Random(seed + w);
            threads.add(Threads.fork("topic-tree-test-writer-" + w, () -> {
                await(start);
                for (int i = 0; i < 20_000; i++) {
                    final int op = r.nextInt(10);
                    final String id = "c" + r.nextInt(6);
                    if (op < 4) {
                        tree.add(filters[r.nextInt(filters.length)], new Entry(id, id, r.nextInt(2), false, false));
                    } else if (op < 7) {
                        tree.remove(filters[r.nextInt(filters.length)], id);
                    } else if (op < 8) {
                        tree.removeAll(id);
                    } else {
                        final String extra = keeperExtras[r.nextInt(keeperExtras.length)];
                        if (r.nextBoolean()) {
                            tree.add(extra, new Entry("keeper", "keeper", 0, false, false));
                        } else {
                            tree.remove(extra, "keeper");
                        }
                    }
                    Thread.yield();
                }
                writersDone.countDown();
            }));
        }
        for (int m = 0; m < READERS; m++) {
            threads.add(Threads.fork("topic-tree-test-reader-" + m, () -> {
                await(start);
                while (!stop.get()) {
                    final Collection<Match> found = tree.match("s/a/x", PUBLISHER);
                    matches.incrementAndGet();
                    final Map<String, Match> byId = new HashMap<>();
                    for (final Match match : found) {
                        if (byId.put(match.subscriberId(), match) != null) {
                            failure.compareAndSet(null, "two matches for " + match.subscriberId());
                        }
                    }
                    final Match keeper = byId.get("keeper");
                    if (keeper == null || keeper.qos() < 1) {
                        failure.compareAndSet(null, "keeper lost: " + found);
                    }
                    if (!byId.containsKey("keeper2")) {
                        failure.compareAndSet(null, "keeper2 lost: " + found);
                    }
                    Thread.yield();
                }
            }));
        }
        start.countDown();
        final boolean written = writersDone.await(JOIN.toMillis(), TimeUnit.MILLISECONDS);
        stop.set(true);
        join(threads);

        assertThat(written).isTrue();
        assertThat(failure.get()).isNull();
        assertThat(matches.get()).isPositive();
        assertThat(tree.filters("keeper")).contains("s/+/x");
    }

    /// `removeAll` reaches a match at once. A writer adds a subscriber on `t/b` (QoS 1) and on `t/#` (retain as
    /// published), so its whole match of `t/b` is QoS 1 retained as published, then removes it whole; a sequence it
    /// makes even between the adds and the next round marks the window where only the removal runs. A match that
    /// started and ended in one such window sees the whole match or none, never one filter's alone, which a removal
    /// filter by filter shows whatever order it takes the filters in.
    @Test
    void removeAllIsSeenWholeOrNotAtAll() throws InterruptedException {
        final TopicTree tree = new TopicTree(LEVELS);
        final Match whole = new Match("x", 1, true);
        final AtomicLong phase = new AtomicLong();
        final AtomicLong checked = new AtomicLong();
        final AtomicReference<String> failure = new AtomicReference<>();
        final AtomicBoolean stop = new AtomicBoolean();
        final CountDownLatch start = new CountDownLatch(1);
        final List<Thread> threads = new ArrayList<>();
        threads.add(Threads.fork("topic-tree-test-writer", () -> {
            try {
                await(start);
                for (int i = 0; i < 20_000; i++) {
                    phase.incrementAndGet();
                    tree.add("t/b", new Entry("x", "x", 1, false, false));
                    tree.add("t/#", new Entry("x", "x", 0, false, true));
                    phase.incrementAndGet();
                    tree.removeAll("x");
                    Thread.yield();
                }
            } finally {
                stop.set(true);
            }
        }));
        for (int m = 0; m < READERS; m++) {
            threads.add(Threads.fork("topic-tree-test-reader-" + m, () -> {
                await(start);
                while (!stop.get()) {
                    final long before = phase.get();
                    final Collection<Match> found = tree.match("t/b", PUBLISHER);
                    if ((before & 1) == 0 && phase.get() == before) {
                        checked.incrementAndGet();
                        if (!found.isEmpty() && !List.copyOf(found).equals(List.of(whole))) {
                            failure.compareAndSet(null, "half removed: " + found);
                        }
                    }
                    Thread.yield();
                }
            }));
        }
        start.countDown();
        join(threads);

        assertThat(failure.get()).isNull();
        assertThat(checked.get()).isPositive();
        assertThat(tree.filters("x")).isEmpty();
    }

    /// A filter or a topic deeper than the tree is refused before the tree walks it, so no walk recurses past the
    /// bound: an add and a match throw, a remove finds nothing; at the bound itself all three work.
    @Test
    void aFilterOrTopicDeeperThanTheTreeIsRefused() {
        final TopicTree tree = new TopicTree(3);
        tree.add("a/+/c", entry("s", 1));
        assertThat(ids(tree.match("a/b/c", PUBLISHER))).containsExactly("s");
        assertThatThrownBy(() -> tree.add("a/b/c/d", entry("s", 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tree.add("///", entry("s", 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tree.match("a/b/c/d", PUBLISHER)).isInstanceOf(IllegalArgumentException.class);
        assertThat(tree.remove("a/+/c/d", "s")).isFalse();
        assertThat(tree.filters("s")).containsExactly("a/+/c");
        assertThat(tree.remove("a/+/c", "s")).isTrue();
        assertThatThrownBy(() -> new TopicTree(0)).isInstanceOf(IllegalArgumentException.class);
    }

    /// The merged matches `model` gives `topic`, filter by filter through `Topics.matches`.
    private static List<Match> expected(final Map<String, Map<String, Entry>> model, final String topic,
            final String publisher) {
        final List<Match> out = new ArrayList<>();
        for (final Map.Entry<String, Map<String, Entry>> subscriber : model.entrySet()) {
            int qos = -1;
            boolean retainAsPublished = false;
            for (final Map.Entry<String, Entry> e : subscriber.getValue().entrySet()) {
                final Entry entry = e.getValue();
                if (Topics.matches(e.getKey(), topic) && !(entry.noLocal() && entry.owner().equals(publisher))) {
                    qos = Math.max(qos, entry.qos());
                    retainAsPublished |= entry.retainAsPublished();
                }
            }
            if (qos >= 0) {
                out.add(new Match(subscriber.getKey(), qos, retainAsPublished));
            }
        }
        return out;
    }

    /// The topic of step `i` under `root`, two levels below it so no level holds more than a hundred names.
    private static String stepTopic(final String root, final int i) {
        return root + '/' + i / 100 + '/' + i % 100;
    }

    private static Entry entry(final String id, final int qos) {
        return new Entry(id, id, qos, false, false);
    }

    private static Set<String> ids(final Collection<Match> matches) {
        final Set<String> ids = new HashSet<>();
        for (final Match match : matches) {
            ids.add(match.subscriberId());
        }
        return ids;
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(JOIN.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("start not given [waitedMs=" + JOIN.toMillis() + ']');
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting to start", e);
        }
    }

    private static void join(final List<Thread> threads) throws InterruptedException {
        final long deadline = System.nanoTime() + JOIN.toNanos();
        for (final Thread t : threads) {
            final long left = deadline - System.nanoTime();
            if (left > 0) {
                t.join(Duration.ofNanos(left));
            }
            assertThat(t.isAlive()).as(t.getName()).isFalse();
        }
    }

    private String randomFilter(final String[] levels) {
        while (true) {
            final StringBuilder filter = new StringBuilder();
            final int n = 1 + random.nextInt(4);
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    filter.append('/');
                }
                final int pick = random.nextInt(levels.length + 2);
                if (pick == levels.length + 1 && i == n - 1) {
                    filter.append('#');
                } else if (pick >= levels.length) {
                    filter.append('+');
                } else {
                    filter.append(levels[pick]);
                }
            }
            if (!filter.isEmpty()) {
                return filter.toString();
            }
        }
    }

    private String randomTopic(final String[] levels) {
        while (true) {
            final StringBuilder topic = new StringBuilder();
            final int n = 1 + random.nextInt(4);
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    topic.append('/');
                }
                topic.append(levels[random.nextInt(levels.length)]);
            }
            if (!topic.isEmpty()) {
                return topic.toString();
            }
        }
    }
}
