package dev.marregui.comando.cdb.sql;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;

import static org.assertj.core.api.Assertions.assertThat;

/// The trees of the statements that remove data or explain a plan: `DROP TABLE`, `TRUNCATE TABLE`, `DELETE`,
/// `ALTER TABLE ... DROP PARTITION`, `EXPLAIN`, and the `SHOW PARTITIONS` and `SHOW WAL` listings; keywords in any
/// case, and where a malformed one fails.
class ParserDestructiveTest {

    /// `DROP TABLE` with and without `IF EXISTS`, in any case.
    @Test
    void dropTable() {
        assertThat(Parser.parse("DROP TABLE trades")).isEqualTo(new Ast.DropTable("trades", false, 0));
        assertThat(Parser.parse("drop table if exists trades")).isEqualTo(new Ast.DropTable("trades", true, 0));
        assertThat(Parser.parse("Drop Table If Exists \"Mixed\"")).isEqualTo(new Ast.DropTable("Mixed", true, 0));
    }

    /// `TRUNCATE` requires `TABLE`, as QuestDB does.
    @Test
    void truncate() {
        assertThat(Parser.parse("TRUNCATE TABLE trades")).isEqualTo(new Ast.Truncate("trades", 0));
        assertThat(Parser.parse("truncate table trades;")).isEqualTo(new Ast.Truncate("trades", 0));
        assertThat(errorPosition("TRUNCATE trades")).isEqualTo(9);
    }

    /// `DELETE FROM t` deletes every row (no condition); with `WHERE` it carries the condition as written.
    @Test
    void delete() {
        assertThat(Parser.parse("DELETE FROM trades")).isEqualTo(new Ast.Delete("trades", null, 0));
        final String sql = "delete from trades where qty > 5";
        assertThat(Parser.parse(sql)).isEqualTo(new Ast.Delete("trades", new Ast.Binary(">",
                new Ast.Ident(List.of("qty"), at(sql, "qty")), new Ast.Literal(5L, at(sql, "5")), at(sql, "qty")), 0));
        assertThat(errorPosition("DELETE trades")).isEqualTo(7);
    }

    /// `DROP PARTITION LIST` keeps the names in order; `DROP PARTITION WHERE` keeps the condition.
    @Test
    void dropPartition() {
        assertThat(Parser.parse("ALTER TABLE trades DROP PARTITION LIST '2026-10'"))
                .isEqualTo(new Ast.DropPartition("trades", List.of("2026-10"), null, 0));
        assertThat(Parser.parse("alter table trades drop partition list '2026-10', '2026-09'"))
                .isEqualTo(new Ast.DropPartition("trades", List.of("2026-10", "2026-09"), null, 0));
        final String sql = "ALTER TABLE trades DROP PARTITION WHERE ts < '2026-11'";
        assertThat(Parser.parse(sql)).isEqualTo(new Ast.DropPartition("trades", List.of(), new Ast.Binary("<",
                new Ast.Ident(List.of("ts"), at(sql, "ts")), new Ast.Literal("2026-11", at(sql, "'2026-11'")),
                at(sql, "ts")), 0));
    }

    /// `DROP PARTITION` takes `LIST` or `WHERE`; a name must be a string and a list does not end in a comma.
    @Test
    void malformedDropPartition() {
        final String bare = "ALTER TABLE t DROP PARTITION '2026-10'";
        assertThat(errorPosition(bare)).isEqualTo(at(bare, "'2026-10'"));
        final String unquoted = "ALTER TABLE t DROP PARTITION LIST 2026";
        assertThat(errorPosition(unquoted)).isEqualTo(at(unquoted, "2026"));
        final String trailing = "ALTER TABLE t DROP PARTITION LIST '2026-10',";
        assertThat(errorPosition(trailing)).isEqualTo(trailing.length());
        final String nothing = "ALTER TABLE t DROP PARTITION WHERE";
        assertThat(errorPosition(nothing)).isEqualTo(nothing.length());
    }

    /// `EXPLAIN` takes any statement after it, another `EXPLAIN` included; its position is its own keyword's.
    @Test
    void explain() {
        assertThat(Parser.parse("EXPLAIN DROP TABLE t"))
                .isEqualTo(new Ast.Explain(new Ast.DropTable("t", false, 8), 0));
        assertThat(Parser.parse("explain delete from t"))
                .isEqualTo(new Ast.Explain(new Ast.Delete("t", null, 8), 0));
        assertThat(Parser.parse("EXPLAIN EXPLAIN TRUNCATE TABLE t"))
                .isEqualTo(new Ast.Explain(new Ast.Explain(new Ast.Truncate("t", 16), 8), 0));
        assertThat(Parser.parse("EXPLAIN SELECT 1")).isInstanceOfSatisfying(Ast.Explain.class,
                e -> assertThat(e.statement()).isInstanceOf(Ast.Query.class));
        assertThat(errorPosition("EXPLAIN")).isEqualTo(7);
    }

    /// `SHOW PARTITIONS` needs `FROM t`; `SHOW WAL` takes it or not.
    @Test
    void showPartitionsAndWal() {
        assertThat(Parser.parse("SHOW PARTITIONS FROM trades")).isEqualTo(new Ast.Show("partitions", "trades", 0));
        assertThat(Parser.parse("show wal")).isEqualTo(new Ast.Show("wal", null, 0));
        assertThat(Parser.parse("SHOW WAL FROM trades")).isEqualTo(new Ast.Show("wal", "trades", 0));
        assertThat(errorPosition("SHOW PARTITIONS trades")).isEqualTo(16);
        assertThat(errorPosition("SHOW WAL trades")).isEqualTo(9);
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
