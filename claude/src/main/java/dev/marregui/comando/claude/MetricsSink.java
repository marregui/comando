package dev.marregui.comando.claude;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
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

import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.loop.Errands;
import dev.marregui.comando.core.model.AtomicFiles;

/**
 * The metrics events on their way to the machine's QuestDB (record 146), beside the day's file, which stays the record.
 * {@link #offer} never blocks: an event joins a queue of {@link #CAPACITY}, or, the queue full or the sink closed, is
 * dropped and counted. One errand drains the queue in batches of at most {@link #BATCH_LINES} lines, sent
 * {@link #BATCH_WAIT} after a batch's first event at the latest, to the server {@link QuestDbEndpoint#live} names; when
 * none answers, {@link QuestDbEndpoint#ensureUp} is asked at most once a minute. The tables are created the first time
 * a process writes to a server, and again after a write is refused for a missing table.
 * <p>
 * Delivery is at most once per event offered: a batch that fails is dropped, counted, and logged at most once a minute.
 * The files hold it: on its first connection a process imports every day's file not yet listed in {@link #IMPORTED},
 * and today's always, through the same mapping, and the tables' dedup keys keep one row per event however often it is
 * sent.
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
    /** The file in the metrics folder that lists the day files already imported, one name a line. */
    static final String IMPORTED = "qdb-imported.txt";
    /**
     * How often, at most, {@link QuestDbEndpoint#ensureUp} is asked and a recurring failure logged: the contract's
     * bound (record 146), as the start it may cause takes up to a minute.
     */
    static final long MINUTE_NANOS = TimeUnit.MINUTES.toNanos(1);
    /** The contract's connect bound (record 146, §5): a server on this machine answers a connect at once. */
    private static final Duration CONNECT_WITHIN = Duration.ofSeconds(1);
    private static final System.Logger LOG = System.getLogger(MetricsSink.class.getName());
    /** What QuestDB says when a line names a table it does not have (its answer of 10.1.0, probed). */
    private static final String MISSING_TABLE = "table does not exist";
    /** The contract's request bound (record 146, §5). */
    private static final Duration REQUEST_WITHIN = Duration.ofSeconds(5);
    /** Put on the queue by {@link #close} to wake the errand; carries no event. */
    private static final ObjectNode WAKE = JsonNodeFactory.instance.objectNode();

    private final Path dir;
    private final AtomicLong dropped = new AtomicLong();
    private final Errands errands = new Errands("metrics-sink");
    private final AtomicLong failed = new AtomicLong();
    /** Written by {@link #close} before it wakes the errand; read by the errand and by {@link #offer}. */
    private final AtomicBoolean halt = new AtomicBoolean();
    private final HttpClient http;
    private final ObjectMapper json;
    private final MetricsLines lines;
    private final LongSupplier nanos;
    private final QuestDbEndpoint qdb;
    private final ArrayBlockingQueue<ObjectNode> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private final Thread thread;
    /** Event types with no table already logged; the errand's only. */
    private final Set<String> unknownLogged = new HashSet<>();
    private final LongSupplier wallMillis;
    // the errand's only, from here on
    private long dropsLoggedAtNanos;
    private boolean dropsLoggedOnce;
    private long ensuredAtNanos;
    private boolean ensuredOnce;
    private long failureLoggedAtNanos;
    private boolean failureLoggedOnce;
    private boolean imported;
    private long reportedDrops;
    private URI server;
    private URI tablesOn;

    /**
     * Starts the errand that sends the events of metrics folder {@code dir} to {@code qdb}; {@code wallMillis} dates
     * today's file, {@code nanos} times the once-a-minute bounds.
     */
    MetricsSink(final Path dir, final QuestDbEndpoint qdb, final MetricsLines lines, final ObjectMapper json,
            final LongSupplier wallMillis, final LongSupplier nanos) {
        this.dir = dir;
        this.qdb = qdb;
        this.lines = lines;
        this.json = json;
        this.wallMillis = wallMillis;
        this.nanos = nanos;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_WITHIN).build();
        try {
            this.thread = errands.start("metrics-sink", this::drain);
        } catch (final RuntimeException | Error e) {
            http.shutdownNow();
            throw e;
        }
    }

    /**
     * Stops taking events, lets the errand send what is queued for at most {@link #CLOSE_WAIT}, then interrupts it and
     * ends the HTTP client. Idempotent; never stops the server.
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
        final HttpResponse<String> answer = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
            for (int from = 0; from < batch.size(); from += BATCH_LINES) {
                deliver(batch.subList(from, Math.min(batch.size(), from + BATCH_LINES)));
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

    /** Adds the lines of {@code event} to {@code batch}; an event of a type with no table is counted and logged once. */
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
     * Sends {@code chunk}, at most {@link #BATCH_LINES} lines, counting its lines as sent or failed; a refused connection
     * is tried once more, on the server found again.
     */
    private boolean deliver(final List<String> chunk) {
        try {
            try {
                return took(attempt(chunk, null), chunk);
            } catch (final ConnectException _) {
                final URI refused = server;
                server = null;
                tablesOn = null;
                return took(attempt(chunk, refused), chunk);
            }
        } catch (final ConnectException _) {
            server = null;
            tablesOn = null;
            return failure(chunk, "cannot reach QuestDB", "server=refused", null);
        } catch (final IOException | RuntimeException e) {
            return failure(chunk, "cannot write metrics to QuestDB", "lines=" + chunk.size(), e);
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
            return failure(chunk, "metrics write interrupted", "lines=" + chunk.size(), null);
        }
    }

    /**
     * The status of one write of {@code chunk}, the tables made again once when the server says one is missing; -1 when
     * no server answers. {@code refused} is the server that just refused a connection, or null.
     */
    private int attempt(final List<String> chunk, final URI refused) throws IOException, InterruptedException {
        final URI base = connect(refused);
        if (base == null) {
            return -1;
        }
        HttpResponse<String> answer = post(base, chunk);
        if (answer.statusCode() == 400 && missingTable(answer.body())) {
            createTables(base);
            answer = post(base, chunk);
        }
        if (answer.statusCode() != 204) {
            LOG.log(System.Logger.Level.DEBUG, "QuestDB refused a write [status=" + answer.statusCode() + ", answer="
                    + shortened(answer.body()) + ']');
        }
        return answer.statusCode();
    }

    private boolean took(final int status, final List<String> chunk) {
        if (status == 204) {
            sent.addAndGet(chunk.size());
            return true;
        }
        return status < 0 ? failure(chunk, "cannot reach QuestDB", "server=none", null)
                : failure(chunk, "QuestDB refused a write", "status=" + status, null);
    }

    /**
     * The server to write to, its tables made and, the first time in the process, the files imported; null when no
     * server answers. When none runs, or the one found is {@code refused}, the endpoint is asked to start one, at most
     * once a minute.
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
            createTables(base);
            tablesOn = base;
            LOG.log(System.Logger.Level.INFO, "metrics go to QuestDB [server=" + base + ", dir=" + dir + ']');
            if (!imported) {
                imported = true;
                importFiles();
            }
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

    private void createTables(final URI base) throws IOException, InterruptedException {
        for (final String ddl : MetricsLines.DDL) {
            exec(base, ddl, REQUEST_WITHIN);
        }
    }

    private HttpResponse<String> post(final URI base, final List<String> chunk) throws IOException, InterruptedException {
        final StringBuilder body = new StringBuilder();
        for (final String line : chunk) {
            body.append(line).append('\n');
        }
        final HttpRequest request = HttpRequest.newBuilder(base.resolve("/write?precision=n")).timeout(REQUEST_WITHIN)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Whether a refused write's answer says a line names a table the server does not have. */
    private boolean missingTable(final String answer) {
        try {
            return json.readTree(answer).path("message").asString("").contains(MISSING_TABLE);
        } catch (final RuntimeException _) {
            return false;
        }
    }

    /** Counts {@code chunk} failed and logs why, at most once a minute; false. */
    private boolean failure(final List<String> chunk, final String what, final String values, final Throwable cause) {
        final long total = failed.addAndGet(chunk.size());
        final long nowNanos = nanos.getAsLong();
        if (failureLoggedOnce && nowNanos - failureLoggedAtNanos < MINUTE_NANOS) {
            return false;
        }
        failureLoggedOnce = true;
        failureLoggedAtNanos = nowNanos;
        final String message = what + ", the day's file keeps it [" + values + ", failedLines=" + total + ']';
        if (cause == null) {
            LOG.log(System.Logger.Level.WARNING, message);
        } else {
            LOG.log(System.Logger.Level.WARNING, message, cause);
        }
        return false;
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
        LOG.log(System.Logger.Level.WARNING, "metrics events dropped, the queue full, the day's file keeps them"
                + " [droppedEvents=" + drops + ", capacity=" + CAPACITY + ']');
    }

    /**
     * Sends every day file not listed in {@link #IMPORTED}, oldest first, and today's, and lists each past day's file
     * the server took whole. Stops at the first file the server did not take, and at the close.
     */
    private void importFiles() {
        if (!Files.isDirectory(dir)) {
            return;
        }
        final Path list = dir.resolve(IMPORTED);
        final Set<String> done = new LinkedHashSet<>();
        final List<Path> files = new ArrayList<>();
        try {
            if (Files.isRegularFile(list)) {
                for (final String name : Mapped.lines(list)) {
                    if (!name.isBlank()) {
                        done.add(name.strip());
                    }
                }
            }
            try (final DirectoryStream<Path> found = Files.newDirectoryStream(dir, "metrics-*.jsonl")) {
                for (final Path f : found) {
                    files.add(f);
                }
            }
        } catch (final IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot list the metrics files to import [dir=" + dir + ']', e);
            return;
        }
        files.sort(Comparator.comparing(f -> f.getFileName().toString()));
        final String today = "metrics-" + LocalDate.ofInstant(Instant.ofEpochMilli(wallMillis.getAsLong()),
                ZoneOffset.UTC) + ".jsonl";
        for (final Path file : files) {
            final String name = file.getFileName().toString();
            final boolean past = name.compareTo(today) < 0;
            if (past && done.contains(name)) {
                continue;
            }
            final Imported result = importFile(file);
            if (result == Imported.CUT) {
                LOG.log(System.Logger.Level.INFO, "metrics import stopped, the next start goes on [file=" + name + ']');
                return;
            }
            if (result == Imported.WHOLE && past) {
                done.add(name);
                try {
                    AtomicFiles.writeString(list, String.join("\n", done) + "\n");
                } catch (final IOException e) {
                    LOG.log(System.Logger.Level.WARNING, "cannot list an imported metrics file [list=" + list
                            + ", file=" + name + ']', e);
                }
            }
        }
    }

    /** Sends the events of one day file, a batch at a time. */
    private Imported importFile(final Path file) {
        final List<String> texts;
        try {
            texts = Mapped.read(file, m -> {
                final List<String> out = new ArrayList<>();
                m.lines(line -> {
                    out.add(Mapped.decode(line));
                    return true;
                });
                return out;
            });
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read a metrics file to import [file=" + file + ']', e);
            return Imported.UNREADABLE;
        }
        final List<String> batch = new ArrayList<>();
        int events = 0;
        int malformed = 0;
        long taken = 0L;
        for (final String text : texts) {
            if (text.isBlank()) {
                continue;
            }
            final JsonNode node;
            try {
                node = json.readTree(text);
            } catch (final RuntimeException _) {
                malformed++;
                continue;
            }
            if (!(node instanceof ObjectNode event)) {
                malformed++;
                continue;
            }
            events++;
            map(event, batch);
            if (batch.size() >= BATCH_LINES) {
                if (halt.get() || !deliver(batch)) {
                    return Imported.CUT;
                }
                taken += batch.size();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            if (halt.get() || !deliver(batch)) {
                return Imported.CUT;
            }
            taken += batch.size();
        }
        LOG.log(System.Logger.Level.INFO, "imported a metrics file into QuestDB [file=" + file.getFileName()
                + ", events=" + events + ", lines=" + taken + ", malformed=" + malformed + ']');
        return Imported.WHOLE;
    }

    /** How the import of one file ended. */
    private enum Imported {
        /** The server took every line. */
        WHOLE,
        /** The file could not be read; the others are imported. */
        UNREADABLE,
        /** The server did not take a batch, or the sink closes: the import stops. */
        CUT
    }
}
