package dev.marregui.comando.cdb.store;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [BlockArenas.Epoch] reuses the segments its blocks give back: a reused segment holds the bytes its last holder
/// left, unzeroed, keeps the alignment asked for and adds no byte to the epoch; each size class keeps at most
/// [BlockArenas#FREE_DEPTH]; a closed epoch keeps none; and threads that allocate and free at once never share a
/// segment. A fake clock keeps one epoch; the concurrent case is seeded (`-Dcdb.arena.seed`).
class BlockArenasRecycleTest {

    private static final long SEED = Long.getLong("cdb.arena.seed", System.nanoTime());
    private static final long AGE_NANOS = 100L;
    private static final long CHUNK = 4_096L;
    // large enough that no join in these tests ends the epoch by its bytes
    private static final long BYTES = 1L << 30;
    // a segment allocated alone: over a quarter of a chunk
    private static final long LONE = CHUNK / 4L + 1L;
    private static final byte STALE = 0x5A;

    private final AtomicLong nowNanos = new AtomicLong(1_000L);
    private final BlockArenas arenas = new BlockArenas(AGE_NANOS, BYTES, CHUNK, nowNanos::get);

    /// Every epoch a test left young is aged and swept, so no arena outlives its test.
    @AfterEach
    void closeEpochs() {
        nowNanos.addAndGet(AGE_NANOS);
        arenas.sweep();
        assertThat(arenas.openArenas()).isZero();
    }

    /// A segment cut from a chunk and freed comes back to the next request of its size class (64 to 127 bytes) it
    /// holds, at its address, cut to the request and holding the bytes its last holder left, and the epoch holds no
    /// more bytes than before.
    @Test
    void aFreedSegmentIsReusedAsItsLastHolderLeftIt() {
        final BlockArenas.Epoch epoch = arenas.join();
        final MemorySegment first = epoch.allocate(120L, Long.BYTES);
        first.fill(STALE);
        final long held = epoch.bytes();
        epoch.free(first);
        assertThat(epoch.freeSegments(120L)).isEqualTo(1);
        assertThat(epoch.allocate(63L, Long.BYTES).address()).as("of the class below").isNotEqualTo(first.address());
        final MemorySegment again = epoch.allocate(100L, Long.BYTES);
        assertThat(again.address()).isEqualTo(first.address());
        assertThat(again.byteSize()).isEqualTo(100L);
        assertThat(count(again, STALE)).as("not zeroed again").isEqualTo(100L);
        assertThat(epoch.bytes()).as("reused, not allocated").isEqualTo(held);
        assertThat(epoch.freeSegments(120L)).isZero();
        arenas.leave(epoch);
    }

    /// A freed segment at an address the request's alignment does not divide is left on the list: the request gets a
    /// segment aligned as it asked.
    @Test
    void aMisalignedFreedSegmentIsNotReused() {
        final BlockArenas.Epoch epoch = arenas.join();
        epoch.allocate(1L, 1L);
        final MemorySegment odd = epoch.allocate(9L, 1L);
        assertThat(odd.address() % Long.BYTES).isNotZero();
        epoch.free(odd);
        final MemorySegment aligned = epoch.allocate(9L, Long.BYTES);
        assertThat(aligned.address() % Long.BYTES).isZero();
        assertThat(epoch.freeSegments(9L)).as("kept for a request it suits").isEqualTo(1);
        assertThat(epoch.allocate(9L, 1L).address()).isEqualTo(odd.address());
        arenas.leave(epoch);
    }

    /// A size class keeps at most FREE_DEPTH segments: of FREE_DEPTH + 10 freed lone segments, FREE_DEPTH come back,
    /// each as it was freed, and the 10 past them are allocated anew, zeroed, the epoch's bytes growing by theirs.
    @Test
    void aSizeClassKeepsAtMostFreeDepthSegments() {
        final BlockArenas.Epoch epoch = arenas.join();
        final int count = BlockArenas.FREE_DEPTH + 10;
        final Set<Long> freed = new HashSet<>();
        final List<MemorySegment> segments = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final MemorySegment segment = epoch.allocate(LONE, Long.BYTES);
            segment.fill(STALE);
            segments.add(segment);
        }
        final long held = epoch.bytes();
        assertThat(held).isEqualTo(count * LONE);
        for (final MemorySegment segment : segments) {
            freed.add(segment.address());
            epoch.free(segment);
        }
        assertThat(epoch.freeSegments(LONE)).isEqualTo(BlockArenas.FREE_DEPTH);
        int reused = 0;
        for (int i = 0; i < count; i++) {
            final MemorySegment segment = epoch.allocate(LONE, Long.BYTES);
            final boolean again = freed.contains(segment.address());
            assertThat(count(segment, again ? STALE : 0)).as("segment %d", i).isEqualTo(LONE);
            reused += again ? 1 : 0;
        }
        assertThat(reused).isEqualTo(BlockArenas.FREE_DEPTH);
        assertThat(epoch.bytes()).as("only those past the bound allocated").isEqualTo(held + 10L * LONE);
        assertThat(epoch.freeSegments(LONE)).isZero();
        arenas.leave(epoch);
    }

    /// An epoch's arena closes with its free list: a segment it kept is not alive, and the next epoch starts with none.
    @Test
    void aClosedEpochKeepsNoFreeSegment() {
        final BlockArenas.Epoch epoch = arenas.join();
        final MemorySegment segment = epoch.allocate(64L, Long.BYTES);
        epoch.free(segment);
        nowNanos.addAndGet(AGE_NANOS);
        arenas.leave(epoch);
        assertThat(segment.scope().isAlive()).isFalse();
        assertThat(epoch.freeSegments(64L)).isZero();
        assertThat(arenas.openArenas()).isZero();
        final BlockArenas.Epoch next = arenas.join();
        assertThat(next).isNotSameAs(epoch);
        assertThat(next.freeSegments(64L)).isZero();
        arenas.leave(next);
    }

    /// Threads of one epoch allocate, stamp, check and free segments of seeded sizes at once: every segment reads one
    /// byte throughout when handed out (zero, or the stamp of the thread that freed it) and keeps its thread's stamp
    /// until freed, so no two threads hold the same bytes.
    @Test
    void threadsOfOneEpochNeverShareASegment() throws Exception {
        System.out.println("seed=" + SEED + " (-Dcdb.arena.seed)");
        final int threads = 4;
        final int rounds = 2_000;
        final BlockArenas.Epoch epoch = arenas.join();
        final CyclicBarrier start = new CyclicBarrier(threads);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicReference<String> failure = new AtomicReference<>();
        final List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final byte stamp = (byte) (t + 1);
            final Random random = new Random(SEED + t);
            workers.add(Thread.ofPlatform().name("arena-recycle-" + t).start(() -> {
                try {
                    start.await(10L, TimeUnit.SECONDS);
                    final ArrayDeque<MemorySegment> held = new ArrayDeque<>();
                    for (int i = 0; i < rounds && failure.get() == null; i++) {
                        final long size = 1L + random.nextInt((int) (2L * LONE));
                        final MemorySegment segment = epoch.allocate(size, Long.BYTES);
                        if (count(segment, segment.get(ValueLayout.JAVA_BYTE, 0L)) != size) {
                            failure.compareAndSet(null, "mixed bytes handed out [thread=" + stamp + ", size=" + size
                                    + ']');
                        }
                        segment.fill(stamp);
                        held.addLast(segment);
                        if (held.size() > 8) {
                            free(epoch, held.removeFirst(), stamp, failure);
                        }
                    }
                    while (!held.isEmpty()) {
                        free(epoch, held.removeFirst(), stamp, failure);
                    }
                } catch (final Exception e) {
                    failure.compareAndSet(null, e.toString());
                } finally {
                    done.countDown();
                }
            }));
        }
        assertThat(done.await(60L, TimeUnit.SECONDS)).as("workers finished").isTrue();
        for (final Thread worker : workers) {
            worker.join(TimeUnit.SECONDS.toMillis(10L));
        }
        assertThat(failure.get()).isNull();
        arenas.leave(epoch);
    }

    private static void free(final BlockArenas.Epoch epoch, final MemorySegment segment, final byte stamp,
                             final AtomicReference<String> failure) {
        for (long i = 0L; i < segment.byteSize(); i++) {
            if (segment.get(ValueLayout.JAVA_BYTE, i) != stamp) {
                failure.compareAndSet(null, "overwritten while held [thread=" + stamp + ", offset=" + i + ']');
                break;
            }
        }
        epoch.free(segment);
    }

    private static long count(final MemorySegment segment, final int value) {
        long found = 0L;
        for (long i = 0L; i < segment.byteSize(); i++) {
            if (segment.get(ValueLayout.JAVA_BYTE, i) == (byte) value) {
                found++;
            }
        }
        return found;
    }
}
