package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A [Version] maps a column's files on its first read and counts them in its [Mappings]; [Version#releaseIfIdle]
/// unmaps an idle version, the next read maps again with the same answers, and a release racing readers neither fails
/// a read nor changes a value. Seed in the output; replay with `-Dcdb.store.seed=<seed>`.
class VersionLazyMapTest {

    private static final int ROWS = 5_000;
    private static final int READERS = 4;

    @TempDir
    private Path dir;
    private Random random;
    private long seed;

    /// A fresh generator per test, from the replay seed or a new one, printed.
    @BeforeEach
    void seed(final TestInfo test) {
        seed = Long.getLong("cdb.store.seed", System.nanoTime());
        System.out.println(test.getDisplayName() + " seed=" + seed);
        random = new Random(seed);
    }

    /// Opening maps nothing; a column's first read maps its files (one for a fixed-width column, two for a VARCHAR),
    /// a second read of it maps nothing more, and a column with no file maps nothing.
    @Test
    void mapsAColumnOnItsFirstRead() throws IOException {
        final Mappings mappings = new Mappings();
        final Version version = open(mappings);
        try {
            assertThat(mappings.count()).isZero();
            final Vector longs = Vector.of(CdbType.LONG, 10);
            version.read(VersionReadTest.L, 0L, 10, longs, 0);
            assertThat(mappings.count()).isEqualTo(1L);
            version.read(VersionReadTest.L, 10L, 10, longs, 0);
            assertThat(mappings.count()).isEqualTo(1L);
            version.read(VersionReadTest.V, 0L, 10, Vector.of(CdbType.VARCHAR, 10), 0);
            assertThat(mappings.count()).isEqualTo(3L);
            version.read(VersionReadTest.LATE, 0L, 10, longs, 0);
            assertThat(mappings.count()).isEqualTo(3L);
            assertThat(longs.isNull(0)).isTrue();
        } finally {
            version.unmap();
        }
        assertThat(mappings.count()).isZero();
    }

    /// `releaseIfIdle` keeps a version used within the idle time, unmaps one idle past it, and a later read maps again
    /// and reads what it read before; with nothing mapped it has nothing to release.
    @Test
    void releaseIfIdleUnmapsAndAReadMapsAgain() throws IOException {
        final Mappings mappings = new Mappings();
        final Version version = open(mappings);
        try {
            final List<Vector> before = readAll(version);
            final long mapped = mappings.count();
            assertThat(mapped).isEqualTo(7L);
            assertThat(version.releaseIfIdle(System.nanoTime(), TimeUnit.HOURS.toNanos(1))).isFalse();
            assertThat(mappings.count()).isEqualTo(mapped);
            assertThat(version.releaseIfIdle(System.nanoTime(), 0L)).isTrue();
            assertThat(mappings.count()).isZero();
            assertThat(version.releaseIfIdle(System.nanoTime(), 0L)).isFalse();
            final List<Vector> after = readAll(version);
            assertThat(mappings.count()).isEqualTo(mapped);
            for (int c = 0; c < before.size(); c++) {
                for (int i = 0; i < ROWS; i++) {
                    assertThat(equal(before.get(c), after.get(c), i)).as("column %d row %d", c, i).isTrue();
                }
            }
        } finally {
            version.unmap();
        }
        assertThat(mappings.count()).isZero();
    }

    /// After `unmap` the version is dropped: a read is refused rather than mapping again.
    @Test
    void aReadAfterUnmapIsRefused() throws IOException {
        final Mappings mappings = new Mappings();
        final Version version = open(mappings);
        version.read(VersionReadTest.L, 0L, 10, Vector.of(CdbType.LONG, 10), 0);
        version.unmap();
        assertThat(mappings.count()).isZero();
        assertThatThrownBy(() -> version.read(VersionReadTest.L, 0L, 10, Vector.of(CdbType.LONG, 10), 0))
                .isInstanceOf(IllegalStateException.class);
        assertThat(mappings.count()).isZero();
    }

    /// Four reader threads read random windows of random columns and compare each with the answer read before the
    /// race, while the main thread calls `releaseIfIdle` with no idle time in a loop: no read fails, no value differs,
    /// releases happen, and the mapping count ends where the mapped columns put it.
    @Test
    void releaseRacingReadersChangesNoValue() throws Exception {
        final Mappings mappings = new Mappings();
        final Version version = open(mappings);
        try {
            final List<Vector> expected = readAll(version);
            final CyclicBarrier start = new CyclicBarrier(READERS + 1);
            final CountDownLatch done = new CountDownLatch(READERS);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicInteger wrong = new AtomicInteger();
            final AtomicInteger reads = new AtomicInteger();
            final List<Thread> readers = new ArrayList<>(READERS);
            for (int t = 0; t < READERS; t++) {
                final Random local = new Random(seed + t);
                readers.add(Thread.ofVirtual().name("version-lazy-map-test-reader-" + t).start(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        for (int round = 0; round < 3_000; round++) {
                            final int c = local.nextInt(VersionReadTest.TYPES.length);
                            final int n = 1 + local.nextInt(256);
                            final long from = local.nextInt(ROWS - n + 1);
                            final Vector dst = Vector.of(VersionReadTest.TYPES[c], n);
                            if (local.nextBoolean()) {
                                version.read(c, from, n, dst, 0);
                            } else {
                                final long[] rows = new long[n];
                                for (int k = 0; k < n; k++) {
                                    rows[k] = from + k;
                                }
                                version.fetch(c, rows, 0, n, dst, 0);
                            }
                            for (int i = 0; i < n; i++) {
                                if (!equal(expected.get(c), (int) from + i, dst, i)) {
                                    wrong.incrementAndGet();
                                }
                            }
                            reads.incrementAndGet();
                        }
                    } catch (final Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                }));
            }
            start.await(10, TimeUnit.SECONDS);
            int releases = 0;
            // the readers' bound, 60 s, set by the test: they take well under one
            final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (done.getCount() > 0L && System.nanoTime() - deadlineNanos < 0L) {
                if (version.releaseIfIdle(System.nanoTime(), 0L)) {
                    releases++;
                }
                assertThat(mappings.count()).isNotNegative();
            }
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
            for (final Thread reader : readers) {
                assertThat(reader.join(Duration.ofSeconds(10))).isTrue();
            }
            assertThat(failure.get()).isNull();
            assertThat(wrong.get()).isZero();
            assertThat(reads.get()).isEqualTo(READERS * 3_000);
            assertThat(releases).isPositive();
            assertThat(mappings.count()).isBetween(0L, 7L);
        } finally {
            version.unmap();
        }
        assertThat(mappings.count()).isZero();
    }

    private Version open(final Mappings mappings) throws IOException {
        final List<SymbolTable> symbols = new ArrayList<>();
        VersionReadTest.writeVersion(dir.resolve("p"), ROWS, random, symbols);
        return Version.open(dir.resolve("p"), VersionReadTest.DEF, ROWS, symbols, mappings);
    }

    /// Every column with a type, read whole into a vector each.
    private static List<Vector> readAll(final Version version) {
        final List<Vector> all = new ArrayList<>();
        for (int c = 0; c < VersionReadTest.TYPES.length; c++) {
            final Vector v = Vector.of(VersionReadTest.TYPES[c], ROWS);
            version.read(c, 0L, ROWS, v, 0);
            all.add(v);
        }
        return all;
    }

    private static boolean equal(final Vector a, final Vector b, final int i) {
        return equal(a, i, b, i);
    }

    private static boolean equal(final Vector a, final int i, final Vector b, final int j) {
        return switch (a.type) {
            case LONG, TIMESTAMP -> a.getLong(i) == b.getLong(j);
            case DOUBLE -> Double.doubleToRawLongBits(a.getDouble(i)) == Double.doubleToRawLongBits(b.getDouble(j));
            case BOOLEAN -> a.getBoolean(i) == b.getBoolean(j);
            case SYMBOL, VARCHAR -> a.isNull(i) ? b.isNull(j) : !b.isNull(j) && a.textEquals(i, b, j);
        };
    }
}
