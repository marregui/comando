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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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
/// - Imports each past day's file once and lists it, and today's file at every start.
class MetricsSinkTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 2026-10-28T10:00:00Z: today is the 28th. */
    private static final long NOW_MILLIS = Instant.parse("2026-10-28T10:00:00Z").toEpochMilli();
    private static final Duration WITHIN = Duration.ofSeconds(5);

    @TempDir
    Path dir;
    private final AtomicLong nanos = new AtomicLong();

    /// The first event reaches the server as its line, after the five tables were created.
    @Test
    void anEventArrivesAsItsLineAfterTheTables() throws Exception {
        try (FakeQuestDb server = new FakeQuestDb();
                MetricsSink sink = sink(Endpoint.at(server.uri()))) {
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
        try (FakeQuestDb server = new FakeQuestDb();
                MetricsSink sink = sink(Endpoint.at(server.uri()))) {
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
        try (MetricsSink sink = sink(none)) {
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
        try (ServerSocket gone = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            refused = URI.create("http://127.0.0.1:" + gone.getLocalPort());
        }
        try (FakeQuestDb server = new FakeQuestDb()) {
            final Endpoint endpoint = Endpoint.at(refused);
            endpoint.up.set(server.uri());
            try (MetricsSink sink = sink(endpoint)) {
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
        try (MetricsSink sink = sink(held)) {
            sink.offer(usage("first"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).as("the errand holds the first event").isTrue();
            for (int i = 0; i < MetricsSink.CAPACITY + 5; i++) {
                sink.offer(usage("w" + i));
            }
            assertThat(sink.droppedEvents()).isEqualTo(5);
            release.countDown();
        }
    }

    /// The first connection imports the past day not yet listed and today's file, and lists the past day only; the
    /// next start sends today's file again and the listed days never.
    @Test
    void pastDaysAreImportedOnceAndTodayAtEveryStart() throws Exception {
        Files.writeString(dir.resolve("metrics-2026-10-26.jsonl"), usage("d26") + "\n");
        Files.writeString(dir.resolve("metrics-2026-10-27.jsonl"),
                usage("d27a") + "\n" + "not json\n\n" + usage("d27b") + "\n");
        Files.writeString(dir.resolve("metrics-2026-10-28.jsonl"), usage("d28") + "\n");
        Files.writeString(dir.resolve(MetricsSink.IMPORTED), "metrics-2026-10-26.jsonl\n");
        try (FakeQuestDb server = new FakeQuestDb()) {
            try (MetricsSink sink = sink(Endpoint.at(server.uri()))) {
                sink.offer(usage("live1"));
                await().atMost(WITHIN).until(() -> server.lines.size() == 4);
            }
            assertThat(server.lines).containsExactlyInAnyOrder(usageLine("d27a"), usageLine("d27b"), usageLine("d28"),
                    usageLine("live1"));
            assertThat(Files.readAllLines(dir.resolve(MetricsSink.IMPORTED)))
                    .containsExactly("metrics-2026-10-26.jsonl", "metrics-2026-10-27.jsonl");

            server.lines.clear();
            try (MetricsSink sink = sink(Endpoint.at(server.uri()))) {
                sink.offer(usage("live2"));
                await().atMost(WITHIN).until(() -> server.lines.size() == 2);
            }
            assertThat(server.lines).containsExactlyInAnyOrder(usageLine("d28"), usageLine("live2"));
            assertThat(Files.readAllLines(dir.resolve(MetricsSink.IMPORTED)))
                    .containsExactly("metrics-2026-10-26.jsonl", "metrics-2026-10-27.jsonl");
        }
    }

    /// A `usage` event of `window`, at 2026-10-28T10:00:00Z.
    static ObjectNode usage(final String window) {
        return JSON.createObjectNode().put("ts", "2026-10-28T10:00:00Z").put("event", "usage").put("provider", "claude")
                .put("window", window).put("pct", 1.0);
    }

    private static String usageLine(final String window) {
        return "comando_usage,instance=/data,provider=claude,window_name=" + window + " pct=1.0 1793181600000000000";
    }

    private MetricsSink sink(final QuestDbEndpoint qdb) {
        return new MetricsSink(dir, qdb, new MetricsLines(JSON, "/data"), JSON, () -> NOW_MILLIS, nanos::get);
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
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            posts.incrementAndGet();
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
