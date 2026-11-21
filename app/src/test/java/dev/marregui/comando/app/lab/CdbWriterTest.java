package dev.marregui.comando.app.lab;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.claude.MetricsEndpoint;
import dev.marregui.comando.core.port.LabPort.Kind;
import dev.marregui.comando.core.port.LabPort.Packet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/// The lab's cdb writer against a fake cdb on a loopback port: the table is made once with its declared DDL before
/// the first write, then each batch is one `/write`; a refusal fails the write and makes the table again before the
/// next; behind the sink, a cdb that is down drops and counts the lines, and once it is up again they are written
/// after the backoff.
class CdbWriterTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String SECRET = "0123456789abcdef";

    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger execStatus = new AtomicInteger(200);
    private final AtomicInteger writeStatus = new AtomicInteger(204);
    private final AtomicBoolean up = new AtomicBoolean(true);
    private HttpServer server;
    private MetricsEndpoint endpoint;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/exec", exchange -> answer(exchange, execStatus.get(), "{\"ddl\":\"OK\"}"));
        server.createContext("/write", exchange -> answer(exchange, writeStatus.get(), ""));
        server.start();
        final URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        endpoint = new Endpoint(base, up);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /// Two batches: one `/exec` of the declared DDL, then one `/write` per batch at nanosecond precision with the
    /// lines one per row; every request carries the owner secret.
    @Test
    void theTableIsMadeOnceThenEachBatchIsWritten() throws Exception {
        try (CdbWriter writer = new CdbWriter(endpoint)) {
            writer.write(List.of("frontear_packet,node=a,kind=published qos=1i 1", "frontear_packet,node=a 2"));
            writer.write(List.of("frontear_packet,node=a 3"));
        }
        assertThat(requests).extracting(Request::path).containsExactly("/exec", "/write", "/write");
        assertThat(requests.getFirst().method()).isEqualTo("GET");
        assertThat(requests.getFirst().query()).isEqualTo("query=" + CdbWriter.DDL);
        assertThat(requests.get(1).method()).isEqualTo("POST");
        assertThat(requests.get(1).query()).isEqualTo("precision=n");
        assertThat(requests.get(1).body())
                .isEqualTo("frontear_packet,node=a,kind=published qos=1i 1\nfrontear_packet,node=a 2\n");
        assertThat(requests.get(2).body()).isEqualTo("frontear_packet,node=a 3\n");
        assertThat(requests).extracting(Request::bearer).containsOnly("Bearer " + SECRET);
    }

    /// A table cdb refuses fails the write with nothing posted; a refused `/write` fails it too, and the next write
    /// makes the table again before its lines.
    @Test
    void aRefusalFailsTheWriteAndTheTableIsMadeAgain() throws Exception {
        try (CdbWriter writer = new CdbWriter(endpoint)) {
            execStatus.set(400);
            assertThatThrownBy(() -> writer.write(List.of("frontear_packet,node=a 1")))
                    .isInstanceOf(IOException.class).hasMessageContaining("status=400");
            assertThat(requests).extracting(Request::path).containsExactly("/exec");

            execStatus.set(200);
            writeStatus.set(500);
            assertThatThrownBy(() -> writer.write(List.of("frontear_packet,node=a 2")))
                    .isInstanceOf(IOException.class).hasMessageContaining("status=500");
            writeStatus.set(204);
            writer.write(List.of("frontear_packet,node=a 3"));
        }
        assertThat(requests).extracting(Request::path).containsExactly("/exec", "/exec", "/write", "/exec", "/write");
    }

    /// A cdb that runs no more, or whose port no longer answers, fails the write before anything is sent.
    @Test
    void aServerThatIsDownFailsTheWrite() {
        up.set(false);
        try (CdbWriter writer = new CdbWriter(endpoint)) {
            assertThatThrownBy(() -> writer.write(List.of("frontear_packet,node=a 1")))
                    .isInstanceOf(IOException.class).hasMessage("cdb is not running");
        }
        server.stop(0);
        up.set(true);
        try (CdbWriter writer = new CdbWriter(endpoint)) {
            assertThatThrownBy(() -> writer.write(List.of("frontear_packet,node=a 1"))).isInstanceOf(IOException.class);
        }
        assertThat(requests).isEmpty();
    }

    /// Behind the sink: while cdb is down each line offered is dropped and counted with nothing sent; once it is up
    /// again, a line offered after the backoff makes the table, then is written.
    @Test
    void behindTheSinkADownServerDropsAndCountsThenIsWrittenToAfterTheBackoff() {
        up.set(false);
        try (PacketSink sink = new PacketSink(new CdbWriter(endpoint))) {
            sink.offer("a", published(1));
            await().atMost(WAIT).until(() -> sink.droppedDown() == 1L);
            sink.offer("a", published(2));
            await().atMost(WAIT).until(() -> sink.droppedDown() == 2L);
            assertThat(requests).isEmpty();

            up.set(true);
            final AtomicInteger next = new AtomicInteger(3);
            await().atMost(WAIT).pollInterval(Duration.ofMillis(100)).until(() -> {
                sink.offer("a", published(next.getAndIncrement()));
                return sink.written() > 0;
            });
            assertThat(sink.droppedDown()).isGreaterThanOrEqualTo(2L);
        }
        assertThat(requests).extracting(Request::path).startsWith("/exec", "/write");
        assertThat(requests.get(1).body()).startsWith("frontear_packet,node=a,kind=published");
    }

    private void answer(final HttpExchange exchange, final int status, final String text) throws IOException {
        final byte[] body;
        try (exchange) {
            body = exchange.getRequestBody().readAllBytes();
            requests.add(new Request(exchange.getRequestMethod(), exchange.getHttpContext().getPath(),
                    exchange.getRequestURI().getQuery(), exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(body, StandardCharsets.UTF_8)));
            final byte[] reply = text.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, reply.length == 0 ? -1 : reply.length);
            if (reply.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(reply);
                }
            }
        }
    }

    private static Packet published(final int i) {
        return new Packet(i, 1_000L + i, Kind.PUBLISHED, "pub", "", "t/" + i, 1, false, 3, 0, -1L, "", "m", "");
    }

    /// One request the fake cdb took; `query` decoded.
    private record Request(String method, String path, String query, String bearer, String body) {
    }

    /// The fake cdb as the writer reaches it: live while `up`, with the owner secret.
    private record Endpoint(URI base, AtomicBoolean up) implements MetricsEndpoint {

        @Override
        public Optional<URI> ensureUp() {
            return live();
        }

        @Override
        public Optional<URI> live() {
            return up.get() ? Optional.of(base) : Optional.empty();
        }

        @Override
        public Optional<String> secret() {
            return Optional.of(SECRET);
        }
    }
}
