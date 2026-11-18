package dev.marregui.comando.cdb.serve;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/// What the read-only secret may run: a statement reads when it parses to a query, a `SHOW` or an `EXPLAIN`; every
/// other statement changes the store; text that does not parse counts as reading, as the engine then refuses it.
class AccessTest {

    /// Each kind of statement the parser knows, and text it does not.
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            select 1                                        | true
            SELECT x FROM t WHERE x > 1                     | true
            show tables                                     | true
            SHOW COLUMNS FROM t                             | true
            show partitions from t                          | true
            show wal                                        | true
            explain select 1                                | true
            EXPLAIN update t set x = 1                      | true
            explain drop table t                            | true
            create table t (x long)                         | false
            insert into t values (1)                        | false
            update t set x = 1                              | false
            alter table t add column y long                 | false
            drop table t                                    | false
            DROP TABLE IF EXISTS t                          | false
            truncate table t                                | false
            alter table t drop partition list '2026-10'     | false
            delete from t                                   | false
            delete from t where x = 1                       | false
            not a statement at all                          | true
            select from                                     | true
            """)
    void readsOnlyWhenTheStatementReads(final String sql, final boolean reads) {
        assertThat(Access.readOnly(sql)).isEqualTo(reads);
    }
}
