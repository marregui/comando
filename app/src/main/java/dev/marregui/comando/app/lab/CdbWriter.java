package dev.marregui.comando.app.lab;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import dev.marregui.comando.claude.MetricsEndpoint;

/**
 * Writes the lab's lines to the machine's cdb the way the metrics sink does (record 146): {@code /write} with the
 * owner secret as bearer. Before its first write to a server it makes the table with {@link #DDL} through
 * {@code /exec}, since cdb's {@code /write} would make a missing table itself from the line's fields, without the
 * declared types, partitions and TTL; after a failed write it makes it again before the next one, as the server may
 * be a new one. It only writes to a server that runs ({@link MetricsEndpoint#live}): the lab never starts cdb.
 * Called by one thread at a time: the sink's.
 */
public final class CdbWriter implements PacketSink.Writer {

    /**
     * The table: one row per packet, its kind, node, origin and link as symbols; no payload (presence carries prompt
     * lines). Kept a week (ADR 0187 §2.3).
     */
    static final String DDL = "CREATE TABLE IF NOT EXISTS frontear_packet (ts TIMESTAMP, node SYMBOL CAPACITY 4096,"
            + " kind SYMBOL CAPACITY 4096, origin SYMBOL CAPACITY 4096, link SYMBOL CAPACITY 4096, topic VARCHAR,"
            + " qos INT, retain BOOLEAN, bytes INT, hops INT, offset LONG, correlation VARCHAR, code INT)"
            + " TIMESTAMP(ts) PARTITION BY DAY TTL 7 DAYS WAL";
    /** The metrics sink's connect bound (record 146, §5): a server on this machine answers a connect at once. */
    private static final Duration CONNECT_WITHIN = Duration.ofSeconds(1);
    /** The metrics sink's request bound (record 146, §5). */
    private static final Duration REQUEST_WITHIN = Duration.ofSeconds(5);
    /** How long {@link #close} waits for the HTTP client to end: the metrics sink's close bound. */
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(2);
    private static final System.Logger LOG = System.getLogger(CdbWriter.class.getName());

    private final MetricsEndpoint cdb;
    private final HttpClient http;
    // the server the table was made on, null before that and after a failed write; read and written by the sink's
    // thread only
    private URI madeOn;

    public CdbWriter(final MetricsEndpoint cdb) {
        this.cdb = cdb;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_WITHIN).build();
    }

    @Override
    public void write(final List<String> lines) throws IOException, InterruptedException {
        final URI base = cdb.live().orElseThrow(() -> new IOException("cdb is not running"));
        final Optional<String> secret = cdb.secret();
        if (!base.equals(madeOn)) {
            exec(base, DDL, secret);
            madeOn = base;
        }
        final HttpResponse<String> answer;
        try {
            answer = post(base, lines, secret);
        } catch (final IOException e) {
            madeOn = null;
            throw e;
        }
        if (answer.statusCode() != 204) {
            madeOn = null;
            throw new IOException("cdb refused frontear packets [status=" + answer.statusCode() + ", lines="
                    + lines.size() + ", answer=" + shortened(answer.body()) + ']');
        }
    }

    /** Ends the HTTP client, waiting for it a bounded time. Idempotent. */
    @Override
    public void close() {
        http.shutdownNow();
        try {
            if (!http.awaitTermination(CLOSE_WAIT)) {
                LOG.log(System.Logger.Level.WARNING, "frontear packet writer's HTTP client still running at close"
                        + " [wait=" + CLOSE_WAIT + ']');
            }
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private HttpResponse<String> post(
            final URI base,
            final List<String> lines,
            final Optional<String> secret
    ) throws IOException, InterruptedException {
        final StringBuilder body = new StringBuilder();
        for (final String line : lines) {
            body.append(line).append('\n');
        }
        final HttpRequest request = authorized(HttpRequest.newBuilder(base.resolve("/write?precision=n")), secret)
                .timeout(REQUEST_WITHIN).header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void exec(final URI base, final String sql, final Optional<String> secret)
            throws IOException, InterruptedException {
        final String query = URLEncoder.encode(sql, StandardCharsets.UTF_8).replace("+", "%20");
        final HttpRequest request = authorized(HttpRequest.newBuilder(base.resolve("/exec?query=" + query)), secret)
                .timeout(REQUEST_WITHIN).GET().build();
        final HttpResponse<String> answer = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (answer.statusCode() != 200) {
            throw new IOException("cdb refused the frontear_packet table [status=" + answer.statusCode()
                    + ", answer=" + shortened(answer.body()) + ']');
        }
    }

    private static HttpRequest.Builder authorized(final HttpRequest.Builder request, final Optional<String> secret) {
        secret.ifPresent(s -> request.header("Authorization", "Bearer " + s));
        return request;
    }

    private static String shortened(final String text) {
        final String flat = text.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }
}
