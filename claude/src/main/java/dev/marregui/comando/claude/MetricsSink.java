package dev.marregui.comando.claude;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.jetbrains.annotations.TestOnly;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.Errands;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.state.Notice;

/**
 * The metrics events on their way to the machine's QuestDB, their only store (records 146, 149).
 * {@link #offer} never blocks: an event joins a queue of {@link #CAPACITY}, or, the queue full or the sink closed, is
 * dropped and counted. One errand drains the queue in batches of at most {@link #BATCH_LINES} lines, sent
 * {@link #BATCH_WAIT} after a batch's first event at the latest, to the server {@link QuestDbEndpoint#live} names; when
 * none answers, {@link QuestDbEndpoint#ensureUp} is asked at most once a minute. The tables are created the first time
 * a process writes to a server, and again after a write is refused for a missing table.
 * <p>
 * Delivery is at most once per event offered: a batch that fails is lost, counted, and logged at most once a minute;
 * nothing is kept on disk to send later. A write the server refuses for some of its lines is sent again in halves, so
 * that only the lines it refuses alone are lost; such a line is logged at most once a minute, and is no outage. The
 * first other failure after a write the server took, or after the start, is also a notice to the user, once for that
 * outage; the next outage, after a batch was taken again, is told again. What the close cuts off, the events still
 * queued and the lines of a batch not sent, is counted and logged once at the close.
 */
final class MetricsSink implements AutoCloseable {

    /** Lines a write carries at most: the contract's batch (record 146, §5). */
    static final int BATCH_LINES = 1_000;
    /** How long a batch waits for more events after its first: the contract's bound (record 146), not measured. */
    static final Duration BATCH_WAIT = Duration.ofMillis(200);
    /**
     * Events held while the server is reached: the contract's bound (record 146), not measured; the peak depth while a
     * server restarts would settle it.
     */
    static final int CAPACITY = 10_000;
    /** How long {@link #close} lets the queue drain before it interrupts the errand: the contract's bound. */
    static final Duration CLOSE_WAIT = Duration.ofSeconds(2);
    /**
     * How often, at most, {@link QuestDbEndpoint#ensureUp} is asked and a recurring failure logged: the contract's
     * bound (record 146).
     */
    static final long MINUTE_NANOS = TimeUnit.MINUTES.toNanos(1);
    /**
     * The setting naming a second server, by its base URL, that gets every batch as well: a server to compare with the
     * machine's QuestDB. Unset, nothing else is written.
     */
    static final String SHADOW_ENV = "COMANDO_QDB_SHADOW";
    /** The contract's connect bound (record 146, §5): a server on this machine answers a connect at once. */
    private static final Duration CONNECT_WITHIN = Duration.ofSeconds(1);
    private static final System.Logger LOG = System.getLogger(MetricsSink.class.getName());
    /** What QuestDB says when a line names a table it does not have (its answer of 10.1.0, probed). */
    private static final String MISSING_TABLE = "table does not exist";
    /** The contract's request bound (record 146, §5). */
    private static final Duration REQUEST_WITHIN = Duration.ofSeconds(5);
    /**
     * Each shadow request's bound: they run on the batch thread after the server's write, so a silent shadow delays a
     * batch by at most this per request (its tables, then the write: seven at most); a loopback cdb answers in
     * milliseconds.
     */
    private static final Duration SHADOW_WITHIN = Duration.ofMillis(500);
    /** Put on the queue by {@link #close} to wake the errand; carries no event. */
    private static final ObjectNode WAKE = JsonNodeFactory.instance.objectNode();

    private final AtomicLong dropped = new AtomicLong();
    private final Errands errands = new Errands("metrics-sink");
    private final AtomicLong failed = new AtomicLong();
    /** Written by {@link #close} before it wakes the errand; read by the errand and by {@link #offer}. */
    private final AtomicBoolean halt = new AtomicBoolean();
    private final HttpClient http;
    private final ObjectMapper json;
    private final MetricsLines lines;
    private final LongSupplier nanos;
    private final IntentSink notices;
    private final QuestDbEndpoint qdb;
    private final ArrayBlockingQueue<ObjectNode> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicLong sent = new AtomicLong();
    /** The server of {@link #SHADOW_ENV}; null when there is none. */
    private final URI shadow;
    private final AtomicLong skipped = new AtomicLong();
    private final Thread thread;
    /** Event types with no table already logged; the errand's only. */
    private final Set<String> unknownLogged = new HashSet<>();
    /** Lines the close cut off before they were sent: written by the errand, read by {@link #close} once it stopped. */
    private final AtomicLong unsent = new AtomicLong();
    // the errand's only, from here on
    private long dropsLoggedAtNanos;
    private boolean dropsLoggedOnce;
    private long ensuredAtNanos;
    private boolean ensuredOnce;
    private long failureLoggedAtNanos;
    private boolean failureLoggedOnce;
    /** A write failed, not for a line refused alone, and none was taken since: the outage told in a notice. */
    private boolean outage;
    private long reportedDrops;
    private URI server;
    private boolean shadowLogged;
    /** The shadow server's tables are made, and no write to it failed since. */
    private boolean shadowReady;
    private URI tablesOn;

    /**
     * Starts the errand that sends the events, as {@code lines} maps them, to {@code qdb}; {@code nanos} times the
     * once-a-minute bounds, and an outage is told to {@code notices}. No shadow: the environment's is read by
     * {@code MetricsLog}'s public constructor alone, so a test of the sink never writes to the user's shadow.
     */
    MetricsSink(
            final QuestDbEndpoint qdb,
            final MetricsLines lines,
            final ObjectMapper json,
            final LongSupplier nanos,
            final IntentSink notices
    ) {
        this(qdb, lines, json, nanos, notices, null);
    }

    /**
     * As the sink above, each batch also sent to {@code shadow}, a base URL, after the server had it; none when it is
     * null or blank. A shadow that fails is logged once and is otherwise ignored: what is counted, logged and told of
     * the server's writes never depends on it.
     */
    MetricsSink(
            final QuestDbEndpoint qdb,
            final MetricsLines lines,
            final ObjectMapper json,
            final LongSupplier nanos,
            final IntentSink notices,
            final String shadow
    ) {
        this.qdb = qdb;
        this.lines = lines;
        this.json = json;
        this.nanos = nanos;
        this.notices = notices;
        this.shadow = shadowOf(shadow);
        if (this.shadow != null) {
            LOG.log(System.Logger.Level.INFO, "metrics also go to a shadow server [server=" + this.shadow + ']');
        }
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_WITHIN).build();
        try {
            this.thread = errands.start("metrics-sink", this::drain);
        } catch (final RuntimeException | Error e) {
            http.shutdownNow();
            throw e;
        }
    }

    /**
     * Stops taking events, lets the errand send what is queued for at most {@link #CLOSE_WAIT}, then interrupts it,
     * counts and logs what it did not send, and ends the HTTP client. Idempotent; never stops the server.
     */
    @Override
    public void close() {
        if (!halt.compareAndSet(false, true)) {
            return;
        }
        // the flag is written before the wake-up; a full queue needs none, the errand is draining it
        queue.offer(WAKE);
        try {
            if (thread != null) {
                thread.join(CLOSE_WAIT);
            }
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        errands.close();
        reportLostAtClose();
        http.shutdownNow();
        try {
            if (!http.awaitTermination(CLOSE_WAIT)) {
                LOG.log(System.Logger.Level.WARNING, "metrics sink's HTTP client still running at close [wait="
                        + CLOSE_WAIT + ']');
            }
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /** Hands {@code event} to the errand; it must not change after. Never blocks, never throws. */
    void offer(final ObjectNode event) {
        if (halt.get() || !queue.offer(event)) {
            dropped.incrementAndGet();
        }
    }

    /**
     * Runs {@code sql} on the server at {@code base} through {@code /exec}, waiting at most {@code timeout} for the
     * answer; its JSON. Any answer but 200 is an {@link IOException}.
     */
    JsonNode exec(final URI base, final String sql, final Duration timeout) throws IOException, InterruptedException {
        final String query = URLEncoder.encode(sql, StandardCharsets.UTF_8).replace("+", "%20");
        final HttpRequest request = HttpRequest.newBuilder(base.resolve("/exec?query=" + query)).timeout(timeout)
                .GET().build();
        final HttpResponse<String> answer = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (answer.statusCode() != 200) {
            throw new IOException("QuestDB refused a query [status=" + answer.statusCode() + ", answer="
                    + shortened(answer.body()) + ']');
        }
        return json.readTree(answer.body());
    }

    /** Lines the server took. */
    @TestOnly
    long sentLines() {
        return sent.get();
    }

    /** Lines of batches that failed. */
    @TestOnly
    long failedLines() {
        return failed.get();
    }

    /** Events dropped at a full queue or after the close. */
    @TestOnly
    long droppedEvents() {
        return dropped.get();
    }

    /** The base URL in {@code value}; null when it is null, blank, or not an absolute URL, the last one logged. */
    static URI shadowOf(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            final URI uri = new URI(value.strip());
            if (uri.getScheme() != null && uri.getHost() != null) {
                return uri;
            }
        } catch (final URISyntaxException _) {
            // logged below, as a value with no scheme or host
        }
        LOG.log(System.Logger.Level.WARNING, "ignoring the shadow server, not a base URL [" + SHADOW_ENV + '=' + value
                + ']');
        return null;
    }

    private static String shortened(final String text) {
        final String flat = text.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }

    /** The errand: batches until the close, then what is left. */
    private void drain() {
        final List<String> batch = new ArrayList<>();
        boolean more = true;
        while (more) {
            batch.clear();
            more = collect(batch);
            int from = 0;
            while (from < batch.size() && !Thread.currentThread().isInterrupted()) {
                final List<String> chunk = batch.subList(from, Math.min(batch.size(), from + BATCH_LINES));
                deliver(chunk);
                shadow(chunk);
                from += BATCH_LINES;
            }
            if (Thread.currentThread().isInterrupted()) {
                // the close cut the errand: the batch's lines not tried are counted, the queue's rest by the close
                unsent.addAndGet(Math.max(0, batch.size() - from));
                more = false;
            }
            reportDrops();
        }
        LOG.log(System.Logger.Level.INFO, "metrics sink stopped [sentLines=" + sent.get() + ", failedLines="
                + failed.get() + ", droppedEvents=" + dropped.get() + ", skippedEvents=" + skipped.get() + ']');
    }

    /**
     * Fills {@code batch} up to {@link #BATCH_LINES} or {@link #BATCH_WAIT} after its first event; false when the
     * errand is to stop once it is sent.
     */
    private boolean collect(final List<String> batch) {
        long deadlineNanos = 0L;
        while (batch.size() < BATCH_LINES) {
            final ObjectNode e;
            try {
                if (halt.get()) {
                    e = queue.poll();
                    if (e == null) {
                        return false;
                    }
                } else if (batch.isEmpty()) {
                    e = queue.poll(BATCH_WAIT.toNanos(), TimeUnit.NANOSECONDS);
                    if (e == null) {
                        return true; // nothing yet: the halt flag is read again
                    }
                    deadlineNanos = System.nanoTime() + BATCH_WAIT.toNanos();
                } else {
                    final long leftNanos = deadlineNanos - System.nanoTime();
                    e = leftNanos > 0L ? queue.poll(leftNanos, TimeUnit.NANOSECONDS) : null;
                    if (e == null) {
                        return true;
                    }
                }
            } catch (final InterruptedException _) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (e != WAKE) {
                map(e, batch);
            }
        }
        return true;
    }

    /**
     * Adds the lines of {@code event} to {@code batch}; an event of a type with no table is counted and logged once.
     */
    private void map(final ObjectNode event, final List<String> batch) {
        final String type = event.path("event").asString("");
        if (!MetricsLines.known(type)) {
            skipped.incrementAndGet();
            if (unknownLogged.add(type)) {
                LOG.log(System.Logger.Level.WARNING, "metrics event has no table, not sent [event=" + type + ']');
            }
            return;
        }
        try {
            batch.addAll(lines.of(event));
        } catch (final RuntimeException e) {
            skipped.incrementAndGet();
            LOG.log(System.Logger.Level.WARNING, "cannot map a metrics event [event=" + type + ']', e);
        }
    }

    /**
     * Sends {@code chunk}, at most {@link #BATCH_LINES} lines, counting its lines as sent or failed; a refused
     * connection is tried once more, on the server found again. QuestDB refuses a whole write for one line it does not
     * take (400): a chunk so refused, for any reason but a missing table, is sent again in halves, down to the lines it
     * refuses alone, so that only those are counted failed; each such line costs at most 2 * ceil(log2 n) more writes.
     * A line refused alone is a refusal of its data, not an outage: it is no notice.
     */
    private void deliver(final List<String> chunk) {
        try {
            HttpResponse<String> answer;
            try {
                answer = attempt(chunk, null);
            } catch (final ConnectException _) {
                final URI refused = server;
                server = null;
                tablesOn = null;
                answer = attempt(chunk, refused);
            }
            if (answer != null && answer.statusCode() == 400 && !missingTable(answer.body())) {
                if (chunk.size() > 1) {
                    split(chunk);
                } else {
                    refused(chunk, answer.body());
                }
                return;
            }
            took(answer == null ? -1 : answer.statusCode(), chunk);
        } catch (final ConnectException _) {
            server = null;
            tablesOn = null;
            failure(chunk, "cannot reach QuestDB", "server=refused", null);
        } catch (final IOException | RuntimeException e) {
            failure(chunk, "cannot write metrics to QuestDB", "lines=" + chunk.size(), e);
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
            unsent.addAndGet(chunk.size());
        }
    }

    /** Each half of {@code chunk}, which the server refused, delivered; the second not tried once the close cut in. */
    private void split(final List<String> chunk) {
        final int half = chunk.size() / 2;
        deliver(chunk.subList(0, half));
        if (Thread.currentThread().isInterrupted()) {
            unsent.addAndGet(chunk.size() - half);
            return;
        }
        deliver(chunk.subList(half, chunk.size()));
    }

    /**
     * Counts {@code line}, a write of one line the server refuses alone, failed, and logs it at most once a minute with
     * the recurring failures: a refusal of its data, not an outage, so no notice, and an outage under way goes on.
     */
    private void refused(final List<String> line, final String answer) {
        final long total = failed.addAndGet(line.size());
        if (mayLogFailure(nanos.getAsLong())) {
            LOG.log(System.Logger.Level.WARNING, "QuestDB refused a line, its metrics row is lost [answer="
                    + shortened(answer) + ", failedLines=" + total + ']');
        }
    }

    /**
     * The answer to one write of {@code chunk}, the tables made again once when the server says one is missing; null
     * when no server answers. {@code refused} is the server that just refused a connection, or null.
     */
    private HttpResponse<String> attempt(
            final List<String> chunk,
            final URI refused
    ) throws IOException, InterruptedException {
        final URI base = connect(refused);
        if (base == null) {
            return null;
        }
        HttpResponse<String> answer = post(base, chunk, REQUEST_WITHIN);
        if (answer.statusCode() == 400 && missingTable(answer.body())) {
            createTables(base, REQUEST_WITHIN);
            answer = post(base, chunk, REQUEST_WITHIN);
        }
        if (answer.statusCode() != 204) {
            LOG.log(System.Logger.Level.DEBUG, "QuestDB refused a write [status=" + answer.statusCode() + ", answer="
                    + shortened(answer.body()) + ']');
        }
        return answer;
    }

    private void took(final int status, final List<String> chunk) {
        if (status == 204) {
            sent.addAndGet(chunk.size());
            if (outage) {
                outage = false;
                LOG.log(System.Logger.Level.INFO, "metrics reach QuestDB again [server=" + server + ", failedLines="
                        + failed.get() + ']');
            }
        } else if (status < 0) {
            failure(chunk, "cannot reach QuestDB", "server=none", null);
        } else {
            failure(chunk, "QuestDB refused a write", "status=" + status, null);
        }
    }

    /**
     * The server to write to, its tables made; null when no server answers. When none runs, or the one found is
     * {@code refused}, the endpoint is asked to start one, at most once a minute.
     */
    private URI connect(final URI refused) throws IOException, InterruptedException {
        if (server == null) {
            URI found = qdb.live().orElse(null);
            if ((found == null || found.equals(refused)) && mayEnsure()) {
                found = qdb.ensureUp().orElse(null);
            }
            if (found == null) {
                return null;
            }
            server = found;
        }
        final URI base = server;
        if (!base.equals(tablesOn)) {
            createTables(base, REQUEST_WITHIN);
            tablesOn = base;
            LOG.log(System.Logger.Level.INFO, "metrics go to QuestDB [server=" + base + ']');
        }
        return base;
    }

    private boolean mayEnsure() {
        final long nowNanos = nanos.getAsLong();
        if (ensuredOnce && nowNanos - ensuredAtNanos < MINUTE_NANOS) {
            return false;
        }
        ensuredOnce = true;
        ensuredAtNanos = nowNanos;
        return true;
    }

    private void createTables(final URI base, final Duration within) throws IOException, InterruptedException {
        for (final String ddl : MetricsLines.DDL) {
            exec(base, ddl, within);
        }
    }

    private HttpResponse<String> post(
            final URI base,
            final List<String> chunk,
            final Duration within
    ) throws IOException, InterruptedException {
        final StringBuilder body = new StringBuilder();
        for (final String line : chunk) {
            body.append(line).append('\n');
        }
        final HttpRequest request = HttpRequest.newBuilder(base.resolve("/write?precision=n")).timeout(within)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * Sends {@code chunk} to the shadow server too, its tables made first after the start or a failed write; the first
     * failure is logged and every one ignored, not counted, retried or told, so the server's writes never see it.
     */
    private void shadow(final List<String> chunk) {
        if (shadow == null || Thread.currentThread().isInterrupted()) {
            return;
        }
        try {
            if (!shadowReady) {
                createTables(shadow, SHADOW_WITHIN);
                shadowReady = true;
            }
            final int status = post(shadow, chunk, SHADOW_WITHIN).statusCode();
            if (status != 204) {
                shadowReady = false;
                shadowFailed("status=" + status, null);
            }
        } catch (final IOException | RuntimeException e) {
            shadowReady = false;
            shadowFailed("lines=" + chunk.size(), e);
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private void shadowFailed(final String values, final Throwable cause) {
        if (shadowLogged) {
            return;
        }
        shadowLogged = true;
        LOG.log(System.Logger.Level.WARNING, "cannot write metrics to the shadow server, its failures ignored from"
                + " now on [server=" + shadow + ", " + values + ']', cause);
    }

    /** Whether a refused write's answer says a line names a table the server does not have. */
    private boolean missingTable(final String answer) {
        try {
            return json.readTree(answer).path("message").asString("").contains(MISSING_TABLE);
        } catch (final RuntimeException _) {
            return false;
        }
    }

    /**
     * Counts {@code chunk} failed and logs why, at most once a minute; the first failure of an outage is a notice as
     * well, logged with it. A failure the close caused, the errand interrupted, is counted for the close to log.
     */
    private void failure(final List<String> chunk, final String what, final String values, final Throwable cause) {
        if (Thread.currentThread().isInterrupted()) {
            unsent.addAndGet(chunk.size());
            return;
        }
        final long total = failed.addAndGet(chunk.size());
        final long nowNanos = nanos.getAsLong();
        final String message = what + ", the metrics rows are lost [" + values + ", failedLines=" + total + ']';
        if (!outage) {
            outage = true;
            failureLoggedOnce = true;
            failureLoggedAtNanos = nowNanos;
            notices.dispatch(Intent.Notify.logged(LOG, Notice.Level.WARN,
                    Character.toUpperCase(message.charAt(0)) + message.substring(1), cause));
            return;
        }
        if (!mayLogFailure(nowNanos)) {
            return;
        }
        if (cause == null) {
            LOG.log(System.Logger.Level.WARNING, message);
        } else {
            LOG.log(System.Logger.Level.WARNING, message, cause);
        }
    }

    /** Whether a recurring failure may be logged at {@code nowNanos}, once a minute at most; if so, a minute starts. */
    private boolean mayLogFailure(final long nowNanos) {
        if (failureLoggedOnce && nowNanos - failureLoggedAtNanos < MINUTE_NANOS) {
            return false;
        }
        failureLoggedOnce = true;
        failureLoggedAtNanos = nowNanos;
        return true;
    }

    /**
     * Counts the events left in the queue as dropped and the lines the errand did not send as failed, and logs them
     * once; no notice, as the state loop is closing too.
     */
    private void reportLostAtClose() {
        long queued = 0L;
        for (ObjectNode e = queue.poll(); e != null; e = queue.poll()) {
            if (e != WAKE) {
                queued++;
            }
        }
        dropped.addAndGet(queued);
        final long lines = unsent.get();
        failed.addAndGet(lines);
        if (queued + lines > 0L) {
            LOG.log(System.Logger.Level.WARNING, "metric events lost at close [queuedEvents=" + queued
                    + ", unsentLines=" + lines + ", wait=" + CLOSE_WAIT + ']');
        }
    }

    private void reportDrops() {
        final long drops = dropped.get();
        if (drops == reportedDrops) {
            return;
        }
        final long nowNanos = nanos.getAsLong();
        if (dropsLoggedOnce && nowNanos - dropsLoggedAtNanos < MINUTE_NANOS) {
            return;
        }
        dropsLoggedOnce = true;
        dropsLoggedAtNanos = nowNanos;
        reportedDrops = drops;
        LOG.log(System.Logger.Level.WARNING, "metrics events dropped, the queue full, they are lost"
                + " [droppedEvents=" + drops + ", capacity=" + CAPACITY + ']');
    }
}
