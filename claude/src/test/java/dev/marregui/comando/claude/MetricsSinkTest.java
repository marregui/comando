package dev.marregui.comando.claude;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// [MetricsSink] sends the metrics events to a QuestDB, here a JDK HTTP server on loopback that answers as QuestDB
/// does (record 146).
///
/// - Creates the tables on the first write to a server, then sends the event's line.
/// - Creates the tables again, once, when a write is refused for a missing table.
/// - Asks the endpoint to start a server at most once a minute while none answers.
/// - Asks the endpoint to start a server when the one it named refuses the connection.
/// - Drops and counts the events a full queue cannot take.
/// - Counts at the close, within its bound, the batch it cut off and the events still queued, with no notice.
/// - Counts at the close a batch of two writes: the one it interrupted and the one never tried.
/// - Tells the user of an outage once, and of the next one again after a batch was taken.
class MetricsSinkTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration WITHIN = Duration.ofSeconds(5);

    private final AtomicLong nanos = new AtomicLong();
    private final RecordingSink notices = new RecordingSink();

    /// The first event reaches the server as its line, after the five tables were created.
    @Test
    void anEventArrivesAsItsLineAfterTheTables() throws Exception {
        try (final FakeQuestDb server = new FakeQuestDb();
                final MetricsSink sink = sink(Endpoint.at(server.uri()))) {
            sink.offer(usage("five_hour"));
            await().atMost(WITHIN).until(() -> server.lines.size() == 1);
            assertThat(server.lines).containsExactly(usageLine("five_hour"));
            assertThat(server.ddls()).isEqualTo(MetricsLines.DDL.size());
            assertThat(sink.sentLines()).isEqualTo(1);
            assertThat(sink.failedLines()).isZero();
        }
    }

    /// A write refused because a table is missing makes the tables again once and is sent again once.
    @Test
    void aMissingTableIsCreatedAgainOnce() throws Exception {
        try (final FakeQuestDb server = new FakeQuestDb();
                final MetricsSink sink = sink(Endpoint.at(server.uri()))) {
            server.refuseAsMissing.set(1);
            sink.offer(usage("five_hour"));
            await().atMost(WITHIN).until(() -> sink.sentLines() == 1);
            assertThat(server.lines).containsExactly(usageLine("five_hour"));
            assertThat(server.posts.get()).isEqualTo(2);
            assertThat(server.ddls()).as("once at the start, once after the refusal")
                    .isEqualTo(2 * MetricsLines.DDL.size());
        }
    }

    /// With no server, the first batch asks the endpoint to start one; batches within the minute after do not, the
    /// first batch a minute later does. Each failed batch is counted.
    @Test
    void aDownServerIsAskedForAtMostOnceAMinute() {
        final Endpoint none = new Endpoint();
        try (final MetricsSink sink = sink(none)) {
            sink.offer(usage("a"));
            await().atMost(WITHIN).until(() -> sink.failedLines() == 1);
            assertThat(none.ensured.get()).isEqualTo(1);

            nanos.addAndGet(TimeUnit.SECONDS.toNanos(59));
            sink.offer(usage("b"));
            await().atMost(WITHIN).until(() -> sink.failedLines() == 2);
            assertThat(none.ensured.get()).as("within the minute").isEqualTo(1);

            nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
            sink.offer(usage("c"));
            await().atMost(WITHIN).until(() -> sink.failedLines() == 3);
            assertThat(none.ensured.get()).as("a minute after").isEqualTo(2);
            assertThat(sink.sentLines()).isZero();
        }
    }

    /// The server the endpoint names refuses the connection: the endpoint is asked to start one, and the batch goes
    /// to the server it returns.
    @Test
    void aRefusedConnectionAsksForAServer() throws Exception {
        final URI refused;
        try (final ServerSocket gone = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            refused = URI.create("http://127.0.0.1:" + gone.getLocalPort());
        }
        try (final FakeQuestDb server = new FakeQuestDb()) {
            final Endpoint endpoint = Endpoint.at(refused);
            endpoint.up.set(server.uri());
            try (final MetricsSink sink = sink(endpoint)) {
                sink.offer(usage("five_hour"));
                await().atMost(WITHIN).until(() -> sink.sentLines() == 1);
                assertThat(server.lines).containsExactly(usageLine("five_hour"));
                assertThat(endpoint.ensured.get()).isEqualTo(1);
            }
        }
    }

    /// While the errand is held, the queue takes its capacity and drops and counts the five events beyond it.
    @Test
    void aFullQueueDropsAndCounts() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final QuestDbEndpoint held = new QuestDbEndpoint() {
            @Override
            public Optional<URI> ensureUp() {
                return Optional.empty();
            }

            @Override
            public Optional<URI> live() {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (final InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                return Optional.empty();
            }
        };
        try (final MetricsSink sink = sink(held)) {
            sink.offer(usage("first"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).as("the errand holds the first event").isTrue();
            for (int i = 0; i < MetricsSink.CAPACITY + 5; i++) {
                sink.offer(usage("w" + i));
            }
            assertThat(sink.droppedEvents()).isEqualTo(5);
            release.countDown();
        }
    }

    /// A close while the server does not answer waits its bound, then counts the batch being sent as failed and the
    /// events still queued as dropped, sends nothing more and tells no notice.
    @Test
    void aCloseCountsWhatItCutsOff() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch never = new CountDownLatch(1);
        final QuestDbEndpoint held = new QuestDbEndpoint() {
            @Override
            public Optional<URI> ensureUp() {
                return Optional.empty();
            }

            @Override
            public Optional<URI> live() {
                entered.countDown();
                try {
                    never.await(30, TimeUnit.SECONDS);
                } catch (final InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                return Optional.empty();
            }
        };
        final MetricsSink sink = sink(held);
        try {
            sink.offer(usage("first"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).as("the errand sends the first event").isTrue();
            for (int i = 0; i < 10; i++) {
                sink.offer(usage("q" + i));
            }
            final long startNanos = System.nanoTime();
            sink.close();
            assertThat(Duration.ofNanos(System.nanoTime() - startNanos)).as("the close's bound")
                    .isLessThan(MetricsSink.CLOSE_WAIT.multipliedBy(2));
            assertThat(sink.sentLines()).isZero();
            assertThat(sink.failedLines()).as("the batch being sent").isEqualTo(1);
            assertThat(sink.droppedEvents()).as("the events still queued").isEqualTo(10);
            assertThat(notices.of(Intent.Notify.class)).as("the state loop is closing too").isEmpty();
        } finally {
            sink.close();
        }
    }

    /// A close while a batch of two writes is on its first, which the server holds, interrupts that write and never
    /// tries the second: both are counted as failed, the events still queued as dropped, and no notice is told.
    @Test
    void aCloseCountsTheWriteItInterruptsAndTheOneNeverTried() throws Exception {
        final CountDownLatch first = new CountDownLatch(1);
        final CountDownLatch second = new CountDownLatch(1);
        try (final FakeQuestDb server = new FakeQuestDb()) {
            final MetricsSink sink = sink(Endpoint.at(server.uri()));
            try {
                server.hold.set(first);
                sink.offer(usage("warm"));
                assertThat(server.arrived.tryAcquire(5, TimeUnit.SECONDS)).as("the first write held").isTrue();
                server.hold.set(second);
                // queued behind the held write: one event of BATCH_LINES + 2 lines, so the batch is full at its first
                // event; a thousand one-line events race BATCH_WAIT (a CI runner mapped 785 of them within it)
                sink.offer(queues(MetricsSink.BATCH_LINES + 2));
                first.countDown();
                assertThat(server.arrived.tryAcquire(5, TimeUnit.SECONDS)).as("the batch's first write held").isTrue();
                for (int i = 0; i < 5; i++) {
                    sink.offer(usage("q" + i));
                }
                sink.close();
                assertThat(sink.sentLines()).as("the first write").isEqualTo(1);
                assertThat(sink.failedLines()).as("the write interrupted and the one never tried")
                        .isEqualTo(MetricsSink.BATCH_LINES + 2);
                assertThat(sink.droppedEvents()).as("the events still queued").isEqualTo(5);
                assertThat(server.posts.get()).as("the second write never tried").isEqualTo(2);
                assertThat(notices.of(Intent.Notify.class)).as("the state loop is closing too").isEmpty();
            } finally {
                second.countDown();
                sink.close();
            }
        }
    }

    /// With no server, the first failed batch is a notice that the rows are lost and the next failed batch is none;
    /// a batch the server takes ends the outage, and the first failure after it is a notice again.
    @Test
    void anOutageIsToldOnceAndTheNextOneAgain() throws Exception {
        final Endpoint endpoint = new Endpoint();
        try (final FakeQuestDb server = new FakeQuestDb();
                final MetricsSink sink = sink(endpoint)) {
            sink.offer(usage("a"));
            await().atMost(WITHIN).until(() -> sink.failedLines() == 1);
            sink.offer(usage("b"));
            await().atMost(WITHIN).until(() -> sink.failedLines() == 2);
            assertThat(notices.of(Intent.Notify.class)).singleElement().satisfies(n -> {
                assertThat(n.level()).isEqualTo(Notice.Level.WARN);
                assertThat(n.text()).isEqualTo("Cannot reach QuestDB, the metrics rows are lost [server=none,"
                        + " failedLines=1]");
            });

            endpoint.live.set(server.uri());
            sink.offer(usage("c"));
            await().atMost(WITHIN).until(() -> sink.sentLines() == 1);
            assertThat(notices.of(Intent.Notify.class)).as("a recovery is no notice").hasSize(1);

            endpoint.live.set(null);
            server.down();
            sink.offer(usage("d"));
            final Intent.Notify next = notices.await(Intent.Notify.class, n -> n.text().contains("failedLines=3"),
                    WITHIN);
            assertThat(next.text()).contains("the metrics rows are lost");
            assertThat(notices.of(Intent.Notify.class)).as("the next outage").hasSize(2);
        }
    }

    /// A `usage` event of `window`, at 2026-10-28T10:00:00Z.
    static ObjectNode usage(final String window) {
        return JSON.createObjectNode().put("ts", "2026-10-28T10:00:00Z").put("event", "usage").put("provider", "claude")
                .put("window", window).put("pct", 1.0);
    }

    /// A `queues` event of `count` queues, each a line of its own.
    private static ObjectNode queues(final int count) {
        final ObjectNode event = JSON.createObjectNode().put("ts", "2026-10-28T10:00:00Z").put("event", "queues");
        final ArrayNode read = event.putArray("queues");
        for (int i = 0; i < count; i++) {
            read.addObject().put("name", "q" + i).put("depth", 0).put("peak", 0);
        }
        return event;
    }

    private static String usageLine(final String window) {
        return "comando_usage,instance=/data,provider=claude,window_name=" + window + " pct=1.0 1793181600000000000";
    }

    private MetricsSink sink(final QuestDbEndpoint qdb) {
        return new MetricsSink(qdb, new MetricsLines(JSON, "/data"), JSON, nanos::get, notices);
    }

    /// An endpoint that names the server it is given and counts the starts asked of it.
    static final class Endpoint implements QuestDbEndpoint {

        final AtomicInteger ensured = new AtomicInteger();
        final AtomicReference<URI> live = new AtomicReference<>();
        final AtomicReference<URI> up = new AtomicReference<>();

        static Endpoint at(final URI uri) {
            final Endpoint e = new Endpoint();
            e.live.set(uri);
            return e;
        }

        @Override
        public Optional<URI> ensureUp() {
            ensured.incrementAndGet();
            return Optional.ofNullable(up.get());
        }

        @Override
        public Optional<URI> live() {
            return Optional.ofNullable(live.get());
        }
    }

    /// A loopback HTTP server that answers `/exec` and `/write` as QuestDB 10.1.0 does: DDL is `{"ddl":"OK"}`, a
    /// `SELECT` gets the answer set, a write gets 204, or 400 naming a missing table while `refuseAsMissing` counts.
    static final class FakeQuestDb implements AutoCloseable {

        /** The body QuestDB 10.1.0 sends when a line names a table it does not have (probed). */
        private static final String MISSING = "{\"code\":\"invalid\",\"message\":\"failed to parse line protocol:"
                + "errors encountered on line(s):\\nerror in line 1: table: comando_usage; table does not exist, "
                + "creating new tables is disabled\",\"line\":1,\"errorId\":\"t-1\"}";

        /** A permit per write received, before it is held. */
        final Semaphore arrived = new Semaphore(0);
        /** While set, each write waits for it before it is answered. */
        final AtomicReference<CountDownLatch> hold = new AtomicReference<>();
        final List<String> lines = new CopyOnWriteArrayList<>();
        final AtomicInteger posts = new AtomicInteger();
        final List<String> queries = new CopyOnWriteArrayList<>();
        final AtomicInteger refuseAsMissing = new AtomicInteger();
        final AtomicReference<String> selectAnswer = new AtomicReference<>(
                "{\"query\":\"\",\"columns\":[],\"dataset\":[],\"count\":0}");
        private final AtomicBoolean closed = new AtomicBoolean();
        private final HttpServer server;

        FakeQuestDb() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/exec", this::exec);
            server.createContext("/write", this::write);
            server.start();
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        long ddls() {
            return queries.stream().filter(q -> q.startsWith("CREATE TABLE IF NOT EXISTS ")).count();
        }

        /** Stops answering, as a server that went down; idempotent. */
        void down() {
            if (closed.compareAndSet(false, true)) {
                server.stop(0);
            }
        }

        @Override
        public void close() {
            down();
        }

        private void exec(final HttpExchange exchange) throws IOException {
            final String raw = exchange.getRequestURI().getRawQuery();
            final String query = URLDecoder.decode(raw.substring(raw.indexOf('=') + 1), StandardCharsets.UTF_8);
            queries.add(query);
            answer(exchange, 200, query.startsWith("SELECT") ? selectAnswer.get() : "{\"ddl\":\"OK\"}");
        }

        private void write(final HttpExchange exchange) throws IOException {
            final String body;
            try (final InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            // the latch is read before the arrival is told: a test that swaps it on the arrival sets the next write's
            final CountDownLatch held = hold.get();
            posts.incrementAndGet();
            arrived.release();
            if (held != null) {
                try {
                    held.await(30, TimeUnit.SECONDS);
                } catch (final InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }
            if (refuseAsMissing.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                answer(exchange, 400, MISSING);
                return;
            }
            lines.addAll(body.lines().toList());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        }

        private static void answer(final HttpExchange exchange, final int status, final String text)
                throws IOException {
            final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
