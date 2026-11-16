package dev.marregui.comando.cdb.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbType;

import static org.assertj.core.api.Assertions.assertThat;

/// Row blocks as the store uses them across threads, on the process's epoch arenas and the real clock: producers build
/// and fill blocks (text past its first segment included) and hand them to one consumer, as request threads hand
/// commits to the applier; the consumer reads every value back and closes the block. Over several epoch rotations no
/// value is torn and, at the end, no block byte and no epoch arena is left. Seeded: `-Dcdb.block.seed=<seed>`.
class RowBlockThreadsTest {

    private static final long SEED = Long.getLong("cdb.block.seed", System.nanoTime());
    private static final List<ColumnDef> COLUMNS = List.of(
            new ColumnDef("ts", CdbType.TIMESTAMP),
            new ColumnDef("n", CdbType.LONG),
            new ColumnDef("d", CdbType.DOUBLE),
            new ColumnDef("b", CdbType.BOOLEAN),
            new ColumnDef("s", CdbType.SYMBOL),
            new ColumnDef("v", CdbType.VARCHAR));
    private static final int PRODUCERS = 6;
    // long enough for ten or more epoch rotations of 100 ms
    private static final long RUN_NANOS = 12L * BlockArenas.ROTATE_NANOS;

    /// Six producers, one consumer, a bounded hand-off: every block the consumer takes reads back what its producer
    /// wrote, then the leak check passes.
    @Test
    void blocksFilledOnProducersReadBackAndFreeOnTheConsumer() throws Exception {
        System.out.println("blocksFilledOnProducersReadBackAndFreeOnTheConsumer seed=" + SEED);
        final long liveBefore = RowBlock.liveBytes();
        final BlockingQueue<Filled> handoff = new ArrayBlockingQueue<>(64);
        final CyclicBarrier start = new CyclicBarrier(PRODUCERS + 2);
        final CountDownLatch producersDone = new CountDownLatch(PRODUCERS);
        final CountDownLatch consumerDone = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicLong checked = new AtomicLong();
        final AtomicLong torn = new AtomicLong();
        final List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < PRODUCERS; p++) {
            final long seed = SEED + p;
            threads.add(Thread.ofPlatform().name("row-block-test-producer-" + p).start(() -> {
                try {
                    final Random random = new Random(seed);
                    start.await(10, TimeUnit.SECONDS);
                    final long until = System.nanoTime() + RUN_NANOS;
                    while (System.nanoTime() - until < 0L && failure.get() == null) {
                        final Filled filled = fill(random);
                        if (!handoff.offer(filled, 10, TimeUnit.SECONDS)) {
                            filled.block().close();
                            throw new IllegalStateException("consumer stalled");
                        }
                    }
                } catch (final Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    producersDone.countDown();
                }
            }));
        }
        threads.add(Thread.ofPlatform().name("row-block-test-consumer").start(() -> {
            try {
                start.await(10, TimeUnit.SECONDS);
                while (producersDone.getCount() > 0L || !handoff.isEmpty()) {
                    final Filled filled = handoff.poll(10, TimeUnit.MILLISECONDS);
                    if (filled != null) {
                        try (RowBlock block = filled.block()) {
                            if (!filled.readsBack(block)) {
                                torn.incrementAndGet();
                            }
                            checked.incrementAndGet();
                        }
                    }
                }
            } catch (final Throwable e) {
                failure.compareAndSet(null, e);
            } finally {
                consumerDone.countDown();
            }
        }));
        start.await(10, TimeUnit.SECONDS);
        final boolean producersEnded;
        final boolean consumerEnded;
        try {
            producersEnded = producersDone.await(60, TimeUnit.SECONDS);
            consumerEnded = consumerDone.await(60, TimeUnit.SECONDS);
            for (final Thread t : threads) {
                t.join(TimeUnit.SECONDS.toMillis(10));
            }
        } finally {
            // a failed run leaves blocks in the hand-off: closed here so no later leak check in this JVM sees them
            Filled left;
            while ((left = handoff.poll()) != null) {
                left.block().close();
            }
        }
        assertThat(producersEnded).as("producers, seed %d", SEED).isTrue();
        assertThat(consumerEnded).as("consumer, seed %d", SEED).isTrue();
        assertThat(failure.get()).as("seed %d", SEED).isNull();
        assertThat(torn.get()).as("blocks not reading back, seed %d", SEED).isZero();
        assertThat(checked.get()).as("blocks handed over").isGreaterThan(100L);
        BlockLeaks.assertNoBlockMemoryHeld(liveBefore);
    }

    // a block of 1 to 64 rows with random values, its text long enough at times to grow past the first segment
    private static Filled fill(final Random random) {
        final int rows = 1 + random.nextInt(64);
        final RowBlock block = new RowBlock("t", COLUMNS, rows);
        try {
            return fill(random, block, rows);
        } catch (final RuntimeException | Error e) {
            block.close();
            throw e;
        }
    }

    private static Filled fill(final Random random, final RowBlock block, final int rows) {
        final long[] longs = new long[rows];
        final String[] texts = new String[rows];
        for (int r = 0; r < rows; r++) {
            longs[r] = random.nextLong();
            texts[r] = random.nextInt(8) == 0 ? null : "v" + "x".repeat(random.nextInt(40)) + r;
            block.setLong(0, r, r);
            block.setLong(1, r, longs[r]);
            block.setDouble(2, r, longs[r] * 0.5);
            block.setBoolean(3, r, (longs[r] & 1L) == 0L);
            block.setText(4, r, texts[r] == null ? null : "s" + r % 3);
            block.setText(5, r, texts[r]);
        }
        block.size = rows;
        return new Filled(block, longs, texts);
    }

    // a block and the values its producer wrote
    private record Filled(RowBlock block, long[] longs, String[] texts) {

        boolean readsBack(final RowBlock b) {
            for (int r = 0; r < longs.length; r++) {
                final String text = b.textAt(5, r);
                final boolean textSame = texts[r] == null ? text == null : texts[r].equals(text);
                if (b.getLong(0, r) != r || b.getLong(1, r) != longs[r] || b.getDouble(2, r) != longs[r] * 0.5
                        || b.getBoolean(3, r) != ((longs[r] & 1L) == 0L) || !textSame) {
                    return false;
                }
            }
            return true;
        }
    }
}
