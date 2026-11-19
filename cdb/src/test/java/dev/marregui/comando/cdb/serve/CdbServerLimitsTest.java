package dev.marregui.comando.cdb.serve;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbCancel;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/// The bounds the server holds a client's work to, each shortened for the test: a query's deadline (408), a result's
/// rows (400), the bytes of the bodies held at once (503), the JDK server's own bounds set by the launcher; and log
/// lines that name a request by its path, never by its statement.
class CdbServerLimitsTest {

    /// Short enough for a test, long enough that a query of the fake answers well inside it.
    private static final long DEADLINE_MILLIS = 300;
    /// Bounds small enough to reach with a few rows and a short body, the wait short enough to keep the test fast.
    private static final int MAX_ROWS = 3;
    private static final long BUDGET_BYTES = 1_000;
    private static final long BODY_WAIT_MILLIS = 300;
    /// A fake query waits this long at most for its cancel, then answers: the bound of a deadline that never came.
    private static final long STUCK_MILLIS = 10_000;
    private static final CdbServer.Bounds BOUNDS = new CdbServer.Bounds(DEADLINE_MILLIS, MAX_ROWS, BUDGET_BYTES,
            BODY_WAIT_MILLIS);

    private Slow backend;
    private CdbServer server;

    /// A fresh backend and a server with the test's bounds and no secrets, on a free loopback port.
    @BeforeEach
    void startServer() {
        backend = new Slow();
        server = CdbServer.start(backend, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, BOUNDS);
    }

    /// Stops the server.
    @AfterEach
    void stopServer() {
        server.close();
    }

    /// An IN subquery's set is bounded as a result is: the executor's copy of the row limit is the server's.
    @Test
    void anInSubqueryIsBoundedAsAResultIs() {
        assertThat(Executor.IN_SUBQUERY_MAX_ROWS).isEqualTo(Limits.MAX_ROWS);
    }

    /// A query still running at its deadline is cancelled through its `CdbCancel` and answered 408 in QuestDB's error
    /// shape; a query that ends sooner is answered as usual.
    @Test
    void aQueryPastItsDeadlineIs408() throws IOException {
        backend.stuck = true;
        final long startNanos = System.nanoTime();

        final CdbServerAccessTest.Answer cancelled = exec("select * from stuck");

        final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        assertThat(cancelled.status()).isEqualTo(408);
        assertThat(cancelled.body()).startsWith("{\"query\":\"select * from stuck\",\"error\":\"cancel query past its"
                + " deadline [deadlineMillis=" + DEADLINE_MILLIS + ", ranMillis=").endsWith("]\",\"position\":-1}");
        assertThat(tookMillis).isGreaterThanOrEqualTo(DEADLINE_MILLIS).isLessThan(STUCK_MILLIS);
        assertThat(backend.cancels).hasSize(1).allSatisfy(cancel -> assertThat(cancel.cancelled()).isTrue());

        backend.stuck = false;
        assertThat(exec("select 1").status()).isEqualTo(200);
        assertThat(backend.cancels.get(1).cancelled()).isFalse();
    }

    /// A query asking for every row reads one past the bound: a result of more rows than the bound is answered 400
    /// rather than cut short, one of the bound's rows is answered whole; a `limit` under the bound passes as asked; a
    /// statement that changes the store is not counted.
    @Test
    void aResultOverTheRowLimitIs400() throws IOException {
        backend.rows = MAX_ROWS + 1;
        final CdbServerAccessTest.Answer over = exec("select * from big");
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.body()).isEqualTo("{\"query\":\"select * from big\",\"error\":\"refuse result over the row"
                + " limit [limit=" + MAX_ROWS + "]\",\"position\":-1}");

        backend.rows = MAX_ROWS;
        final CdbServerAccessTest.Answer whole = exec("select * from fits");
        assertThat(whole.status()).isEqualTo(200);
        assertThat(whole.body()).contains("\"count\":" + MAX_ROWS);

        backend.rows = 2;
        assertThat(CdbServerAccessTest.Raw.send(server.port(), "GET", "/exec?query=select%201&limit=2",
                Map.of("Host", host()), new byte[0]).status()).isEqualTo(200);
        backend.updated = true;
        assertThat(exec("update t set x = 1").status()).isEqualTo(200);

        assertThat(backend.asked).containsExactly(MAX_ROWS + 1, MAX_ROWS + 1, 2, MAX_ROWS + 1);
    }

    /// While one body holds most of the budget, another that needs more than is left waits its bounded time and is
    /// answered 503 without being read; once the first is written, the budget is free again.
    @Test
    void aBodyPastTheBudgetIs503() throws IOException {
        final byte[] body = lines(BUDGET_BYTES * 3 / 4);
        try (Socket holder = CdbServerAccessTest.Raw.open(server.port())) {
            final OutputStream out = holder.getOutputStream();
            CdbServerAccessTest.Raw.head(out, "POST", "/write", Map.of("Host", host()), body.length);
            out.write(body, 0, 1);
            out.flush();

            // the holder takes its share once the server has read its headers: a body sent before may still pass
            final List<Integer> statuses = new ArrayList<>();
            final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            int status = 0;
            while (status != 503 && System.nanoTime() - deadlineNanos < 0) {
                status = CdbServerAccessTest.Raw.send(server.port(), "POST", "/write", Map.of("Host", host()), body)
                        .status();
                statuses.add(status);
            }
            assertThat(status).as(statuses.toString()).isEqualTo(503);
            assertThat(statuses).allSatisfy(seen -> assertThat(seen).isIn(204, 503));

            out.write(body, 1, body.length - 1);
            out.flush();
            assertThat(CdbServerAccessTest.Raw.read(holder).status()).isEqualTo(204);
        }
        final CdbServerAccessTest.Answer again = CdbServerAccessTest.Raw.send(server.port(), "POST", "/write",
                Map.of("Host", host()), body);
        assertThat(again.status()).isEqualTo(204);
    }

    /// No log line of a refused, failed, timed-out or answered request carries its statement, and a failed one names
    /// its path.
    @Test
    void logLinesNameThePathNeverTheStatement() throws IOException {
        final String statement = "select 'kept-to-itself-7f3a' from t";
        final Logger logger = Logger.getLogger(CdbServer.class.getName());
        final Level level = logger.getLevel();
        final List<LogRecord> records = new CopyOnWriteArrayList<>();
        final Handler capture = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
                // records are kept in memory
            }

            @Override
            public void close() {
                // nothing is held
            }
        };
        capture.setLevel(Level.ALL);
        logger.setLevel(Level.ALL);
        logger.addHandler(capture);
        try {
            backend.crash = new IllegalStateException("broken");
            assertThat(exec(statement).status()).isEqualTo(500);
            backend.crash = null;
            backend.stuck = true;
            assertThat(exec(statement).status()).isEqualTo(408);
            backend.stuck = false;
            assertThat(exec(statement).status()).isEqualTo(200);
            assertThat(CdbServerAccessTest.Raw.exec(server.port(), "rebound.example:1", statement, Map.of()).status())
                    .isEqualTo(403);
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(level);
        }

        assertThat(records).anySatisfy(record -> assertThat(record.getMessage()).contains("[path=/exec]"));
        assertThat(records).allSatisfy(record -> {
            assertThat(record.getMessage()).doesNotContain("kept-to-itself");
            if (record.getThrown() != null) {
                assertThat(String.valueOf(record.getThrown().getMessage())).doesNotContain("kept-to-itself");
            }
        });
    }

    /// The launcher sets the JDK server's request time, response time and connection bounds from `Limits`, and keeps
    /// a bound its operator set on the command line.
    @Test
    void theJdkBoundsAreSetUnlessGiven() {
        final List<String> names = List.of("sun.net.httpserver.maxReqTime", "sun.net.httpserver.maxRspTime",
                "jdk.httpserver.maxConnections");
        final List<String> before = new ArrayList<>();
        for (final String name : names) {
            before.add(System.getProperty(name));
        }
        try {
            System.clearProperty(names.get(0));
            System.clearProperty(names.get(1));
            System.setProperty(names.get(2), "7");

            CdbServer.applyJdkLimits();

            assertThat(System.getProperty(names.get(0))).isEqualTo(Integer.toString(Limits.HTTP_MAX_REQUEST_SECONDS));
            assertThat(System.getProperty(names.get(1))).isEqualTo(Integer.toString(Limits.HTTP_MAX_RESPONSE_SECONDS));
            assertThat(System.getProperty(names.get(2))).isEqualTo("7");
        } finally {
            for (int i = 0; i < names.size(); i++) {
                if (before.get(i) == null) {
                    System.clearProperty(names.get(i));
                } else {
                    System.setProperty(names.get(i), before.get(i));
                }
            }
        }
    }

    private CdbServerAccessTest.Answer exec(final String sql) throws IOException {
        return CdbServerAccessTest.Raw.exec(server.port(), host(), sql, Map.of());
    }

    private String host() {
        return "127.0.0.1:" + server.port();
    }

    // line protocol of about `bytes` bytes, whole lines
    private static byte[] lines(final long bytes) {
        final String line = "t f=1i 1\n";
        return line.repeat((int) (bytes / line.length())).getBytes(StandardCharsets.UTF_8);
    }

    /// Answers a query of `rows` rows, or a change; with `stuck`, runs until its cancel or `STUCK_MILLIS`, then fails
    /// as the engine does; with `crash`, throws it. Records the rows each query asked for and each cancel.
    private static final class Slow implements CdbServer.Backend {

        final List<Integer> asked = new CopyOnWriteArrayList<>();
        final List<CdbCancel> cancels = new CopyOnWriteArrayList<>();
        // the four below: written by the test thread between requests, read by the request's virtual thread
        volatile boolean stuck;
        volatile boolean updated;
        volatile int rows = 1;
        volatile RuntimeException crash;

        @Override
        public Rows run(final String sql, final int maxRows, final Long maxLagMillis) {
            throw new UnsupportedOperationException("the server passes a cancel");
        }

        @Override
        public Rows run(final String sql, final long offset, final int maxRows, final Long maxLagMillis,
                final CdbCancel cancel) {
            asked.add(maxRows);
            cancels.add(cancel);
            if (crash != null) {
                throw crash;
            }
            if (stuck) {
                // the engine's drain checks its cancel at each page; this one at each millisecond
                final long startNanos = System.nanoTime();
                while (!cancel.cancelled()
                        && System.nanoTime() - startNanos < TimeUnit.MILLISECONDS.toNanos(STUCK_MILLIS)) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                throw new CdbException("query cancelled", -1);
            }
            if (updated) {
                return FakeRows.updated(1);
            }
            final List<List<Object>> cells = new ArrayList<>();
            for (long i = 0; i < Math.min(rows, maxRows); i++) {
                cells.add(List.of(i));
            }
            return FakeRows.query(-1, List.of("n", CdbType.LONG), cells);
        }

        @Override
        public CdbAppender appender() {
            return new RecordingAppender();
        }
    }
}
