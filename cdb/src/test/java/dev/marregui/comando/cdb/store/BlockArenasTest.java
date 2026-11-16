package dev.marregui.comando.cdb.store;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [BlockArenas]: blocks opened within one epoch share one shared arena; an epoch ends by age or bytes, and its arena
/// closes only once it ended and its last block left, by that leave or by a sweep; memory stays readable while a
/// block holds it, across rotations. A fake clock drives the ages; the concurrent case is seeded (`-Dcdb.arena.seed`).
class BlockArenasTest {

    private static final long SEED = Long.getLong("cdb.arena.seed", System.nanoTime());
    private static final long AGE = 100L;
    private static final long BYTES = 1_024L;

    private final AtomicLong now = new AtomicLong(1_000L);
    private final BlockArenas arenas = new BlockArenas(AGE, BYTES, now::get);

    /// Blocks joined within one epoch share its arena: ten of them open one arena, and it stays open after they leave
    /// while the epoch is young, so a steady stream of commits reuses it instead of closing one arena per commit.
    @Test
    void blocksOfOneEpochShareOneArena() {
        final List<BlockArenas.Epoch> joined = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            joined.add(arenas.join());
        }
        assertThat(joined).allMatch(e -> e == joined.getFirst());
        assertThat(arenas.openArenas()).isEqualTo(1L);
        for (final BlockArenas.Epoch e : joined) {
            arenas.leave(e);
        }
        assertThat(arenas.openArenas()).as("young and current: kept for the next block").isEqualTo(1L);
        assertThat(arenas.join()).isSameAs(joined.getFirst());
    }

    /// An epoch past its age ends at the next join: the new block gets a new arena, the old arena stays open while a
    /// block holds it and its bytes stay readable, and it closes when that block leaves.
    @Test
    void anOldEpochClosesWhenItsLastBlockLeaves() {
        final BlockArenas.Epoch first = arenas.join();
        final MemorySegment held = first.allocate(Long.BYTES, Long.BYTES);
        held.set(ValueLayout.JAVA_LONG, 0L, 42L);
        now.addAndGet(AGE);
        final BlockArenas.Epoch second = arenas.join();
        assertThat(second).isNotSameAs(first);
        assertThat(arenas.openArenas()).isEqualTo(2L);
        assertThat(held.get(ValueLayout.JAVA_LONG, 0L)).as("held across the rotation").isEqualTo(42L);
        assertThat(arenas.heldBytes()).isEqualTo(Long.BYTES);
        arenas.leave(first);
        assertThat(arenas.openArenas()).isEqualTo(1L);
        assertThat(held.scope().isAlive()).as("closed with its last block").isFalse();
        assertThat(arenas.heldBytes()).as("given back with the arena").isZero();
        arenas.leave(second);
        assertThat(arenas.openArenas()).as("second is young and current").isEqualTo(1L);
    }

    /// An epoch that handed out its bytes ends at the next join even when young.
    @Test
    void aFullEpochEndsAtTheNextJoin() {
        final BlockArenas.Epoch first = arenas.join();
        first.allocate(BYTES, 1L);
        assertThat(first.bytes()).isEqualTo(BYTES);
        final BlockArenas.Epoch second = arenas.join();
        assertThat(second).isNotSameAs(first);
        arenas.leave(first);
        arenas.leave(second);
        assertThat(arenas.openArenas()).isEqualTo(1L);
    }

    /// The last block leaving an epoch past its age closes it at once; an epoch whose last block left while young is
    /// closed by a sweep once old, and a sweep leaves an epoch a block still holds, or one still young.
    @Test
    void theLeaveOrTheSweepClosesAnIdleEpoch() {
        final BlockArenas.Epoch late = arenas.join();
        now.addAndGet(AGE);
        arenas.leave(late);
        assertThat(arenas.openArenas()).as("left past its age").isZero();

        final BlockArenas.Epoch young = arenas.join();
        arenas.leave(young);
        arenas.sweep();
        assertThat(arenas.openArenas()).as("young: the sweep keeps it").isEqualTo(1L);
        final BlockArenas.Epoch again = arenas.join();
        now.addAndGet(AGE);
        arenas.sweep();
        assertThat(arenas.openArenas()).as("held: the sweep keeps it").isEqualTo(1L);
        assertThat(again).isSameAs(young);
        arenas.leave(again);
        assertThat(arenas.openArenas()).as("left past its age").isZero();

        arenas.leave(arenas.join());
        now.addAndGet(AGE);
        arenas.sweep();
        assertThat(arenas.openArenas()).as("idle and old: swept").isZero();
    }

    /// Eight threads join, write, read back and leave while the clock moves and a sweeper runs: every block reads its
    /// own bytes until it leaves, no access fails, and once all left and the epochs aged, every arena is closed.
    @Test
    void concurrentBlocksReadTheirOwnBytesAndEveryArenaCloses() throws Exception {
        System.out.println("concurrentBlocksReadTheirOwnBytesAndEveryArenaCloses seed=" + SEED);
        final int threads = 8;
        final int rounds = 2_000;
        final CyclicBarrier start = new CyclicBarrier(threads + 2);
        final CountDownLatch done = new CountDownLatch(threads + 1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicLong mismatches = new AtomicLong();
        final List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final long seed = SEED + t;
            workers.add(Thread.ofPlatform().name("block-arenas-test-" + t).start(() -> {
                try {
                    final Random random = new Random(seed);
                    start.await(10, TimeUnit.SECONDS);
                    for (int r = 0; r < rounds; r++) {
                        final BlockArenas.Epoch e = arenas.join();
                        final MemorySegment s = e.allocate(64L + random.nextInt(64), Long.BYTES);
                        final long mark = random.nextLong();
                        s.set(ValueLayout.JAVA_LONG, 0L, mark);
                        if (random.nextInt(4) == 0) {
                            now.addAndGet(random.nextInt((int) AGE));
                        }
                        if (s.get(ValueLayout.JAVA_LONG, 0L) != mark) {
                            mismatches.incrementAndGet();
                        }
                        arenas.leave(e);
                    }
                } catch (final Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            }));
        }
        workers.add(Thread.ofPlatform().name("block-arenas-test-sweeper").start(() -> {
            try {
                start.await(10, TimeUnit.SECONDS);
                while (done.getCount() > 1L) {
                    arenas.sweep();
                    LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(50L));
                }
            } catch (final Throwable e) {
                failure.compareAndSet(null, e);
            } finally {
                done.countDown();
            }
        }));
        start.await(10, TimeUnit.SECONDS);
        assertThat(done.await(60, TimeUnit.SECONDS)).as("workers done, seed %d", SEED).isTrue();
        for (final Thread w : workers) {
            w.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertThat(failure.get()).as("seed %d", SEED).isNull();
        assertThat(mismatches.get()).as("seed %d", SEED).isZero();
        now.addAndGet(AGE);
        arenas.sweep();
        assertThat(arenas.openArenas()).as("seed %d", SEED).isZero();
        assertThat(arenas.heldBytes()).as("seed %d", SEED).isZero();
    }

    /// An epoch's bytes are held until its arena closes, closed blocks included: a young epoch whose blocks all left
    /// keeps them, and the sweep that closes it gives them all back.
    @Test
    void heldBytesAreGivenBackOnlyWhenTheArenaCloses() {
        final BlockArenas.Epoch e = arenas.join();
        e.allocate(100L, 1L);
        final BlockArenas.Epoch same = arenas.join();
        same.allocate(28L, 1L);
        arenas.leave(e);
        arenas.leave(same);
        assertThat(arenas.heldBytes()).as("young: kept").isEqualTo(128L);
        now.addAndGet(AGE);
        arenas.sweep();
        assertThat(arenas.heldBytes()).isZero();
        assertThat(arenas.openArenas()).isZero();
    }
}
