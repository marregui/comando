package dev.marregui.comando.cdb.plan;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Field;
import dev.marregui.comando.cdb.sql.Parser;
import dev.marregui.comando.cdb.store.ColumnDef;
import dev.marregui.comando.cdb.store.PartitionBy;
import dev.marregui.comando.cdb.store.Store;
import dev.marregui.comando.cdb.store.TableDef;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The plans of `ALTER TABLE ... DROP COLUMN`, `ALTER TABLE ... RENAME COLUMN` and `BACKUP TO`, parsed from SQL,
/// and every refusal the planner makes for them.
class ColumnPlanTest {

    private static final long NOW = Times.parse("2026-11-19T12:00:00", 0);

    private static final Map<String, TableDef> TABLES = Map.of(
            "trades", new TableDef("trades", List.of(
                    new ColumnDef("ts", CdbType.TIMESTAMP),
                    new ColumnDef("sym", CdbType.SYMBOL),
                    new ColumnDef("price", CdbType.DOUBLE),
                    new ColumnDef("qty", CdbType.LONG)), 0, PartitionBy.DAY, List.of(), 0),
            "keyed", new TableDef("keyed", List.of(
                    new ColumnDef("ts", CdbType.TIMESTAMP),
                    new ColumnDef("sym", CdbType.SYMBOL),
                    new ColumnDef("price", CdbType.DOUBLE)), 0, PartitionBy.DAY, List.of("ts", "SYM"), 0),
            "single", new TableDef("single", List.of(
                    new ColumnDef("a", CdbType.LONG)), -1, PartitionBy.NONE, List.of(), 0),
            "stamp", new TableDef("stamp", List.of(
                    new ColumnDef("ts", CdbType.TIMESTAMP)), 0, PartitionBy.NONE, List.of(), 0),
            "plain", new TableDef("plain", List.of(
                    new ColumnDef("ts", CdbType.TIMESTAMP),
                    new ColumnDef("qty", CdbType.LONG)), -1, PartitionBy.NONE, List.of(), 0)
    );

    /// `DROP COLUMN` names the table and the column as the store has them, whatever case the statement used.
    @Test
    void dropColumn() {
        assertThat(plan("ALTER TABLE TRADES DROP COLUMN PRICE")).isEqualTo(new Plan.DropColumn("trades", "price"));
        assertThat(plan("ALTER TABLE trades DROP COLUMN IF EXISTS Qty"))
                .isEqualTo(new Plan.DropColumn("trades", "qty"));
        assertThat(plan("ALTER TABLE keyed DROP COLUMN price")).isEqualTo(new Plan.DropColumn("keyed", "price"));
    }

    /// A table without designated timestamp may lose a TIMESTAMP column that is not designated.
    @Test
    void dropColumnOfUndesignatedTimestamp() {
        assertThat(plan("ALTER TABLE plain DROP COLUMN ts")).isEqualTo(new Plan.DropColumn("plain", "ts"));
    }

    /// A missing column under `IF EXISTS` plans a drop of the name as written, for the runner to find absent; without
    /// it the column is refused.
    @Test
    void dropMissingColumn() {
        assertThat(plan("ALTER TABLE trades DROP COLUMN IF EXISTS Nope"))
                .isEqualTo(new Plan.DropColumn("trades", "Nope"));
        assertRefused("ALTER TABLE trades DROP COLUMN nope", "cannot find column [table=trades, column=nope]");
    }

    /// DROP COLUMN refuses a missing table (`IF EXISTS` names the column, not the table), the designated timestamp
    /// (also under `IF EXISTS`, also as the only column), a dedup key in any case, and the last column.
    @Test
    void dropColumnRefusals() {
        assertRefused("ALTER TABLE nope DROP COLUMN a", "cannot find table [table=nope]");
        assertRefused("ALTER TABLE nope DROP COLUMN IF EXISTS a", "cannot find table [table=nope]");
        assertRefused("ALTER TABLE trades DROP COLUMN TS",
                "cannot drop the designated timestamp [table=trades, column=ts]");
        assertRefused("ALTER TABLE trades DROP COLUMN IF EXISTS ts",
                "cannot drop the designated timestamp [table=trades, column=ts]");
        assertRefused("ALTER TABLE stamp DROP COLUMN ts",
                "cannot drop the designated timestamp [table=stamp, column=ts]");
        assertRefused("ALTER TABLE keyed DROP COLUMN sym", "cannot drop a dedup key [table=keyed, column=sym]");
        assertRefused("ALTER TABLE keyed DROP COLUMN Sym", "cannot drop a dedup key [table=keyed, column=sym]");
        assertRefused("ALTER TABLE single DROP COLUMN A", "cannot drop the last column [table=single, column=a]");
    }

    /// `RENAME COLUMN` names the table and the old column as the store has them and the new name as written; a dedup
    /// key may be renamed (the store renames the key with it).
    @Test
    void renameColumn() {
        assertThat(plan("ALTER TABLE Trades RENAME COLUMN QTY TO size"))
                .isEqualTo(new Plan.RenameColumn("trades", "qty", "size"));
        assertThat(plan("ALTER TABLE keyed RENAME COLUMN SYM TO symbol"))
                .isEqualTo(new Plan.RenameColumn("keyed", "sym", "symbol"));
        assertThat(plan("ALTER TABLE single RENAME COLUMN a TO b"))
                .isEqualTo(new Plan.RenameColumn("single", "a", "b"));
    }

    /// RENAME COLUMN refuses a missing table, a missing column, the designated timestamp, and a new name a column has
    /// in any case: the designated timestamp's, and the column's own in another case, whose files would collide on a
    /// file system that folds case.
    @Test
    void renameColumnRefusals() {
        assertRefused("ALTER TABLE nope RENAME COLUMN a TO b", "cannot find table [table=nope]");
        assertRefused("ALTER TABLE trades RENAME COLUMN nope TO b", "cannot find column [table=trades, column=nope]");
        assertRefused("ALTER TABLE trades RENAME COLUMN Ts TO at",
                "cannot rename the designated timestamp [table=trades, column=ts]");
        assertRefused("ALTER TABLE trades RENAME COLUMN qty TO PRICE",
                "cannot rename to an existing column [table=trades, column=qty, to=PRICE]");
        assertRefused("ALTER TABLE trades RENAME COLUMN qty TO Ts",
                "cannot rename to an existing column [table=trades, column=qty, to=Ts]");
        assertRefused("ALTER TABLE trades RENAME COLUMN qty TO QTY",
                "cannot rename to an existing column [table=trades, column=qty, to=QTY]");
        assertRefused("ALTER TABLE plain RENAME COLUMN qty TO \"TS\"",
                "cannot rename to an existing column [table=plain, column=qty, to=TS]");
    }

    /// A new name of `Store.MAX_NAME_LENGTH` characters is taken; one character more is refused.
    @Test
    void renameColumnNameLength() {
        final String longest = "c".repeat(Store.MAX_NAME_LENGTH);
        assertThat(plan("ALTER TABLE trades RENAME COLUMN qty TO " + longest))
                .isEqualTo(new Plan.RenameColumn("trades", "qty", longest));
        final String tooLong = longest + 'c';
        assertRefused("ALTER TABLE trades RENAME COLUMN qty TO " + tooLong,
                "cannot rename column: name too long [table=trades, column=qty, length="
                        + (Store.MAX_NAME_LENGTH + 1) + ", limit=" + Store.MAX_NAME_LENGTH + ']');
    }

    /// `BACKUP TO` and `BACKUP DATABASE TO` plan the folder as written, whatever tables exist; a folder without a name
    /// is refused.
    @Test
    void backup() {
        assertThat(plan("BACKUP TO '/tmp/cdb-nightly'")).isEqualTo(new Plan.Backup("/tmp/cdb-nightly"));
        assertThat(plan("backup database to 'Nightly'")).isEqualTo(new Plan.Backup("Nightly"));
        assertThat(Planner.plan(Parser.parse("BACKUP TO 'b'"), _ -> null, NOW)).isEqualTo(new Plan.Backup("b"));
        assertRefused("BACKUP TO ''", "cannot back up into a folder without a name [folder=]");
        assertRefused("BACKUP TO '   '", "cannot back up into a folder without a name [folder=   ]");
    }

    /// EXPLAIN plans each of the three under one VARCHAR field `QUERY PLAN` and holds the change unrun; a change it
    /// would refuse cannot be explained.
    @Test
    void explain() {
        final List<Field> fields = List.of(new Field("QUERY PLAN", CdbType.VARCHAR));
        assertThat(plan("EXPLAIN ALTER TABLE trades DROP COLUMN qty"))
                .isEqualTo(new Plan.Explain(new Plan.DropColumn("trades", "qty"), fields));
        assertThat(plan("EXPLAIN ALTER TABLE trades RENAME COLUMN qty TO size"))
                .isEqualTo(new Plan.Explain(new Plan.RenameColumn("trades", "qty", "size"), fields));
        assertThat(plan("EXPLAIN BACKUP TO 'b'")).isEqualTo(new Plan.Explain(new Plan.Backup("b"), fields));
        assertThat(PlanText.of(plan("EXPLAIN ALTER TABLE trades RENAME COLUMN qty TO size"))).isEqualTo("""
                Explain
                  RenameColumn trades qty to size
                """);
        assertRefused("EXPLAIN ALTER TABLE trades DROP COLUMN ts",
                "cannot drop the designated timestamp [table=trades, column=ts]");
    }

    private static Plan plan(final String sql) {
        return Planner.plan(Parser.parse(sql), name -> TABLES.get(name.toLowerCase(Locale.ROOT)), NOW);
    }

    private static void assertRefused(final String sql, final String message) {
        assertThatThrownBy(() -> plan(sql)).as(sql)
                .isInstanceOf(CdbException.class)
                .hasMessage(message);
    }
}
