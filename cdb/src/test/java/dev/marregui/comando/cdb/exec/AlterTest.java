package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbException;

import static dev.marregui.comando.cdb.exec.SqlRows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `ALTER TABLE t ADD COLUMN ...` and `ALTER TABLE t SET TTL ...` end to end: the definition changes in one commit,
/// rows written before an added column read it as NULL, and a TTL set later expires partitions as a CREATE's does.
class AlterTest {

    @TempDir
    private Path root;

    /// The added columns appear in `table_columns` in order; rows written before read NULL in them, an INSERT after
    /// fills them, and both survive a reopen.
    @Test
    void addedColumnsReadNullForOldRowsAndTakeNewOnes() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE t (ts TIMESTAMP, n LONG) TIMESTAMP(ts) PARTITION BY DAY");
            cdb.run("INSERT INTO t VALUES ('2026-11-15T10:07:00.000000Z', 1)");
            assertThat(cdb.run("ALTER TABLE t ADD COLUMN \"v\" VARCHAR, d DOUBLE").updateCount()).isZero();
            cdb.run("INSERT INTO t VALUES ('2026-11-15T10:08:00.000000Z', 2, 'x', 2.5)");
        }
        try (final Cdb cdb = Cdb.open(root)) {
            assertThat(rows(cdb.run("SELECT \"column\", type FROM table_columns('t')")))
                    .containsExactly("ts|TIMESTAMP", "n|LONG", "v|VARCHAR", "d|DOUBLE");
            assertThat(rows(cdb.run("SELECT * FROM t"))).containsExactly(
                    "2026-11-15T10:07:00Z|1||",
                    "2026-11-15T10:08:00Z|2|x|2.5"
            );
        }
    }

    /// `ADD COLUMN IF NOT EXISTS` of a column the table has with the same type changes nothing; of another type, or
    /// without `IF NOT EXISTS`, or the same name twice in one statement, it is refused.
    @Test
    void existingColumnsAreRefusedUnlessIfNotExistsWithTheSameType() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE t (ts TIMESTAMP, n LONG) TIMESTAMP(ts) PARTITION BY DAY");
            cdb.run("ALTER TABLE t ADD COLUMN IF NOT EXISTS n LONG");
            cdb.run("ALTER TABLE t ADD COLUMN IF NOT EXISTS n LONG, IF NOT EXISTS s SYMBOL");
            assertThatThrownBy(() -> cdb.run("ALTER TABLE t ADD COLUMN n LONG"))
                    .isInstanceOf(CdbException.class)
                    .hasMessage("cannot add existing column [table=t, column=n]");
            assertThatThrownBy(() -> cdb.run("ALTER TABLE t ADD COLUMN IF NOT EXISTS n DOUBLE"))
                    .isInstanceOf(CdbException.class)
                    .hasMessage("cannot add existing column of other type [table=t, column=n, type=LONG,"
                            + " requested=DOUBLE]");
            assertThatThrownBy(() -> cdb.run("ALTER TABLE t ADD COLUMN a LONG, a LONG"))
                    .isInstanceOf(CdbException.class)
                    .hasMessage("cannot repeat column name [name=a]");
            assertThat(rows(cdb.run("SELECT \"column\" FROM table_columns('t')"))).containsExactly("ts", "n", "s");
        }
    }

    /// Altering a table that does not exist is refused, naming it.
    @Test
    void unknownTableIsRefused() {
        try (final Cdb cdb = Cdb.open(root)) {
            assertThatThrownBy(() -> cdb.run("ALTER TABLE nope ADD COLUMN n LONG"))
                    .isInstanceOf(CdbException.class)
                    .hasMessage("cannot find table [table=nope]");
            assertThatThrownBy(() -> cdb.run("ALTER TABLE nope SET TTL 2 HOURS"))
                    .isInstanceOf(CdbException.class)
                    .hasMessage("cannot find table [table=nope]");
        }
    }

    /// `SET TTL` shows in `SHOW CREATE TABLE`, survives a reopen, and at its commit drops the partitions the new TTL
    /// expires, as the TTL of a CREATE does.
    @Test
    void setTtlIsStoredAndEnforced() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("CREATE TABLE t (ts TIMESTAMP, n LONG) TIMESTAMP(ts) PARTITION BY HOUR");
            cdb.run("INSERT INTO t VALUES ('2026-11-15T00:30:00.000000Z', 1), ('2026-11-15T10:30:00.000000Z', 2)");
            assertThat(cdb.run("ALTER TABLE t SET TTL 2 HOURS").updateCount()).isZero();
            assertThat(rows(cdb.run("SELECT n FROM t"))).containsExactly("2");
        }
        try (final Cdb cdb = Cdb.open(root)) {
            assertThat(rows(cdb.run("SHOW CREATE TABLE t"))).containsExactly(
                    "CREATE TABLE 't' ( \n\tts TIMESTAMP,\n\tn LONG\n) timestamp(ts) PARTITION BY HOUR TTL 2 HOURS;");
            cdb.run("INSERT INTO t VALUES ('2026-11-15T14:30:00.000000Z', 3)");
            assertThat(rows(cdb.run("SELECT n FROM t"))).containsExactly("3");
        }
    }
}
