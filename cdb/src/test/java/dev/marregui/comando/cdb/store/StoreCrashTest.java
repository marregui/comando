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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static dev.marregui.comando.cdb.store.StoreRows.rows;
import static org.assertj.core.api.Assertions.assertThat;

/// A process killed between writing a commit's version folders and replacing `_txn` leaves the previous
/// transaction: the reopened store reads it exactly, and deletes the orphan folders.
class StoreCrashTest {

    /// [StoreCrashChild] commits transaction 1, writes transaction 2's folders and is killed before `_txn` names them.
    @Test
    void aKillBeforeTheTxnReplaceLeavesThePreviousTransaction(@TempDir final Path root) throws Exception {
        final Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath(),
                StoreCrashChild.class.getName(),
                root.toString())
                .redirectErrorStream(true)
                .start();
        final List<String> output = new CopyOnWriteArrayList<>();
        final CountDownLatch ready = new CountDownLatch(1);
        final Thread reader = Thread.ofVirtual().name("store-crash-test-output").start(() -> {
            try (final BufferedReader in = new BufferedReader(
                    new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    output.add(line);
                    if (line.equals(StoreCrashChild.READY)) {
                        ready.countDown();
                    }
                }
            } catch (final IOException _) {
                // the stream closes when the child is killed
            }
        });
        final boolean reachedReplace;
        try {
            reachedReplace = ready.await(60L, TimeUnit.SECONDS);
        } finally {
            child.destroyForcibly();
        }
        assertThat(child.waitFor(30L, TimeUnit.SECONDS)).isTrue();
        assertThat(reader.join(Duration.ofSeconds(10L))).isTrue();
        assertThat(reachedReplace).as("child output %s", output).isTrue();

        final Path table = root.resolve("m");
        final long day1 = Retention.floor(StoreCrashChild.DAY1, PartitionBy.DAY);
        final long day2 = Retention.floor(StoreCrashChild.DAY2, PartitionBy.DAY);
        assertThat(folders(table)).containsExactlyInAnyOrder(
                TableFiles.folder(day1, PartitionBy.DAY, 1L),
                TableFiles.folder(day1, PartitionBy.DAY, 2L),
                TableFiles.folder(day2, PartitionBy.DAY, 2L));

        try (final Store store = Store.open(root); final TableReader r = store.reader("m")) {
            assertThat(((DefaultReader) r).txn()).isEqualTo(1L);
            assertThat(rows(r)).containsExactly(
                    "1|\"one\"|" + StoreCrashChild.DAY1,
                    "2|\"two\"|" + (StoreCrashChild.DAY1 + 1L));
        }
        assertThat(folders(table)).containsExactly(TableFiles.folder(day1, PartitionBy.DAY, 1L));
    }

    private static String classpath() throws URISyntaxException {
        return Path.of(StoreCrashChild.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator
                + Path.of(Store.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static List<String> folders(final Path table) throws IOException {
        try (final Stream<Path> entries = Files.list(table)) {
            return entries.filter(Files::isDirectory).map(p -> p.getFileName().toString()).toList();
        }
    }
}
