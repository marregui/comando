package dev.marregui.comando.cdb.store;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static dev.marregui.comando.cdb.store.BoundedRewriteChild.B;
import static dev.marregui.comando.cdb.store.BoundedRewriteChild.BETWEEN;
import static dev.marregui.comando.cdb.store.BoundedRewriteChild.D;
import static dev.marregui.comando.cdb.store.BoundedRewriteChild.ID;
import static dev.marregui.comando.cdb.store.BoundedRewriteChild.J;
import static dev.marregui.comando.cdb.store.BoundedRewriteChild.REPLACED;
import static dev.marregui.comando.cdb.store.BoundedRewriteChild.ROWS;
import static dev.marregui.comando.cdb.store.BoundedRewriteChild.S;
import static dev.marregui.comando.cdb.store.BoundedRewriteChild.TIES;
import static dev.marregui.comando.cdb.store.BoundedRewriteChild.V;
import static org.assertj.core.api.Assertions.assertThat;

/// A rewrite holds a bounded number of bytes whatever the partition's size: a process whose heap is 64 MiB rewrites a
/// partition of over 300 MB of VARCHAR, JSON, SYMBOL and fixed-width values when a commit's rows land among its
/// committed rows, and the table then reads every row once, in timestamp order, a new row after the committed row of
/// its timestamp and the row whose keys it repeats replaced.
class BoundedRewriteTest {

    // the heap the child runs in: a quarter of the 256 MiB of cdb's server, a fifth of the partition
    private static final String HEAP = "-Xmx64m";
    private static final long HEAP_BYTES = 64L << 20;
    private static final long PARTITION_BYTES = 300L << 20;
    // the child writes about 700 MB twice over; measured at under a minute on a laptop, bounded well above
    private static final long CHILD_SECONDS = 300L;
    private static final int READ_ROWS = 4_096;

    /// [BoundedRewriteChild] fills the partition and commits rows among its rows in a 64 MiB heap; the reopened store
    /// reads the merged partition, in its rewrite's folder, row by row as the child wrote it.
    @Test
    void aSmallHeapRewritesALargePartition(@TempDir final Path root) throws Exception {
        final List<String> output = run(root);
        assertThat(output).as("child output").contains(BoundedRewriteChild.DONE);
        final long maxHeap = output.stream()
                .filter(line -> line.startsWith(BoundedRewriteChild.MAX_HEAP))
                .mapToLong(line -> Long.parseLong(line.substring(BoundedRewriteChild.MAX_HEAP.length())))
                .findFirst()
                .orElseThrow();
        assertThat(maxHeap).as("child heap").isLessThanOrEqualTo(HEAP_BYTES);

        final Path table = root.resolve("m");
        final TableFiles.Txn txn = TableFiles.decodeTxn(
                StoreFiles.readText(table.resolve(TableFiles.TXN)), table.toString());
        final long floor = BoundedRewriteChild.DAY1;
        // the in-order commits append to the first folder; the last commit rewrites it as its own
        assertThat(txn.partitions().stream().map(TableFiles.Entry::folder).toList())
                .containsExactly(TableFiles.folder(floor, PartitionBy.DAY, txn.number()));
        assertThat(bytes(table.resolve(TableFiles.folder(floor, PartitionBy.DAY, txn.number()))))
                .isGreaterThanOrEqualTo(PARTITION_BYTES);

        try (final Store store = Store.open(root); final TableReader r = store.reader("m")) {
            assertThat(folders(table)).containsExactly(TableFiles.folder(floor, PartitionBy.DAY, txn.number()));
            final List<PartitionReader> partitions = r.partitions();
            assertThat(partitions).hasSize(1);
            check(partitions.getFirst());
        }
    }

    // every row once, in timestamp order, each with the values the child wrote for its id
    private static void check(final PartitionReader p) {
        final int total = ROWS + BETWEEN + TIES;
        assertThat(p.rowCount()).isEqualTo(total);
        final BitSet seen = new BitSet(total);
        final long[] ts = new long[READ_ROWS];
        final long[] ids = new long[READ_ROWS];
        final String[] v = new String[READ_ROWS];
        final String[] j = new String[READ_ROWS];
        final String[] s = new String[READ_ROWS];
        final double[] d = new double[READ_ROWS];
        final boolean[] b = new boolean[READ_ROWS];
        long previousTs = Long.MIN_VALUE;
        boolean previousNew = false;
        for (long at = 0L; at < total; at += READ_ROWS) {
            final int n = (int) Math.min(READ_ROWS, total - at);
            p.longs(0, at, n, ts);
            p.longs(ID, at, n, ids);
            p.texts(V, at, n, v);
            p.texts(J, at, n, j);
            p.texts(S, at, n, s);
            p.doubles(D, at, n, d);
            p.booleans(B, at, n, b);
            for (int x = 0; x < n; x++) {
                final long id = ids[x];
                final String where = "row " + (at + x) + ", id " + id;
                assertThat(id).as(where).isBetween(0L, total - 1L);
                assertThat(seen.get((int) id)).as(where + " read twice").isFalse();
                seen.set((int) id);
                assertThat(ts[x]).as(where).isEqualTo(BoundedRewriteChild.timestamp(id));
                assertThat(ts[x]).as(where + " out of order").isGreaterThanOrEqualTo(previousTs);
                // the replacement and every other new row come after the committed rows of their timestamp
                final boolean isNew = id >= ROWS || id == REPLACED;
                if (ts[x] == previousTs && previousNew) {
                    assertThat(isNew).as(where + " committed row after a new one").isTrue();
                }
                assertThat(v[x]).as(where).isEqualTo(BoundedRewriteChild.text(id, id == REPLACED));
                assertThat(j[x]).as(where).isEqualTo(BoundedRewriteChild.json(id));
                assertThat(s[x]).as(where).isEqualTo(BoundedRewriteChild.symbol(id));
                assertThat(d[x]).as(where).isEqualTo(BoundedRewriteChild.number(id));
                assertThat(b[x]).as(where).isEqualTo(BoundedRewriteChild.flag(id));
                previousTs = ts[x];
                previousNew = isNew;
            }
        }
        assertThat(seen.cardinality()).isEqualTo(total);
    }

    // runs the child on root in the small heap; its output once it exited 0
    private static List<String> run(final Path root) throws Exception {
        final Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                HEAP,
                "--enable-native-access=ALL-UNNAMED",
                "-cp", classpath(),
                BoundedRewriteChild.class.getName(),
                root.toString())
                .redirectErrorStream(true)
                .start();
        final List<String> output = new CopyOnWriteArrayList<>();
        final CountDownLatch ended = new CountDownLatch(1);
        final Thread reader = Thread.ofVirtual().name("bounded-rewrite-test-output").start(() -> {
            try (final BufferedReader in = new BufferedReader(
                    new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    output.add(line);
                }
            } catch (final IOException _) {
                // the stream closes when the child is destroyed
            } finally {
                ended.countDown();
            }
        });
        try {
            assertThat(ended.await(CHILD_SECONDS, TimeUnit.SECONDS)).as("child output %s", output).isTrue();
            assertThat(child.waitFor(30L, TimeUnit.SECONDS)).as("child output %s", output).isTrue();
        } finally {
            child.destroyForcibly();
        }
        assertThat(reader.join(Duration.ofSeconds(10L))).isTrue();
        assertThat(child.exitValue()).as("child output %s", output).isZero();
        return output;
    }

    private static String classpath() throws URISyntaxException {
        return Path.of(BoundedRewriteChild.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator
                + Path.of(Store.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static List<String> folders(final Path table) throws IOException {
        try (final Stream<Path> entries = Files.list(table)) {
            return entries.filter(Files::isDirectory).map(p -> p.getFileName().toString()).toList();
        }
    }

    private static long bytes(final Path folder) throws IOException {
        long total = 0L;
        try (final Stream<Path> entries = Files.list(folder)) {
            for (final Path file : entries.toList()) {
                total += Files.size(file);
            }
        }
        return total;
    }
}
