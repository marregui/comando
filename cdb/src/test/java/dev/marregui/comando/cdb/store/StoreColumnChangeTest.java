package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static dev.marregui.comando.cdb.store.StoreRows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [TableWriter#dropColumn] and [TableWriter#renameColumn] on a store in a temporary folder, driven as the SQL runner
/// drives them: the writer, a snapshot, the change, a truncate, every row appended again under the new definition, one
/// commit. A reader opened before reads the old definition on; one opened after, and every later open of the folder,
/// the new one; a failure or a power cut before the commit's `_txn` leaves the table as before.
class StoreColumnChangeTest {

    private static final long DAY1 = micros("2026-11-01T00:00:00Z");
    private static final long DAY2 = DAY1 + Retention.DAY_MICROS;
    private static final long WAIT_MILLIS = 1_000L;
    private static final TableDef M = new TableDef("m", List.of(
            new ColumnDef("k", CdbType.SYMBOL),
            new ColumnDef("l", CdbType.LONG),
            new ColumnDef("v", CdbType.VARCHAR),
            new ColumnDef("ts", CdbType.TIMESTAMP)), 3, PartitionBy.DAY, List.of(), 0L);
    private static final List<String> BEFORE = List.of(
            "\"a\"|1|\"one\"|" + (DAY1 + 1L),
            "\"b\"|null|null|" + (DAY1 + 2L),
            "\"a\"|3|\"three\"|" + (DAY2 + 1L));

    @TempDir
    private Path root;

    /// A drop leaves the column out of the definition, of the rows and of the new partition folders; the designated
    /// timestamp, after it, moves down one index. The column is named in any case. A reader opened before the commit
    /// reads the old definition and rows; a new reader and the next open read the new ones, with no `_meta.prev` left.
    @Test
    void dropLeavesTheColumnOutAndMovesTheTimestampDown() throws IOException {
        final List<String> after = List.of(
                "\"a\"|\"one\"|" + (DAY1 + 1L),
                "\"b\"|null|" + (DAY1 + 2L),
                "\"a\"|\"three\"|" + (DAY2 + 1L));
        try (final Store store = Store.open(root)) {
            seed(store);
            try (final TableReader old = store.reader("m")) {
                change(store, w -> w.dropColumn("L"), w -> {
                    row(w, "a", "one", DAY1 + 1L);
                    row(w, "b", null, DAY1 + 2L);
                    row(w, "a", "three", DAY2 + 1L);
                });
                assertThat(old.def()).isEqualTo(M);
                assertThat(rows(old)).containsExactlyElementsOf(BEFORE);
            }
            try (final TableReader r = store.reader("m")) {
                assertThat(names(r.def())).containsExactly("k", "v", "ts");
                assertThat(r.def().timestampIndex()).isEqualTo(2);
                assertThat(rows(r)).containsExactlyElementsOf(after);
            }
        }
        try (final Store store = Store.open(root); final TableReader r = store.reader("m")) {
            assertThat(names(r.def())).containsExactly("k", "v", "ts");
            assertThat(rows(r)).containsExactlyElementsOf(after);
        }
        final Path table = root.resolve("m");
        assertThat(table.resolve(TableFiles.META_PREV)).doesNotExist();
        for (final Path folder : partitionFolders(table)) {
            assertThat(folder.resolve("l.d")).doesNotExist();
        }
    }

    /// A rename keeps the column's values under the new name: a SYMBOL column's dictionary moves to a file of the new
    /// name, the old file goes, and values interned after the change, new and old, read back after a reopen.
    @Test
    void renameKeepsTheSymbolValuesUnderTheNewName() {
        try (final Store store = Store.open(root)) {
            seed(store);
            try (final TableReader old = store.reader("m")) {
                change(store, w -> w.renameColumn("K", "key"), w -> {
                    row(w, "a", 1L, "one", DAY1 + 1L);
                    row(w, "b", null, null, DAY1 + 2L);
                    row(w, "a", 3L, "three", DAY2 + 1L);
                });
                assertThat(names(old.def())).containsExactly("k", "l", "v", "ts");
                assertThat(rows(old)).containsExactlyElementsOf(BEFORE);
            }
            try (final TableReader r = store.reader("m")) {
                assertThat(names(r.def())).containsExactly("key", "l", "v", "ts");
                assertThat(rows(r)).containsExactlyElementsOf(BEFORE);
            }
        }
        final Path table = root.resolve("m");
        assertThat(table.resolve("key.sym")).exists();
        assertThat(table.resolve("k.sym")).doesNotExist();
        try (final Store store = Store.open(root)) {
            try (final TableWriter w = store.writer("m", WAIT_MILLIS)) {
                row(w, "c", 4L, "four", DAY2 + 2L);
                row(w, "b", 5L, "five", DAY2 + 3L);
                w.commit();
            }
        }
        final List<String> more = new ArrayList<>(BEFORE);
        more.add("\"c\"|4|\"four\"|" + (DAY2 + 2L));
        more.add("\"b\"|5|\"five\"|" + (DAY2 + 3L));
        try (final Store store = Store.open(root); final TableReader r = store.reader("m")) {
            assertThat(rows(r)).containsExactlyElementsOf(more);
        }
    }

    /// Dropping a SYMBOL column deletes its dictionary; a column of that name added later starts with an empty one.
    @Test
    void dropOfASymbolColumnDeletesItsDictionary() {
        try (final Store store = Store.open(root)) {
            seed(store);
            change(store, w -> w.dropColumn("k"), w -> {
                row(w, 1L, "one", DAY1 + 1L);
                row(w, null, null, DAY1 + 2L);
                row(w, 3L, "three", DAY2 + 1L);
            });
        }
        assertThat(root.resolve("m").resolve("k.sym")).doesNotExist();
        try (final Store store = Store.open(root)) {
            try (final TableWriter w = store.writer("m", WAIT_MILLIS)) {
                final int k = w.addColumn(new ColumnDef("k", CdbType.SYMBOL));
                w.newRow(DAY2 + 2L);
                w.putText(k, "z");
                w.endRow();
                w.commit();
            }
        }
        try (final Store store = Store.open(root); final TableReader r = store.reader("m")) {
            assertThat(rows(r)).containsExactly(
                    "1|\"one\"|" + (DAY1 + 1L) + "|null",
                    "null|null|" + (DAY1 + 2L) + "|null",
                    "3|\"three\"|" + (DAY2 + 1L) + "|null",
                    "null|null|" + (DAY2 + 2L) + "|\"z\"");
        }
    }

    /// A rename of an upsert key renames the key: a row of the same designated timestamp and key value, written after,
    /// replaces the one there.
    @Test
    void renameRenamesADedupKey() {
        final TableDef keyed = new TableDef("m", M.columns(), 3, PartitionBy.DAY, List.of("ts", "k"), 0L);
        try (final Store store = Store.open(root)) {
            store.create(keyed, false);
            try (final TableWriter w = store.writer("m", WAIT_MILLIS)) {
                row(w, "a", 1L, "one", DAY1 + 1L);
                row(w, "b", 2L, "two", DAY1 + 1L);
                w.commit();
            }
            change(store, w -> w.renameColumn("k", "key"), w -> {
                row(w, "a", 1L, "one", DAY1 + 1L);
                row(w, "b", 2L, "two", DAY1 + 1L);
            });
            try (final TableWriter w = store.writer("m", WAIT_MILLIS)) {
                assertThat(w.def().dedupKeys()).containsExactly("ts", "key");
                row(w, "a", 9L, "nine", DAY1 + 1L);
                w.commit();
            }
        }
        try (final Store store = Store.open(root); final TableReader r = store.reader("m")) {
            assertThat(r.def().dedupKeys()).containsExactly("ts", "key");
            assertThat(rows(r)).containsExactlyInAnyOrder(
                    "\"a\"|9|\"nine\"|" + (DAY1 + 1L),
                    "\"b\"|2|\"two\"|" + (DAY1 + 1L));
        }
    }

    /// An empty table commits the change with no row to write, and a table without designated timestamp takes it.
    @Test
    void anEmptyTableAndOneWithoutTimestampTakeTheChange() {
        final TableDef plain = new TableDef("p", List.of(
                new ColumnDef("n", CdbType.LONG),
                new ColumnDef("s", CdbType.SYMBOL)), -1, PartitionBy.NONE, List.of(), 0L);
        try (final Store store = Store.open(root)) {
            store.create(M, false);
            change(store, w -> w.dropColumn("v"), _ -> {
            });
            store.create(plain, false);
            try (final TableWriter w = store.writer("p", WAIT_MILLIS)) {
                w.newRow(Long.MIN_VALUE);
                w.putLong(0, 1L);
                w.putText(1, "x");
                w.endRow();
                w.commit();
            }
            try (final TableWriter w = store.writer("p", WAIT_MILLIS)) {
                w.renameColumn("s", "t");
                w.truncate();
                w.newRow(Long.MIN_VALUE);
                w.putLong(0, 1L);
                w.putText(1, "x");
                w.endRow();
                w.commit();
            }
        }
        try (final Store store = Store.open(root)) {
            try (final TableReader r = store.reader("m")) {
                assertThat(names(r.def())).containsExactly("k", "l", "ts");
                assertThat(r.def().timestampIndex()).isEqualTo(2);
                assertThat(rows(r)).isEmpty();
            }
            try (final TableReader r = store.reader("p")) {
                assertThat(names(r.def())).containsExactly("n", "t");
                assertThat(rows(r)).containsExactly("1|\"x\"");
            }
        }
    }

    /// A writer holding rows, a whole row or one being written, refuses either change and keeps its rows; once they
    /// are committed the same change is taken.
    @Test
    void rowsPendingRefuseTheChange() {
        try (final Store store = Store.open(root)) {
            store.create(M, false);
            try (final TableWriter w = store.writer("m", WAIT_MILLIS)) {
                row(w, "a", 1L, "one", DAY1 + 1L);
                assertThatThrownBy(() -> w.dropColumn("l")).isInstanceOf(CdbException.class);
                assertThatThrownBy(() -> w.renameColumn("l", "x")).isInstanceOf(CdbException.class);
                w.newRow(DAY1 + 2L);
                assertThatThrownBy(() -> w.dropColumn("l")).isInstanceOf(CdbException.class);
                w.putLong(1, 2L);
                w.endRow();
                assertThat(w.def()).isEqualTo(M);
                w.commit();
                w.dropColumn("l");
                w.rollback();
            }
            try (final TableReader r = store.reader("m")) {
                assertThat(r.def()).isEqualTo(M);
                assertThat(rows(r)).containsExactly(
                        "\"a\"|1|\"one\"|" + (DAY1 + 1L),
                        "null|2|null|" + (DAY1 + 2L));
            }
        }
    }

    /// Each refused change leaves the writer's definition as it was: a column the table lacks, the designated
    /// timestamp, an upsert key or the last column dropped; a rename to a name another column has in any case, the
    /// column's own in another case, a name the store refuses or one over the length limit.
    @Test
    void refusedChangesLeaveTheDefinition() {
        final TableDef keyed = new TableDef("m", M.columns(), 3, PartitionBy.DAY, List.of("ts", "k"), 0L);
        final TableDef single = new TableDef("one", List.of(new ColumnDef("x", CdbType.LONG)), -1, PartitionBy.NONE,
                List.of(), 0L);
        try (final Store store = Store.open(root)) {
            store.create(keyed, false);
            store.create(single, false);
            try (final TableWriter w = store.writer("m", WAIT_MILLIS)) {
                assertThatThrownBy(() -> w.dropColumn("nope")).isInstanceOf(CdbException.class);
                assertThatThrownBy(() -> w.dropColumn("ts")).isInstanceOf(CdbException.class);
                assertThatThrownBy(() -> w.dropColumn("K")).isInstanceOf(CdbException.class);
                assertThatThrownBy(() -> w.renameColumn("nope", "x")).isInstanceOf(CdbException.class);
                assertThatThrownBy(() -> w.renameColumn("l", "V")).isInstanceOf(CdbException.class);
                assertThatThrownBy(() -> w.renameColumn("l", "L")).isInstanceOf(CdbException.class);
                assertThatThrownBy(() -> w.renameColumn("l", "a/b")).isInstanceOf(CdbException.class);
                assertThatThrownBy(() -> w.renameColumn("l", "")).isInstanceOf(CdbException.class);
                assertThatThrownBy(() -> w.renameColumn("l", "x".repeat(Store.MAX_NAME_LENGTH + 1)))
                        .isInstanceOf(CdbException.class);
                assertThat(w.def()).isEqualTo(keyed);
                w.renameColumn("l", "x".repeat(Store.MAX_NAME_LENGTH));
                w.rollback();
                assertThat(w.def()).isEqualTo(keyed);
            }
            try (final TableWriter w = store.writer("one", WAIT_MILLIS)) {
                assertThatThrownBy(() -> w.dropColumn("x")).isInstanceOf(CdbException.class);
                assertThat(w.def()).isEqualTo(single);
            }
        }
    }

    /// One transaction takes one column change: a second drop or rename, or a column added after it, is refused, and
    /// so is a drop after a column was added; after a rollback the same change is taken.
    @Test
    void oneColumnChangePerTransaction() {
        try (final Store store = Store.open(root)) {
            store.create(M, false);
            try (final TableWriter w = store.writer("m", WAIT_MILLIS)) {
                w.dropColumn("l");
                assertThatThrownBy(() -> w.dropColumn("v")).isInstanceOf(CdbException.class);
                assertThatThrownBy(() -> w.renameColumn("v", "x")).isInstanceOf(CdbException.class);
                assertThatThrownBy(() -> w.addColumn(new ColumnDef("x", CdbType.LONG)))
                        .isInstanceOf(CdbException.class);
                w.rollback();
                w.addColumn(new ColumnDef("x", CdbType.LONG));
                assertThatThrownBy(() -> w.dropColumn("v")).isInstanceOf(CdbException.class);
                w.rollback();
                w.renameColumn("v", "x");
                w.commit();
            }
            try (final TableReader r = store.reader("m")) {
                assertThat(names(r.def())).containsExactly("k", "l", "x", "ts");
            }
        }
    }

    /// A commit that fails before its `_txn` (an injected failure there) leaves the table as before, in the process and
    /// at the next open: the old definition and rows, `_meta` holding the old definition, no `_meta.prev` and no
    /// dictionary of the new name; the table takes commits under the old definition, and the change again.
    @Test
    void aFailedCommitLeavesTheTableAsBefore() throws IOException {
        final Path table = root.resolve("m");
        try (final Store store = Store.open(root)) {
            seed(store);
            store.beforeTxnReplace = () -> {
                throw new UncheckedIOException(new IOException("injected failure before the txn replace"));
            };
            try (final TableWriter w = store.writer("m", WAIT_MILLIS)) {
                w.renameColumn("k", "key");
                w.truncate();
                row(w, "a", 1L, "one", DAY1 + 1L);
                assertThatThrownBy(w::commit).isInstanceOf(CdbException.class);
            }
            store.beforeTxnReplace = () -> {
            };
            assertThat(TableFiles.decodeMeta(StoreFiles.readText(table.resolve(TableFiles.META)), "_meta"))
                    .isEqualTo(M);
            assertThat(table.resolve(TableFiles.META_PREV)).doesNotExist();
            assertThat(table.resolve("key.sym")).doesNotExist();
            try (final TableReader r = store.reader("m")) {
                assertThat(r.def()).isEqualTo(M);
                assertThat(rows(r)).containsExactlyElementsOf(BEFORE);
            }
            try (final TableWriter w = store.writer("m", WAIT_MILLIS)) {
                row(w, "c", 4L, "four", DAY2 + 2L);
                w.commit();
            }
        }
        final List<String> more = new ArrayList<>(BEFORE);
        more.add("\"c\"|4|\"four\"|" + (DAY2 + 2L));
        try (final Store store = Store.open(root)) {
            try (final TableReader r = store.reader("m")) {
                assertThat(r.def()).isEqualTo(M);
                assertThat(rows(r)).containsExactlyElementsOf(more);
            }
            change(store, w -> w.renameColumn("k", "key"), w -> {
                row(w, "a", 1L, "one", DAY1 + 1L);
                row(w, "b", null, null, DAY1 + 2L);
                row(w, "a", 3L, "three", DAY2 + 1L);
                row(w, "c", 4L, "four", DAY2 + 2L);
            });
        }
        try (final Store store = Store.open(root); final TableReader r = store.reader("m")) {
            assertThat(names(r.def())).containsExactly("key", "l", "v", "ts");
            assertThat(rows(r)).containsExactlyElementsOf(more);
        }
    }

    /// The folder as a power cut finds it just before the commit's `_txn` is replaced (`_meta` and `_meta.prev`
    /// written, the new partitions and dictionaries there, `_txn` the old one) opens as before, `_meta.prev` moved
    /// back and deleted, the new name's dictionary deleted; the folder the commit completed opens as after.
    @Test
    void aPowerCutBeforeTheTxnOpensAsBefore(@TempDir final Path cut) throws IOException {
        try (final Store store = Store.open(root)) {
            seed(store);
            store.beforeTxnReplace = () -> copy(root, cut);
            change(store, w -> w.renameColumn("k", "key"), w -> {
                row(w, "a", 1L, "one", DAY1 + 1L);
                row(w, "b", null, null, DAY1 + 2L);
                row(w, "a", 3L, "three", DAY2 + 1L);
            });
        }
        final Path cutTable = cut.resolve("m");
        assertThat(cutTable.resolve(TableFiles.META_PREV)).exists();
        assertThat(cutTable.resolve("key.sym")).exists();
        try (final Store store = Store.open(cut)) {
            try (final TableReader r = store.reader("m")) {
                assertThat(r.def()).isEqualTo(M);
                assertThat(rows(r)).containsExactlyElementsOf(BEFORE);
            }
            assertThat(cutTable.resolve(TableFiles.META_PREV)).doesNotExist();
            assertThat(cutTable.resolve("key.sym")).doesNotExist();
            assertThat(TableFiles.decodeMeta(StoreFiles.readText(cutTable.resolve(TableFiles.META)), "_meta"))
                    .isEqualTo(M);
            try (final TableWriter w = store.writer("m", WAIT_MILLIS)) {
                row(w, "c", 4L, "four", DAY2 + 2L);
                w.commit();
            }
        }
        final List<String> more = new ArrayList<>(BEFORE);
        more.add("\"c\"|4|\"four\"|" + (DAY2 + 2L));
        try (final Store store = Store.open(cut); final TableReader r = store.reader("m")) {
            assertThat(r.def()).isEqualTo(M);
            assertThat(rows(r)).containsExactlyElementsOf(more);
        }
        try (final Store store = Store.open(root); final TableReader r = store.reader("m")) {
            assertThat(names(r.def())).containsExactly("key", "l", "v", "ts");
            assertThat(rows(r)).containsExactlyElementsOf(BEFORE);
        }
    }

    /// A power cut after the commit's `_txn` held but before its leftovers went (`_meta.prev`, the old name's
    /// dictionary) opens as after, the leftovers deleted.
    @Test
    void leftoversOfAHeldChangeGoAtTheNextOpen() throws IOException {
        final Path table = root.resolve("m");
        final String metaBefore;
        try (final Store store = Store.open(root)) {
            seed(store);
            metaBefore = StoreFiles.readText(table.resolve(TableFiles.META));
            change(store, w -> w.renameColumn("k", "key"), w -> {
                row(w, "a", 1L, "one", DAY1 + 1L);
                row(w, "b", null, null, DAY1 + 2L);
                row(w, "a", 3L, "three", DAY2 + 1L);
            });
        }
        final long txn = TableFiles.decodeTxn(StoreFiles.readText(table.resolve(TableFiles.TXN)), "_txn").number();
        Files.writeString(table.resolve(TableFiles.META_PREV), TableFiles.encodePrev(new TableFiles.Prev(txn,
                metaBefore)));
        Files.writeString(table.resolve("k.sym"), "");
        try (final Store store = Store.open(root); final TableReader r = store.reader("m")) {
            assertThat(names(r.def())).containsExactly("key", "l", "v", "ts");
            assertThat(rows(r)).containsExactlyElementsOf(BEFORE);
        }
        assertThat(table.resolve(TableFiles.META_PREV)).doesNotExist();
        assertThat(table.resolve("k.sym")).doesNotExist();
        assertThat(table.resolve("key.sym")).exists();
    }

    private static void seed(final Store store) {
        store.create(M, false);
        try (final TableWriter w = store.writer("m", WAIT_MILLIS)) {
            row(w, "a", 1L, "one", DAY1 + 1L);
            row(w, "b", null, null, DAY1 + 2L);
            row(w, "a", 3L, "three", DAY2 + 1L);
            w.commit();
        }
    }

    // the runner's sequence: the writer, a snapshot held across the change, the change, a truncate, the rows, a commit
    private static void change(final Store store, final Consumer<TableWriter> change,
            final Consumer<TableWriter> rows) {
        try (final TableWriter w = store.writer("m", WAIT_MILLIS); final TableReader _ = store.reader("m")) {
            change.accept(w);
            w.truncate();
            rows.accept(w);
            w.commit();
        }
    }

    // a row of w's columns, values in column order, null for NULL, the designated timestamp's a Long
    private static void row(final TableWriter w, final Object... values) {
        final TableDef def = w.def();
        final int ts = def.timestampIndex();
        w.newRow(ts < 0 ? Long.MIN_VALUE : (Long) values[ts]);
        for (int c = 0; c < values.length; c++) {
            if (c == ts || values[c] == null) {
                continue;
            }
            switch (def.columns().get(c).type()) {
                case LONG, TIMESTAMP -> w.putLong(c, (Long) values[c]);
                case DOUBLE -> w.putDouble(c, (Double) values[c]);
                case BOOLEAN -> w.putBoolean(c, (Boolean) values[c]);
                case SYMBOL, VARCHAR -> w.putText(c, (String) values[c]);
            }
        }
        w.endRow();
    }

    private static List<String> names(final TableDef def) {
        final List<String> names = new ArrayList<>();
        for (final ColumnDef column : def.columns()) {
            names.add(column.name());
        }
        return names;
    }

    private static List<Path> partitionFolders(final Path table) throws IOException {
        try (final Stream<Path> entries = Files.list(table)) {
            return entries.filter(Files::isDirectory).toList();
        }
    }

    // a copy of every file under from into to, as a power cut would leave them were the page cache on the device
    private static void copy(final Path from, final Path to) {
        try (final Stream<Path> paths = Files.walk(from)) {
            for (final Path path : paths.toList()) {
                final Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
