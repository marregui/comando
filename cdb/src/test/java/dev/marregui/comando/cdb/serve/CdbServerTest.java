package dev.marregui.comando.cdb.serve;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/// The server over real loopback HTTP, in front of a fake backend: statuses, the JSON relayed, the parameters passed
/// on, and a `/write` body applied whole or not at all.
class CdbServerTest {

    private static Http http;

    private FakeBackend backend;
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

    /// A fresh backend and server per test, on a free loopback port.
    @BeforeEach
    void startServer() {
        backend = new FakeBackend();
        server = CdbServer.start(backend, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    }

    /// Stops the server.
    @AfterEach
    void stopServer() {
        server.close();
    }

    /// A query's rows as QuestDB's JSON, the limit passed on, `count` and `nm` accepted.
    @Test
    void execAnswersRowsAndPassesTheLimit() throws IOException, InterruptedException {
        final String query = "select x, x * 0.5 h from long_sequence(5)";
        backend.answer = FakeRows.query(-1, List.of("x", CdbType.LONG, "h", CdbType.DOUBLE),
                List.of(List.of(1L, 0.5), List.of(2L, 1.0)));

        final HttpResponse<String> response = http.exec(server.port(), query, "&limit=2&count=true&nm=true");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json; charset=utf-8");
        assertThat(response.body()).isEqualTo(
                "{\"query\":\"select x, x * 0.5 h from long_sequence(5)\",\"columns\":[{\"name\":\"x\","
                        + "\"type\":\"LONG\"},"
                        + "{\"name\":\"h\",\"type\":\"DOUBLE\"}],\"timestamp\":-1,\"dataset\":[[1,0.5],[2,1.0]],"
                        + "\"count\":2}");
        assertThat(backend.calls).containsExactly(query + " | maxRows=2");
    }

    /// `limit=0,n`, the form the kit's `qdb` sends on every query, bounds the rows as `limit=n` does; `lo,hi` keeps
    /// QuestDB's rows lo to hi counted from 1; a negative bound is refused.
    @Test
    void execTakesTheRangeLimitFromZero() throws IOException, InterruptedException {
        backend.answer = FakeRows.updated(0);

        assertThat(http.exec(server.port(), "select 1", "&limit=0,10000000").statusCode()).isEqualTo(200);
        assertThat(backend.calls).containsExactly("select 1 | maxRows=10000000");
        assertThat(CdbServer.maxRows("0, 5")).isEqualTo(5);
        assertThat(CdbServer.maxRows("2,4")).isEqualTo(3);
        assertThat(CdbServer.maxRows("-3")).isEqualTo(-1);
        assertThat(CdbServer.maxRows("0,x")).isEqualTo(0);
    }

    /// Concurrent `/write` requests commit each table through its own appender, one commit per table at a time, so two
    /// bodies that touch the same tables in opposite orders cannot each hold a writer the other waits for.
    @Test
    void writesHoldOneAppenderPerTableAtATime() throws Exception {
        final StringBuilder body = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            body.append("a n=").append(i).append("i\nb n=").append(i).append("i\n");
        }
        final List<Thread> writers = new ArrayList<>();
        final AtomicInteger accepted = new AtomicInteger();
        for (int i = 0; i < 8; i++) {
            writers.add(Thread.ofVirtual().name("write-test-" + i).start(() -> {
                try {
                    if (http.post(server.port(), "/write", body.toString()).statusCode() == 204) {
                        accepted.incrementAndGet();
                    }
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                } catch (final InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        for (final Thread writer : writers) {
            assertThat(writer.join(Duration.ofSeconds(30))).isTrue();
        }
        assertThat(accepted.get()).isEqualTo(8);
        assertThat(backend.mostOpen.get()).isBetween(1, 2);
        assertThat(backend.appenders).allSatisfy(a -> assertThat(a.flushed.stream()
                .filter(c -> c.startsWith("table(")).distinct().count()).isEqualTo(1L));
        assertThat(backend.appenders.stream().mapToLong(a -> a.flushed.stream()
                .filter(c -> c.startsWith("at(")).count()).sum()).isEqualTo(8L * 2L * 20_000L);
    }

    /// Without `limit` the query runs unbounded.
    @Test
    void execWithoutLimitRunsUnbounded() throws IOException, InterruptedException {
        backend.answer = FakeRows.updated(4);

        final HttpResponse<String> response = http.exec(server.port(), "update t set x = 1", "");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"dml\":\"OK\",\"updated\":4}");
        assertThat(backend.calls).containsExactly("update t set x = 1 | maxRows=0");
    }

    /// A refused query answers 400 with QuestDB's error JSON and the position the parser gave.
    @Test
    void execRefusalIs400WithItsPosition() throws IOException, InterruptedException {
        backend.failure = new CdbException("Invalid column: nosuch", 7);

        final HttpResponse<String> response = http.exec(server.port(), "select nosuch from comando_usage", "");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).isEqualTo(
                "{\"query\":\"select nosuch from comando_usage\",\"error\":\"Invalid column: nosuch\",\"position\":7}");
    }

    /// A failure that is not a refusal answers 500 and leaves the server serving.
    @Test
    void execFailureIs500AndTheServerKeepsServing() throws IOException, InterruptedException {
        backend.crash = new IllegalStateException("broken");

        assertThat(http.exec(server.port(), "select 1", "").statusCode()).isEqualTo(500);

        backend.crash = null;
        backend.answer = FakeRows.ddl();
        assertThat(http.exec(server.port(), "create table t (x long)", "").body()).isEqualTo("{\"ddl\":\"OK\"}");
    }

    /// No query and a blank one answer 200 with QuestDB's empty-query error; a limit that is no number is refused;
    /// the backend runs for none of them.
    @Test
    void execRefusesBadParameters() throws IOException, InterruptedException {
        assertThat(http.get(server.port(), "/exec").statusCode()).isEqualTo(200);
        assertThat(http.get(server.port(), "/exec?query=%20").statusCode()).isEqualTo(200);
        assertThat(http.exec(server.port(), "select 1", "&limit=ten").statusCode()).isEqualTo(400);
        assertThat(backend.calls).isEmpty();
    }

    /// `max_lag_ms=n` reaches the backend as the query's read lag; a request without it leaves the lag to the store.
    @Test
    void execPassesTheReadLagToTheBackend() throws IOException, InterruptedException {
        backend.answer = FakeRows.query(-1, List.of("n", CdbType.LONG), List.of(List.of(1L)));

        assertThat(http.exec(server.port(), "select 1", "&max_lag_ms=250").statusCode()).isEqualTo(200);
        assertThat(http.exec(server.port(), "select 2", "&max_lag_ms=0").statusCode()).isEqualTo(200);
        assertThat(http.exec(server.port(), "select 3", "").statusCode()).isEqualTo(200);

        assertThat(backend.calls).containsExactly("select 1 | maxRows=0 | maxLagMillis=250",
                "select 2 | maxRows=0 | maxLagMillis=0", "select 3 | maxRows=0");
    }

    /// A negative, a fractional, a non-numeric and an empty `max_lag_ms` are refused as `limit` is, with QuestDB's
    /// error shape and the backend never run.
    @Test
    void execRefusesABadReadLag() throws IOException, InterruptedException {
        for (final String bad : List.of("-1", "1.5", "soon", "")) {
            final HttpResponse<String> response = http.exec(server.port(), "select 1", "&max_lag_ms=" + bad);

            assertThat(response.statusCode()).as(bad).isEqualTo(400);
            assertThat(response.body()).as(bad).isEqualTo("{\"query\":\"select 1\",\"error\":"
                    + "\"invalid max_lag_ms [max_lag_ms=" + bad + "]\",\"position\":-1}");
        }
        assertThat(backend.calls).isEmpty();
    }

    /// Each endpoint takes one method and its own path only.
    @Test
    void refusesOtherMethodsAndPaths() throws IOException, InterruptedException {
        assertThat(http.post(server.port(), "/exec?query=select%201", "").statusCode()).isEqualTo(405);
        assertThat(http.get(server.port(), "/write").statusCode()).isEqualTo(405);
        assertThat(http.get(server.port(), "/exec/more?query=select%201").statusCode()).isEqualTo(404);
        assertThat(http.get(server.port(), "/").statusCode()).isEqualTo(404);
        assertThat(backend.calls).isEmpty();
    }

    /// A good body reaches each of its tables' appenders whole, flushed and closed, and answers 204.
    @Test
    void writeAppliesEveryLineAndAnswers204() throws IOException, InterruptedException {
        final HttpResponse<String> response = http.post(server.port(), "/write?precision=u", "a f=1i 1\nb f=2i 2\n");

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(backend.appenders).hasSize(2);
        assertThat(backend.appenders.stream().map(a -> List.copyOf(a.flushed)).toList()).containsExactlyInAnyOrder(
                List.of("table(a)", "long(f=1)", "at(1)"), List.of("table(b)", "long(f=2)", "at(2)"));
        assertThat(backend.appenders).allSatisfy(a -> assertThat(a.closes()).isEqualTo(1));
    }

    /// A malformed line refuses the whole body with the parser's error and writes none of it.
    @Test
    void writeWithAMalformedLineWritesNothing() throws IOException, InterruptedException {
        final String body = "a f=1i 1\nb f=x 2\n";
        final IlpException refusal = catchThrowableOfType(IlpException.class,
                () -> IlpParser.parse(body, new RecordingAppender(), Precision.MICROS, 0L));

        final HttpResponse<String> response = http.post(server.port(), "/write?precision=u", body);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).isEqualTo(JsonWriter.writeError(refusal.getMessage(), 2));
        assertThat(backend.appenders).isEmpty();
    }

    /// A row the store refuses answers 400 with its line, as a lone parse into that appender names it; the appender is
    /// closed, and the rows before it are discarded, as QuestDB writes nothing of the body.
    @Test
    void writeRefusedByTheStoreIs400() throws IOException, InterruptedException {
        backend.refuseAt = 2;
        final String body = "a f=1i 1\na f=2i 2\n";
        final IlpException refusal = catchThrowableOfType(IlpException.class,
                () -> IlpParser.parse(body, new RecordingAppender(2), Precision.MICROS, 0L));

        final HttpResponse<String> response = http.post(server.port(), "/write?precision=u", body);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).isEqualTo(JsonWriter.writeError(refusal.getMessage(), refusal.line()));
        final RecordingAppender appender = backend.appenders.getFirst();
        assertThat(appender.closes()).isEqualTo(1);
        assertThat(appender.flushed).doesNotContain("at(1)");
    }

    /// A precision QuestDB does not name, and a body over the limit, are refused before any appender exists.
    @Test
    void writeRefusesBadPrecisionAndOversizedBody() throws IOException, InterruptedException {
        assertThat(http.post(server.port(), "/write?precision=us", "a f=1i 1").statusCode()).isEqualTo(400);

        final byte[] oversized = new byte[CdbServer.MAX_BODY_BYTES + 1];
        assertThat(http.post(server.port(), "/write", oversized).statusCode()).isEqualTo(413);
        assertThat(backend.appenders).isEmpty();
    }

    /// The real port is served; close is idempotent and stops serving.
    @Test
    void closeIsIdempotentAndStopsServing() throws IOException, InterruptedException {
        final int port = server.port();
        backend.answer = FakeRows.ddl();
        assertThat(port).isPositive();
        assertThat(http.exec(port, "create table t (x long)", "").statusCode()).isEqualTo(200);

        server.close();
        server.close();

        assertThat(server.port()).isEqualTo(port);
        assertThatThrownBy(() -> http.exec(port, "create table t (x long)", "")).isInstanceOf(IOException.class);
    }

    /// Answers a set result or throws a set exception, records each call, and hands out recording appenders.
    private static final class FakeBackend implements CdbServer.Backend {

        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<RecordingAppender> appenders = new CopyOnWriteArrayList<>();
        // the four below: written by the test thread before a request, read by the request's virtual thread
        volatile Rows answer;
        volatile CdbException failure;
        volatile RuntimeException crash;
        volatile int refuseAt;
        // appenders open at once, and the most seen: written by request threads, read by the test thread
        final AtomicInteger open = new AtomicInteger();
        final AtomicInteger mostOpen = new AtomicInteger();

        @Override
        public Rows run(final String sql, final int maxRows, final Long maxLagMillis) {
            calls.add(sql + " | maxRows=" + maxRows + (maxLagMillis == null ? "" : " | maxLagMillis=" + maxLagMillis));
            if (failure != null) {
                throw failure;
            }
            if (crash != null) {
                throw crash;
            }
            return answer;
        }

        @Override
        public CdbAppender appender() {
            mostOpen.accumulateAndGet(open.incrementAndGet(), Math::max);
            final RecordingAppender appender = new RecordingAppender(refuseAt, open::decrementAndGet);
            appenders.add(appender);
            return appender;
        }
    }
}
