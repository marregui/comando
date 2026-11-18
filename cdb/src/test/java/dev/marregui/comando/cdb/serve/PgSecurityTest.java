package dev.marregui.comando.cdb.serve;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// pgjdbc against a [PgServer] served with [Secrets]: the owner's secret runs every statement, the read-only secret
/// queries only (`42501` otherwise), any other password is refused with `28P01`; and, with or without secrets, a
/// statement past its deadline is cancelled with `57014`, a result past the row limit refused with `54000`, and no
/// statement's text reaches the log.
class PgSecurityTest {

    /// Two fixed secrets in the files' form, 64 lowercase hex characters each; no randomness in the test.
    private static final String OWNER = "0123456789abcdef".repeat(4);
    private static final String READ_ONLY = "fedcba9876543210".repeat(4);
    /// Long enough for a statement of a few milliseconds, short enough that a test waits a fraction of a second.
    private static final long SHORT_DEADLINE_MILLIS = 300;
    private static final int SMALL_MAX_ROWS = 10;
    // 2e11 rows: minutes of work, so only the deadline ends it in time
    private static final String LONG_QUERY = "select max(x) from long_sequence(200000000000)";
    /// A text no log line may carry: it stands in every statement of the log test.
    private static final String MARKER = "zz_marker_9f3c";

    @TempDir
    private Path root;

    @TempDir
    private Path auth;

    private Cdb cdb;
    private PgServer server;

    /// A fresh cdb per test, and the auth folder holding the two secrets.
    @BeforeEach
    void open() throws IOException {
        cdb = Cdb.open(root);
        writeSecret(auth.resolve("secret"), OWNER);
        writeSecret(auth.resolve("secret.readonly"), READ_ONLY);
    }

    /// Closes the server a test started, then the cdb.
    @AfterEach
    void close() {
        if (server != null) {
            server.close();
        }
        cdb.close();
    }

    /// The owner's secret is asked as a clear-text password and, once given, runs DDL, writes and queries in both
    /// query modes.
    @Test
    void theOwnerSecretRunsEveryStatement() throws Exception {
        server = PgServer.start(cdb, loopback(), Secrets.load(auth));
        try (Connection connection = connect("", OWNER);
             Statement statement = connection.createStatement()) {
            statement.execute("create table t (ts TIMESTAMP, n LONG) timestamp(ts) partition by DAY");
            assertThat(statement.executeUpdate("insert into t values ('2026-11-18T10:00:00.000000Z', 1)"))
                    .isEqualTo(1);
            assertThat(count(statement, "t")).isEqualTo(1);
        }
        try (Connection connection = connect("?preferQueryMode=simple", OWNER);
             Statement statement = connection.createStatement()) {
            statement.execute("insert into t values ('2026-11-18T11:00:00.000000Z', 2)");
            assertThat(count(statement, "t")).isEqualTo(2);
        }
    }

    /// The read-only secret runs a query, but a CREATE TABLE and an INSERT are refused with `insufficient_privilege`
    /// (42501), in the extended and the simple query mode alike, and change nothing.
    @Test
    void theReadOnlySecretRunsQueriesOnly() throws Exception {
        server = PgServer.start(cdb, loopback(), Secrets.load(auth));
        try (Connection owner = connect("", OWNER);
             Statement statement = owner.createStatement()) {
            statement.execute("create table t (ts TIMESTAMP, n LONG) timestamp(ts) partition by DAY");
            statement.execute("insert into t values ('2026-11-18T10:00:00.000000Z', 1)");
        }
        for (final String mode : List.of("", "?preferQueryMode=simple")) {
            try (Connection reader = connect(mode, READ_ONLY);
                 Statement statement = reader.createStatement()) {
                assertThat(count(statement, "t")).isEqualTo(1);
                assertThat(sqlState(() -> statement.execute("create table u (n LONG)"))).isEqualTo("42501");
                assertThat(sqlState(() -> statement.executeUpdate(
                        "insert into t values ('2026-11-18T11:00:00.000000Z', 2)"))).isEqualTo("42501");
                assertThat(count(statement, "t")).isEqualTo(1);
            }
        }
        assertThat(cdb.tables()).containsExactly("t");
    }

    /// A password that is neither secret, an empty one included, is refused with `invalid_password` (28P01) and the
    /// connection closed; the server then lets the owner in.
    @Test
    void aWrongPasswordIsRefused() throws Exception {
        server = PgServer.start(cdb, loopback(), Secrets.load(auth));
        for (final String password : List.of("quest", "", OWNER.toUpperCase(Locale.ROOT))) {
            assertThat(sqlState(() -> connect("", password).close())).isEqualTo("28P01");
        }
        try (Connection connection = connect("", OWNER);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select 1 x from long_sequence(1)")) {
            assertThat(rows.next()).isTrue();
        }
    }

    /// A statement that would run for minutes is cancelled at its deadline with `query_canceled` (57014), well
    /// before a minute, and the connection runs the next statement.
    @Test
    void aStatementPastItsDeadlineIsCancelled() throws Exception {
        server = PgServer.start(cdb, loopback(), null, PgServer.STARTUP_TIMEOUT_MILLIS, PgServer.IDLE_TIMEOUT_MILLIS,
                SHORT_DEADLINE_MILLIS, Limits.MAX_ROWS);
        for (final String mode : List.of("", "?preferQueryMode=simple")) {
            try (Connection connection = connect(mode, "quest");
                 Statement statement = connection.createStatement()) {
                final long startNanos = System.nanoTime();
                assertThat(sqlState(() -> statement.executeQuery(LONG_QUERY).close())).isEqualTo("57014");
                final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                assertThat(tookMillis).isGreaterThanOrEqualTo(SHORT_DEADLINE_MILLIS).isLessThan(10_000L);
                try (ResultSet rows = statement.executeQuery("select 1 x from long_sequence(1)")) {
                    assertThat(rows.next()).isTrue();
                }
            }
        }
    }

    /// A result of exactly the row limit is answered whole; one row more is refused with `program_limit_exceeded`
    /// (54000) rather than cut short, in both query modes, and the connection stays usable.
    @Test
    void aResultPastTheRowLimitIsRefused() throws Exception {
        server = PgServer.start(cdb, loopback(), null, PgServer.STARTUP_TIMEOUT_MILLIS, PgServer.IDLE_TIMEOUT_MILLIS,
                Limits.QUERY_DEADLINE_MILLIS, SMALL_MAX_ROWS);
        for (final String mode : List.of("", "?preferQueryMode=simple")) {
            try (Connection connection = connect(mode, "quest");
                 Statement statement = connection.createStatement()) {
                int rows = 0;
                try (ResultSet result = statement.executeQuery(
                        "select x from long_sequence(" + SMALL_MAX_ROWS + ')')) {
                    while (result.next()) {
                        rows++;
                    }
                }
                assertThat(rows).isEqualTo(SMALL_MAX_ROWS);
                assertThat(sqlState(() -> statement.executeQuery(
                        "select x from long_sequence(" + (SMALL_MAX_ROWS + 1) + ')').close())).isEqualTo("54000");
                assertThat(count(statement, "long_sequence(3)")).isEqualTo(3);
            }
        }
    }

    /// With every level of the PG wire's loggers recorded, statements that succeed, fail to parse, are refused for
    /// the read-only secret, pass the row limit or hit the deadline leave no line carrying their text.
    @Test
    void noStatementTextReachesTheLog() throws Exception {
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());
        final Handler handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                lines.add(record.getMessage() + ' ' + record.getThrown());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        final List<Logger> loggers = List.of(Logger.getLogger(PgSession.class.getName()),
                Logger.getLogger(PgServer.class.getName()));
        final List<Level> levels = new ArrayList<>();
        for (final Logger logger : loggers) {
            levels.add(logger.getLevel());
            logger.setLevel(Level.ALL);
            logger.addHandler(handler);
        }
        try {
            server = PgServer.start(cdb, loopback(), Secrets.load(auth), PgServer.STARTUP_TIMEOUT_MILLIS,
                    PgServer.IDLE_TIMEOUT_MILLIS, SHORT_DEADLINE_MILLIS, SMALL_MAX_ROWS);
            for (final String mode : List.of("", "?preferQueryMode=simple")) {
                try (Connection connection = connect(mode, OWNER);
                     Statement statement = connection.createStatement()) {
                    statement.executeQuery("select '" + MARKER + "' m from long_sequence(1)").close();
                    sqlState(() -> statement.execute("select from where '" + MARKER + '\''));
                    sqlState(() -> statement.executeQuery("select '" + MARKER
                            + "' m from long_sequence(100)").close());
                    sqlState(() -> statement.executeQuery("select max(x), '" + MARKER
                            + "' m from long_sequence(200000000000)").close());
                }
                try (Connection connection = connect(mode, READ_ONLY);
                     Statement statement = connection.createStatement()) {
                    sqlState(() -> statement.execute("create table " + MARKER + " (n LONG)"));
                }
            }
            sqlState(() -> connect("", MARKER).close());
        } finally {
            for (int i = 0; i < loggers.size(); i++) {
                loggers.get(i).removeHandler(handler);
                loggers.get(i).setLevel(levels.get(i));
            }
        }
        assertThat(lines).isNotEmpty().noneMatch(line -> line.contains(MARKER));
    }

    private Connection connect(final String options, final String password) throws SQLException {
        final Properties properties = new Properties();
        properties.setProperty("user", "admin");
        properties.setProperty("password", password);
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + server.port() + "/qdb" + options,
                properties);
    }

    private static InetSocketAddress loopback() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    }

    private static long count(final Statement statement, final String table) throws SQLException {
        try (ResultSet rows = statement.executeQuery("select count() from " + table)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    // the SQLSTATE the call fails with; a call that does not fail fails the test
    private static String sqlState(final SqlCall call) {
        final String[] state = new String[1];
        assertThatThrownBy(() -> {
            try {
                call.run();
            } catch (final SQLException e) {
                state[0] = e.getSQLState();
                throw e;
            }
        }).isInstanceOf(SQLException.class);
        return state[0];
    }

    private static void writeSecret(final Path file, final String secret) throws IOException {
        Files.writeString(file, secret + '\n', StandardCharsets.US_ASCII);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }

    /// A JDBC call that may fail.
    @FunctionalInterface
    private interface SqlCall {

        /// Runs the call.
        void run() throws SQLException;
    }
}
