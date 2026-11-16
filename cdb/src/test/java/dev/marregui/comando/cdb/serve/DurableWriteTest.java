package dev.marregui.comando.cdb.serve;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbServe;
import dev.marregui.comando.cdb.exec.QueryPool;
import dev.marregui.comando.cdb.store.StoreOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The durable flag at the servers: `/write?durable=true` answered only after the store's force held, 503 when the
/// wait runs out, 500 when the force failed, today's answer without the flag; the PostgreSQL wire's
/// `SET cdb.durable`; and the server's `--query-threads` and `--log-force-ms` options. Against a real store with a
/// timed log force, a durable write is seen to be forced by JFR's `jdk.FileForce` event on the store's log-force
/// thread, which without a durable waiter forces only at its interval, a minute here.
class DurableWriteTest {

    /// Ten seconds bounds a hung run; every request here answers in milliseconds when the server works.
    private static final long TIMEOUT_SECONDS = 10L;
    private static final String LINE = "t x=1i 1000000000\n";
    /// Far past every test's run: a log force seen within one is a durable waiter's.
    private static final long LOG_FORCE_MILLIS = 60_000L;

    private static Http http;

    @TempDir
    private Path root;

    private DurableBackend backend;
    private CdbServer server;

    /// One client for the class.
    @BeforeAll
    static void openClient() {
        http = new Http();
    }

    /// Closes the client.
    @AfterAll
    static void closeClient() {
        http.close();
    }

    /// A server over a backend whose appender's durability wait each test sets.
    @BeforeEach
    void start() {
        backend = new DurableBackend();
        server = CdbServer.start(backend, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    }

    /// Stops the server.
    @AfterEach
    void stop() {
        server.close();
    }

    /// A durable request is answered only once the force it waits on returned: while the wait holds, the rows are
    /// flushed and the request is unanswered; once it returns, the answer is 204. The wait asks for what remains of
    /// the ten seconds.
    @Test
    void durableWriteAnswersAfterTheForce() throws Exception {
        final CountDownLatch waiting = new CountDownLatch(1);
        final CountDownLatch forced = new CountDownLatch(1);
        backend.durability = timeoutMillis -> {
            waiting.countDown();
            return forced.await(timeoutMillis, TimeUnit.MILLISECONDS);
        };
        try (ExecutorService client = Executors.newVirtualThreadPerTaskExecutor()) {
            final Future<HttpResponse<String>> answer =
                    client.submit(() -> http.post(server.port(), "/write?durable=true", LINE));
            assertThat(waiting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("the request waits for the force")
                    .isTrue();
            assertThat(backend.lastRecording().flushed).contains("long(x=1)");
            assertThat(answer).isNotDone();
            forced.countDown();
            assertThat(answer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
        }
        assertThat(backend.waits.get()).isEqualTo(1);
        assertThat(backend.lastTimeoutMillis).isBetween(1L, 10_000L);
    }

    /// A durable request's wait holds nothing a plain write of the same table needs: while it waits, a write without
    /// the flag is committed and answered, and the durable one is still unanswered.
    @Test
    void plainWriteIsAnsweredWhileADurableOneWaits() throws Exception {
        final CountDownLatch waiting = new CountDownLatch(1);
        final CountDownLatch forced = new CountDownLatch(1);
        backend.durability = timeoutMillis -> {
            waiting.countDown();
            return forced.await(timeoutMillis, TimeUnit.MILLISECONDS);
        };
        try (ExecutorService client = Executors.newVirtualThreadPerTaskExecutor()) {
            final Future<HttpResponse<String>> durable =
                    client.submit(() -> http.post(server.port(), "/write?durable=true", LINE));
            assertThat(waiting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("the durable request waits").isTrue();
            final Future<HttpResponse<String>> plain = client.submit(() -> http.post(server.port(), "/write", LINE));
            assertThat(plain.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
            assertThat(durable).as("the durable request still waits for its force").isNotDone();
            forced.countDown();
            assertThat(durable.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
        }
        assertThat(backend.rows()).isEqualTo(2);
        assertThat(backend.waits.get()).isEqualTo(1);
    }

    /// Against a real store with a timed log force, `/write?durable=true` is answered 204 after the store's log-force
    /// thread forced the table's log, long before its interval.
    @Test
    void durableWriteForcesATimedStore() throws IOException, InterruptedException {
        try (LogForces forces = new LogForces();
             Cdb cdb = Cdb.open(root, new StoreOptions(LOG_FORCE_MILLIS));
             CdbServer timed = CdbServer.start(cdb, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            assertThat(http.post(timed.port(), "/write?durable=true", LINE).statusCode()).isEqualTo(204);
            assertThat(forces.awaitForce()).as("the durable write's log forced").isTrue();
        }
    }

    /// Without the flag, or with `durable=false`, the request is answered once committed: nothing waits on the force.
    @Test
    void writeWithoutTheFlagDoesNotWait() throws IOException, InterruptedException {
        backend.durability = _ -> {
            throw new AssertionError("waited for a write that is not durable");
        };
        assertThat(http.post(server.port(), "/write", LINE).statusCode()).isEqualTo(204);
        assertThat(http.post(server.port(), "/write?durable=false", LINE).statusCode()).isEqualTo(204);
        assertThat(backend.waits.get()).isZero();
        assertThat(backend.rows()).isEqualTo(2);
    }

    /// A wait that runs out answers 503 in QuestDB's error shape, saying the rows are written and not yet durable;
    /// the rows are written.
    @Test
    void durableWriteWhoseWaitRunsOutAnswers503() throws IOException, InterruptedException {
        backend.durability = _ -> false;
        final HttpResponse<String> answer = http.post(server.port(), "/write?durable=true", LINE);
        assertThat(answer.statusCode()).isEqualTo(503);
        assertThat(answer.body())
                .isEqualTo(JsonWriter.writeError("wrote rows, not yet durable [table=t, waitMillis=10000]", 0));
        assertThat(backend.rows()).isEqualTo(1);
    }

    /// A force that failed answers 500, the commit in doubt.
    @Test
    void durableWriteWhoseForceFailedAnswers500() throws IOException, InterruptedException {
        backend.durability = _ -> {
            throw new CdbException("commit in doubt [table=t]", -1);
        };
        final HttpResponse<String> answer = http.post(server.port(), "/write?durable=true", LINE);
        assertThat(answer.statusCode()).isEqualTo(500);
        assertThat(answer.body()).isEqualTo(JsonWriter.writeError(
                "commit in doubt [table=t, error=commit in doubt [table=t]]", 0));
    }

    /// A durable flag other than `true` or `false` is refused before the body is read; nothing is written.
    @Test
    void unknownDurableValueIsRefused() throws IOException, InterruptedException {
        final HttpResponse<String> answer = http.post(server.port(), "/write?durable=yes", LINE);
        assertThat(answer.statusCode()).isEqualTo(400);
        assertThat(answer.body()).isEqualTo(JsonWriter.writeError("invalid durable [durable=yes]", 0));
        assertThat(backend.appenders.get()).isZero();
    }

    /// `SET cdb.durable` takes on and off, quoted or not, with `=` or `TO`, and `DEFAULT`; `RESET cdb.durable` turns
    /// it off; any other value is refused.
    @Test
    void setDurableParsesItsValues() {
        assertThat(PgSql.local("SET cdb.durable = on").durable()).isEqualTo(new PgSql.DurableChange(true));
        assertThat(PgSql.local("set session CDB.DURABLE to 'true'").durable()).isEqualTo(new PgSql.DurableChange(true));
        assertThat(PgSql.local("SET cdb.durable TO 'off'").durable()).isEqualTo(new PgSql.DurableChange(false));
        assertThat(PgSql.local("SET cdb.durable = DEFAULT").durable()).isEqualTo(new PgSql.DurableChange(false));
        assertThat(PgSql.local("RESET cdb.durable").durable()).isEqualTo(new PgSql.DurableChange(false));
        assertThat(PgSql.local("RESET cdb.durable").tag()).isEqualTo("RESET");
        assertThat(PgSql.local("SET cdb.max_lag_ms = 5").durable()).isNull();
        assertThatThrownBy(() -> PgSql.local("SET cdb.durable = maybe")).isInstanceOf(CdbException.class);
    }

    /// Over the wire against a store with a timed log force: a durable session's INSERT is answered with its row
    /// count after the store's log-force thread forced the table's log, long before its interval; the setting turns
    /// off again, and a bad value fails the statement only.
    @Test
    void durableSessionInsertsOverTheWire() throws SQLException, InterruptedException {
        try (LogForces forces = new LogForces();
             Cdb cdb = Cdb.open(root, new StoreOptions(LOG_FORCE_MILLIS));
             PgServer pg = PgServer.start(cdb, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
             Connection connection = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:" + pg.port() + "/qdb", credentials());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE t (ts TIMESTAMP, x LONG) TIMESTAMP(ts) PARTITION BY DAY");
            statement.execute("SET cdb.durable = on");
            assertThat(statement.executeUpdate("INSERT INTO t (ts, x) VALUES ('2026-11-16T10:00:00.000000Z', 1)"))
                    .isEqualTo(1);
            assertThat(forces.awaitForce()).as("the durable INSERT's log forced").isTrue();
            statement.execute("RESET cdb.durable");
            assertThat(statement.executeUpdate("INSERT INTO t (ts, x) VALUES ('2026-11-16T10:00:01.000000Z', 2)"))
                    .isEqualTo(1);
            assertThatThrownBy(() -> statement.execute("SET cdb.durable = maybe")).isInstanceOf(SQLException.class);
            try (ResultSet rows = statement.executeQuery("SELECT count() FROM t")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(2L);
            }
        }
    }

    /// `--query-threads` and `--log-force-ms` are read with the other options; not given, they are -1 and 0;
    /// a negative or missing value is refused.
    @Test
    void serveParsesTheQueryThreadsAndLogForceOptions() {
        final CdbServe.Options given = CdbServe.parse(new String[] {
            "/data", "--query-threads", "3", "9000", "--log-force-ms", "250"});
        assertThat(given.queryThreads()).isEqualTo(3);
        assertThat(given.logForceMillis()).isEqualTo(250L);
        assertThat(given.port()).isEqualTo(9000);
        final CdbServe.Options defaults = CdbServe.parse(new String[] {"/data", "9000"});
        assertThat(defaults.queryThreads()).isEqualTo(-1);
        assertThat(defaults.logForceMillis()).isZero();
        assertThat(CdbServe.parse(new String[] {"/data", "9000", "--query-threads", "0"}).queryThreads()).isZero();
        assertThatThrownBy(() -> CdbServe.parse(new String[] {"/data", "9000", "--query-threads", "-1"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CdbServe.parse(new String[] {"/data", "9000", "--log-force-ms", "-5"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CdbServe.parse(new String[] {"/data", "9000", "--log-force-ms"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /// The server opens what its options name: `--log-force-ms` a store with a timed log force, whose force thread
    /// runs for the store's folder, and none without it; `--query-threads` sizes the query pool, which refuses a size
    /// other than the running pool's, before any store opens.
    @Test
    void serveOpensTheStoreAndPoolItsOptionsName() {
        final Path timed = root.resolve("serve-timed");
        try (Cdb _ = CdbServe.open(CdbServe.parse(new String[] {timed.toString(), "0", "--log-force-ms", "60000"}))) {
            assertThat(logForceThreadRuns(timed)).isTrue();
        }
        final Path untimed = root.resolve("serve-untimed");
        try (Cdb _ = CdbServe.open(CdbServe.parse(new String[] {untimed.toString(), "0"}))) {
            assertThat(logForceThreadRuns(untimed)).isFalse();
        }
        final int running = QueryPool.shared().threads();
        final Path resized = root.resolve("serve-resized");
        assertThatThrownBy(() -> CdbServe.open(CdbServe.parse(new String[] {
            resized.toString(), "0", "--query-threads", Integer.toString(running + 1)})))
                .isInstanceOf(IllegalStateException.class);
        assertThat(resized).doesNotExist();
        try (Cdb _ = CdbServe.open(CdbServe.parse(new String[] {
            resized.toString(), "0", "--query-threads", Integer.toString(running)}))) {
            assertThat(QueryPool.shared().threads()).isEqualTo(running);
        }
    }

    private static boolean logForceThreadRuns(final Path store) {
        final String name = "cdb-log-force-" + store.getFileName();
        for (final Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().equals(name) && thread.isAlive()) {
                return true;
            }
        }
        return false;
    }

    private static Properties credentials() {
        final Properties properties = new Properties();
        properties.setProperty("user", "admin");
        properties.setProperty("password", "quest");
        return properties;
    }

    /// What an appender answers when a request waits for its rows to be durable.
    @FunctionalInterface
    private interface Durability {

        boolean await(long timeoutMillis) throws InterruptedException;
    }

    /// A backend for writes only: each appender records its rows and answers `awaitDurable` with the test's
    /// `durability`.
    private static final class DurableBackend implements CdbServer.Backend {

        // the three counters: incremented by request threads, read by the test thread
        final AtomicInteger appenders = new AtomicInteger();
        final AtomicInteger waits = new AtomicInteger();
        private final AtomicInteger rows = new AtomicInteger();
        // written by the test thread before its requests, read by the request threads that wait
        volatile Durability durability = _ -> true;
        // written by the request thread that waits, read by the test thread after that request is answered
        volatile long lastTimeoutMillis;
        // written by the request thread that takes an appender, read by the test thread while a request waits
        private volatile RecordingAppender last;

        @Override
        public Rows run(final String sql, final int maxRows, final Long maxLagMillis) {
            throw new UnsupportedOperationException("no queries here [sql=" + sql + ']');
        }

        @Override
        public CdbAppender appender() {
            appenders.incrementAndGet();
            final RecordingAppender recording = new RecordingAppender();
            last = recording;
            return new DurableAppender(recording, this);
        }

        RecordingAppender lastRecording() {
            return last;
        }

        int rows() {
            return rows.get();
        }
    }

    /// The forces of a table log that a store's log-force thread made, from JFR's `jdk.FileForce` events, recorded
    /// from the construction on.
    private static final class LogForces implements AutoCloseable {

        private final RecordingStream stream = new RecordingStream();
        private final CountDownLatch forced = new CountDownLatch(1);

        LogForces() {
            // a force on a fast device takes well under the event's default threshold of 20 ms
            stream.enable("jdk.FileForce").withoutThreshold();
            stream.onEvent("jdk.FileForce", event -> {
                final RecordedThread thread = event.getThread();
                final String path = event.getString("path");
                if (path != null && path.endsWith("_wal") && thread != null && thread.getJavaName() != null
                        && thread.getJavaName().startsWith("cdb-log-force-")) {
                    forced.countDown();
                }
            });
            stream.startAsync();
        }

        /// True once a log force was seen, waiting at most the test's timeout for JFR to hand it over.
        boolean awaitForce() throws InterruptedException {
            return forced.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            stream.close();
        }
    }

    /// A recording appender whose `awaitDurable` is its backend's `durability`.
    private static final class DurableAppender implements CdbAppender {

        private final RecordingAppender recording;
        private final DurableBackend backend;

        DurableAppender(final RecordingAppender recording, final DurableBackend backend) {
            this.recording = recording;
            this.backend = backend;
        }

        @Override
        public CdbAppender table(final String table) {
            recording.table(table);
            return this;
        }

        @Override
        public CdbAppender symbol(final String column, final String value) {
            recording.symbol(column, value);
            return this;
        }

        @Override
        public CdbAppender stringColumn(final String column, final String value) {
            recording.stringColumn(column, value);
            return this;
        }

        @Override
        public CdbAppender longColumn(final String column, final long value) {
            recording.longColumn(column, value);
            return this;
        }

        @Override
        public CdbAppender doubleColumn(final String column, final double value) {
            recording.doubleColumn(column, value);
            return this;
        }

        @Override
        public CdbAppender booleanColumn(final String column, final boolean value) {
            recording.booleanColumn(column, value);
            return this;
        }

        @Override
        public CdbAppender timestampColumn(final String column, final long micros) {
            recording.timestampColumn(column, micros);
            return this;
        }

        @Override
        public void at(final long micros) {
            recording.at(micros);
            backend.rows.incrementAndGet();
        }

        @Override
        public void flush() {
            recording.flush();
        }

        @Override
        public void discard() {
            recording.discard();
        }

        @Override
        public boolean awaitDurable(final long timeoutMillis) throws InterruptedException {
            backend.waits.incrementAndGet();
            backend.lastTimeoutMillis = timeoutMillis;
            return backend.durability.await(timeoutMillis);
        }

        @Override
        public void close() {
            recording.close();
        }
    }
}
