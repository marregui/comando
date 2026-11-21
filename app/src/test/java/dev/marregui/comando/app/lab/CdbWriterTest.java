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
import java.util.ArrayList;
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
import dev.marregui.comando.frontear.watch.Watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/// The lab's cdb writer against a fake cdb on a loopback port: the table is made once with its declared DDL and its
/// payload columns added before the first write, then each batch is one `/write`; a write returns the lines that
/// landed, and a refusal part way says how many had; it fails the write and makes the table again before the next;
/// behind the sink, a cdb that is down drops and counts the lines, and once it is up again they are written after
/// the backoff.
class CdbWriterTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String SECRET = "0123456789abcdef";

    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger execStatus = new AtomicInteger(200);
    private final AtomicInteger writeStatus = new AtomicInteger(204);
    /// The `/write` request, counted from 1, that answers 500; 0 for none.
    private final AtomicInteger failWrite = new AtomicInteger();
    private final AtomicInteger writes = new AtomicInteger();
    private final AtomicBoolean up = new AtomicBoolean(true);
    private HttpServer server;
    private MetricsEndpoint endpoint;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/exec", exchange -> answer(exchange, execStatus.get(), "{\"ddl\":\"OK\"}"));
        server.createContext("/write", exchange -> answer(exchange,
                writes.incrementAndGet() == failWrite.get() ? 500 : writeStatus.get(), ""));
        server.start();
        final URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        endpoint = new Endpoint(base, up);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /// Two batches: the declared DDL then the two payload column ALTERs, each one `/exec`, once; then one `/write`
    /// per batch at nanosecond precision with the lines one per row, each write returning its lines; every request
    /// carries the owner secret.
    @Test
    void theTableIsMadeOnceThenEachBatchIsWritten() throws Exception {
        try (CdbWriter writer = new CdbWriter(endpoint)) {
            assertThat(writer.write(List.of("frontear_packet,node=a,kind=published qos=1i 1",
                    "frontear_packet,node=a 2"))).isEqualTo(2);
            assertThat(writer.write(List.of("frontear_packet,node=a 3"))).isEqualTo(1);
        }
        assertThat(requests).extracting(Request::path).containsExactly("/exec", "/exec", "/exec", "/write", "/write");
        assertThat(requests.subList(0, 3)).extracting(Request::method).containsOnly("GET");
        assertThat(requests.subList(0, 3)).extracting(Request::query).containsExactly("query=" + CdbWriter.DDL,
                "query=" + CdbWriter.ALTERS.get(0), "query=" + CdbWriter.ALTERS.get(1));
        assertThat(requests.get(3).method()).isEqualTo("POST");
        assertThat(requests.get(3).query()).isEqualTo("precision=n");
        assertThat(requests.get(3).body())
                .isEqualTo("frontear_packet,node=a,kind=published qos=1i 1\nfrontear_packet,node=a 2\n");
        assertThat(requests.get(4).body()).isEqualTo("frontear_packet,node=a 3\n");
        assertThat(requests).extracting(Request::bearer).containsOnly("Bearer " + SECRET);
    }

    /// The table declares both payload columns, JSON and text, beside the rest and keeps a week; a table made before
    /// them gets each from an ALTER that changes nothing where the column is there.
    @Test
    void theTableDeclaresBothPayloadColumnsAndAddsThemToAnOlderTable() {
        assertThat(CdbWriter.DDL).contains("correlation VARCHAR, code INT, payload JSON, payload_text VARCHAR)")
                .contains("TTL 7 DAYS");
        assertThat(CdbWriter.ALTERS).containsExactly(
                "ALTER TABLE frontear_packet ADD COLUMN IF NOT EXISTS payload JSON",
                "ALTER TABLE frontear_packet ADD COLUMN IF NOT EXISTS payload_text VARCHAR");
    }

    /// A publish whose payload is JSON and kept whole reaches cdb in field `payload`, the JSON column; one that is
    /// not JSON, and one cut at the watch's bound though its first bytes parse, in field `payload_text`.
    @Test
    void aJsonPayloadGoesToTheJsonColumnAndAnyOtherToTheTextOne() throws Exception {
        final String json = PacketSink.line("a", publish(11, "{\"k\":\"v\"}\n"));
        final String text = PacketSink.line("a", publish(9, "k=v; t=1\n"));
        final String cut = PacketSink.line("a", publish(Watch.PAYLOAD_BYTES + 1, "{\"k\":\"v\"}"));
        try (CdbWriter writer = new CdbWriter(endpoint)) {
            assertThat(writer.write(List.of(json, text, cut))).isEqualTo(3);
        }
        assertThat(requests).extracting(Request::path).containsExactly("/exec", "/exec", "/exec", "/write");
        assertThat(requests.get(3).body()).isEqualTo(json + '\n' + text + '\n' + cut + '\n');
        assertThat(fields(json)).contains("payload").doesNotContain("payload_text");
        assertThat(fields(text)).contains("payload_text").doesNotContain("payload");
        assertThat(fields(cut)).contains("payload_text").doesNotContain("payload");
    }

    /// A batch split in two requests whose second cdb refuses: the write fails saying the first request's line
    /// landed; a line too long for any request, before them, counts in neither.
    @Test
    void aBatchWhoseSecondRequestFailsSaysHowManyLanded() throws Exception {
        final String half = "x".repeat(CdbWriter.MAX_BODY_CHARS / 2);
        final String tooLong = "y".repeat(CdbWriter.MAX_BODY_CHARS);
        failWrite.set(2);
        try (CdbWriter writer = new CdbWriter(endpoint)) {
            assertThatThrownBy(() -> writer.write(List.of(tooLong, half, half, "z")))
                    .isInstanceOfSatisfying(PacketSink.PartialWrite.class,
                            e -> assertThat(e.landed()).isEqualTo(1));
        }
        assertThat(requests).extracting(Request::path).containsExactly("/exec", "/exec", "/exec", "/write", "/write");
        assertThat(requests.get(3).body()).isEqualTo(half + '\n');
        assertThat(requests.get(4).body()).isEqualTo(half + '\n' + "z\n");
    }

    /// cdb refuses a body over 16 MiB: a batch longer than a request carries goes in several, each within the bound,
    /// every line once and in order; a line that could never fit is dropped, the rest written, and the write returns
    /// the lines that landed.
    @Test
    void aLongBatchGoesInRequestsWithinTheBoundAndALineThatNeverFitsIsDropped() throws Exception {
        final String half = "x".repeat(CdbWriter.MAX_BODY_CHARS / 2);
        final String tooLong = "y".repeat(CdbWriter.MAX_BODY_CHARS);
        try (CdbWriter writer = new CdbWriter(endpoint)) {
            assertThat(writer.write(List.of(half, half, "z", tooLong, "w"))).isEqualTo(4);
        }
        assertThat(requests).extracting(Request::path)
                .containsExactly("/exec", "/exec", "/exec", "/write", "/write", "/write");
        assertThat(requests.get(3).body()).isEqualTo(half + '\n');
        assertThat(requests.get(4).body()).isEqualTo(half + '\n' + "z\n");
        assertThat(requests.get(5).body()).isEqualTo("w\n");
        assertThat(requests.subList(3, 6)).allSatisfy(r -> assertThat(r.body().length())
                .isLessThanOrEqualTo(CdbWriter.MAX_BODY_CHARS));
    }

    /// A table cdb refuses fails the write with nothing posted and no ALTER sent; a refused `/write` fails it too,
    /// none of its lines landed, and the next write makes the table and adds its columns again before its lines.
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
                    .isInstanceOfSatisfying(PacketSink.PartialWrite.class, e -> assertThat(e.landed()).isZero())
                    .hasMessageContaining("status=500");
            writeStatus.set(204);
            writer.write(List.of("frontear_packet,node=a 3"));
        }
        assertThat(requests).extracting(Request::path).containsExactly("/exec", "/exec", "/exec", "/exec", "/write",
                "/exec", "/exec", "/exec", "/write");
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
        assertThat(requests).extracting(Request::path).startsWith("/exec", "/exec", "/exec", "/write");
        assertThat(requests.get(3).body()).startsWith("frontear_packet,node=a,kind=published");
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

    /// The field names of `line`, read back from its line protocol: what follows the first unescaped space, up to
    /// the timestamp, split at the commas outside quotes.
    private static List<String> fields(final String line) {
        final List<String> names = new ArrayList<>();
        boolean quoted = false;
        boolean escaped = false;
        int spaces = 0;
        final StringBuilder name = new StringBuilder();
        boolean inName = false;
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == ' ') {
                spaces++;
                inName = spaces == 1;
            } else if (!quoted && spaces == 1 && c == ',') {
                inName = true;
            } else if (!quoted && inName && c == '=') {
                names.add(name.toString());
                name.setLength(0);
                inName = false;
            } else if (inName) {
                name.append(c);
            }
        }
        return names;
    }

    private static Packet publish(final int bytes, final String payload) {
        return new Packet(1, 1_000L, Kind.PUBLISHED, "pub", "", "t/1", 1, false, bytes, 0, -1L, "", "", "", payload);
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
