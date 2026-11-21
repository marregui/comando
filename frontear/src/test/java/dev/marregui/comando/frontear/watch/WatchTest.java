package dev.marregui.comando.frontear.watch;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.frontear.protocol.Packet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The watch's ring on its own: sequences from 1, reads from any point, overwrites counted missed, writers that never
/// wait for a reader, and nothing built while it is off.
class WatchTest {

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final int WRITERS = 8;
    private static final int EACH = 100_000;
    // the node's ring size, so the concurrent test overwrites as a node under load would
    private static final int CAPACITY = 16_384;
    // the reader's pause when it found nothing new, so it does not spin on a virtual thread
    private static final long IDLE_NANOS = TimeUnit.MICROSECONDS.toNanos(50);

    /// The first event is sequence 1; `since(0)` reads every event kept, oldest first, and its `last` is the next
    /// call's `after`, which then reads nothing new.
    @Test
    void sinceZeroReadsFromSequenceOne() {
        final Watch watch = on(8);
        for (int i = 0; i < 5; i++) {
            watch.step("l", LinkStep.Kind.SENT, i, "t", 1, "");
        }
        final Watch.Batch batch = watch.since(0, 100);
        assertThat(batch.events()).extracting(Observation::seq).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(batch.events()).allSatisfy(e -> assertThat(e).isInstanceOf(LinkStep.class));
        assertThat(batch.missed()).isZero();
        assertThat(batch.last()).isEqualTo(5);
        final Watch.Batch none = watch.since(batch.last(), 100);
        assertThat(none.events()).isEmpty();
        assertThat(none.missed()).isZero();
        assertThat(none.last()).isEqualTo(5);
    }

    /// A read from the middle starts after the sequence given; `max` bounds a batch, and the next read goes on from
    /// its `last`.
    @Test
    void sinceTheMiddleReadsWhatFollowsInBatchesOfMax() {
        final Watch watch = on(16);
        for (int i = 0; i < 10; i++) {
            watch.round("l", 1, 10, 100);
        }
        final Watch.Batch first = watch.since(4, 3);
        assertThat(first.events()).extracting(Observation::seq).containsExactly(5L, 6L, 7L);
        assertThat(first.last()).isEqualTo(7);
        final Watch.Batch rest = watch.since(first.last(), 100);
        assertThat(rest.events()).extracting(Observation::seq).containsExactly(8L, 9L, 10L);
        assertThat(rest.missed()).isZero();
        assertThat(rest.last()).isEqualTo(10);
    }

    /// Past a ring's worth the oldest are overwritten: a read from before them returns the latest ring and counts the
    /// rest missed, `last` covering both; a read inside the ring misses nothing.
    @Test
    void anOverwrittenRingCountsWhatItLost() {
        final Watch watch = on(8);
        for (int i = 0; i < 20; i++) {
            watch.step("l", LinkStep.Kind.APPENDED, i, "t", 1, "");
        }
        final Watch.Batch all = watch.since(0, 100);
        assertThat(all.events()).extracting(Observation::seq).containsExactly(13L, 14L, 15L, 16L, 17L, 18L, 19L,
                20L);
        assertThat(all.missed()).isEqualTo(12);
        assertThat(all.last()).isEqualTo(20);
        final Watch.Batch some = watch.since(2, 3);
        assertThat(some.events()).extracting(Observation::seq).containsExactly(13L, 14L, 15L);
        assertThat(some.missed()).isEqualTo(10);
        assertThat(some.last()).isEqualTo(15);
        final Watch.Batch inside = watch.since(15, 100);
        assertThat(inside.events()).extracting(Observation::seq).containsExactly(16L, 17L, 18L, 19L, 20L);
        assertThat(inside.missed()).isZero();
    }

    /// A published message is kept with its correlation data and payload head cut to their bounds, its hops and the
    /// subscriptions it matched.
    @Test
    void aPublishedMessageKeepsItsBoundedCorrelationAndHead() {
        final Watch watch = on(8);
        final byte[] payload = "x".repeat(Watch.HEAD_BYTES + 10).getBytes(StandardCharsets.UTF_8);
        final byte[] correlation = new byte[Watch.CORRELATION_BYTES + 4];
        watch.published("pub", new Packet.Publish("t/a", payload, 1, false, false, 1, "r/a", correlation), 2);
        assertThat(watch.since(0, 1).events()).singleElement().isInstanceOfSatisfying(Published.class, p -> {
            assertThat(p.origin()).isEqualTo("pub");
            assertThat(p.topic()).isEqualTo("t/a");
            assertThat(p.qos()).isEqualTo(1);
            assertThat(p.bytes()).isEqualTo(payload.length);
            assertThat(p.matches()).isEqualTo(2);
            assertThat(p.head()).hasSize(Watch.HEAD_BYTES);
            assertThat(p.correlation()).hasSize(Watch.CORRELATION_BYTES);
            assertThat(p.hops()).isZero();
        });
    }

    /// Off, the hooks keep nothing and allocate nothing on the calling thread; turned on, the first event kept is
    /// sequence 1, since no sequence was claimed while off.
    @Test
    void anOffWatchAllocatesNoEvent() {
        final Watch watch = new Watch(8);
        final Packet.Publish message = new Packet.Publish("t/a", new byte[64], 1, false, false, 1, null,
                new byte[4]);
        final com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled()).isTrue();
        final long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < EACH; i++) {
            watch.published("pub", message, 1);
            watch.step("l", LinkStep.Kind.SENT, i, "t/a", 64, "");
            watch.round("l", 1, 64, 10);
        }
        final long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        // 300,000 hook calls: one event each would be megabytes; what remains is the measurement's own few bytes
        assertThat(allocated).isLessThan(1_024);
        assertThat(watch.since(0, 10).events()).isEmpty();
        watch.on(true);
        watch.step("l", LinkStep.Kind.CONNECTED, -1, null, 0, "");
        assertThat(watch.since(0, 10).events()).extracting(Observation::seq).containsExactly(1L);
    }

    /// Eight writers keep 100,000 events each while a reader drains the ring as fast as it can: no writer waits, so
    /// the reader loses some to overwrites, and every sequence is seen at most once, in order, and is either seen or
    /// counted missed.
    @Test
    void concurrentWritersAndAReaderAccountForEverySequence() throws Exception {
        final Watch watch = on(CAPACITY);
        final long written = (long) WRITERS * EACH;
        final CountDownLatch start = new CountDownLatch(1);
        final List<Thread> writers = new ArrayList<>();
        for (int w = 0; w < WRITERS; w++) {
            final String link = "w" + w;
            writers.add(Threads.fork("watch-test-writer-" + w, () -> {
                try {
                    if (start.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                        for (int i = 0; i < EACH; i++) {
                            watch.step(link, LinkStep.Kind.APPENDED, i, "t", 1, "");
                        }
                    }
                } catch (final InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        final BitSet seen = new BitSet();
        final AtomicLong seenCount = new AtomicLong();
        final AtomicLong duplicates = new AtomicLong();
        final AtomicLong missed = new AtomicLong();
        final AtomicLong outOfOrder = new AtomicLong();
        final Thread reader = Threads.fork("watch-test-reader", () -> {
            long last = 0;
            while (last < written && !Thread.currentThread().isInterrupted()) {
                final Watch.Batch batch = watch.since(last, CAPACITY);
                long previous = last;
                for (final Observation e : batch.events()) {
                    if (e.seq() <= previous) {
                        outOfOrder.incrementAndGet();
                    }
                    previous = e.seq();
                    if (seen.get((int) e.seq())) {
                        duplicates.incrementAndGet();
                    }
                    seen.set((int) e.seq());
                    seenCount.incrementAndGet();
                }
                missed.addAndGet(batch.missed());
                if (batch.last() == last) {
                    LockSupport.parkNanos(IDLE_NANOS);
                }
                last = batch.last();
            }
        });
        start.countDown();
        for (final Thread t : writers) {
            assertThat(t.join(WAIT)).as("writer %s ended", t.getName()).isTrue();
        }
        if (!reader.join(WAIT)) {
            reader.interrupt();
            assertThat(reader.join(WAIT)).as("reader ended once interrupted").isTrue();
        }
        assertThat(duplicates.get()).isZero();
        assertThat(outOfOrder.get()).isZero();
        assertThat(seenCount.get() + missed.get()).isEqualTo(written);
        assertThat(seen.cardinality()).isEqualTo((int) seenCount.get());
        assertThat(seen.nextSetBit(0)).isGreaterThanOrEqualTo(1);
        assertThat(seen.length() - 1L).isLessThanOrEqualTo(written);
    }

    /// A capacity that is not a power of two, and a negative batch bound, are refused.
    @Test
    void aBadCapacityOrBoundIsRefused() {
        assertThatThrownBy(() -> new Watch(12)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Watch(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> on(8).since(0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Watch on(final int capacity) {
        final Watch watch = new Watch(capacity);
        watch.on(true);
        return watch;
    }
}
