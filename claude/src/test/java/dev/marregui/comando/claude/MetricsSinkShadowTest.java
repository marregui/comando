package dev.marregui.comando.claude;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.claude.MetricsSinkTest.Endpoint;
import dev.marregui.comando.claude.MetricsSinkTest.FakeQuestDb;
import dev.marregui.comando.testkit.RecordingSink;

import static dev.marregui.comando.claude.MetricsSinkTest.usage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// [MetricsSink]'s shadow server, named by [MetricsSink#SHADOW_ENV], gets every batch the server gets; with none, it
/// gets nothing, and a shadow that fails changes nothing the server's writes count or tell.
/// - With a shadow, the batch reaches both servers, each with its tables made first.
/// - With no shadow, or a blank one, only the server is written.
/// - With a shadow that refuses connections, the server's line is sent and nothing is counted failed or told.
/// - The setting is a base URL; anything else is no shadow.
class MetricsSinkShadowTest {

    private static final Duration WITHIN = Duration.ofSeconds(5);

    private final AtomicLong nanos = new AtomicLong();
    private final RecordingSink notices = new RecordingSink();

    /// With the shadow set, the event's line reaches the shadow as it reaches the server, after its tables.
    @Test
    void aShadowGetsTheBatchTheServerGets() throws Exception {
        try (final FakeQuestDb server = new FakeQuestDb();
                final FakeQuestDb shadow = new FakeQuestDb();
                final MetricsSink sink = sink(Endpoint.at(server.uri()), shadow.uri().toString())) {
            sink.offer(usage("five_hour"));

            await().atMost(WITHIN).until(() -> sink.sentLines() == 1 && shadow.lines.size() == 1);
            assertThat(shadow.lines).containsExactlyElementsOf(server.lines);
            assertThat(shadow.ddls()).isEqualTo(MetricsLines.DDL.size());
        }
    }

    /// With no shadow set, or a blank one, the server takes the line and the would-be shadow receives no request.
    @Test
    void noShadowIsWrittenWhenNoneIsSet() throws Exception {
        try (final FakeQuestDb server = new FakeQuestDb();
                final FakeQuestDb shadow = new FakeQuestDb();
                final MetricsSink unset = sink(Endpoint.at(server.uri()), null);
                final MetricsSink blank = sink(Endpoint.at(server.uri()), " ")) {
            unset.offer(usage("five_hour"));
            blank.offer(usage("seven_day"));

            await().atMost(WITHIN).until(() -> unset.sentLines() == 1 && blank.sentLines() == 1);
            assertThat(shadow.posts.get()).isZero();
            assertThat(shadow.queries).isEmpty();
        }
    }

    /// With a shadow that refuses every connection, two batches still reach the server, none is counted failed and
    /// no notice is raised.
    @Test
    void aFailingShadowChangesNothingTheServerCounts() throws Exception {
        final URI refused;
        try (final ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            refused = URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
        try (final FakeQuestDb server = new FakeQuestDb();
                final MetricsSink sink = sink(Endpoint.at(server.uri()), refused.toString())) {
            sink.offer(usage("five_hour"));
            await().atMost(WITHIN).until(() -> sink.sentLines() == 1);
            sink.offer(usage("seven_day"));

            await().atMost(WITHIN).until(() -> sink.sentLines() == 2);
            assertThat(sink.failedLines()).isZero();
            assertThat(notices.intents()).isEmpty();
        }
    }

    /// With a shadow that takes connections and never answers, the server still gets each batch about as soon as it
    /// is offered: the shadow's tables and writes wait at most their own short bound, not the server's 5 s each.
    @Test
    void aSilentShadowDelaysTheServerLittle() throws Exception {
        try (final ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
                final FakeQuestDb server = new FakeQuestDb();
                final MetricsSink sink = sink(Endpoint.at(server.uri()),
                        "http://127.0.0.1:" + silent.getLocalPort())) {
            sink.offer(usage("five_hour"));
            await().atMost(WITHIN).until(() -> sink.sentLines() == 1);
            sink.offer(usage("seven_day"));

            // one shadow write (its tables made before it) at 0.5 s each, against 5 s each at the server's bound
            await().atMost(Duration.ofSeconds(4)).until(() -> sink.sentLines() == 2);
            assertThat(sink.failedLines()).isZero();
        }
    }

    /// An absolute URL is the shadow; nothing, blank text, a bare host or text that is no URL is none.
    @Test
    void theSettingIsABaseUrl() {
        assertThat(MetricsSink.shadowOf(" http://127.0.0.1:9100 ")).isEqualTo(URI.create("http://127.0.0.1:9100"));
        assertThat(MetricsSink.shadowOf(null)).isNull();
        assertThat(MetricsSink.shadowOf("")).isNull();
        assertThat(MetricsSink.shadowOf("127.0.0.1:9100")).isNull();
        assertThat(MetricsSink.shadowOf("http://bad host")).isNull();
    }

    private MetricsSink sink(final QuestDbEndpoint qdb, final String shadow) {
        return new MetricsSink(qdb, new MetricsLines("/data"), nanos::get, notices, shadow);
    }
}
