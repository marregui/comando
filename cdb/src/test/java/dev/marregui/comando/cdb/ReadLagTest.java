package dev.marregui.comando.cdb;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.serve.CdbServer;
import dev.marregui.comando.cdb.serve.PgServer;
import dev.marregui.comando.cdb.store.Store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The read lag a caller asks for reaches the store: [Cdb] runs each query with the lag bound for the whole execution,
/// the HTTP `max_lag_ms` parameter and the PG session's `cdb.max_lag_ms` ask for it, and the launcher's flag sets the
/// store's default. What a query sees is read from the scope its execution runs in, through [Cdb]'s test hook.
class ReadLagTest {

    private static final String QUERY = "SELECT x FROM long_sequence(1)";
    private static final String UNBOUND = "unbound";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    private Path root;

    // appended by the threads that run queries (virtual per request, the session's, the test's), read by the test
    private final ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();
    private Cdb cdb;

    /// A cdb whose every query records the lag bound around its execution, or that none is.
    @BeforeEach
    void open() {
        cdb = Cdb.open(root);
        cdb.beforeQuery = () -> seen.add(
                Store.READ_MAX_LAG_MILLIS.isBound() ? String.valueOf(Store.READ_MAX_LAG_MILLIS.get()) : UNBOUND);
    }

    /// Closes the cdb.
    @AfterEach
    void close() {
        cdb.close();
    }

    /// The overload binds its lag around the query and no longer; the plain `run` binds nothing.
    @Test
    void theFacadeBindsTheLagAroundTheQueryOnly() {
        cdb.run(QUERY, 0, 0L);
        cdb.run(QUERY, 0, 250L);
        cdb.run(QUERY);

        assertThat(seen).containsExactly("0", "250", UNBOUND);
        assertThat(Store.READ_MAX_LAG_MILLIS.isBound()).isFalse();
    }

    /// A negative lag is refused before anything runs, and so is a negative default.
    @Test
    void theFacadeRefusesANegativeLag() {
        assertThatThrownBy(() -> cdb.run(QUERY, 0, -1L)).isInstanceOf(CdbException.class)
                .hasMessageContaining("millis=-1");
        assertThatThrownBy(() -> cdb.readMaxLagMillis(-1L)).isInstanceOf(CdbException.class)
                .hasMessageContaining("millis=-1");
        assertThat(seen).isEmpty();
    }

    /// `/exec?max_lag_ms=n` runs the query bound to n, one without it runs unbound, and a bad value runs nothing.
    @Test
    void httpMaxLagMsBindsTheQueryLag() throws Exception {
        try (final CdbServer server = CdbServer.start(cdb, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
             final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            assertThat(exec(client, server.port(), "&max_lag_ms=250").statusCode()).isEqualTo(200);
            assertThat(exec(client, server.port(), "&max_lag_ms=0").statusCode()).isEqualTo(200);
            assertThat(exec(client, server.port(), "").statusCode()).isEqualTo(200);
            assertThat(seen).containsExactly("250", "0", UNBOUND);

            seen.clear();
            for (final String bad : List.of("-1", "soon", "")) {
                assertThat(exec(client, server.port(), "&max_lag_ms=" + bad).statusCode()).as(bad).isEqualTo(400);
            }
            assertThat(seen).isEmpty();
        }
    }

    /// `SET cdb.max_lag_ms` (with `=` or `TO`, quoted or not) binds every later query of the connection, `RESET` and
    /// `DEFAULT` unbind them, another connection never sees it, and the extended protocol is bound as well.
    @Test
    void pgSetAndResetBindTheSessionLag() throws SQLException {
        try (final PgServer server = PgServer.start(cdb, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
             final Connection connection = connect(server.port());
             final Connection other = connect(server.port());
             final Statement statement = connection.createStatement()) {
            statement.execute("SET cdb.max_lag_ms = 100");
            assertThat(queried(statement)).isEqualTo("100");

            statement.execute("SET cdb.max_lag_ms TO '7'");
            assertThat(queried(statement)).isEqualTo("7");

            statement.execute("SET SESSION CDB.MAX_LAG_MS = 0");
            assertThat(queried(statement)).isEqualTo("0");

            statement.execute("SET cdb.max_lag_ms = 100");
            try (final PreparedStatement prepared = connection.prepareStatement("SELECT x FROM long_sequence(?)")) {
                seen.clear();
                prepared.setInt(1, 1);
                prepared.execute();
                assertThat(seen).containsExactly("100");
            }
            try (final Statement unaffected = other.createStatement()) {
                assertThat(queried(unaffected)).isEqualTo(UNBOUND);
            }

            statement.execute("RESET cdb.max_lag_ms");
            assertThat(queried(statement)).isEqualTo(UNBOUND);

            statement.execute("SET cdb.max_lag_ms = 100");
            statement.execute("SET cdb.max_lag_ms = DEFAULT");
            assertThat(queried(statement)).isEqualTo(UNBOUND);
        }
    }

    /// A negative, a fractional, a non-numeric and a quoted-DEFAULT value is refused, and the lag set before stays.
    @Test
    void pgRefusesABadLagAndKeepsTheOneSet() throws SQLException {
        try (final PgServer server = PgServer.start(cdb, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
             final Connection connection = connect(server.port());
             final Statement statement = connection.createStatement()) {
            statement.execute("SET cdb.max_lag_ms = 100");
            for (final String bad : List.of("-1", "1.5", "soon", "'default'", "''")) {
                assertThatThrownBy(() -> statement.execute("SET cdb.max_lag_ms = " + bad)).as(bad)
                        .isInstanceOf(SQLException.class).hasMessageContaining("invalid cdb.max_lag_ms");
            }
            assertThat(queried(statement)).isEqualTo("100");
        }
    }

    /// The flag reads a whole number of milliseconds, 0 when absent, and refuses a negative or non-numeric one, or
    /// one with no value, before anything opens.
    @Test
    void theLauncherReadsTheLagFlag() {
        assertThat(CdbServe.parse(new String[] {"/data", "9000"}).readMaxLagMillis()).isZero();
        assertThat(CdbServe.parse(new String[] {"/data", "--read-max-lag-ms", "500", "9000", "--pg-port", "8812"})
                .readMaxLagMillis()).isEqualTo(500L);
        for (final String bad : List.of("-1", "soon", "1.5")) {
            assertThatThrownBy(() -> CdbServe.parse(new String[] {"/data", "9000", "--read-max-lag-ms", bad}))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> CdbServe.parse(new String[] {"/data", "9000", "--read-max-lag-ms"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--read-max-lag-ms");
    }

    private static HttpResponse<String> exec(final HttpClient client, final int port, final String extra)
            throws IOException, InterruptedException {
        final URI uri = URI.create("http://127.0.0.1:" + port + "/exec?query="
                + URLEncoder.encode(QUERY, StandardCharsets.UTF_8) + extra);
        return client.send(HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static Connection connect(final int port) throws SQLException {
        final Properties properties = new Properties();
        properties.setProperty("user", "admin");
        properties.setProperty("password", "quest");
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port + "/qdb", properties);
    }

    // what the one query run on `statement` saw
    private String queried(final Statement statement) throws SQLException {
        seen.clear();
        statement.execute(QUERY);
        assertThat(seen).hasSize(1);
        return seen.peek();
    }
}
