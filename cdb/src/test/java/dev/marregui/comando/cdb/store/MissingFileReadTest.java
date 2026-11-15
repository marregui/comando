package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static dev.marregui.comando.cdb.store.StoreRows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A partition folder or a column file that a committed table names, and that is gone, fails the read with the table,
/// the partition and the file named; a column added after the partition was written, which has no file there, still
/// reads NULL.
class MissingFileReadTest {

    private static final TableDef M = new TableDef("m", List.of(
            new ColumnDef("value", CdbType.DOUBLE),
            new ColumnDef("note", CdbType.VARCHAR),
            new ColumnDef("ts", CdbType.TIMESTAMP)), 2, PartitionBy.DAY, List.of(), 0L);
    private static final long TS = micros("2026-11-01T10:00:00Z");
    // no other writer exists in these tests: a wait this long is a leak
    private static final long WAIT_MILLIS = 1_000L;

    /// The folder of the only partition is deleted: opening the table fails, naming the table and the partition.
    @Test
    void aMissingPartitionFolderFailsTheRead(@TempDir final Path root) throws IOException {
        final Path partition = writeOnePartition(root);
        StoreFiles.deleteFolder(partition);
        assertThatThrownBy(() -> readAll(root))
                .isInstanceOf(CdbException.class)
                .hasMessageContaining("table=m")
                .hasMessageContaining("partition=" + partition.getFileName());
    }

    /// The data file of a column that is not the last one is deleted: the read fails naming the file, and does not
    /// answer NULL for the column.
    @Test
    void aMissingColumnFileFailsTheRead(@TempDir final Path root) throws IOException {
        final Path partition = writeOnePartition(root);
        Files.delete(partition.resolve("value.d"));
        assertThatThrownBy(() -> readAll(root))
                .isInstanceOf(CdbException.class)
                .hasMessageContaining("table=m")
                .hasMessageContaining("partition=" + partition.getFileName())
                .hasMessageContaining("file=value.d");
    }

    /// The data file of the designated timestamp, the last column, is deleted: no column after it holds a file, yet
    /// the partition holds rows, so the read fails.
    @Test
    void aMissingTimestampFileFailsTheRead(@TempDir final Path root) throws IOException {
        final Path partition = writeOnePartition(root);
        Files.delete(partition.resolve("ts.d"));
        assertThatThrownBy(() -> readAll(root))
                .isInstanceOf(CdbException.class)
                .hasMessageContaining("file=ts.d");
    }

    /// The data file of a VARCHAR column is deleted while its index file stays: the read fails.
    @Test
    void aMissingVarcharDataFileFailsTheRead(@TempDir final Path root) throws IOException {
        final Path partition = writeOnePartition(root);
        Files.delete(partition.resolve("note.d"));
        assertThatThrownBy(() -> readAll(root))
                .isInstanceOf(CdbException.class)
                .hasMessageContaining("file=note.d");
    }

    /// The data file of the last column, not the designated timestamp, is deleted: `_txn` records that the partition
    /// had files for every column, so the read fails instead of taking the column for one added later.
    @Test
    void aMissingLastColumnFileFailsTheRead(@TempDir final Path root) throws IOException {
        final Path partition = writeLastValuePartition(root);
        Files.delete(partition.resolve("value.d"));
        assertThatThrownBy(() -> readAll(root))
                .isInstanceOf(CdbException.class)
                .hasMessageContaining("file=value.d");
    }

    /// A partition line of a `_txn` written before the column count was recorded keeps the tail rule: a missing file
    /// of the last column is taken for a column added later and reads NULL.
    @Test
    void aTxnWithoutColumnCountsKeepsTheTailRule(@TempDir final Path root) throws IOException {
        final Path partition = writeLastValuePartition(root);
        final Path txn = root.resolve("m").resolve(TableFiles.TXN);
        final String text = Files.readString(txn);
        final String line = partition.getFileName() + "\t1\t2\n";
        assertThat(text).contains(line);
        Files.writeString(txn, text.replace(line, partition.getFileName() + "\t1\n"));
        Files.delete(partition.resolve("value.d"));
        assertThat(readAll(root)).containsExactly(TS + "|null");
    }

    /// A column added after the partition was written has no file in it: the partition reads NULL there, before and
    /// after a reopen, and a missing file of a column the partition does have still fails.
    @Test
    void aColumnAddedAfterThePartitionWasWrittenReadsNull(@TempDir final Path root) throws IOException {
        final Path partition = writeOnePartition(root);
        try (final Store store = Store.open(root)) {
            try (final TableWriter writer = store.writer("m", WAIT_MILLIS)) {
                writer.addColumn(new ColumnDef("extra", CdbType.LONG));
                writer.commit();
            }
            try (final TableReader reader = store.reader("m")) {
                assertThat(rows(reader)).containsExactly("1.0|\"a\"|" + TS + "|null");
            }
        }
        assertThat(partition.resolve("extra.d")).doesNotExist();
        assertThat(readAll(root)).containsExactly("1.0|\"a\"|" + TS + "|null");
        Files.delete(partition.resolve("value.d"));
        assertThatThrownBy(() -> readAll(root)).isInstanceOf(CdbException.class).hasMessageContaining("file=value.d");
    }

    // a table whose last column is not its designated timestamp, one row in one partition
    private static Path writeLastValuePartition(final Path root) throws IOException {
        final TableDef last = new TableDef("m", List.of(
                new ColumnDef("ts", CdbType.TIMESTAMP),
                new ColumnDef("value", CdbType.DOUBLE)), 0, PartitionBy.DAY, List.of(), 0L);
        try (final Store store = Store.open(root)) {
            store.create(last, false);
            try (final TableWriter writer = store.writer("m", WAIT_MILLIS)) {
                writer.newRow(TS);
                writer.putDouble(1, 1.0);
                writer.endRow();
                writer.commit();
            }
        }
        try (final Stream<Path> entries = Files.list(root.resolve("m"))) {
            return entries.filter(Files::isDirectory).findFirst().orElseThrow();
        }
    }

    private static Path writeOnePartition(final Path root) throws IOException {
        try (final Store store = Store.open(root)) {
            store.create(M, false);
            try (final TableWriter writer = store.writer("m", WAIT_MILLIS)) {
                writer.newRow(TS);
                writer.putDouble(0, 1.0);
                writer.putText(1, "a");
                writer.endRow();
                writer.commit();
            }
        }
        try (final Stream<Path> entries = Files.list(root.resolve("m"))) {
            return entries.filter(Files::isDirectory).findFirst().orElseThrow();
        }
    }

    private static List<String> readAll(final Path root) {
        try (final Store store = Store.open(root); final TableReader reader = store.reader("m")) {
            return rows(reader);
        }
    }
}
