package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static dev.marregui.comando.cdb.store.StoreRows.timestamps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/// [Store#dropTable], [TableWriter#dropPartitions], [Store#partitions] and the store's limits on tables, columns and
/// names.
///
/// - a drop leaves a held snapshot readable, the table gone for new readers, and its folder deleted once unheld
/// - a drop takes the table's queued commits off the store's budget once, and no apply follows it
/// - a line-protocol write waiting behind a drop creates the table again; a writer waiting behind one cannot find it
/// - a create takes no folder that is there, and a line refused after its last writer attempt is not kept refused
/// - a partition drop removes whole partitions only, the pending rows in them included, and survives a reopen
/// - the limits refuse a create, an added column and a line-protocol write past them
///
/// The suspension case sets [StoreFiles#recorder], a static: safe because cdb's tests run no two classes at once in
/// one JVM, and the test resets it.
class StoreDropTest {

    private static final long DAY1 = micros("2026-11-01T00:00:00Z");
    private static final long DAY2 = DAY1 + Retention.DAY_MICROS;
    private static final long DAY3 = DAY2 + Retention.DAY_MICROS;
    private static final long DAY4 = DAY3 + Retention.DAY_MICROS;
    private static final long HOUR = Retention.HOUR_MICROS;
    // a request no test makes wait for a writer: a wait this long is a leak
    private static final long WAIT_MILLIS = 1_000L;
    // a request a test makes wait for a writer it holds: it gives up only when the test hangs
    private static final long HELD_WAIT_MILLIS = 30_000L;
    // a lag-0 read the applier will not serve gives up after this: long enough for its turn to have run, as
    // StoreApplyTest's
    private static final long SHORT_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(200L);
    // how long a condition the applier brings about may take on a loaded machine, as StoreApplyTest's
    private static final long EVENTUALLY_NANOS = TimeUnit.SECONDS.toNanos(30L);

    /// A drop of a table that is not there answers false and changes nothing.
    @Test
    void dropOfAnAbsentTableIsFalse(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(table("m"), false);
            assertThat(store.dropTable("other", WAIT_MILLIS)).isFalse();
            assertThat(store.tables()).containsExactly("m");
        }
    }

    /// A drop with no reader deletes the folder at once; the table is gone for `def`, `reader`, `writer` and
    /// `partitions`, a second drop is false, and a reopen finds no table.
    @Test
    void dropWithoutReadersDeletesTheFolderAtOnce(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(table("m"), false);
            write(store, "m", DAY1, DAY2);
            assertThat(store.dropTable("M", WAIT_MILLIS)).isTrue();
            assertThat(store.def("m")).isNull();
            assertThat(store.tables()).isEmpty();
            assertThatThrownBy(() -> store.reader("m")).isInstanceOf(CdbException.class);
            assertThatThrownBy(() -> store.writer("m", WAIT_MILLIS)).isInstanceOf(CdbException.class);
            assertThatThrownBy(() -> store.partitions("m")).isInstanceOf(CdbException.class);
            assertThat(store.dropTable("m", WAIT_MILLIS)).isFalse();
            assertThat(root.resolve("m")).doesNotExist();
        }
        try (final Store store = Store.open(root)) {
            assertThat(store.tables()).isEmpty();
        }
    }

    /// A reader that held a snapshot before the drop reads it whole after; a line-protocol write recreates the table
    /// beside the held folder, empty but for its own row; the old folder is deleted when that reader closes, and a
    /// reopen reads the new table alone.
    @Test
    void aHeldSnapshotReadsThroughTheDropAndTheLineProtocolRecreatesTheTable(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(table("m"), false);
            write(store, "m", DAY1, DAY2, DAY3);
            final TableReader held = store.reader("m");
            try {
                assertThat(store.dropTable("m", WAIT_MILLIS)).isTrue();
                assertThat(store.def("m")).isNull();
                assertThat(root.resolve("m")).isDirectory();
                try (final CdbAppender ilp = store.appender()) {
                    ilp.table("m").longColumn("v", 7L).at(DAY4);
                    ilp.flush();
                }
                try (final TableReader fresh = store.reader("m")) {
                    assertThat(timestamps(fresh)).containsExactly(DAY4);
                }
                assertThat(held.rowCount()).isEqualTo(3L);
                assertThat(timestamps(held)).containsExactly(DAY1, DAY2, DAY3);
            } finally {
                held.close();
            }
            assertThat(root.resolve("m")).doesNotExist();
        }
        try (final Store store = Store.open(root); final TableReader r = store.reader("m")) {
            assertThat(timestamps(r)).containsExactly(DAY4);
        }
    }

    /// A folder that holds the drop marker, as a store closed while a reader held the dropped table, or a crash after
    /// the drop, leaves it: the next open deletes the folder and does not load the table.
    @Test
    void theNextOpenDeletesTheFolderOfADroppedTable(@TempDir final Path root) throws IOException {
        try (final Store store = Store.open(root)) {
            store.create(table("m"), false);
            write(store, "m", DAY1);
        }
        StoreFiles.replaceText(root.resolve("m").resolve(TableFiles.DROPPED), "");
        try (final Store store = Store.open(root)) {
            assertThat(store.tables()).isEmpty();
            assertThat(root.resolve("m")).doesNotExist();
            store.create(table("m"), false);
            assertThat(store.partitions("m")).isEmpty();
        }
    }

    /// A drop waits for the open writer and fails when it stays open, the table left whole; once the writer closes,
    /// the drop goes through.
    @Test
    void aDropWaitsForTheWriter(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(table("m"), false);
            write(store, "m", DAY1);
            try (final TableWriter w = store.writer("m", WAIT_MILLIS)) {
                assertThatThrownBy(() -> store.dropTable("m", 50L)).isInstanceOf(CdbException.class);
                w.newRow(DAY2);
                w.endRow();
                w.commit();
            }
            try (final TableReader r = store.reader("m")) {
                assertThat(timestamps(r)).containsExactly(DAY1, DAY2);
            }
            assertThat(store.dropTable("m", WAIT_MILLIS)).isTrue();
        }
    }

    /// Commits answered from the log and still queued for the applier are dropped with the table: the reopen does not
    /// replay them, and a table created again under the name starts empty.
    @Test
    void aDropDiscardsTheQueuedCommits(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(table("m"), false);
            store.applierPaused = true;
            write(store, "m", DAY1);
            write(store, "m", DAY2);
            assertThat(store.applier().queuedBytes()).isPositive();
            assertThat(store.dropTable("m", WAIT_MILLIS)).isTrue();
            assertThat(store.applier().queuedBytes()).isZero();
            store.applierPaused = false;
            store.create(table("m"), false);
            try (final TableReader r = store.reader("m")) {
                assertThat(r.rowCount()).isZero();
            }
        }
        try (final Store store = Store.open(root); final TableReader r = store.reader("m")) {
            assertThat(r.rowCount()).isZero();
        }
    }

    /// A table the applier suspended, its queued commits already off the store's budget, leaves the budget at 0 when
    /// dropped: the drop does not take them off a second time.
    @Test
    void aDropOfASuspendedTableTakesItsCommitsOffTheBudgetOnce(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(table("m"), false);
            final Path folder = root.resolve("m");
            StoreFiles.recorder = new Silent() {
                @Override
                public void wrote(final Path file, final long position, final ByteBuffer bytes) {
                    if (file.startsWith(folder) && file.getFileName().toString().endsWith(".d")) {
                        throw new OutOfMemoryError("injected");
                    }
                }
            };
            try {
                write(store, "m", DAY1, DAY2);
                assertEventually(() -> suspended(store), "table m suspended");
                assertThat(store.applier().queuedBytes()).isZero();
                assertThat(store.dropTable("m", WAIT_MILLIS)).isTrue();
                assertThat(store.applier().queuedBytes()).isZero();
            } finally {
                StoreFiles.recorder = null;
            }
        }
    }

    /// The drop lets go of the apply lock before it closes the table: a turn of the applier that takes the lock in
    /// between, here demanded by a waiting reader, finds the table marked and applies none of its queued commits, so
    /// nothing is written into the dropped folder and the budget stays at 0 through the drop.
    @Test
    void aTurnAfterTheDropLetGoOfTheApplyLockAppliesNothing(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(table("m"), false);
            store.applierPaused = true;
            write(store, "m", DAY1);
            write(store, "m", DAY2);
            final TableState state = store.states().iterator().next();
            // the drop's own step under the lock, without the close that follows it
            state.applyLock.lock();
            try {
                store.applier().forget(state);
            } finally {
                state.applyLock.unlock();
            }
            assertThat(store.applier().queuedBytes()).isZero();
            store.readWaitNanos = SHORT_WAIT_NANOS;
            store.applierPaused = false;
            assertThatThrownBy(() -> store.reader("m")).isInstanceOf(CdbException.class);
            assertThat(state.appliedTxn()).isZero();
            assertThat(store.applier().queuedBytes()).isZero();
            assertThat(store.dropTable("m", WAIT_MILLIS)).isTrue();
            assertThat(store.applier().queuedBytes()).isZero();
        }
    }

    /// A commit queued after a suspend took the table off the budget, as when the suspend comes between the writer's
    /// check and its queue, adds nothing to the budget: no later path would take its bytes off.
    @Test
    void aCommitQueuedAfterTheTableWentOffTheBudgetAddsNothing(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(table("m"), false);
            store.applierPaused = true;
            write(store, "m", DAY1);
            final TableState state = store.states().iterator().next();
            // the suspend's own step, without the mark that refuses the next commit
            final long taken = state.takeBudgetBytes();
            assertThat(taken).isPositive();
            assertThat(store.applier().queuedBytes()).isEqualTo(taken);
            write(store, "m", DAY2);
            assertThat(store.applier().queuedBytes()).isEqualTo(taken);
        }
    }

    /// A create skips the folder of a dropped table a reader still holds, taking the next free name, and takes a stray
    /// folder a cut create left without `_meta`, as before drops existed.
    @Test
    void aCreateSkipsADyingFolderAndTakesAStrayOne(@TempDir final Path root) throws IOException {
        try (final Store store = Store.open(root)) {
            store.create(table("m"), false);
            write(store, "m", DAY1);
            try (final TableReader held = store.reader("m")) {
                assertThat(store.dropTable("m", HELD_WAIT_MILLIS)).isTrue();
                assertThat(store.create(table("m"), false)).isTrue();
                assertThat(root.resolve("m~1")).isDirectory();
                assertThat(timestamps(held)).containsExactly(DAY1);
            }
            assertThat(root.resolve("m")).doesNotExist();
            Files.createDirectory(root.resolve("s"));
            assertThat(store.create(table("s"), false)).isTrue();
            assertThat(root.resolve("s").resolve("_meta")).exists();
            assertThat(root.resolve("s~1")).doesNotExist();
            write(store, "s", DAY2);
            try (final TableReader r = store.reader("s")) {
                assertThat(timestamps(r)).containsExactly(DAY2);
            }
        }
    }

    /// A line whose table a drop removes before each of the appender's writer attempts is refused, here at a cap of
    /// one; the refusal is not kept, so the appender's next line to the table creates it again.
    @Test
    void aLineDroppedBeforeEachWriterAttemptIsRefusedOnce(@TempDir final Path root) throws InterruptedException {
        try (final Store store = Store.open(root);
                final CdbAppender ilp = new IlpAppender(store, HELD_WAIT_MILLIS, 1)) {
            store.create(table("m"), false);
            write(store, "m", DAY1);
            final Throwable refused = afterADropWaitingFirst(store, () -> ilp.table("m").longColumn("v", 7L).at(DAY2));
            assertThat(refused)
                    .isInstanceOf(CdbException.class)
                    .hasMessage("refuse table dropped before each writer [table=m, attempts=1]");
            assertThat(store.def("m")).isNull();
            ilp.table("m").longColumn("v", 8L).at(DAY3);
            ilp.flush();
            try (final TableReader r = store.reader("m")) {
                assertThat(timestamps(r)).containsExactly(DAY3);
            }
        }
    }

    /// A line-protocol write waiting for the writer of a table that a drop, waiting first, removes is not refused: it
    /// creates the table again, holding its row alone.
    @Test
    void aLineWaitingBehindADropCreatesTheTableAgain(@TempDir final Path root) throws InterruptedException {
        try (final Store store = Store.open(root)) {
            store.create(table("m"), false);
            write(store, "m", DAY1);
            final Throwable refused = afterADropWaitingFirst(store, () -> {
                try (final CdbAppender ilp = store.appender()) {
                    ilp.table("m").longColumn("v", 7L).at(DAY2);
                    ilp.flush();
                }
            });
            assertThat(refused).isNull();
            try (final TableReader r = store.reader("m")) {
                assertThat(timestamps(r)).containsExactly(DAY2);
            }
        }
    }

    /// A writer asked for a table that is absent, or that a drop waiting first removes, is refused as a table that
    /// cannot be found, as the SQL statements refuse one.
    @Test
    void aWriterOfATableDroppedBeforeItIsTakenCannotFindTheTable(@TempDir final Path root)
            throws InterruptedException {
        try (final Store store = Store.open(root)) {
            store.create(table("m"), false);
            write(store, "m", DAY1);
            assertThatThrownBy(() -> store.writer("nope", WAIT_MILLIS))
                    .isInstanceOf(CdbException.class)
                    .hasMessage("cannot find table [table=nope]");
            final Throwable refused = afterADropWaitingFirst(store, () -> {
                try (final TableWriter _ = store.writer("m", HELD_WAIT_MILLIS)) {
                    fail("writer of a dropped table");
                }
            });
            assertThat(refused)
                    .isInstanceOf(CdbException.class)
                    .hasMessage("cannot find table [table=m]");
            assertThat(store.def("m")).isNull();
        }
    }

    /// A partition drop removes the committed partitions wholly inside the range and the pending rows that fall in
    /// them; a row appended after the call starts its partition again; a reader that held a snapshot reads it whole;
    /// a reopen reads what the commit published.
    @Test
    void dropPartitionsRemovesWholePartitionsAndThePendingRowsInThem(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(table("m"), false);
            write(store, "m", DAY1, DAY1 + HOUR, DAY2, DAY3, DAY4);
            try (final TableReader held = store.reader("m")) {
                try (final TableWriter w = store.writer("m", WAIT_MILLIS)) {
                    w.newRow(DAY2 + HOUR);
                    w.endRow();
                    w.newRow(DAY4 + HOUR);
                    w.endRow();
                    w.dropPartitions(DAY2, DAY4);
                    w.newRow(DAY3 + 2 * HOUR);
                    w.endRow();
                    w.commit();
                }
                assertThat(timestamps(held)).containsExactly(DAY1, DAY1 + HOUR, DAY2, DAY3, DAY4);
            }
            try (final TableReader r = store.reader("m")) {
                assertThat(timestamps(r)).containsExactly(DAY1, DAY1 + HOUR, DAY3 + 2 * HOUR, DAY4, DAY4 + HOUR);
            }
        }
        try (final Store store = Store.open(root); final TableReader r = store.reader("m")) {
            assertThat(timestamps(r)).containsExactly(DAY1, DAY1 + HOUR, DAY3 + 2 * HOUR, DAY4, DAY4 + HOUR);
        }
    }

    /// A partition only partly inside the range stays; ranges add up within one commit; an empty range drops nothing.
    @Test
    void dropPartitionsKeepsAPartitionPartlyOutsideTheRange(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(table("m"), false);
            write(store, "m", DAY1, DAY2, DAY3, DAY4);
            try (final TableWriter w = store.writer("m", WAIT_MILLIS)) {
                w.dropPartitions(DAY1 + 1L, DAY2 + Retention.DAY_MICROS);
                w.dropPartitions(DAY4, DAY4);
                w.dropPartitions(DAY3, DAY4 - 1L);
                w.commit();
            }
            try (final TableReader r = store.reader("m")) {
                assertThat(timestamps(r)).containsExactly(DAY1, DAY3, DAY4);
            }
            try (final TableWriter w = store.writer("m", WAIT_MILLIS)) {
                w.dropPartitions(DAY3, DAY4);
                w.dropPartitions(DAY4, DAY4 + Retention.DAY_MICROS);
                w.commit();
            }
            try (final TableReader r = store.reader("m")) {
                assertThat(timestamps(r)).containsExactly(DAY1);
            }
        }
    }

    /// A second partition drop before the commit removes the pending rows in its own range only: a row appended after
    /// the first drop, in the first range, stays in a new partition, and one in the second range goes.
    @Test
    void aSecondDropPartitionsKeepsTheRowsAppendedAfterTheFirst(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(table("m"), false);
            write(store, "m", DAY1, DAY2, DAY3);
            try (final TableWriter w = store.writer("m", WAIT_MILLIS)) {
                w.dropPartitions(DAY1, DAY2);
                w.newRow(DAY1 + HOUR);
                w.endRow();
                w.newRow(DAY2 + HOUR);
                w.endRow();
                w.dropPartitions(DAY2, DAY3);
                w.commit();
            }
            try (final TableReader r = store.reader("m")) {
                assertThat(timestamps(r)).containsExactly(DAY1 + HOUR, DAY3);
            }
        }
    }

    /// The commits queued for the applier are applied before the drop, so the drop removes their rows too, and a
    /// reopen, whose replay skips the log records the drop's checkpoint covers, does not bring them back.
    @Test
    void dropPartitionsRemovesTheQueuedCommitsRowsForGood(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(table("m"), false);
            store.applierPaused = true;
            write(store, "m", DAY1, DAY2);
            write(store, "m", DAY1 + HOUR);
            try (final TableWriter w = store.writer("m", WAIT_MILLIS)) {
                w.dropPartitions(DAY1, DAY2);
                w.commit();
            }
            store.applierPaused = false;
            try (final TableReader r = store.reader("m")) {
                assertThat(timestamps(r)).containsExactly(DAY2);
            }
        }
        try (final Store store = Store.open(root); final TableReader r = store.reader("m")) {
            assertThat(timestamps(r)).containsExactly(DAY2);
        }
    }

    /// A rollback forgets the partition drop; a table without partitions refuses one.
    @Test
    void dropPartitionsIsRolledBackAndRefusedWithoutPartitions(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(table("m"), false);
            write(store, "m", DAY1);
            try (final TableWriter w = store.writer("m", WAIT_MILLIS)) {
                w.dropPartitions(DAY1, DAY2);
                w.rollback();
                w.commit();
            }
            try (final TableReader r = store.reader("m")) {
                assertThat(timestamps(r)).containsExactly(DAY1);
            }
            store.create(new TableDef("n", List.of(new ColumnDef("v", CdbType.LONG),
                    new ColumnDef("ts", CdbType.TIMESTAMP)), 1, PartitionBy.NONE, List.of(), 0L), false);
            try (final TableWriter w = store.writer("n", WAIT_MILLIS)) {
                assertThatThrownBy(() -> w.dropPartitions(Long.MIN_VALUE, Long.MAX_VALUE))
                        .isInstanceOf(CdbException.class);
            }
        }
    }

    /// The partitions are listed in time order with their names, rows, timestamp ranges and file bytes; the newest is
    /// the active one; an empty table lists none and an unknown one is refused.
    @Test
    void partitionsListsEachPartitionAndTheActiveOne(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(table("m"), false);
            assertThat(store.partitions("m")).isEmpty();
            write(store, "m", DAY3, DAY1 + HOUR, DAY1, DAY3 + 2 * HOUR, DAY3 + HOUR);
            final List<PartitionInfo> partitions = store.partitions("m");
            assertThat(partitions).extracting(PartitionInfo::name).containsExactly("2026-11-01", "2026-11-03");
            assertThat(partitions).extracting(PartitionInfo::rows).containsExactly(2L, 3L);
            assertThat(partitions).extracting(PartitionInfo::minTimestampMicros).containsExactly(DAY1, DAY3);
            assertThat(partitions).extracting(PartitionInfo::maxTimestampMicros)
                    .containsExactly(DAY1 + HOUR, DAY3 + 2 * HOUR);
            assertThat(partitions).extracting(PartitionInfo::active).containsExactly(false, true);
            // two LONG columns, eight bytes per row each
            assertThat(partitions).extracting(PartitionInfo::diskBytes).containsExactly(32L, 48L);
            assertThatThrownBy(() -> store.partitions("other")).isInstanceOf(CdbException.class);
        }
    }

    /// The store holds at most [Store#MAX_TABLES] tables: one more is refused by `create` and by a line-protocol
    /// write, until a drop frees a place.
    @Test
    void createRefusesATableOverTheTableLimit(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            for (int i = 0; i < Store.MAX_TABLES; i++) {
                store.create(table("t" + i), false);
            }
            assertThatThrownBy(() -> store.create(table("over"), false)).isInstanceOf(CdbException.class);
            try (final CdbAppender ilp = store.appender()) {
                assertThatThrownBy(() -> ilp.table("over").longColumn("v", 1L).at(DAY1))
                        .isInstanceOf(CdbException.class);
            }
            assertThat(store.def("over")).isNull();
            assertThat(store.create(table("t0"), true)).isFalse();
            assertThat(store.dropTable("t0", WAIT_MILLIS)).isTrue();
            assertThat(store.create(table("over"), false)).isTrue();
        }
    }

    /// A table of [Store#MAX_COLUMNS] columns is created; one more column is refused by `create`, by `addColumn` and
    /// by a line-protocol field.
    @Test
    void theColumnLimitHoldsForCreateAddColumnAndTheLineProtocol(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(wide("w", Store.MAX_COLUMNS), false);
            assertThatThrownBy(() -> store.create(wide("x", Store.MAX_COLUMNS + 1), false))
                    .isInstanceOf(CdbException.class);
            assertThat(store.def("x")).isNull();
            try (final TableWriter w = store.writer("w", WAIT_MILLIS)) {
                assertThatThrownBy(() -> w.addColumn(new ColumnDef("more", CdbType.LONG)))
                        .isInstanceOf(CdbException.class);
            }
            try (final CdbAppender ilp = store.appender()) {
                assertThatThrownBy(() -> {
                    ilp.table("w").longColumn("more", 1L).at(DAY1);
                    ilp.flush();
                }).isInstanceOf(CdbException.class);
            }
            assertThat(store.def("w").columns()).hasSize(Store.MAX_COLUMNS);
        }
    }

    /// A table or column name of [Store#MAX_NAME_LENGTH] characters is taken; one character more is refused by
    /// `create`, by `addColumn` and by the line protocol.
    @Test
    void theNameLimitHoldsForTablesAndColumns(@TempDir final Path root) {
        final String longest = "n".repeat(Store.MAX_NAME_LENGTH);
        final String over = longest + "n";
        try (final Store store = Store.open(root)) {
            assertThat(store.create(table(longest), false)).isTrue();
            assertThatThrownBy(() -> store.create(table(over), false)).isInstanceOf(CdbException.class);
            assertThatThrownBy(() -> store.create(new TableDef("c", List.of(new ColumnDef(over, CdbType.LONG),
                    new ColumnDef("ts", CdbType.TIMESTAMP)), 1, PartitionBy.DAY, List.of(), 0L), false))
                    .isInstanceOf(CdbException.class);
            try (final TableWriter w = store.writer(longest, WAIT_MILLIS)) {
                assertThat(w.addColumn(new ColumnDef(longest, CdbType.LONG))).isEqualTo(2);
                assertThatThrownBy(() -> w.addColumn(new ColumnDef(over, CdbType.LONG)))
                        .isInstanceOf(CdbException.class);
            }
            try (final CdbAppender ilp = store.appender()) {
                assertThatThrownBy(() -> ilp.table(over).longColumn("v", 1L).at(DAY1))
                        .isInstanceOf(CdbException.class);
            }
            assertThat(store.tables()).containsExactly(longest);
        }
    }

    // a LONG column v and the designated timestamp ts, by day
    private static TableDef table(final String name) {
        return new TableDef(name, List.of(new ColumnDef("v", CdbType.LONG), new ColumnDef("ts", CdbType.TIMESTAMP)), 1,
                PartitionBy.DAY, List.of(), 0L);
    }

    // columns LONG columns, the last the designated timestamp
    private static TableDef wide(final String name, final int columns) {
        final List<ColumnDef> defs = new ArrayList<>(columns);
        for (int c = 0; c < columns - 1; c++) {
            defs.add(new ColumnDef("c" + c, CdbType.LONG));
        }
        defs.add(new ColumnDef("ts", CdbType.TIMESTAMP));
        return new TableDef(name, defs, columns - 1, PartitionBy.DAY, List.of(), 0L);
    }

    // one commit of a row at each of timestamps, v its index
    private static void write(final Store store, final String table, final long... timestamps) {
        try (final TableWriter w = store.writer(table, WAIT_MILLIS)) {
            for (int i = 0; i < timestamps.length; i++) {
                w.newRow(timestamps[i]);
                w.putLong(0, i);
                w.endRow();
            }
            w.commit();
        }
    }

    // holds m's writer while a drop of m and then `second` wait for it, lets it go, and answers what `second` threw,
    // null when nothing: the drop, first in the lease's queue, takes the writer first and removes the table
    private static Throwable afterADropWaitingFirst(final Store store, final Runnable second)
            throws InterruptedException {
        final AtomicBoolean dropped = new AtomicBoolean();
        final AtomicReference<Throwable> dropFailure = new AtomicReference<>();
        final AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        final Thread drop;
        final Thread waiter;
        try (final TableWriter _ = store.writer("m", WAIT_MILLIS)) {
            drop = Thread.ofPlatform().name("store-drop-test-drop").start(() -> {
                try {
                    dropped.set(store.dropTable("m", HELD_WAIT_MILLIS));
                } catch (final Throwable e) {
                    dropFailure.set(e);
                }
            });
            assertEventually(() -> drop.getState() == Thread.State.TIMED_WAITING, "drop waits for the writer");
            waiter = Thread.ofPlatform().name("store-drop-test-waiter").start(() -> {
                try {
                    second.run();
                } catch (final Throwable e) {
                    secondFailure.set(e);
                }
            });
            assertEventually(() -> waiter.getState() == Thread.State.TIMED_WAITING, "second waits for the writer");
        }
        final Duration join = Duration.ofNanos(EVENTUALLY_NANOS);
        assertThat(drop.join(join)).as("drop ended").isTrue();
        assertThat(waiter.join(join)).as("second ended").isTrue();
        assertThat(dropFailure.get()).isNull();
        assertThat(dropped.get()).isTrue();
        return secondFailure.get();
    }

    // polls `condition` with a doubling back-off until it holds or EVENTUALLY_NANOS pass
    private static void assertEventually(final BooleanSupplier condition, final String what) {
        final long start = System.nanoTime();
        long backoffNanos = TimeUnit.MICROSECONDS.toNanos(100L);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - start - EVENTUALLY_NANOS > 0L) {
                fail("condition not reached [what=" + what + ", waitMillis="
                        + TimeUnit.NANOSECONDS.toMillis(EVENTUALLY_NANOS) + ']');
            }
            LockSupport.parkNanos(backoffNanos);
            backoffNanos = Math.min(2L * backoffNanos, TimeUnit.MILLISECONDS.toNanos(50L));
        }
    }

    // whether the applier suspended the store's one table
    private static boolean suspended(final Store store) {
        return store.walStatus().getFirst().suspended();
    }

    /// A recorder that sees nothing; a test overrides the operation it fails.
    private static class Silent implements StoreFiles.Recorder {

        @Override
        public void created(final Path path) {
        }

        @Override
        public void wrote(final Path file, final long position, final ByteBuffer bytes) {
        }

        @Override
        public void truncated(final Path file, final long size) {
        }

        @Override
        public void forcing(final Path file) {
        }

        @Override
        public void folderForcing(final Path folder) {
        }

        @Override
        public void moved(final Path from, final Path to) {
        }

        @Override
        public void deleted(final Path path) {
        }
    }
}
