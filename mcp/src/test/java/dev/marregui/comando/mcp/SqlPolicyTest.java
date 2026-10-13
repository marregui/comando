package dev.marregui.comando.mcp;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SqlPolicyTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            SELECT * FROM t                                   | true
            select 1;                                         | true
            -- comment\\nSELECT 1                             | true
            WITH a AS (SELECT 1) SELECT * FROM a              | true
            SHOW TABLES                                       | true
            EXPLAIN SELECT * FROM t                           | true
            SELECT ';' FROM t                                 | true
            INSERT INTO t VALUES (1)                          | false
            DROP TABLE t                                      | false
            SELECT 1; DROP TABLE t                            | false
            WITH a AS (SELECT 1) INSERT INTO t SELECT * FROM a | false
            /* SELECT */ DELETE FROM t                        | false
            UPDATE t SET x = 1                                | false
            ;                                                 | false
            WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d | false
            WITH u AS (UPDATE t SET x = 1 RETURNING x) SELECT 1 | false
            EXPLAIN ANALYZE DELETE FROM t                     | false
            EXPLAIN ANALYZE SELECT * FROM t                   | true
            SELECT * INTO t2 FROM t                           | false
            SELECT * FROM t FOR UPDATE                        | false
            SELECT * FROM t FOR SHARE                         | false
            SELECT "delete", 'update' FROM t                  | true
            SELECT deleted_at, updated FROM t                 | true
            SELECT $$;$$                                      | false
            SELECT E'\\'' FROM t                             | false
            SELECT 'a\\' FROM t                              | false
            SELECT 'it''s' FROM t                             | true
            SELECT/**/1                                       | true
            (SELECT 1)                                        | false
            SELECT 'x                                         | false
            """)
    void readOnly(String sql, boolean expected) {
        assertThat(SqlPolicy.isReadOnly(sql.replace("\\n", "\n"))).isEqualTo(expected);
    }
}
