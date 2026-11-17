package dev.marregui.comando.cdb.serve;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.PGConnection;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.TransactionState;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbAppender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// pgjdbc 42.7.13, the driver Cosa runs, against a cdb under a temporary folder served by [PgServer]: the startup in
/// both query modes, every cdb type read back, prepared statements bound in each form pgjdbc sends, errors, portals
/// paged by the fetch size, transaction status, two connections at once and Cosa's own query shapes.
class PgServerTest {

    private static final Instant T0 = Instant.parse("2026-11-14T10:00:00Z");
    private static final String CREATE = "CREATE TABLE probe (ts TIMESTAMP, host SYMBOL, n LONG, x DOUBLE,"
            + " ok BOOLEAN, note VARCHAR, at TIMESTAMP) TIMESTAMP(ts) PARTITION BY DAY";
    // 2e11 rows: minutes of work at the ~1.4 G rows/s measured on an M-series Mac, so only a cancel ends it in time
    private static final String LONG_QUERY = "select max(x) from long_sequence(200000000000)";

    @TempDir
    private Path root;

    private Cdb cdb;
    private PgServer server;

    @BeforeEach
    void start() {
        cdb = Cdb.open(root);
        server = PgServer.start(cdb, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    }

    @AfterEach
    void stop() {
        server.close();
        cdb.close();
    }

    /// The default `sslmode` asks for SSL first; the server declines and the startup goes on in plain text, with
    /// the parameters pgjdbc reads (the client's `application_name` echoed, `is_superuser` off).
    @Test
    void connectsWithTheDefaultSslMode() throws SQLException {
        final Properties properties = credentials();
        properties.setProperty("ApplicationName", "probe-app");
        try (Connection connection = DriverManager.getConnection(url(""), properties);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select 1 x from long_sequence(1)")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong(1)).isEqualTo(1);
            final PGConnection pg = connection.unwrap(PGConnection.class);
            assertThat(pg.getParameterStatus("application_name")).isEqualTo("probe-app");
            assertThat(pg.getParameterStatus("is_superuser")).isEqualTo("off");
            assertThat(pg.getParameterStatus("integer_datetimes")).isEqualTo("on");
            assertThat(pg.getBackendPID()).isPositive();
        }
    }

    /// `preferQueryMode=simple` sends every statement as one Query message: DDL, a query, two statements in one.
    @Test
    void servesTheSimpleQueryMode() throws SQLException {
        try (Connection connection = connect("?preferQueryMode=simple")) {
            assertThat(create(connection)).isFalse();
            writeRows();
            try (Statement statement = connection.createStatement()) {
                try (ResultSet rows = statement.executeQuery("select host, n from probe where n > 0 order by n")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("a");
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("c");
                    assertThat(rows.next()).isFalse();
                }
                assertThat(statement.execute("select 1 x from long_sequence(1); select 2 y from long_sequence(1)"))
                        .isTrue();
                assertThat(statement.getMoreResults()).isTrue();
                try (ResultSet second = statement.getResultSet()) {
                    assertThat(second.next()).isTrue();
                    assertThat(second.getLong("y")).isEqualTo(2);
                }
            }
        }
    }

    /// A table created over `Statement.execute`, rows written through the appender, every cdb type read back with
    /// its PostgreSQL type, in text and, once pgjdbc prepares on the server, in binary.
    @Test
    void readsEveryType() throws SQLException {
        try (Connection connection = connect("")) {
            assertThat(create(connection)).isFalse();
            writeRows();
            for (int run = 0; run < 7; run++) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "select ts, host, n, x, ok, note, at from probe order by ts");
                     ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject(1, LocalDateTime.class)).isEqualTo(LocalDateTime.of(2026, 11, 14, 10, 0));
                    assertThat(rows.getString(2)).isEqualTo("a");
                    assertThat(rows.getLong(3)).isEqualTo(1);
                    assertThat(rows.getDouble(4)).isEqualTo(1.5);
                    assertThat(rows.getBoolean(5)).isTrue();
                    assertThat(rows.getString(6)).isEqualTo("hi 'quoted'");
                    assertThat(rows.getObject(7, LocalDateTime.class))
                            .isEqualTo(LocalDateTime.of(2026, 11, 14, 10, 0, 0, 123_456_000));
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(3)).isEqualTo(-2);
                    assertThat(rows.getDouble(4)).isEqualTo(Double.POSITIVE_INFINITY);
                    assertThat(rows.getBoolean(5)).isFalse();
                    assertThat(rows.getString(6)).isNull();
                    assertThat(rows.wasNull()).isTrue();
                    assertThat(rows.getObject(7)).isNull();
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(3)).isEqualTo(3);
                    assertThat(rows.next()).isFalse();
                }
            }
        }
    }

    /// A TIMESTAMP is `timestamp` (1114), without a zone, read as a `LocalDateTime` in UTC, as Cosa's `SqlTime`
    /// reads it; the text form carries six fraction digits.
    @Test
    void servesTimestampsWithoutAZone() throws SQLException {
        try (Connection connection = connect("")) {
            create(connection);
            writeRows();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("select at from probe where host = 'a'")) {
                assertThat(rows.getMetaData().getColumnTypeName(1)).isEqualTo("timestamp");
                assertThat(rows.getMetaData().getColumnType(1)).isEqualTo(Types.TIMESTAMP);
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("2026-11-14 10:00:00.123456");
                assertThat(rows.getObject(1, LocalDateTime.class))
                        .isEqualTo(LocalDateTime.of(2026, 11, 14, 10, 0, 0, 123_456_000));
            }
        }
    }

    /// Column names and types as `ResultSetMetaData` reports them, aliases included.
    @Test
    void describesColumns() throws SQLException {
        try (Connection connection = connect("")) {
            create(connection);
            writeRows();
            try (PreparedStatement statement = connection.prepareStatement(
                    "select ts, host h, n, x, ok, note from probe where host = ?")) {
                statement.setString(1, "a");
                try (ResultSet rows = statement.executeQuery()) {
                    final ResultSetMetaData meta = rows.getMetaData();
                    assertThat(meta.getColumnCount()).isEqualTo(6);
                    final List<String> names = new ArrayList<>();
                    final List<String> types = new ArrayList<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        names.add(meta.getColumnLabel(i));
                        types.add(meta.getColumnTypeName(i));
                    }
                    assertThat(names).containsExactly("ts", "h", "n", "x", "ok", "note");
                    assertThat(types).containsExactly("timestamp", "varchar", "int8", "float8", "bool", "varchar");
                }
            }
        }
    }

    /// Every parameter form pgjdbc sends (varchar, binary int8 and float8, a zoneless timestamp in text with an
    /// offset, timestamptz, null), ten runs so the statement moves to a named server-side one, the same answer each.
    @Test
    void bindsEveryParameterFormTheSameEachRun() throws SQLException {
        try (Connection connection = connect("")) {
            create(connection);
            writeRows();
            try (PreparedStatement statement = connection.prepareStatement("select host, n from probe"
                    + " where host <> ? and n >= ? and x < ? and ts >= ? and ts <= ? order by n")) {
                for (int run = 0; run < 10; run++) {
                    statement.setString(1, "zz");
                    statement.setLong(2, -10);
                    statement.setDouble(3, 1e300);
                    statement.setTimestamp(4, Timestamp.from(T0));
                    statement.setObject(5, OffsetDateTime.ofInstant(T0.plusSeconds(10), ZoneOffset.UTC));
                    try (ResultSet rows = statement.executeQuery()) {
                        final List<String> hosts = new ArrayList<>();
                        while (rows.next()) {
                            hosts.add(rows.getString(1) + '=' + rows.getLong(2));
                        }
                        assertThat(hosts).as("run %d", run).containsExactly("a=1", "c=3");
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO probe (ts, host, n, note) VALUES (?, ?, ?, ?)")) {
                for (int run = 0; run < 10; run++) {
                    insert.setTimestamp(1, Timestamp.from(T0.plusSeconds(100 + run)));
                    insert.setString(2, "null-note");
                    insert.setLong(3, run);
                    insert.setNull(4, Types.VARCHAR);
                    assertThat(insert.executeUpdate()).as("run %d", run).isEqualTo(1);
                }
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "select count(), count(note) from probe where host = 'null-note'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(10);
                assertThat(rows.getLong(2)).isZero();
            }
        }
    }

    /// The parameters of a statement described before any Bind, as `getParameterMetaData` asks for them: an INSERT
    /// described that way writes nothing.
    @Test
    void describesAStatementWithoutRunningIt() throws SQLException {
        try (Connection connection = connect("")) {
            create(connection);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO probe (ts, host, n) VALUES (?, ?, ?)")) {
                final ParameterMetaData parameters = insert.getParameterMetaData();
                assertThat(parameters.getParameterCount()).isEqualTo(3);
            }
            assertThat(count(connection)).isZero();
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO probe (ts, host, n) VALUES (?, ?, ?)")) {
                insert.setObject(1, OffsetDateTime.ofInstant(T0, ZoneOffset.UTC));
                insert.setString(2, "d");
                insert.setLong(3, 4);
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
            assertThat(count(connection)).isEqualTo(1);
        }
    }

    /// A failed statement is an `SQLException` with cdb's message; the same connection serves the next one.
    @Test
    void reportsAnErrorAndKeepsTheConnection() throws SQLException {
        try (Connection connection = connect("")) {
            create(connection);
            try (Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeQuery("select nosuch from probe"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("nosuch");
                try (ResultSet rows = statement.executeQuery("select count() from probe")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isZero();
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("select nosuch from probe where n = ?")) {
                statement.setLong(1, 1);
                assertThatThrownBy(statement::executeQuery).isInstanceOf(SQLException.class);
            }
            assertThat(count(connection)).isZero();
        }
    }

    /// A batch pipelines its statements before one Sync: once the first fails, the server skips the rest up to the
    /// Sync, so the good statement after it never runs; the connection then serves the next statement.
    @Test
    void skipsAPipelineToItsSyncAfterAFailure() throws SQLException {
        try (Connection connection = connect("")) {
            create(connection);
            try (Statement batch = connection.createStatement()) {
                batch.addBatch("INSERT INTO nosuch (ts) VALUES ('2026-11-14T10:00:00.000000Z')");
                batch.addBatch("INSERT INTO probe (ts, host, n) VALUES ('2026-11-14T10:00:00.000000Z', 'p', 1)");
                assertThatThrownBy(batch::executeBatch).isInstanceOf(BatchUpdateException.class);
            }
            assertThat(count(connection)).isZero();
            try (Statement statement = connection.createStatement()) {
                assertThat(statement.executeUpdate(
                        "INSERT INTO probe (ts, host, n) VALUES ('2026-11-14T10:00:00.000000Z', 'p', 1)"))
                        .isEqualTo(1);
            }
            assertThat(count(connection)).isEqualTo(1);
        }
    }

    /// With autocommit off, ReadyForQuery reports the transaction: open after a query, idle after commit, failed
    /// after an error until rollback, then open again.
    @Test
    void reportsTheTransactionStatus() throws SQLException {
        try (Connection connection = connect("")) {
            create(connection);
            connection.setAutoCommit(false);
            final BaseConnection base = connection.unwrap(BaseConnection.class);
            assertThat(count(connection)).isZero();
            assertThat(base.getTransactionState()).isEqualTo(TransactionState.OPEN);
            connection.commit();
            assertThat(base.getTransactionState()).isEqualTo(TransactionState.IDLE);
            try (Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeQuery("select nosuch from probe"))
                        .isInstanceOf(SQLException.class);
            }
            assertThat(base.getTransactionState()).isEqualTo(TransactionState.FAILED);
            connection.rollback();
            assertThat(base.getTransactionState()).isEqualTo(TransactionState.IDLE);
            assertThat(count(connection)).isZero();
            assertThat(base.getTransactionState()).isEqualTo(TransactionState.OPEN);
            connection.rollback();
        }
    }

    /// A fetch size with autocommit off reads a result through one portal in pages; a row limit stops it early.
    @Test
    void pagesAPortalByTheFetchSize() throws SQLException {
        try (Connection connection = connect("")) {
            create(connection);
            try (CdbAppender appender = cdb.appender()) {
                for (int i = 0; i < 5; i++) {
                    appender.table("probe").symbol("host", "h" + i).longColumn("n", i).at(micros(T0) + i);
                }
            }
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("select n from probe order by n")) {
                statement.setFetchSize(2);
                final List<Long> read = new ArrayList<>();
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        read.add(rows.getLong(1));
                    }
                }
                assertThat(read).containsExactly(0L, 1L, 2L, 3L, 4L);
                statement.setMaxRows(3);
                read.clear();
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        read.add(rows.getLong(1));
                    }
                }
                assertThat(read).containsExactly(0L, 1L, 2L);
            }
            connection.commit();
        }
    }

    /// Two connections hold open sessions at once, each with its own statements, on their own threads.
    @Test
    void servesTwoConnectionsAtOnce() throws Exception {
        try (Connection setup = connect("")) {
            create(setup);
            writeRows();
        }
        final CountDownLatch bothOpen = new CountDownLatch(2);
        try (ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Future<Long>> sums = new ArrayList<>();
            for (int c = 0; c < 2; c++) {
                sums.add(clients.submit(() -> {
                    try (Connection connection = connect("")) {
                        bothOpen.countDown();
                        // the outcome reaches the main thread through the future, asserted there
                        if (!bothOpen.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("other connection did not open [waitedSeconds=10]");
                        }
                        long sum = 0;
                        for (int i = 0; i < 20; i++) {
                            try (PreparedStatement statement = connection.prepareStatement(
                                    "select sum(n) from probe where n > ?")) {
                                statement.setLong(1, -100);
                                try (ResultSet rows = statement.executeQuery()) {
                                    rows.next();
                                    sum += rows.getLong(1);
                                }
                            }
                        }
                        return sum;
                    }
                }));
            }
            for (final Future<Long> sum : sums) {
                assertThat(sum.get(30, TimeUnit.SECONDS)).isEqualTo(40L);
            }
        }
    }

    /// The session queries pgjdbc and Grafana send, answered without cdb's parser.
    @Test
    void answersSessionQueries() throws SQLException {
        try (Connection connection = connect("");
             Statement statement = connection.createStatement()) {
            assertThat(single(statement, "select version()")).startsWith("PostgreSQL 12.3");
            assertThat(single(statement, "SELECT current_schema()")).isEqualTo("public");
            assertThat(single(statement, "show server_version")).isEqualTo(PgSession.SERVER_VERSION);
            assertThat(single(statement, "SHOW TimeZone")).isEqualTo("UTC");
            assertThat(statement.execute("DEALLOCATE ALL")).isFalse();
            assertThat(statement.execute("SET extra_float_digits = 3")).isFalse();
            assertThat(connection.getMetaData().getDatabaseProductVersion()).isEqualTo(PgSession.SERVER_VERSION);
        }
    }

    /// A `SHOW` that is not a session parameter is cdb's: `SHOW TABLES`, `SHOW COLUMNS` and `SHOW CREATE TABLE`
    /// read over pgjdbc, in both query modes, the same columns and values `/exec` answers.
    @Test
    void passesCatalogShowsToCdb() throws Exception {
        try (Connection connection = connect("")) {
            create(connection);
        }
        writeRows();
        try (CdbServer http = CdbServer.start(cdb, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
             Http client = new Http()) {
            for (final String mode : List.of("", "?preferQueryMode=simple")) {
                try (Connection connection = connect(mode);
                     Statement statement = connection.createStatement()) {
                    for (final String sql : List.of("SHOW TABLES", "SHOW COLUMNS FROM probe",
                            "SHOW CREATE TABLE probe")) {
                        final HttpResponse<String> response = client.exec(http.port(), sql, "");
                        assertThat(response.statusCode()).as(sql).isEqualTo(200);
                        try (ResultSet rows = statement.executeQuery(sql)) {
                            assertThat(table(rows)).as(sql + mode).isEqualTo(exec(response.body()));
                        }
                    }
                }
            }
        }
    }

    /// Cosa's statements against QuestDB, replayed with its bindings: the registry lookup and the progress and
    /// cursor state (`LATEST ON`), the progress write with `setTimestamp`, the seq-paged read and the row at a time.
    @Test
    void replaysCosasQueries() throws SQLException {
        try (Connection connection = connect("");
             Statement ddl = connection.createStatement()) {
            ddl.execute("CREATE TABLE poc_tables (ts TIMESTAMP, topic SYMBOL, table_name VARCHAR,"
                    + " schema_json VARCHAR, time_property VARCHAR, retention VARCHAR) TIMESTAMP(ts) PARTITION BY DAY");
            ddl.execute("CREATE TABLE poc_computation_state (ts TIMESTAMP, name SYMBOL, done_until TIMESTAMP)"
                    + " TIMESTAMP(ts) PARTITION BY DAY");
            ddl.execute("CREATE TABLE poc_publication_state (ts TIMESTAMP, name SYMBOL, last_seq LONG)"
                    + " TIMESTAMP(ts) PARTITION BY DAY");
            ddl.execute("CREATE TABLE \"sensor\" (time TIMESTAMP, seq LONG, \"value\" DOUBLE)"
                    + " TIMESTAMP(time) PARTITION BY DAY");
            try (CdbAppender appender = cdb.appender()) {
                appender.table("poc_tables").symbol("topic", "plant/a").stringColumn("table_name", "sensor")
                        .stringColumn("schema_json", "{}").stringColumn("time_property", "time").at(micros(T0));
                appender.table("poc_publication_state").symbol("name", "pub").longColumn("last_seq", 7)
                        .at(micros(T0));
                for (int seq = 1; seq <= 4; seq++) {
                    appender.table("sensor").longColumn("seq", seq).doubleColumn("value", seq * 0.5)
                            .at(micros(T0.plusSeconds(seq)));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT table_name, schema_json,"
                    + " time_property FROM poc_tables WHERE topic = ? LATEST ON ts PARTITION BY topic")) {
                statement.setString(1, "plant/a");
                assertThat(column(statement)).containsExactly("sensor");
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT topic FROM"
                    + " (poc_tables LATEST ON ts PARTITION BY topic) WHERE table_name = ? AND topic <> ?")) {
                statement.setString(1, "sensor");
                statement.setString(2, "plant/b");
                assertThat(column(statement)).containsExactly("plant/a");
            }
            final Instant doneUntil = T0.plusSeconds(3);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO poc_computation_state (ts, name, done_until) VALUES (now(), ?, ?)")) {
                statement.setString(1, "job");
                statement.setTimestamp(2, Timestamp.from(doneUntil));
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT done_until FROM poc_computation_state WHERE name = ? LATEST ON ts PARTITION BY name")) {
                statement.setString(1, "job");
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getMetaData().getColumnTypeName(1)).isEqualTo("timestamp");
                    assertThat(rows.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC)).isEqualTo(doneUntil);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT last_seq FROM poc_publication_state WHERE name = ? LATEST ON ts PARTITION BY name")) {
                statement.setString(1, "pub");
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isEqualTo(7);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT time, seq, \"value\" FROM \"sensor\" WHERE seq > ? ORDER BY seq LIMIT 500")) {
                statement.setLong(1, 2);
                assertThat(column(statement)).hasSize(2);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT time, \"value\" FROM \"sensor\" WHERE time = ? ORDER BY seq DESC LIMIT 1")) {
                statement.setObject(1, OffsetDateTime.ofInstant(T0.plusSeconds(2), ZoneOffset.UTC));
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getDouble(2)).isEqualTo(1.0);
                }
            }
            try (ResultSet rows = ddl.executeQuery("SELECT coalesce(max(seq), 0) FROM \"sensor\"")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(4);
            }
        }
    }

    /// `Statement.cancel()`, which comando's stop and pgjdbc's query timeout both send as a CancelRequest carrying the
    /// session's process id and secret key, stops a statement that would run for minutes: it fails with
    /// `query_canceled` (57014) within a second of the cancel that reached it, and the connection runs the next one.
    @Test
    void aCancelRequestStopsTheRunningStatement() throws Exception {
        try (Connection connection = connect("");
             Statement statement = connection.createStatement()) {
            final ExecutorService runner = Executors.newSingleThreadExecutor();
            try {
                final Future<?> running = runner.submit(() -> {
                    try (ResultSet rows = statement.executeQuery(LONG_QUERY)) {
                        return rows.next();
                    }
                });
                final long cancelledAt = cancelUntilDone(statement, running);
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelledAt)).isLessThan(1_000L);
                assertThatThrownBy(running::get).hasCauseInstanceOf(SQLException.class)
                        .cause().extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("57014");
            } finally {
                runner.shutdownNow();
                assertThat(runner.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
            try (ResultSet rows = statement.executeQuery("select 1 x from long_sequence(1)")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(1);
            }
        }
    }

    /// A CancelRequest with the session's process id and a wrong secret key cancels nothing, as in PostgreSQL: the
    /// statement keeps running until the right key stops it.
    @Test
    void aCancelRequestWithTheWrongKeyCancelsNothing() throws Exception {
        try (Connection connection = connect("");
             Statement statement = connection.createStatement()) {
            final int processId = connection.unwrap(PGConnection.class).getBackendPID();
            final ExecutorService runner = Executors.newSingleThreadExecutor();
            try {
                final Future<?> running = runner.submit(() -> {
                    try (ResultSet rows = statement.executeQuery(LONG_QUERY)) {
                        return rows.next();
                    }
                });
                for (int key = -2; key <= 2; key++) {
                    sendCancel(processId, key);
                }
                assertThatThrownBy(() -> running.get(500, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                cancelUntilDone(statement, running);
                assertThatThrownBy(running::get).hasCauseInstanceOf(SQLException.class);
            } finally {
                runner.shutdownNow();
                assertThat(runner.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    // a cancel sent before the statement reached the server reaches nothing, so it is repeated until one stops it;
    // returns when the effective cancel was sent
    private static long cancelUntilDone(final Statement statement, final Future<?> running) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() - deadline < 0) {
            final long sentAt = System.nanoTime();
            statement.cancel();
            try {
                running.get(200, TimeUnit.MILLISECONDS);
                return sentAt;
            } catch (final ExecutionException _) {
                return sentAt;
            } catch (final TimeoutException _) {
                // not stopped yet: the cancel went out before the statement started, or was ignored
            }
        }
        throw new AssertionError("statement still running 10 s after the first cancel");
    }

    // a CancelRequest packet (length 16, code 80877102, process id, secret key) on a connection of its own
    private void sendCancel(final int processId, final int secretKey) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port());
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
            out.writeInt(16);
            out.writeInt(80_877_102);
            out.writeInt(processId);
            out.writeInt(secretKey);
            out.flush();
            assertThat(socket.getInputStream().read()).isEqualTo(-1);
        }
    }

    private Connection connect(final String options) throws SQLException {
        return DriverManager.getConnection(url(options), credentials());
    }

    private String url(final String options) {
        return "jdbc:postgresql://127.0.0.1:" + server.port() + "/qdb" + options;
    }

    // three rows a few seconds apart: every type set in the first, nulls and an infinity in the second
    private void writeRows() {
        try (CdbAppender appender = cdb.appender()) {
            appender.table("probe").symbol("host", "a").longColumn("n", 1).doubleColumn("x", 1.5)
                    .booleanColumn("ok", true).stringColumn("note", "hi 'quoted'")
                    .timestampColumn("at", micros(T0) + 123_456).at(micros(T0));
            appender.table("probe").symbol("host", "b").longColumn("n", -2)
                    .doubleColumn("x", Double.POSITIVE_INFINITY).booleanColumn("ok", false)
                    .at(micros(T0.plusSeconds(1)));
            appender.table("probe").symbol("host", "c").longColumn("n", 3).doubleColumn("x", 2.5)
                    .booleanColumn("ok", true).stringColumn("note", "c").at(micros(T0.plusSeconds(2)));
        }
    }

    private static boolean create(final Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.execute(CREATE);
        }
    }

    private static long count(final Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select count() from probe")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static String single(final Statement statement, final String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            final String value = rows.getString(1);
            assertThat(rows.next()).isFalse();
            return value;
        }
    }

    private static List<String> column(final PreparedStatement statement) throws SQLException {
        final List<String> values = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    // column names, then each row's values as pgjdbc's getObject reads them
    private static List<List<Object>> table(final ResultSet rows) throws SQLException {
        final ResultSetMetaData meta = rows.getMetaData();
        final List<List<Object>> table = new ArrayList<>();
        final List<Object> names = new ArrayList<>();
        for (int column = 1; column <= meta.getColumnCount(); column++) {
            names.add(meta.getColumnLabel(column));
        }
        table.add(names);
        while (rows.next()) {
            final List<Object> row = new ArrayList<>();
            for (int column = 1; column <= meta.getColumnCount(); column++) {
                row.add(rows.getObject(column));
            }
            table.add(row);
        }
        return table;
    }

    // an /exec answer in the shape of table(): its column names, then its dataset rows
    @SuppressWarnings("unchecked") // a JSON array is read as a list of values, an object as a map of them
    private static List<List<Object>> exec(final String json) {
        final Map<String, Object> answer = (Map<String, Object>) new JsonValues(json).value();
        final List<List<Object>> table = new ArrayList<>();
        final List<Object> names = new ArrayList<>();
        for (final Object column : (List<Object>) answer.get("columns")) {
            names.add(((Map<String, Object>) column).get("name"));
        }
        table.add(names);
        for (final Object row : (List<Object>) answer.get("dataset")) {
            table.add((List<Object>) row);
        }
        return table;
    }

    private static Properties credentials() {
        final Properties properties = new Properties();
        properties.setProperty("user", "admin");
        properties.setProperty("password", "quest");
        return properties;
    }

    private static long micros(final Instant instant) {
        return TimeUnit.SECONDS.toMicros(instant.getEpochSecond()) + TimeUnit.NANOSECONDS.toMicros(instant.getNano());
    }

    /// A JSON reader for `/exec`'s answers: objects as ordered maps, arrays as lists, integers as `Long`, other
    /// numbers as `Double`; only the two-character escapes are decoded, the answers read here carry no other.
    private static final class JsonValues {

        private final String text;
        private int at;

        JsonValues(final String text) {
            this.text = text;
        }

        Object value() {
            blanks();
            final char c = text.charAt(at);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> word("true", Boolean.TRUE);
                case 'f' -> word("false", Boolean.FALSE);
                case 'n' -> word("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            final Map<String, Object> map = new LinkedHashMap<>();
            at++;
            blanks();
            if (text.charAt(at) == '}') {
                at++;
                return map;
            }
            while (true) {
                blanks();
                final String key = string();
                blanks();
                at++;
                map.put(key, value());
                blanks();
                if (text.charAt(at++) == '}') {
                    return map;
                }
            }
        }

        private List<Object> array() {
            final List<Object> list = new ArrayList<>();
            at++;
            blanks();
            if (text.charAt(at) == ']') {
                at++;
                return list;
            }
            while (true) {
                list.add(value());
                blanks();
                if (text.charAt(at++) == ']') {
                    return list;
                }
            }
        }

        private String string() {
            final StringBuilder out = new StringBuilder();
            at++;
            while (text.charAt(at) != '"') {
                final char c = text.charAt(at++);
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                final char escaped = text.charAt(at++);
                out.append(switch (escaped) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    default -> escaped;
                });
            }
            at++;
            return out.toString();
        }

        private Object word(final String word, final Object value) {
            assertThat(text.startsWith(word, at)).as("JSON word at " + at).isTrue();
            at += word.length();
            return value;
        }

        private Object number() {
            final int start = at;
            while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
            final String number = text.substring(start, at);
            // two returns, not a conditional expression, which would promote the Long to a Double
            if (number.contains(".") || number.contains("e") || number.contains("E")) {
                return Double.parseDouble(number);
            }
            return Long.parseLong(number);
        }

        private void blanks() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }
    }
}
