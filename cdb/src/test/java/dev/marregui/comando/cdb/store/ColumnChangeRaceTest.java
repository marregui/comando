package dev.marregui.comando.cdb.store;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Cursor;
import dev.marregui.comando.cdb.exec.Executor;
import dev.marregui.comando.cdb.exec.QueryContext;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.plan.Planner;
import dev.marregui.comando.cdb.sql.Parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The two races a column change has with the statements around it: a rewrite under a read lag above 0 must keep the
/// commits still queued behind the applier, and a plan made before a column change must not read or write columns by
/// the indexes of the old definition.
class ColumnChangeRaceTest {

    private static final long TS = 1_763_000_000_000_000L;

    @TempDir
    private Path root;

    /// Five rows committed, the last two queued behind a paused applier; DROP COLUMN, RENAME COLUMN, DELETE and
    /// UPDATE, each run under a 60 s read lag, keep every one of them.
    @Test
    void aRewriteUnderAReadLagKeepsTheQueuedCommits() {
        final List<Plan.Change> changes = List.of(
                new Plan.DropColumn("m", "x"),
                new Plan.RenameColumn("m", "value", "v"),
                change("DELETE FROM m WHERE ts < '2000-01-01'"),
                change("UPDATE m SET value = 1.0 WHERE ts < '2000-01-01'"));
        for (int i = 0; i < changes.size(); i++) {
            final Plan.Change change = changes.get(i);
            try (final Store store = Store.open(root.resolve("store" + i))) {
                store.create(def(), false);
                for (int row = 0; row < 3; row++) {
                    commit(store, row);
                }
                store.applierPaused = true;
                for (int row = 3; row < 5; row++) {
                    commit(store, row);
                }
                ScopedValue.where(Store.READ_MAX_LAG_MILLIS, 60_000L)
                        .call(() -> Executor.execute(change, store, TS));
                store.applierPaused = false;
                try (final TableReader reader = store.reader("m")) {
                    assertThat(reader.rowCount()).as("%s", change).isEqualTo(5L);
                }
            }
        }
    }

    /// A query planned on t(ts, a, b, c) and run after DROP COLUMN a committed is refused when it takes its snapshot,
    /// rather than reading c's values as b's; planned again, it reads b.
    @Test
    void aPlanMadeBeforeAColumnChangeIsRefused() {
        try (final Store store = Store.open(root.resolve("store"))) {
            store.create(new TableDef("t", List.of(new ColumnDef("ts", CdbType.TIMESTAMP),
                    new ColumnDef("a", CdbType.LONG), new ColumnDef("b", CdbType.LONG),
                    new ColumnDef("c", CdbType.LONG)), 0, PartitionBy.DAY, List.of(), 0L), false);
            try (final TableWriter writer = store.writer("t", 1_000L)) {
                writer.newRow(TS);
                writer.putLong(1, 1L);
                writer.putLong(2, 2L);
                writer.putLong(3, 3L);
                writer.endRow();
                writer.commit();
            }
            final Store.PlannedDefs defs = store.plannedDefs();
            final Plan before = Planner.plan(Parser.parse("SELECT b FROM t"), defs, TS);
            Executor.execute(new Plan.DropColumn("t", "a"), store, TS);
            assertThatThrownBy(() -> ScopedValue.where(Store.PLANNED_UNDER, defs).call(() -> first(before, store)))
                    .isInstanceOf(Store.DefinitionChanged.class);
            final Store.PlannedDefs again = store.plannedDefs();
            final Plan after = Planner.plan(Parser.parse("SELECT b FROM t"), again, TS);
            assertThat(ScopedValue.where(Store.PLANNED_UNDER, again).call(() -> first(after, store))).isEqualTo(2L);
        }
    }

    /// A plan that looks one table up twice holds the first answer for both: a DROP COLUMN committed between the two
    /// lookups (here, of an IN subquery on the table itself) refuses the run instead of writing c as b.
    @Test
    void aTableLookedUpTwiceHoldsTheFirstDefinition() {
        try (final Store store = Store.open(root.resolve("store"))) {
            store.create(def(), false);
            commit(store, 0);
            final Store.PlannedDefs defs = store.plannedDefs();
            final TableDef first = defs.apply("m");
            Executor.execute(new Plan.DropColumn("m", "value"), store, TS);
            assertThat(defs.apply("M")).isSameAs(first);
            assertThatThrownBy(() -> ScopedValue.where(Store.PLANNED_UNDER, defs).call(() -> rows(store, "m")))
                    .isInstanceOf(Store.DefinitionChanged.class);
        }
    }

    /// A writer is checked too: an UPDATE planned before a DROP COLUMN, which waits for the writer the drop holds, does
    /// not write by the old definition's indexes.
    @Test
    void aWriterTakenUnderAStaleStampIsRefused() {
        try (final Store store = Store.open(root.resolve("store"))) {
            store.create(def(), false);
            commit(store, 0);
            final Store.PlannedDefs defs = store.plannedDefs();
            final Plan.Change update = (Plan.Change) Planner.plan(Parser.parse("UPDATE m SET x = 7"), defs, TS);
            Executor.execute(new Plan.DropColumn("m", "value"), store, TS);
            assertThatThrownBy(() -> ScopedValue.where(Store.PLANNED_UNDER, defs)
                    .call(() -> Executor.execute(update, store, TS)))
                    .isInstanceOf(Store.DefinitionChanged.class);
        }
    }

    /// Only a change to a planned table's leading columns refuses: a column added at the end (as the line protocol
    /// adds one), a table created beside it, and another store's table of the same name read on.
    @Test
    void otherChangesDoNotRefuse() {
        try (final Store store = Store.open(root.resolve("a"));
                final Store other = Store.open(root.resolve("b"))) {
            store.create(def(), false);
            other.create(def(), false);
            final Store.PlannedDefs defs = store.plannedDefs();
            final Plan plan = Planner.plan(Parser.parse("SELECT x FROM m"), defs, TS);
            commit(store, 1);
            try (final TableWriter writer = store.writer("m", 1_000L)) {
                writer.addColumn(new ColumnDef("added", CdbType.LONG));
                writer.commit();
            }
            store.create(new TableDef("n", def().columns(), 2, PartitionBy.DAY, List.of(), 0L), false);
            Executor.execute(new Plan.DropColumn("m", "value"), other, TS);
            assertThat(ScopedValue.where(Store.PLANNED_UNDER, defs).call(() -> first(plan, store))).isEqualTo(1L);
            assertThat(ScopedValue.where(Store.PLANNED_UNDER, defs).call(() -> rows(other, "m"))).isZero();
        }
    }

    /// Through `Cdb`, a statement run after a column change plans under the new definition: SELECT b reads b's value
    /// before and after DROP COLUMN a.
    @Test
    void cdbReadsTheRightColumnAfterADrop() {
        try (final Cdb cdb = Cdb.open(root.resolve("cdb"))) {
            cdb.run("CREATE TABLE t (ts TIMESTAMP, a LONG, b LONG, c LONG) TIMESTAMP(ts) PARTITION BY DAY");
            cdb.run("INSERT INTO t VALUES ('2026-11-19T10:00:00.000000Z', 1, 2, 3)");
            cdb.run("ALTER TABLE t DROP COLUMN a");
            assertThat(cdb.run("SELECT b, c FROM t", 10).getLong(0, 0)).isEqualTo(2L);
            assertThat(cdb.run("SELECT b, c FROM t", 10).getLong(0, 1)).isEqualTo(3L);
        }
    }

    private static Plan.Change change(final String sql) {
        return (Plan.Change) Planner.plan(Parser.parse(sql), name -> "m".equals(name) ? def() : null, TS);
    }

    private static long first(final Plan plan, final Store store) {
        try (final Cursor cursor = Executor.open(plan, store, TS, QueryContext.uncancellable())) {
            return cursor.next().columns[0].getLong(0);
        }
    }

    private static long rows(final Store store, final String table) {
        try (final TableReader reader = store.reader(table)) {
            return reader.rowCount();
        }
    }

    private static TableDef def() {
        return new TableDef("m", List.of(new ColumnDef("value", CdbType.DOUBLE), new ColumnDef("x", CdbType.LONG),
                new ColumnDef("ts", CdbType.TIMESTAMP)), 2, PartitionBy.DAY, List.of(), 0L);
    }

    private static void commit(final Store store, final int row) {
        try (final TableWriter writer = store.writer("m", 1_000L)) {
            writer.newRow(TS + row);
            writer.putDouble(0, row);
            writer.putLong(1, row);
            writer.endRow();
            writer.commit();
        }
    }
}
