package dev.marregui.comando.cdb.serve;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/// Concurrent `/write` requests over real loopback HTTP into a cdb under a temporary folder: every row written exactly
/// once, a bad body failing alone, tables written in parallel, a close with requests pending, the PostgreSQL wire's
/// and `Cdb.run`'s writes beside them, and the latency of small writes at 1 to 20 at once.
class CdbServerWritesTest {

    /// Thirty seconds bounds a hung run; every request here answers in milliseconds when the server works.
    private static final long TIMEOUT_SECONDS = 30L;
    /// Five seconds bounds the barrier two parallel commits meet at; they meet in milliseconds when not serialised.
    private static final long PARALLEL_SECONDS = 5L;
    private static final long T0 =micros(Instant.parse("2026-11-15T10:00:00Z"));
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
            .withZone(ZoneOffset.UTC);
    /// Half the server's admission bound: a request answered releases its permit just after the client saw the
    /// answer, so a client at the bound itself could see its next connection closed unanswered.
    private static final int IN_FLIGHT = CdbServer.MAX_REQUESTS / 2;

    private static Http http;

    @TempDir
    private Path root;

    private Cdb cdb;
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

    /// A fresh cdb and server per test, on a free loopback port.
    @BeforeEach
    void start() {
        cdb = Cdb.open(root);
        server = CdbServer.start(cdb, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    }

    /// Stops the server, then the cdb.
    @AfterEach
    void stop() {
        server.close();
        cdb.close();
    }

    /// `bodies` bodies of three lines at once into one table, or of five lines over five tables: each answers 204,
    /// and every row is there exactly once (the timestamp and the `s` symbol are unique per line).
    @ParameterizedTest(name = "{0} bodies over {1} tables")
    @CsvSource({"20, 1", "100, 1", "20, 5", "100, 5"})
    void concurrentBodiesWriteEveryRowOnce(final int bodies, final int tables) throws Exception {
        final int lines = tables == 1 ? 3 : 5;
        final Outcomes outcomes = post(bodies, w -> {
            final StringBuilder body = new StringBuilder();
            for (int k = 0; k < lines; k++) {
                body.append(line("t" + (w + k) % tables, w * 10 + k));
            }
            return body.toString();
        });

        assertThat(outcomes.failures).isEmpty();
        assertThat(outcomes.statuses).containsOnly(204).hasSize(bodies);
        final List<String> rows = new ArrayList<>();
        for (int t = 0; t < tables; t++) {
            rows.addAll(rows("t" + t));
        }
        final Set<String> expected = new HashSet<>();
        for (int w = 0; w < bodies; w++) {
            for (int k = 0; k < lines; k++) {
                expected.add((T0 + w * 10 + k) + "|w" + (w * 10 + k));
            }
        }
        assertThat(rows).hasSize(bodies * lines).containsExactlyInAnyOrderElementsOf(expected);
    }

    /// Among twenty good bodies, one with a malformed line and one whose second row the table refuses: those two
    /// answer 400 with the same body a lone request gets today, the malformed one writes nothing, the refused one
    /// keeps its first row, and every good body is written.
    @Test
    void aBadBodyAmongGoodOnesFailsAlone() throws Exception {
        assertThat(http.post(server.port(), "/write?precision=u", line("t", 0)).statusCode()).isEqualTo(204);
        final String malformed = "t,s=m v=1i " + (T0 + 500) + "\nt,s=m2 v=x " + (T0 + 501) + '\n';
        final String refused = line("t", 600) + "t,s=w601 v=\"text\" " + (T0 + 601) + '\n';
        final Outcomes outcomes = post(22, w -> switch (w) {
            case 0 -> malformed;
            case 1 -> refused;
            default -> line("t", w);
        });

        assertThat(outcomes.failures).isEmpty();
        assertThat(outcomes.statuses).filteredOn(s -> s == 204).hasSize(20);
        assertThat(rows("t")).hasSize(1 + 20 + 1).contains((T0 + 600) + "|w600")
                .doesNotContain((T0 + 500) + "|m", (T0 + 601) + "|w601");
        final IlpException malformedAlone = catchThrowableOfType(IlpException.class,
                () -> IlpParser.parse(malformed, new RecordingAppender(), Precision.MICROS, 0L));
        assertThat(outcomes.answers.get(0)).isEqualTo(
                "400 " + JsonWriter.writeError(malformedAlone.getMessage(), malformedAlone.line()));
        final IlpException refusedAlone;
        try (CdbAppender appender = cdb.appender()) {
            refusedAlone = catchThrowableOfType(IlpException.class,
                    () -> IlpParser.parse(refused, appender, Precision.MICROS, 0L));
        }
        assertThat(refusedAlone.line()).isEqualTo(2);
        assertThat(outcomes.answers.get(1)).isEqualTo(
                "400 " + JsonWriter.writeError(refusedAlone.getMessage(), refusedAlone.line()));
    }

    /// A close while one commit runs and five requests wait behind it: every one is answered 204 and written, and the
    /// closed server serves no more.
    @Test
    void closeAnswersTheWritesPending() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        // the first commit closes its appender only once released, holding the next commit back
        final FakeBackend backend = new FakeBackend(n -> n == 0 ? () -> awaitReleased(release) : () -> { });
        final List<RecordingAppender> appenders = backend.appenders;
        final Outcomes outcomes = new Outcomes();
        final CountDownLatch done = new CountDownLatch(6);
        // closed by the closer thread; the try only covers a failure before it (close is idempotent)
        try (CdbServer closing = CdbServer.start(backend,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            Thread.ofVirtual().name("close-test-0").start(() -> outcomes.post(closing.port(), 0, line("t", 0), null,
                    done, null));
            CommitQueueTest.awaitTrue(() -> appenders.size() == 1);
            for (int w = 1; w <= 5; w++) {
                final int n = w;
                Thread.ofVirtual().name("close-test-" + w).start(() -> outcomes.post(closing.port(), n, line("t", n),
                        null, done, null));
            }
            CommitQueueTest.awaitTrue(() -> closing.queued("t") == 5);
            final Thread closer = Thread.ofVirtual().name("close-test-closer").start(closing::close);
            release.countDown();

            assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(closer.join(Duration.ofSeconds(TIMEOUT_SECONDS))).isTrue();
            assertThat(catchThrowableOfType(IOException.class,
                    () -> http.post(closing.port(), "/write?precision=u", line("t", 99)))).isNotNull();
        }
        assertThat(outcomes.failures).isEmpty();
        assertThat(outcomes.statuses).containsOnly(204).hasSize(6);
        assertThat(appenders).hasSize(2);
        assertThat(appenders.stream().flatMap(a -> a.flushed.stream()).filter(c -> c.startsWith("at(")).toList())
                .hasSize(6);
    }

    /// Two requests into two tables commit at once: each commit's appender closes only when the other's does too, at
    /// a barrier that meets only when the two run in parallel; serialised, the first would wait out the barrier and
    /// fail.
    @Test
    void twoTablesOverHttpCommitInParallel() throws Exception {
        final CyclicBarrier both = new CyclicBarrier(2);
        final FakeBackend backend = new FakeBackend(_ -> () -> {
            try {
                both.await(PARALLEL_SECONDS, TimeUnit.SECONDS);
            } catch (final Exception e) {
                throw new IllegalStateException("commits not in parallel", e);
            }
        });
        final long start = System.nanoTime();
        final Outcomes outcomes;
        try (CdbServer parallel = CdbServer.start(backend,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            outcomes = post(parallel.port(), 2, w -> line(w == 0 ? "a" : "b", w));
        }
        final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(outcomes.failures).isEmpty();
        assertThat(outcomes.statuses).containsOnly(204).hasSize(2);
        assertThat(both.isBroken()).isFalse();
        assertThat(backend.appenders).hasSize(2);
        assertThat(tookMillis).isLessThan(TimeUnit.SECONDS.toMillis(PARALLEL_SECONDS));
    }

    /// The PostgreSQL wire's INSERT and `Cdb.run`'s UPDATE take the same tables' writers as `/write`'s committers,
    /// with bodies touching the two tables in both orders: all of them finish, and every inserted row is there.
    @Test
    void sqlWritesBesideHttpWritesDoNotDeadlock() throws Exception {
        assertThat(http.post(server.port(), "/write?precision=u", line("a", 0) + line("b", 0)).statusCode())
                .isEqualTo(204);
        final Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        final CountDownLatch sqlDone = new CountDownLatch(8);
        try (PgServer pg = PgServer.start(cdb, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            for (int i = 0; i < 8; i++) {
                final int n = i;
                Thread.ofVirtual().name("sql-test-" + i).start(() -> {
                    try (Connection connection = DriverManager.getConnection(
                            "jdbc:postgresql://127.0.0.1:" + pg.port() + "/qdb", credentials());
                         Statement statement = connection.createStatement()) {
                        for (int k = 0; k < 5; k++) {
                            final String table = (n + k) % 2 == 0 ? "a" : "b";
                            statement.execute("INSERT INTO " + table + " (s, v, timestamp) VALUES ('q" + (n * 10 + k)
                                    + "', 1, '" + ISO.format(Instant.EPOCH.plus(T0 + 1_000 + n * 10 + k,
                                    ChronoUnit.MICROS)) + "')");
                            cdb.run("UPDATE " + (table.equals("a") ? "b" : "a") + " SET v = 2");
                        }
                    } catch (final SQLException | RuntimeException e) {
                        failures.add(e);
                    } finally {
                        sqlDone.countDown();
                    }
                });
            }
            final Outcomes outcomes = post(40, w -> w % 2 == 0
                    ? line("a", 2_000 + w) + line("b", 2_000 + w)
                    : line("b", 2_000 + w) + line("a", 2_000 + w));

            assertThat(sqlDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(failures).isEmpty();
            assertThat(outcomes.failures).isEmpty();
            assertThat(outcomes.statuses).containsOnly(204).hasSize(40);
        }
        assertThat(rows("a").size() + rows("b").size()).isEqualTo(2 + 40 + 2 * 40);
    }

    /// The latency of 1, 5, 10 and 20 one-line writes sent at once over five tables, 20 rounds each: the median and
    /// the most of every request's time, printed for the record; every request answers 204.
    @Test
    void measuresSmallWritesAtOnce() throws Exception {
        for (final int writers : new int[] {1, 5, 10, 20}) {
            final List<Long> micros = new ArrayList<>();
            for (int round = 0; round < 20; round++) {
                final int base = 100_000 * writers + 100 * round;
                final Outcomes outcomes = post(writers, w -> line("m" + w % 5, base + w));
                assertThat(outcomes.failures).isEmpty();
                assertThat(outcomes.statuses).containsOnly(204);
                micros.addAll(outcomes.micros);
            }
            final long[] sorted = micros.stream().mapToLong(Long::longValue).sorted().toArray();
            System.out.println("small writes at once [writers=" + writers + ", requests=" + sorted.length
                    + ", medianMillis=" + sorted[sorted.length / 2] / 1_000.0 + ", maxMillis="
                    + sorted[sorted.length - 1] / 1_000.0 + ", p90Millis="
                    + sorted[(int) (sorted.length * 0.9)] / 1_000.0 + ']');
            assertThat(Arrays.stream(sorted).min().orElseThrow()).isPositive();
        }
    }

    private static String line(final String table, final int n) {
        return table + ",s=w" + n + " v=" + n + "i " + (T0 + n) + '\n';
    }

    // the table's rows as "timestamp|s", in no particular order
    private List<String> rows(final String table) {
        final CdbResult result = cdb.run("select timestamp, s from " + table);
        final List<String> rows = new ArrayList<>(result.rowCount());
        for (int r = 0; r < result.rowCount(); r++) {
            rows.add(result.getLong(r, 0) + "|" + result.getText(r, 1));
        }
        return rows;
    }

    private Outcomes post(final int bodies, final IntFunction<String> body) throws Exception {
        return post(server.port(), bodies, body);
    }

    // posts body(w) for w in [0, bodies) at once to port, at most IN_FLIGHT in flight, and waits for every answer
    private static Outcomes post(final int port, final int bodies, final IntFunction<String> body) throws Exception {
        final Outcomes outcomes = new Outcomes();
        final CountDownLatch done = new CountDownLatch(bodies);
        final CyclicBarrier go = new CyclicBarrier(bodies);
        final Semaphore inFlight = new Semaphore(IN_FLIGHT);
        for (int w = 0; w < bodies; w++) {
            final int n = w;
            Thread.ofVirtual().name("write-test-" + w).start(() -> outcomes.post(port, n, body.apply(n), go, done,
                    inFlight));
        }
        assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        return outcomes;
    }

    private static void awaitReleased(final CountDownLatch release) {
        try {
            if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
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

    /// Recording appenders behind a server with no SQL; appender `n` (from 0) runs `onClose.apply(n)` on its close,
    /// after the flush.
    private static final class FakeBackend implements CdbServer.Backend {

        final List<RecordingAppender> appenders = new CopyOnWriteArrayList<>();
        private final IntFunction<Runnable> onClose;

        FakeBackend(final IntFunction<Runnable> onClose) {
            this.onClose = onClose;
        }

        @Override
        public Rows run(final String sql, final int maxRows) {
            throw new UnsupportedOperationException("refuse sql in this test [sql=" + sql + ']');
        }

        @Override
        public synchronized CdbAppender appender() {
            final RecordingAppender appender = new RecordingAppender(0, onClose.apply(appenders.size()));
            appenders.add(appender);
            return appender;
        }
    }

    /// What the request threads saw, for the test thread to assert once every one answered.
    private static final class Outcomes {

        final Queue<Integer> statuses = new ConcurrentLinkedQueue<>();
        final Map<Integer, String> answers = new ConcurrentHashMap<>();
        final Queue<Long> micros = new ConcurrentLinkedQueue<>();
        final Queue<Throwable> failures = new ConcurrentLinkedQueue<>();

        // waits at go with the others, then posts body as request n; inFlight bounds the requests at once, if set
        void post(final int port, final int n, final String body, final CyclicBarrier go, final CountDownLatch done,
                final Semaphore inFlight) {
            try {
                if (go != null) {
                    go.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }
                if (inFlight != null) {
                    inFlight.acquire();
                }
                try {
                    final long start = System.nanoTime();
                    final HttpResponse<String> response = http.post(port, "/write?precision=u", body);
                    micros.add((System.nanoTime() - start) / 1_000L);
                    statuses.add(response.statusCode());
                    answers.put(n, response.statusCode() + " " + response.body());
                } finally {
                    if (inFlight != null) {
                        inFlight.release();
                    }
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                failures.add(e);
            } catch (final Exception e) {
                failures.add(e);
            } finally {
                done.countDown();
            }
        }
    }
}
