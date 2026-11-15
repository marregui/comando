package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbResult;

import static dev.marregui.comando.cdb.exec.SqlRows.columns;
import static dev.marregui.comando.cdb.exec.SqlRows.rows;
import static org.assertj.core.api.Assertions.assertThat;

/// The statements a Cosa node sends to QuestDB, run on cdb as its driver sends them with every `?` written as a
/// literal: a number raw, a string quoted with `''` escaping, a timestamp as a quoted ISO string. The tables are made
/// as Cosa makes them, filled with a known set of readings, and each answer is the one QuestDB gives on the same data.
class CosaStatementsTest {

    private static final String LINE1 = "factory_line1_environment";
    private static final String LINE2 = "factory_line2_environment";

    @TempDir
    private Path root;

    private Cdb cdb;

    /// Opens a cdb with Cosa's three state tables and two data tables, the first data table altered as a schema change
    /// alters it, and fills both with readings from 10:07 to 10:08:30.
    @BeforeEach
    void openAndFill() {
        cdb = Cdb.open(root);
        cdb.run("CREATE TABLE IF NOT EXISTS poc_tables (ts TIMESTAMP, topic SYMBOL, table_name VARCHAR,"
                + " schema_json VARCHAR, time_property VARCHAR, retention VARCHAR)"
                + " TIMESTAMP(ts) PARTITION BY YEAR WAL");
        cdb.run("CREATE TABLE IF NOT EXISTS poc_computation_state (ts TIMESTAMP, name SYMBOL,"
                + " done_until TIMESTAMP) TIMESTAMP(ts) PARTITION BY YEAR WAL");
        cdb.run("CREATE TABLE IF NOT EXISTS poc_publication_state (ts TIMESTAMP, name SYMBOL,"
                + " last_seq LONG) TIMESTAMP(ts) PARTITION BY YEAR WAL");
        cdb.run("CREATE TABLE IF NOT EXISTS \"" + LINE1 + "\" (time TIMESTAMP, seq LONG, \"temperature\" DOUBLE)"
                + " TIMESTAMP(time) PARTITION BY HOUR TTL 24 HOURS WAL");
        cdb.run("ALTER TABLE \"" + LINE1 + "\" ADD COLUMN \"status\" VARCHAR");
        cdb.run("ALTER TABLE \"" + LINE1 + "\" SET TTL 48 HOURS");
        cdb.run("CREATE TABLE IF NOT EXISTS \"" + LINE2 + "\" (time TIMESTAMP, seq LONG, \"temperature\" DOUBLE)"
                + " TIMESTAMP(time) PARTITION BY HOUR TTL 24 HOURS WAL");
        cdb.run("INSERT INTO \"" + LINE1 + "\" (time, seq, \"temperature\", \"status\") VALUES"
                + " ('2026-11-15T10:07:00.000000Z', 1, 20.0, 'ok'), ('2026-11-15T10:07:10.000000Z', 2, 21.0, 'warning'),"
                + " ('2026-11-15T10:07:20.000000Z', 3, 22.0, 'ok'), ('2026-11-15T10:07:30.000000Z', 4, 23.0, 'warning'),"
                + " ('2026-11-15T10:07:40.000000Z', 5, 24.0, 'warning'), ('2026-11-15T10:07:50.000000Z', 6, 25.0, 'ok'),"
                + " ('2026-11-15T10:08:30.000000Z', 7, 30.0, 'ok'), ('2026-11-15T10:08:30.000000Z', 8, 31.0, 'ok')");
        cdb.run("INSERT INTO \"" + LINE2 + "\" (time, seq, \"temperature\") VALUES"
                + " ('2026-11-15T10:07:05.000000Z', 1, 30.0), ('2026-11-15T10:07:25.000000Z', 2, 31.0),"
                + " ('2026-11-15T10:07:45.000000Z', 3, 32.0)");
    }

    /// Closes the cdb.
    @AfterEach
    void close() {
        cdb.close();
    }

    /// `table_columns` lists the added column with the type names Cosa compares, and `SHOW CREATE TABLE` the TTL the
    /// ALTER set, 48 HOURS shown in its largest whole unit.
    @Test
    void schemaChangesShowInTheCatalog() {
        assertThat(rows(cdb.run("SELECT \"column\", type FROM table_columns('" + LINE1 + "')")))
                .containsExactly("time|TIMESTAMP", "seq|LONG", "temperature|DOUBLE", "status|VARCHAR");
        assertThat(rows(cdb.run("SHOW CREATE TABLE \"" + LINE1 + "\"")).getFirst()).contains("TTL 2 DAYS");
    }

    /// The table registry: an INSERT with `now()` and string literals, then the lookup and the registry listing by
    /// `LATEST ON`, which see only a topic's newest row, and the query for another topic on the same table.
    @Test
    void tableRegistry() {
        cdb.run("INSERT INTO poc_tables (ts, topic, table_name, schema_json, time_property, retention)"
                + " VALUES ('2026-01-01T00:00:00.000000Z', 'factory/line1/environment', '" + LINE1 + "',"
                + " '{\"type\":\"object\"}', 'time', '24 HOURS')");
        cdb.run("INSERT INTO poc_tables (ts, topic, table_name, schema_json, time_property, retention)"
                + " VALUES (now(), 'factory/line1/environment', '" + LINE1 + "', '{\"title\":\"it''s\"}', 'time',"
                + " '48 HOURS')");
        cdb.run("INSERT INTO poc_tables (ts, topic, table_name, schema_json, time_property, retention)"
                + " VALUES (now(), 'factory/line2/environment', '" + LINE2 + "', '{}', 'time', null)");
        assertThat(rows(cdb.run("SELECT table_name, schema_json, time_property FROM poc_tables"
                + " WHERE topic = 'factory/line1/environment' LATEST ON ts PARTITION BY topic")))
                .containsExactly(LINE1 + "|{\"title\":\"it's\"}|time");
        final CdbResult registry = cdb.run("SELECT topic, table_name, time_property, retention, ts AS updated_at"
                + " FROM (poc_tables LATEST ON ts PARTITION BY topic) ORDER BY topic");
        assertThat(columns(registry)).containsExactly("topic:SYMBOL", "table_name:VARCHAR", "time_property:VARCHAR",
                "retention:VARCHAR", "updated_at:TIMESTAMP");
        final List<String> topics = rows(registry);
        assertThat(topics).hasSize(2);
        assertThat(topics.get(0)).startsWith("factory/line1/environment|" + LINE1 + "|time|48 HOURS|2026-");
        assertThat(topics.get(1)).startsWith("factory/line2/environment|" + LINE2 + "|time||2026-");
        assertThat(rows(cdb.run("SELECT topic FROM (poc_tables LATEST ON ts PARTITION BY topic)"
                + " WHERE table_name = '" + LINE1 + "' AND topic <> 'factory/line9/environment'")))
                .containsExactly("factory/line1/environment");
        assertThat(rows(cdb.run("SELECT topic FROM (poc_tables LATEST ON ts PARTITION BY topic)"
                + " WHERE table_name = '" + LINE1 + "' AND topic <> 'factory/line1/environment'"))).isEmpty();
    }

    /// The computation and publication progress: INSERTs with `now()` and a TIMESTAMP or LONG literal, read back by
    /// `LATEST ON`, the newest row per name.
    @Test
    void progressState() {
        assertThat(rows(cdb.run("SELECT done_until FROM poc_computation_state WHERE name = 'line1-stats-1m'"
                + " LATEST ON ts PARTITION BY name"))).isEmpty();
        cdb.run("INSERT INTO poc_computation_state (ts, name, done_until)"
                + " VALUES (now(), 'line1-stats-1m', '2026-11-15T10:07:00.000000Z')");
        cdb.run("INSERT INTO poc_computation_state (ts, name, done_until)"
                + " VALUES (now(), 'line1-stats-1m', '2026-11-15T10:08:00.000000Z')");
        cdb.run("INSERT INTO poc_computation_state (ts, name, done_until)"
                + " VALUES (now(), 'line-spread-10s', CAST(null AS TIMESTAMP))");
        assertThat(rows(cdb.run("SELECT done_until FROM poc_computation_state WHERE name = 'line1-stats-1m'"
                + " LATEST ON ts PARTITION BY name"))).containsExactly("2026-11-15T10:08:00Z");
        cdb.run("INSERT INTO poc_publication_state (ts, name, last_seq) VALUES (now(), 'line1-out', 123)");
        cdb.run("INSERT INTO poc_publication_state (ts, name, last_seq) VALUES (now(), 'line1-out', 456)");
        assertThat(rows(cdb.run("SELECT last_seq FROM poc_publication_state WHERE name = 'line1-out'"
                + " LATEST ON ts PARTITION BY name"))).containsExactly("456");
    }

    /// The data-table reads: the next sequence, rows after a sequence, the newest row at a time, and Grafana's rows
    /// and per-minute averages over a time range.
    @Test
    void dataTableReads() {
        assertThat(rows(cdb.run("SELECT max(seq) FROM \"" + LINE1 + "\""))).containsExactly("8");
        assertThat(rows(cdb.run("SELECT coalesce(max(seq), 0) FROM \"" + LINE2 + "\""))).containsExactly("3");
        assertThat(rows(cdb.run("SELECT time, seq, \"temperature\" FROM \"" + LINE1 + "\" WHERE seq > 5"
                + " ORDER BY seq LIMIT 500"))).containsExactly("2026-11-15T10:07:50Z|6|25.0",
                "2026-11-15T10:08:30Z|7|30.0", "2026-11-15T10:08:30Z|8|31.0");
        assertThat(rows(cdb.run("SELECT time, \"temperature\", \"status\" FROM \"" + LINE1 + "\""
                + " WHERE time = '2026-11-15T10:08:30.000000Z' ORDER BY seq DESC LIMIT 1")))
                .containsExactly("2026-11-15T10:08:30Z|31.0|ok");
        assertThat(rows(cdb.run("SELECT * FROM \"" + LINE1 + "\" WHERE time >= '2026-11-15T10:07:30.000000Z'"
                + " AND time < '2026-11-15T10:09:00.000000Z' ORDER BY time DESC, seq DESC LIMIT 2")))
                .containsExactly("2026-11-15T10:08:30Z|8|31.0|ok", "2026-11-15T10:08:30Z|7|30.0|ok");
        assertThat(rows(cdb.run("SELECT time, avg(\"temperature\") AS v FROM \"" + LINE1 + "\""
                + " WHERE time >= '2026-11-15T10:00:00.000000Z' AND time < '2026-11-15T11:00:00.000000Z'"
                + " SAMPLE BY 60s ALIGN TO CALENDAR")))
                .containsExactly("2026-11-15T10:07:00Z|22.5", "2026-11-15T10:08:00Z|30.5");
    }

    /// The windowed averages: a window with readings gives one row, its bounds cast from the literals; an empty window
    /// gives none, the outer `WHERE count > 0` dropping the aggregate's single row.
    @Test
    void windowAverage() {
        final String query = "SELECT window_start, window_end, average, count FROM ("
                + " SELECT CAST('%1$s' AS TIMESTAMP) AS window_start, CAST('%2$s' AS TIMESTAMP) AS window_end,"
                + " avg(temperature) AS average, count(temperature) AS count"
                + " FROM " + LINE1 + " WHERE time >= '%1$s' AND time < '%2$s') WHERE count > 0";
        final CdbResult window = cdb.run(query.formatted("2026-11-15T10:07:00.000000Z", "2026-11-15T10:07:30.000000Z"));
        assertThat(columns(window))
                .containsExactly("window_start:TIMESTAMP", "window_end:TIMESTAMP", "average:DOUBLE", "count:LONG");
        assertThat(rows(window)).containsExactly("2026-11-15T10:07:00Z|2026-11-15T10:07:30Z|21.0|3");
        assertThat(rows(cdb.run(query.formatted("2026-11-15T09:00:00.000000Z", "2026-11-15T09:00:10.000000Z"))))
                .isEmpty();
    }

    /// The per-minute statistics: min, max, avg, `percentile_cont(temperature, 0.95)`, a `sum(CASE ...)` count of
    /// warnings and `count(*)`, over the minute 10:07; QuestDB gives 24.75 for the 95th percentile of 20 to 25.
    @Test
    void windowStatistics() {
        assertThat(rows(cdb.run("SELECT window_start, temperature__minimum, temperature__maximum,"
                + " temperature__average, temperature__p95, warnings, count FROM ("
                + " SELECT CAST('2026-11-15T10:07:00.000000Z' AS TIMESTAMP) AS window_start,"
                + " min(temperature) AS temperature__minimum, max(temperature) AS temperature__maximum,"
                + " avg(temperature) AS temperature__average, percentile_cont(temperature, 0.95) AS temperature__p95,"
                + " sum(CASE WHEN status = 'warning' THEN 1 ELSE 0 END) AS warnings, count(*) AS count"
                + " FROM " + LINE1 + " WHERE time >= '2026-11-15T10:07:00.000000Z'"
                + " AND time < '2026-11-15T10:08:00.000000Z') WHERE count > 0")))
                .containsExactly("2026-11-15T10:07:00Z|20.0|25.0|22.5|24.75|3|6");
    }

    /// The spread between the lines: a CROSS JOIN of two single-row aggregates; a window where one line has no reading
    /// gives no row, its average NULL.
    @Test
    void lineSpread() {
        final String query = "SELECT CAST('%1$s' AS TIMESTAMP) AS window_start, l1.average AS line1,"
                + " l2.average AS line2, l2.average - l1.average AS spread"
                + " FROM (SELECT avg(temperature) AS average FROM " + LINE1
                + " WHERE time >= '%1$s' AND time < '%2$s') l1"
                + " CROSS JOIN (SELECT avg(temperature) AS average FROM " + LINE2
                + " WHERE time >= '%1$s' AND time < '%2$s') l2"
                + " WHERE l1.average IS NOT NULL AND l2.average IS NOT NULL";
        assertThat(rows(cdb.run(query.formatted("2026-11-15T10:07:00.000000Z", "2026-11-15T10:08:00.000000Z"))))
                .containsExactly("2026-11-15T10:07:00Z|22.5|31.0|8.5");
        assertThat(rows(cdb.run(query.formatted("2026-11-15T10:08:00.000000Z", "2026-11-15T10:09:00.000000Z"))))
                .isEmpty();
    }
}
