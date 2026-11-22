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
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;

/**
 * Writes the lab's lines to the machine's cdb the way the metrics sink does (record 146): {@code /write} with the
 * owner secret as bearer. Before its first write to a server it makes the table with {@link #DDL}, then adds the
 * payload columns with {@link #ALTERS}, through {@code /exec}: cdb's {@code /write} would make a missing table itself
 * from the line's fields, without the declared types, partitions and TTL, and a column it adds is VARCHAR, never
 * JSON. After a failed write it does both again before the next one, as the server may be a new one. A table made
 * before the payload columns gets them from the {@code ALTER}s. One that already has a VARCHAR {@code payload},
 * from an earlier write, keeps it: the {@code ALTER} changes nothing there and its JSON payloads land as text, until
 * the table is dropped; nothing migrates it. A cdb without the JSON type refuses {@link #DDL}, and nothing is written
 * to it. It only writes to a server that runs ({@link MetricsEndpoint#live}): the lab never starts cdb. A batch goes
 * in requests of at most {@link #MAX_BODY_CHARS}; when one fails, those before it have landed. A line longer than
 * that, which cdb could never take, is dropped with a warning and the rest written. Called by one thread at a time:
 * the sink's.
 */
public final class CdbWriter implements PacketSink.Writer {

    /**
     * The table: one row per packet, its kind, node, origin and link as symbols, a publish's payload in
     * {@code payload} when it is JSON and kept whole, else in {@code payload_text} (see {@link PacketSink#json}).
     * Partitioned by hour and kept a day: in the load test of 2026-11-22 a day's partition grew to 705 MB, and a
     * commit that rewrote it ran cdb out of memory and suspended the table; an hour's partition bounds what one commit
     * rewrites, and the day bounds the disk.
     */
    static final String DDL = "CREATE TABLE IF NOT EXISTS frontear_packet (ts TIMESTAMP, node SYMBOL CAPACITY 4096,"
            + " kind SYMBOL CAPACITY 4096, origin SYMBOL CAPACITY 4096, link SYMBOL CAPACITY 4096, topic VARCHAR,"
            + " qos INT, retain BOOLEAN, bytes INT, hops INT, offset LONG, correlation VARCHAR, code INT,"
            + " payload JSON, payload_text VARCHAR) TIMESTAMP(ts) PARTITION BY HOUR TTL 24 HOURS WAL";
    /**
     * For a table made before the payload columns or the day's TTL: the columns, a no-op where the column is there
     * whatever its type, and the TTL. cdb has no {@code ALTER} for the partitioning, so a table made by day keeps its
     * day partitions until it is dropped; a day's TTL is a whole number of them, which cdb requires.
     */
    static final List<String> ALTERS = List.of(
            "ALTER TABLE frontear_packet ADD COLUMN IF NOT EXISTS payload JSON",
            "ALTER TABLE frontear_packet ADD COLUMN IF NOT EXISTS payload_text VARCHAR",
            "ALTER TABLE frontear_packet SET TTL 24 HOURS");
    /**
     * The characters one request carries at most: cdb refuses a body over 16 MiB, and a character is at most three
     * bytes of UTF-8 (a surrogate pair, two characters, four), so 4 Mi characters stay under it with room.
     */
    static final int MAX_BODY_CHARS = 4 << 20;
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
    public int write(final List<String> lines) throws IOException, InterruptedException {
        final URI base = cdb.live().orElseThrow(() -> new IOException("cdb is not running"));
        final Optional<String> secret = cdb.secret();
        if (!base.equals(madeOn)) {
            exec(base, DDL, secret);
            for (final String alter : ALTERS) {
                exec(base, alter, secret);
            }
            madeOn = base;
        }
        int landed = 0;
        int from = 0;
        while (from < lines.size()) {
            if (lines.get(from).length() + 1L > MAX_BODY_CHARS) {
                LOG.log(System.Logger.Level.WARNING, "frontear packet too long for cdb, dropped [chars="
                        + lines.get(from).length() + ", max=" + MAX_BODY_CHARS + ']');
                from++;
                continue;
            }
            final int to = end(lines, from);
            final HttpResponse<String> answer;
            try {
                answer = post(base, lines.subList(from, to), secret);
            } catch (final IOException e) {
                madeOn = null;
                throw new PacketSink.PartialWrite("cannot write frontear packets [lines=" + (to - from)
                        + ", landed=" + landed + ']', landed, e);
            }
            if (answer.statusCode() != 204) {
                madeOn = null;
                throw new PacketSink.PartialWrite("cdb refused frontear packets [status=" + answer.statusCode()
                        + ", lines=" + (to - from) + ", landed=" + landed + ", answer=" + shortened(answer.body())
                        + ']', landed, null, tableFault(answer.body()));
            }
            landed += to - from;
            from = to;
        }
        return landed;
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
            throw new PacketSink.PartialWrite("cdb refused the frontear_packet table [status="
                    + answer.statusCode() + ", sql=" + sql + ", answer=" + shortened(answer.body()) + ']', 0,
                    null, tableFault(answer.body()));
        }
    }

    /**
     * The end of the request that starts at line {@code from}, which fits: the lines after it while the body stays
     * within {@link #MAX_BODY_CHARS}, up to the first that does not fit.
     */
    static int end(final List<String> lines, final int from) {
        int to = from + 1;
        long chars = lines.get(from).length() + 1L;
        while (to < lines.size() && chars + lines.get(to).length() + 1L <= MAX_BODY_CHARS) {
            chars += lines.get(to).length() + 1L;
            to++;
        }
        return to;
    }

    /**
     * The reason in cdb's answer {@code body} when it says the table takes no writes, else empty: it names a failed
     * table, as a refused commit does, or a suspended one, as a refused read does (not {@code suspended=false}). The
     * reason is the message of its JSON error, {@code message} from {@code /write} and {@code error} from
     * {@code /exec}, or the body as it came. cdb gives no code for it: its text is all the answer says.
     */
    static String tableFault(final String body) {
        if (!body.contains("failed table") && !body.replace("suspended=false", "").contains("suspended")) {
            return "";
        }
        final JsonNode answer = Json.tryReadTree(body);
        if (answer != null && answer.path("message").isString()) {
            return answer.path("message").stringValue();
        }
        if (answer != null && answer.path("error").isString()) {
            return answer.path("error").stringValue();
        }
        return shortened(body);
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
