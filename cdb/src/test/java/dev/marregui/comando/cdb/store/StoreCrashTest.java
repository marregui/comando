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

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static dev.marregui.comando.cdb.store.StoreRows.rows;
import static org.assertj.core.api.Assertions.assertThat;

/// A process killed at any step of a commit or a checkpoint reopens at the last durable transaction: before the
/// commit's record is in the log, the previous transaction; once the record is forced, the commit. The reopened store
/// reads it exactly, and keeps only the folders its `_txn` names.
class StoreCrashTest {

    private static final List<String> ROWS = List.of(
            "1|\"one\"|" + StoreCrashChild.DAY1,
            "2|\"two\"|" + (StoreCrashChild.DAY1 + 1L),
            "3|\"three\"|" + (StoreCrashChild.DAY1 + 2L),
            "4|\"four\"|" + StoreCrashChild.DAY2);

    /// [StoreCrashChild] commits transaction 1, then is killed at the [StoreCrashChild.Kill] point while transaction 2
    /// is committed or checkpointed; the reopened store reads the transaction that point leaves durable.
    @ParameterizedTest
    @EnumSource(StoreCrashChild.Kill.class)
    void aKillAtEachStepReopensAtTheLastDurableTransaction(
            final StoreCrashChild.Kill kill,
            @TempDir final Path root) throws Exception {
        final Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath(),
                StoreCrashChild.class.getName(),
                root.toString(),
                kill.name())
                .redirectErrorStream(true)
                .start();
        final List<String> output = new CopyOnWriteArrayList<>();
        final CountDownLatch ended = new CountDownLatch(1);
        final Thread reader = Thread.ofVirtual().name("store-crash-test-output").start(() -> {
            try (final BufferedReader in = new BufferedReader(
                    new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    output.add(line);
                    if (line.equals(StoreCrashChild.READY)) {
                        ended.countDown();
                    }
                }
            } catch (final IOException _) {
                // the stream closes when the child is killed
            } finally {
                // a child that ends without reaching its kill point fails the test at once, not at the timeout
                ended.countDown();
            }
        });
        try {
            assertThat(ended.await(60L, TimeUnit.SECONDS)).as("child output %s", output).isTrue();
        } finally {
            child.destroyForcibly();
        }
        assertThat(child.waitFor(30L, TimeUnit.SECONDS)).isTrue();
        assertThat(reader.join(Duration.ofSeconds(10L))).isTrue();
        assertThat(output).as("child output").contains(StoreCrashChild.READY);

        final Path table = root.resolve("m");
        final long day1 = Retention.floor(StoreCrashChild.DAY1, PartitionBy.DAY);
        final long day2 = Retention.floor(StoreCrashChild.DAY2, PartitionBy.DAY);
        assertThat(folders(table)).containsExactlyInAnyOrder(
                TableFiles.folder(day1, PartitionBy.DAY, 1L),
                TableFiles.folder(day2, PartitionBy.DAY, 2L));

        try (final Store store = Store.open(root); final TableReader r = store.reader("m")) {
            assertThat(((DefaultReader) r).txn()).isEqualTo(kill.txn);
            assertThat(rows(r)).containsExactlyElementsOf(ROWS.subList(0, (int) (2L * kill.txn)));
        }
        final TableFiles.Txn txn = TableFiles.decodeTxn(
                StoreFiles.readText(table.resolve(TableFiles.TXN)), table.toString());
        assertThat(txn.number()).isEqualTo(kill.txn);
        final List<String> named = txn.partitions().stream().map(TableFiles.Entry::folder).toList();
        assertThat(folders(table)).containsExactlyInAnyOrderElementsOf(named);
        if (kill.txn == 1L) {
            assertThat(named).containsExactly(TableFiles.folder(day1, PartitionBy.DAY, 1L));
        } else {
            assertThat(named).containsExactlyInAnyOrder(
                    TableFiles.folder(day1, PartitionBy.DAY, 1L),
                    TableFiles.folder(day2, PartitionBy.DAY, 2L));
        }
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
