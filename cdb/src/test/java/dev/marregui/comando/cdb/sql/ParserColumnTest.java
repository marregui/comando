package dev.marregui.comando.cdb.sql;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;

import static org.assertj.core.api.Assertions.assertThat;

/// The trees of `ALTER TABLE ... DROP COLUMN`, `ALTER TABLE ... RENAME COLUMN` and `BACKUP [DATABASE] TO`, keywords
/// in any case, and where a malformed one fails.
class ParserColumnTest {

    /// `DROP COLUMN` with and without `IF EXISTS`, in any case, a double-quoted name kept as written.
    @Test
    void dropColumn() {
        assertThat(Parser.parse("ALTER TABLE trades DROP COLUMN qty"))
                .isEqualTo(new Ast.DropColumn("trades", "qty", false, 0));
        assertThat(Parser.parse("alter table trades drop column if exists qty;"))
                .isEqualTo(new Ast.DropColumn("trades", "qty", true, 0));
        assertThat(Parser.parse("Alter Table \"Mixed\" Drop Column If Exists \"Odd Name\""))
                .isEqualTo(new Ast.DropColumn("Mixed", "Odd Name", true, 0));
    }

    /// `RENAME COLUMN a TO b`, in any case, double-quoted names kept as written.
    @Test
    void renameColumn() {
        assertThat(Parser.parse("ALTER TABLE trades RENAME COLUMN qty TO size"))
                .isEqualTo(new Ast.RenameColumn("trades", "qty", "size", 0));
        assertThat(Parser.parse("alter table trades rename column \"Qty\" to \"Size Now\";"))
                .isEqualTo(new Ast.RenameColumn("trades", "Qty", "Size Now", 0));
    }

    /// `BACKUP TO` and QuestDB's `BACKUP DATABASE TO` take the folder as a string, as written.
    @Test
    void backup() {
        assertThat(Parser.parse("BACKUP TO '/tmp/cdb-backup'")).isEqualTo(new Ast.Backup("/tmp/cdb-backup", 0));
        assertThat(Parser.parse("backup database to 'nightly';")).isEqualTo(new Ast.Backup("nightly", 0));
        assertThat(Parser.parse("Backup Database To ''")).isEqualTo(new Ast.Backup("", 0));
    }

    /// `backup` not followed by `TO` or `DATABASE` is still a table's name, read as `SELECT * FROM backup`.
    @Test
    void backupAloneIsATableName() {
        assertThat(Parser.parse("backup")).isInstanceOf(Ast.Query.class);
        assertThat(Parser.parse("backup LIMIT 5")).isInstanceOf(Ast.Query.class);
        assertThat(Parser.parse("SELECT * FROM backup")).isInstanceOf(Ast.Query.class);
    }

    /// The existing `ALTER TABLE` forms parse as before beside the new ones.
    @Test
    void otherAlterFormsUnchanged() {
        assertThat(Parser.parse("ALTER TABLE t DROP PARTITION LIST '2026-10'")).isInstanceOf(Ast.DropPartition.class);
        assertThat(Parser.parse("ALTER TABLE t ADD COLUMN c LONG")).isInstanceOf(Ast.AlterTable.class);
        assertThat(Parser.parse("ALTER TABLE t SET TTL 2 DAYS")).isInstanceOf(Ast.AlterTable.class);
    }

    /// `DROP` takes `PARTITION` or `COLUMN`; `COLUMN` is required, `IF` needs `EXISTS`, one column only, and a
    /// reserved word is a name only when double-quoted.
    @Test
    void malformedDropColumn() {
        final String bare = "ALTER TABLE t DROP qty";
        assertThat(errorPosition(bare)).isEqualTo(at(bare, "qty"));
        final String noExists = "ALTER TABLE t DROP COLUMN IF qty";
        assertThat(errorPosition(noExists)).isEqualTo(at(noExists, "qty"));
        final String two = "ALTER TABLE t DROP COLUMN a, b";
        assertThat(errorPosition(two)).isEqualTo(at(two, ","));
        final String reserved = "ALTER TABLE t DROP COLUMN from";
        assertThat(errorPosition(reserved)).isEqualTo(at(reserved, "from"));
        final String nothing = "ALTER TABLE t DROP COLUMN";
        assertThat(errorPosition(nothing)).isEqualTo(nothing.length());
        assertThat(Parser.parse("ALTER TABLE t DROP COLUMN \"from\""))
                .isEqualTo(new Ast.DropColumn("t", "from", false, 0));
    }

    /// `RENAME` needs `COLUMN`, both names and `TO` between them.
    @Test
    void malformedRenameColumn() {
        final String noColumn = "ALTER TABLE t RENAME qty TO size";
        assertThat(errorPosition(noColumn)).isEqualTo(at(noColumn, "qty"));
        final String noTo = "ALTER TABLE t RENAME COLUMN qty size";
        assertThat(errorPosition(noTo)).isEqualTo(at(noTo, "size"));
        final String noTarget = "ALTER TABLE t RENAME COLUMN qty TO";
        assertThat(errorPosition(noTarget)).isEqualTo(noTarget.length());
        final String reserved = "ALTER TABLE t RENAME COLUMN qty TO select";
        assertThat(errorPosition(reserved)).isEqualTo(at(reserved, "select"));
    }

    /// `BACKUP` needs `TO` and a string after it; an identifier is not a folder.
    @Test
    void malformedBackup() {
        final String unquoted = "BACKUP TO nightly";
        assertThat(errorPosition(unquoted)).isEqualTo(at(unquoted, "nightly"));
        final String quoted = "BACKUP TO \"nightly\"";
        assertThat(errorPosition(quoted)).isEqualTo(at(quoted, "\"nightly\""));
        final String noTo = "BACKUP DATABASE 'nightly'";
        assertThat(errorPosition(noTo)).isEqualTo(at(noTo, "'nightly'"));
        final String nothing = "BACKUP TO";
        assertThat(errorPosition(nothing)).isEqualTo(nothing.length());
        final String trailing = "BACKUP TO 'a' 'b'";
        assertThat(errorPosition(trailing)).isEqualTo(at(trailing, "'b'"));
    }

    /// `EXPLAIN` takes each of the three; the inner statement's position is its own keyword's.
    @Test
    void explain() {
        assertThat(Parser.parse("EXPLAIN ALTER TABLE t DROP COLUMN c"))
                .isEqualTo(new Ast.Explain(new Ast.DropColumn("t", "c", false, 8), 0));
        assertThat(Parser.parse("explain alter table t rename column a to b"))
                .isEqualTo(new Ast.Explain(new Ast.RenameColumn("t", "a", "b", 8), 0));
        assertThat(Parser.parse("EXPLAIN BACKUP DATABASE TO 'f'"))
                .isEqualTo(new Ast.Explain(new Ast.Backup("f", 8), 0));
    }

    private static int at(final String sql, final String text) {
        final int i = sql.indexOf(text);
        assertThat(i).as("offset of %s in %s", text, sql).isGreaterThanOrEqualTo(0);
        return i;
    }

    private static int errorPosition(final String sql) {
        try {
            Parser.parse(sql);
        } catch (final CdbException e) {
            return e.position();
        }
        throw new AssertionError("parsed [sql=" + sql + "]");
    }
}
