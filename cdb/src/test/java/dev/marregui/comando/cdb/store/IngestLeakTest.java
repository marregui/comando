package dev.marregui.comando.cdb.store;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Ingest leaks no native row memory: every row block a write builds (pending rows, queued commits, a commit's
/// partition rows and merges) is closed once its rows are applied or dropped, failed inserts included, and every epoch
/// arena those blocks came from closes with no byte held, under one writer and under many at once.
class IngestLeakTest {

    private static final int INSERTS = 1_000;
    private static final long DAY_MICROS = 86_400_000_000L;
    private static final long BASE_MICROS = 1_762_992_000_000_000L;
    private static final DateTimeFormatter MICROS_UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
            .withZone(ZoneOffset.UTC);

    /// A thousand ILP rows flushed in groups and a thousand SQL inserts, out of order and across partitions so commits
    /// append, rewrite and merge, with failed inserts between them: once every commit is applied and read, and the
    /// store closed, the live native bytes of row blocks are back where they started.
    @Test
    void liveRowBlockBytesReturnToZeroOnceApplied(@TempDir final Path root) {
        final long before = RowBlock.liveBytes();
        try (Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE q (ts TIMESTAMP, s SYMBOL, v VARCHAR, n LONG, d DOUBLE, b BOOLEAN) TIMESTAMP(ts)"
                    + " PARTITION BY DAY");
            try (CdbAppender appender = cdb.appender()) {
                for (int i = 0; i < INSERTS; i++) {
                    appender.table("ilp").symbol("s", "s" + i % 7).stringColumn("v", i % 5 == 0 ? "é" + i : "v" + i)
                            .longColumn("n", i).doubleColumn("d", i * 0.25).booleanColumn("b", i % 2 == 0)
                            .at(micros(i));
                    if (i % 50 == 49) {
                        appender.flush();
                    }
                }
                appender.flush();
                assertThatThrownBy(() -> {
                    appender.table("ilp").stringColumn("n", "not a long").at(micros(1));
                    appender.flush();
                }).isInstanceOf(CdbException.class);
            }
            for (int i = 0; i < INSERTS; i++) {
                cdb.run("INSERT INTO q VALUES ('" + timestamp(i) + "', 's" + i % 3 + "', 'ü" + i + "', " + i + ", "
                        + i * 0.5 + ", " + (i % 2 == 0) + ')');
                if (i % 250 == 0) {
                    final int bad = i;
                    assertThatThrownBy(() -> cdb.run("INSERT INTO q VALUES ('" + timestamp(bad)
                            + "', 's', 'v', 'not a long', 1.0, true)")).isInstanceOf(CdbException.class);
                }
            }
            final CdbResult ilp = cdb.run("SELECT count() FROM ilp");
            final CdbResult sql = cdb.run("SELECT count() FROM q");
            assertThat(new long[] {ilp.getLong(0, 0), sql.getLong(0, 0)}).containsExactly(INSERTS, INSERTS);
        }
        // the close joins the applier, which closes a batch's blocks after it publishes them
        BlockLeaks.assertNoBlockMemoryHeld(before);
    }

    /// Eight ILP writers on tables of their own, two SQL writers sharing one table and two readers counting, all at
    /// once on one store, flushing in groups of 1 to 64 rows: every row lands, and once the store is closed no row
    /// block byte and no epoch arena is left. Seeded: `-Dcdb.ingest.seed=<seed>`.
    @Test
    void concurrentWritersAndReadersLeaveNoBlockMemory(@TempDir final Path root) throws Exception {
        final long seed = Long.getLong("cdb.ingest.seed", System.nanoTime());
        System.out.println("concurrentWritersAndReadersLeaveNoBlockMemory seed=" + seed);
        final int ilpWriters = 8;
        final int sqlWriters = 2;
        final int readers = 2;
        final int rowsEach = 600;
        final long before = RowBlock.liveBytes();
        try (Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE q (ts TIMESTAMP, s SYMBOL, v VARCHAR, n LONG) TIMESTAMP(ts) PARTITION BY DAY");
            final CyclicBarrier start = new CyclicBarrier(ilpWriters + sqlWriters + readers + 1);
            final CountDownLatch writersDone = new CountDownLatch(ilpWriters + sqlWriters);
            final CountDownLatch readersDone = new CountDownLatch(readers);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final List<Thread> threads = new ArrayList<>();
            for (int w = 0; w < ilpWriters; w++) {
                final int writer = w;
                threads.add(Thread.ofPlatform().name("ingest-leak-test-ilp-" + w).start(() -> {
                    try (CdbAppender appender = cdb.appender()) {
                        final Random random = new Random(seed + writer);
                        start.await(10, TimeUnit.SECONDS);
                        int group = 1 + random.nextInt(64);
                        for (int i = 0; i < rowsEach; i++) {
                            appender.table("ilp" + writer).symbol("s", "s" + i % 5)
                                    .stringColumn("v", "v" + "x".repeat(random.nextInt(30)) + i).longColumn("n", i)
                                    .at(micros(i));
                            if (--group == 0) {
                                appender.flush();
                                group = 1 + random.nextInt(64);
                            }
                        }
                        appender.flush();
                    } catch (final Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        writersDone.countDown();
                    }
                }));
            }
            for (int w = 0; w < sqlWriters; w++) {
                final int writer = w;
                threads.add(Thread.ofPlatform().name("ingest-leak-test-sql-" + w).start(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        for (int i = 0; i < rowsEach / 4; i++) {
                            cdb.run("INSERT INTO q VALUES ('" + timestamp(i) + "', 's" + writer + "', 'w" + writer
                                    + "-" + i + "', " + i + ')');
                        }
                    } catch (final Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        writersDone.countDown();
                    }
                }));
            }
            for (int r = 0; r < readers; r++) {
                threads.add(Thread.ofPlatform().name("ingest-leak-test-reader-" + r).start(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        while (writersDone.getCount() > 0L) {
                            cdb.run("SELECT count() FROM q");
                            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2L));
                        }
                    } catch (final Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        readersDone.countDown();
                    }
                }));
            }
            start.await(10, TimeUnit.SECONDS);
            assertThat(writersDone.await(120, TimeUnit.SECONDS)).as("writers, seed %d", seed).isTrue();
            assertThat(readersDone.await(30, TimeUnit.SECONDS)).as("readers, seed %d", seed).isTrue();
            for (final Thread t : threads) {
                t.join(TimeUnit.SECONDS.toMillis(10));
            }
            assertThat(failure.get()).as("seed %d", seed).isNull();
            for (int w = 0; w < ilpWriters; w++) {
                assertThat(cdb.run("SELECT count() FROM ilp" + w).getLong(0, 0)).as("ilp%d", w).isEqualTo(rowsEach);
            }
            assertThat(cdb.run("SELECT count() FROM q").getLong(0, 0)).isEqualTo((long) sqlWriters * (rowsEach / 4));
        }
        BlockLeaks.assertNoBlockMemoryHeld(before);
    }

    // row i's designated timestamp: three days, every seventh row back in an earlier one
    private static long micros(final int i) {
        final long day = i % 7 == 0 ? 0L : i % 3;
        return BASE_MICROS + day * DAY_MICROS + i * 1_000L;
    }

    private static String timestamp(final int i) {
        return MICROS_UTC.format(Instant.EPOCH.plus(micros(i), ChronoUnit.MICROS));
    }
}
