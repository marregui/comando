package dev.marregui.comando.cdb.serve;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbCancel;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbResult;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.sql.Ast;
import dev.marregui.comando.cdb.sql.Parser;
import dev.marregui.comando.cdb.store.Store;

/**
 * cdb over HTTP with QuestDB's wire formats, so the kit's clients work unchanged: {@code GET /exec?query=…} answers
 * QuestDB's JSON ({@code query}, {@code columns} with {@code name} and {@code type}, {@code timestamp},
 * {@code dataset}, {@code count}; an error as {@code {"query","error","position"}} with status 400), and
 * {@code POST /write} takes line protocol (204 on success). Started and stopped by its owner; one per Cdb.
 *
 * <p>{@code /exec} takes {@code limit=n} (at most n rows), {@code limit=lo,hi} (rows lo to hi, counted from 1) and {@code max_lag_ms=n} (the query's reads may lag the
 * answered commits by at most n milliseconds; refused when negative or not a number), and accepts and ignores
 * {@code count} and {@code nm}. A
 * request with no query text is answered 200 with {@code "error":"empty query"}, as QuestDB answers it. A
 * {@code /write} body is parsed whole before its first row reaches the store, so a malformed line (400, with its line
 * and offset) writes nothing; a row the store refuses (400, with its line) writes none of its table's rows of the body. Concurrent bodies for one table are written together in one commit, different tables in parallel
 * ({@link CommitQueue}); a body that touches several tables is written table by table, not atomically across them, and
 * answers when every table is done. {@code /write?durable=true} answers 204 only once its rows are durable, waiting
 * a bounded time for the store's log force: 503 when the wait runs out and 500 ("commit in doubt") when the force
 * failed, the rows written either way; {@code durable=false} or none answers once they are committed. Each request
 * runs on a virtual thread of its own; past {@link #MAX_REQUESTS} in flight, a new connection is closed unanswered.
 *
 * <p>Started with {@link Secrets}, a request carries {@code Authorization: Bearer <secret>}: none or a wrong one is
 * answered 401, the read-only secret on a statement that is not a query, {@code SHOW} or {@code EXPLAIN}, or on
 * {@code /write}, 403. Bound to a loopback address, a request whose {@code Host} is not {@code 127.0.0.1},
 * {@code localhost} or {@code [::1]} with the server's port is answered 403, so a page that renamed its host to the
 * loopback address reaches nothing; a request with an {@code Origin}, which only a browser sends, is answered 403
 * wherever the server is bound. Each refusal keeps the endpoint's JSON error shape. A query runs at most
 * {@link Limits#QUERY_DEADLINE_MILLIS}, then is cancelled and answered 408; a query result over {@link Limits#MAX_ROWS}
 * rows, however many {@code limit} asked for, is answered 400 rather than cut short; the {@code /write} bodies being
 * read and written at once hold at most {@link Limits#BODY_BUDGET_BYTES}, and a body that cannot get its share within
 * {@link Limits#BODY_WAIT_MILLIS} is answered 503. A log line names a request by its path, never by its query. A
 * result is answered chunked, written as it is formatted, and a failure once it is under way drops the connection
 * rather than end the answer as if whole; a body of up to {@link Limits#POOLED_BODY_BYTES} is read into a buffer the
 * server keeps for the next one, while one of {@link #POOLED_BODIES} is free, and holds the buffer's size of the
 * budget.
 */
public final class CdbServer implements AutoCloseable {

    /** Not measured: the kit's clients send one request at a time per process; a few dozen run at once at most. */
    static final int MAX_REQUESTS = 64;
    /** The kit's {@code qdb} sends at most 1,000 lines a request, a few hundred bytes each; not measured beyond it. */
    static final int MAX_BODY_BYTES = 16 << 20;
    /**
     * Body buffers the server makes and keeps, 16 MiB in all, a quarter of {@link Limits#BODY_BUDGET_BYTES}: one in use
     * holds its size of the budget, an idle one none. Not measured: the kit's clients send one write at a time per
     * process, so a few cover them; a write while all are in use reads its body into an array of its own, as a longer
     * body does.
     */
    static final int POOLED_BODIES = 16;
    private static final String BEARER = "Bearer ";
    private static final String JSON = "application/json; charset=utf-8";
    private static final System.Logger LOG = System.getLogger(CdbServer.class.getName());
    /** Not measured: a query of the corpus runs in milliseconds; one second lets the ones in flight answer. */
    private static final int STOP_DELAY_SECONDS = 1;

    private final Admission admission;
    private final BodyBuffers bodies;
    private final CommitQueue commits;
    private final int port;
    private final HttpServer server;
    private boolean closed;

    private CdbServer(final HttpServer server, final Admission admission, final CommitQueue commits,
            final BodyBuffers bodies) {
        this.server = server;
        this.admission = admission;
        this.commits = commits;
        this.bodies = bodies;
        this.port = server.getAddress().getPort();
    }

    /** Serves {@code cdb} on {@code address} until {@link #close()}, to every client. */
    public static CdbServer start(final Cdb cdb, final InetSocketAddress address) {
        return start(new CdbBackend(cdb), address);
    }

    /** Serves {@code cdb} on {@code address} until {@link #close()}, to a client with one of {@code secrets}. */
    public static CdbServer start(final Cdb cdb, final InetSocketAddress address, final Secrets secrets) {
        return start(new CdbBackend(cdb), address, secrets, Bounds.DEFAULT);
    }

    /**
     * Sets the JDK HTTP server's bounds from {@link Limits}, each unless already set: the time to read a request, the
     * time to write a response, the open connections. The JDK reads them once, when its first server is made, so a
     * launcher calls this before it starts one.
     */
    public static void applyJdkLimits() {
        limit("sun.net.httpserver.maxReqTime", Limits.HTTP_MAX_REQUEST_SECONDS);
        limit("sun.net.httpserver.maxRspTime", Limits.HTTP_MAX_RESPONSE_SECONDS);
        limit("jdk.httpserver.maxConnections", Limits.HTTP_MAX_CONNECTIONS);
    }

    /** The port served, the real one when started on port 0. */
    public int port() {
        return port;
    }

    /**
     * Stops serving; idempotent. Requests in flight get {@link #STOP_DELAY_SECONDS} to answer; the writes already queued
     * are then committed, a write queued after it is refused (400).
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            server.stop(STOP_DELAY_SECONDS);
        } finally {
            commits.close();
            admission.close();
        }
        LOG.log(Level.INFO, "stopped serving cdb [port=" + port + ']');
    }

    /** The {@code /write} batches of {@code table} waiting for their commit. */
    @TestOnly
    int queued(final String table) {
        return commits.queued(table);
    }

    /** The body buffers kept for the next {@code /write}, at most {@link #POOLED_BODIES}. */
    @TestOnly
    int pooledBodies() {
        return bodies.pooled();
    }

    static CdbServer start(final Backend backend, final InetSocketAddress address) {
        return start(backend, address, null, Bounds.DEFAULT);
    }

    /** Serves {@code backend} to a client with one of {@code secrets} (any client when null) within {@code bounds}. */
    static CdbServer start(final Backend backend, final InetSocketAddress address, final Secrets secrets,
            final Bounds bounds) {
        final Admission admission = new Admission();
        final CommitQueue commits = new CommitQueue(backend::appender);
        final BodyBuffers bodies = new BodyBuffers();
        // fair, so a large body waiting for its share is not passed over by smaller ones until its wait runs out
        final Semaphore budget = new Semaphore(Math.toIntExact(bounds.bodyBudgetBytes()), true);
        HttpServer server = null;
        try {
            server = HttpServer.create(address, 0);
            final InetSocketAddress bound = server.getAddress();
            final Gate gate = new Gate(secrets, bound.getAddress().isLoopbackAddress(), bound.getPort());
            server.setExecutor(admission);
            server.createContext("/exec",
                    exchange -> handle(exchange, gate, role -> exec(backend, admission, bounds, role, exchange)));
            server.createContext("/write",
                    exchange -> handle(exchange, gate, role -> write(commits, bodies, budget, bounds, role, exchange)));
            server.start();
            LOG.log(Level.INFO, "serving cdb [address=" + server.getAddress() + ']');
            return new CdbServer(server, admission, commits, bodies);
        } catch (final IOException e) {
            abandon(server, admission);
            throw new CdbException("could not serve cdb [address=" + address + ']', e);
        } catch (final RuntimeException | Error e) {
            abandon(server, admission);
            throw e;
        }
    }

    private static void abandon(final HttpServer server, final Admission admission) {
        try {
            if (server != null) {
                server.stop(0);
            }
        } finally {
            admission.close();
        }
    }

    private static void limit(final String property, final int value) {
        if (System.getProperty(property) == null) {
            System.setProperty(property, Integer.toString(value));
        }
    }

    // every exchange is answered and closed: a handler that throws would leave the client without a status. An answer
    // whose status went out before its handler failed is left open instead, as closing it would end its chunked body
    // as if whole: the IOException thrown, an Error wrapped too, makes the JDK's server drop the connection (it
    // rethrows an Error and leaves the connection open), so its client sees the answer fail. The log names the path
    // alone: the query string holds the statement, which may carry what its client keeps to itself
    private static void handle(final HttpExchange exchange, final Gate gate, final Handler handler)
            throws IOException {
        boolean answered = false;
        try {
            final Role role = admit(exchange, gate);
            if (role != Role.NONE) {
                handler.run(role);
            }
            answered = true;
        } catch (final RuntimeException e) {
            LOG.log(Level.WARNING, "could not answer request [path=" + exchange.getRequestURI().getPath() + ']', e);
            if (exchange.getResponseCode() >= 0) {
                throw cutShort(exchange, e);
            }
            send(exchange, 500, JsonWriter.error("", "could not answer request [error=" + e + ']', -1));
            answered = true;
        } catch (final Error e) {
            if (exchange.getResponseCode() >= 0) {
                LOG.log(Level.WARNING, "could not answer request [path=" + exchange.getRequestURI().getPath() + ']',
                        e);
                throw cutShort(exchange, e);
            }
            throw e;
        } finally {
            if (answered || exchange.getResponseCode() < 0) {
                exchange.close();
            }
        }
    }

    private static IOException cutShort(final HttpExchange exchange, final Throwable cause) {
        return new IOException("cut answer short [path=" + exchange.getRequestURI().getPath() + ']', cause);
    }

    // the client's role, or NONE once the request is answered: a browser's request (it alone sends Origin) and one for
    // a host name other than loopback's are refused before the secret is read
    private static Role admit(final HttpExchange exchange, final Gate gate) throws IOException {
        final Headers headers = exchange.getRequestHeaders();
        final String origin = headers.getFirst("Origin");
        if (origin != null) {
            return refuse(exchange, 403, "refuse request from a browser page [origin=" + origin + ']');
        }
        if (gate.loopback() && !gate.isLoopbackHost(headers.get("Host"))) {
            return refuse(exchange, 403, "refuse request for a host other than loopback [host="
                    + headers.getFirst("Host") + ']');
        }
        if (gate.secrets() == null) {
            return Role.OWNER;
        }
        final Role role = gate.secrets().roleOf(bearer(headers.getFirst("Authorization")));
        if (role == Role.NONE) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"cdb\"");
            return refuse(exchange, 401, "refuse request without a valid secret");
        }
        return role;
    }

    // the secret of an Authorization header of the Bearer scheme, whose name any case matches; null otherwise
    private static String bearer(final String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        return authorization.substring(BEARER.length());
    }

    // answers the refusal in the endpoint's error shape; always NONE
    private static Role refuse(final HttpExchange exchange, final int status, final String message)
            throws IOException {
        final String path = exchange.getRequestURI().getPath();
        LOG.log(Level.DEBUG, () -> "refuse request [path=" + path + ", status=" + status + ']');
        send(exchange, status, exchange.getHttpContext().getPath().equals("/write")
                ? JsonWriter.writeError(message, 0) : JsonWriter.error("", message, -1));
        return Role.NONE;
    }

    private static void exec(final Backend backend, final Admission admission, final Bounds bounds, final Role role,
            final HttpExchange exchange) throws IOException {
        if (refused(exchange, "/exec", "GET")) {
            return;
        }
        final String query = parameter(exchange, "query");
        final String limit = parameter(exchange, "limit");
        final String lag = parameter(exchange, "max_lag_ms");
        if (query == null || query.isBlank()) {
            // QuestDB answers a request with no query text 200, the error in the body
            send(exchange, 200, JsonWriter.emptyQuery(query == null ? "" : query));
            return;
        }
        if (role == Role.READ_ONLY && !Access.readOnly(query)) {
            send(exchange, 403, JsonWriter.error(query, "refuse a statement that changes the store with the read-only"
                    + " secret", -1));
            return;
        }
        final int requested = maxRows(limit);
        if (requested < 0) {
            send(exchange, 400, JsonWriter.error(query, "invalid limit [limit=" + limit + ']', -1));
            return;
        }
        // a query that asks for every row, or more than the bound, reads one row past it: a result over the bound is
        // refused rather than cut short, so a client never takes part of an answer for the whole
        final int maxRows = requested == 0 || requested > bounds.maxRows() ? bounds.maxRows() + 1 : requested;
        final CdbCancel cancel = new CdbCancel();
        final long startNanos = System.nanoTime();
        final Runnable ended = admission.watch(cancel, bounds.queryDeadlineMillis());
        final Rows rows;
        try {
            rows = backend.run(query, offset(limit), maxRows, ReadLag.parse("max_lag_ms", lag), cancel);
        } catch (final CdbException e) {
            if (cancel.cancelled()) {
                cancelled(exchange, query, bounds, startNanos);
            } else {
                send(exchange, 400, JsonWriter.error(query, e.getMessage(), e.position()));
            }
            return;
        } finally {
            ended.run();
        }
        if (rows.updateCount() < 0L && rows.rowCount() > bounds.maxRows()) {
            send(exchange, 400, JsonWriter.error(query, "refuse result over the row limit [limit=" + bounds.maxRows()
                    + ']', -1));
            return;
        }
        // chunked, as the answer's length is known only once it is written: no copy of it is held whole. Closed only
        // once written whole, as the close ends the body; a failure leaves it open for handle to drop
        exchange.getResponseHeaders().set("Content-Type", JSON);
        exchange.sendResponseHeaders(200, 0);
        final Writer out = new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8);
        JsonWriter.result(query, rows, offset(limit), out);
        out.close();
    }

    // a query its deadline cancelled is answered 408; one cancelled sooner, as the server stops, 503
    private static void cancelled(final HttpExchange exchange, final String query, final Bounds bounds,
            final long startNanos) throws IOException {
        final long ranMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        if (ranMillis >= bounds.queryDeadlineMillis()) {
            send(exchange, 408, JsonWriter.error(query, "cancel query past its deadline [deadlineMillis="
                    + bounds.queryDeadlineMillis() + ", ranMillis=" + ranMillis + ']', -1));
        } else {
            send(exchange, 503, JsonWriter.error(query, "cancel query as the server stops [ranMillis=" + ranMillis
                    + ']', -1));
        }
    }

    /**
     * The row bound of QuestDB's {@code limit} parameter, after the rows {@link #offset(String)} skips: {@code n}, or
     * {@code lo,hi} (rows lo to hi, counted from 1, as QuestDB reads it; {@code 0,n} as the kit's {@code qdb} sends
     * it); 0 when absent or {@code lo,} with no upper bound or one that is no number; 1 when {@code hi} is below
     * {@code lo} or 0, as QuestDB serves the first row before it checks the bound; -1 when {@code lo} or a lone
     * {@code n} is no number or negative.
     */
    static int maxRows(final String limit) {
        if (limit == null) {
            return 0;
        }
        final int comma = limit.indexOf(',');
        try {
            if (comma < 0) {
                return Math.max(-1, Integer.parseInt(limit.strip()));
            }
            final long skipped = Math.max(0L, Long.parseLong(limit.substring(0, comma).strip()) - 1L);
            final String upper = limit.substring(comma + 1).strip();
            if (upper.isEmpty()) {
                return 0;
            }
            final int hi;
            try {
                hi = Integer.parseInt(upper);
            } catch (final NumberFormatException _) {
                return 0;
            }
            if (hi < 0) {
                return -1;
            }
            return hi < skipped + 1L ? 1 : (int) (hi - skipped);
        } catch (final NumberFormatException _) {
            return -1;
        }
    }

    /** The rows {@code limit=lo,hi} skips: {@code lo - 1}, as QuestDB counts rows from 1; 0 when there is no such form. */
    static long offset(final String limit) {
        final int comma = limit == null ? -1 : limit.indexOf(',');
        if (comma < 0) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(limit.substring(0, comma).strip()) - 1L);
        } catch (final NumberFormatException _) {
            return 0L;
        }
    }

    private static void write(final CommitQueue commits, final BodyBuffers bodies, final Semaphore budget,
            final Bounds bounds, final Role role, final HttpExchange exchange) throws IOException {
        if (refused(exchange, "/write", "POST")) {
            return;
        }
        if (role == Role.READ_ONLY) {
            send(exchange, 403, JsonWriter.writeError("refuse line-protocol writes with the read-only secret", 0));
            return;
        }
        final Precision precision;
        try {
            precision = Precision.of(parameter(exchange, "precision"));
        } catch (final IllegalArgumentException e) {
            send(exchange, 400, JsonWriter.writeError(e.getMessage(), 0));
            return;
        }
        final String durableValue = parameter(exchange, "durable");
        if (durableValue != null && !durableValue.equals("true") && !durableValue.equals("false")) {
            send(exchange, 400, JsonWriter.writeError("invalid durable [durable=" + durableValue + ']', 0));
            return;
        }
        final boolean durable = "true".equals(durableValue);
        final long declared = declaredLength(exchange);
        // null for a body declared longer than a buffer, under a budget smaller than one, or when every buffer is in
        // use; a body read into a buffer holds the buffer's size of the budget, so the buffers in use count against it
        final byte[] pooled = declared <= Limits.POOLED_BODY_BYTES
                && Limits.POOLED_BODY_BYTES <= bounds.bodyBudgetBytes() ? bodies.take() : null;
        try {
            final int share = pooled == null ? share(declared, bounds)
                    : Math.max(share(declared, bounds), Limits.POOLED_BODY_BYTES);
            if (!acquire(budget, share, bounds)) {
                send(exchange, 503, JsonWriter.writeError("refuse body while the body budget is in use [budgetBytes="
                        + bounds.bodyBudgetBytes() + ", waitedMillis=" + bounds.bodyWaitMillis() + ']', 0));
                return;
            }
            try {
                write(commits, exchange, pooled, precision, durable);
            } finally {
                budget.release(share);
            }
        } finally {
            if (pooled != null) {
                bodies.give(pooled);
            }
        }
    }

    // the bytes a body holds of the budget while it is read and written: its declared length, at most what the server
    // reads before it refuses the body and at most the whole budget; a body of unknown length takes the most
    private static int share(final long declared, final Bounds bounds) {
        final long held = declared < 0L ? MAX_BODY_BYTES + 1L : Math.min(declared, MAX_BODY_BYTES + 1L);
        return (int) Math.min(held, bounds.bodyBudgetBytes());
    }

    // the body's declared length, -1 when unknown
    private static long declaredLength(final HttpExchange exchange) {
        final String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            try {
                return Math.max(0L, Long.parseLong(length.strip()));
            } catch (final NumberFormatException _) {
                // the JDK's server refuses a malformed length before a handler runs; unknown is the safe answer
            }
        }
        return -1L;
    }

    private static boolean acquire(final Semaphore budget, final int share, final Bounds bounds) {
        try {
            return budget.tryAcquire(share, bounds.bodyWaitMillis(), TimeUnit.MILLISECONDS);
        } catch (final InterruptedException _) {
            // the server stops: the body is refused as when its wait runs out
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // the body is parsed in place, in pooled when it fits and in an array of its own when longer or pooled is null;
    // pooled holds the bytes of earlier bodies past this one's length, which the parser never reads
    private static void write(final CommitQueue commits, final HttpExchange exchange, final byte[] pooled,
            final Precision precision, final boolean durable) throws IOException {
        final InputStream input = exchange.getRequestBody();
        final byte[] bytes;
        final int length;
        if (pooled == null) {
            bytes = input.readNBytes(MAX_BODY_BYTES + 1);
            length = bytes.length;
        } else {
            final int read = input.readNBytes(pooled, 0, pooled.length);
            // a body of unknown length may go on past a full buffer: the rest is read as a longer body is
            final byte[] rest = read < pooled.length ? null : input.readNBytes(MAX_BODY_BYTES + 1 - read);
            if (rest == null || rest.length == 0) {
                bytes = pooled;
                length = read;
            } else {
                bytes = Arrays.copyOf(pooled, read + rest.length);
                System.arraycopy(rest, 0, bytes, read, rest.length);
                length = bytes.length;
            }
        }
        if (length > MAX_BODY_BYTES) {
            send(exchange, 413, JsonWriter.writeError("refuse body over the limit [limit=" + MAX_BODY_BYTES + ']', 0));
            return;
        }
        final long nowMicros = nowMicros();
        final int rows;
        try {
            final CommitQueue.Body batches = new CommitQueue.Body();
            rows = IlpParser.parse(bytes, length, batches, precision, nowMicros);
            final CommitQueue.Refusal refusal = commits.write(batches, durable);
            if (refusal != null) {
                // the parser names the refused row's line, as when the store refused it during the parse
                IlpParser.parse(bytes, length, new RefusingAppender(refusal), precision, nowMicros);
                send(exchange, 400, JsonWriter.writeError(refusal.cause().getMessage(), 0));
                return;
            }
        } catch (final IlpException e) {
            send(exchange, 400, JsonWriter.writeError(e.getMessage(), e.line()));
            return;
        } catch (final CdbException e) {
            send(exchange, 400, JsonWriter.writeError(e.getMessage(), 0));
            return;
        } catch (final CommitQueue.NotDurableException e) {
            // the rows are written and visible: a retry writes them again, so the client learns which case it is in
            send(exchange, e.isInDoubt() ? 500 : 503, JsonWriter.writeError(e.getMessage(), 0));
            return;
        }
        LOG.log(Level.DEBUG, () -> "wrote rows [rows=" + rows + ", bytes=" + length + ", durable=" + durable
                + ']');
        exchange.sendResponseHeaders(204, -1);
    }

    // true when the request is not for this endpoint or not of its method, answered 404 or 405
    private static boolean refused(final HttpExchange exchange, final String path, final String method)
            throws IOException {
        if (!exchange.getRequestURI().getPath().equals(path)) {
            send(exchange, 404, JsonWriter.error("", "unknown path [path=" + exchange.getRequestURI().getPath() + ']',
                    -1));
            return true;
        }
        if (!exchange.getRequestMethod().equals(method)) {
            send(exchange, 405, JsonWriter.error("", "unsupported method [method=" + exchange.getRequestMethod()
                    + ", allowed=" + method + ']', -1));
            return true;
        }
        return false;
    }

    // the first value of the parameter, form-decoded, or null; the server parsed the request line as a URI, so every
    // escape left is a well-formed %HH pair and decoding cannot fail
    private static String parameter(final HttpExchange exchange, final String name) {
        final String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return null;
        }
        int start = 0;
        while (start <= raw.length()) {
            final int amp = raw.indexOf('&', start);
            final int end = amp < 0 ? raw.length() : amp;
            final int equals = raw.indexOf('=', start);
            final int keyEnd = equals < 0 || equals > end ? end : equals;
            if (URLDecoder.decode(raw.substring(start, keyEnd), StandardCharsets.UTF_8).equals(name)) {
                return keyEnd == end ? "" : URLDecoder.decode(raw.substring(keyEnd + 1, end), StandardCharsets.UTF_8);
            }
            start = end + 1;
        }
        return null;
    }

    private static void send(final HttpExchange exchange, final int status, final String json) throws IOException {
        final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", JSON);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static long nowMicros() {
        final Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000;
    }

    /** What the server serves: cdb's facade, or a fake in tests. */
    interface Backend {

        /**
         * Runs {@code sql}, at most {@code maxRows} rows of a query (no bound when not positive); its reads lag the
         * answered commits by at most {@code maxLagMillis} milliseconds, the store's default when null.
         */
        Rows run(String sql, int maxRows, Long maxLagMillis);

        /**
         * As {@link #run(String, int, Long)}, a query skipping its first {@code offset} rows and giving up once
         * {@code cancel} (null: none) is cancelled; a backend that cannot skip refuses a non-zero offset.
         */
        default Rows run(final String sql, final long offset, final int maxRows, final Long maxLagMillis,
                final CdbCancel cancel) {
            if (offset != 0L) {
                throw new CdbException("refuse row offset [offset=" + offset + ']', -1);
            }
            return run(sql, maxRows, maxLagMillis);
        }

        /** A line-protocol appender; the caller closes it. */
        CdbAppender appender();
    }

    @FunctionalInterface
    private interface Handler {
        void run(Role role) throws IOException;
    }

    /**
     * The bounds a server holds its clients' work to: {@link Limits}' in production, shorter ones in tests. A query of
     * every row reads {@code maxRows + 1}, so {@code maxRows} is below {@link Integer#MAX_VALUE}.
     */
    record Bounds(long queryDeadlineMillis, int maxRows, long bodyBudgetBytes, long bodyWaitMillis) {

        static final Bounds DEFAULT = new Bounds(Limits.QUERY_DEADLINE_MILLIS, Limits.MAX_ROWS,
                Limits.BODY_BUDGET_BYTES, Limits.BODY_WAIT_MILLIS);
    }

    // what a request must show before a handler runs: a loopback Host when bound to loopback, a secret when given any
    private record Gate(Secrets secrets, boolean loopback, int port) {

        boolean isLoopbackHost(final List<String> hosts) {
            if (hosts == null || hosts.size() != 1) {
                return false;
            }
            final String host = hosts.getFirst();
            return host.equalsIgnoreCase("127.0.0.1:" + port) || host.equalsIgnoreCase("localhost:" + port)
                    || host.equalsIgnoreCase("[::1]:" + port);
        }
    }

    private record CdbBackend(Cdb cdb) implements Backend {

        // a statement that changed the store is DDL when it parses as CREATE TABLE
        @Override
        public Rows run(final String sql, final int maxRows, final Long maxLagMillis) {
            return run(sql, 0L, maxRows, maxLagMillis, null);
        }

        @Override
        public Rows run(final String sql, final long offset, final int maxRows, final Long maxLagMillis,
                final CdbCancel cancel) {
            final CdbResult result = maxLagMillis == null ? cdb.run(sql, offset, maxRows, cancel)
                    : ScopedValue.where(Store.READ_MAX_LAG_MILLIS, maxLagMillis)
                            .call(() -> cdb.run(sql, offset, maxRows, cancel));
            final boolean ddl = result.updateCount() >= 0 && Parser.parse(sql) instanceof Ast.CreateTable;
            return new CdbRows(result, ddl, result.timestampIndex());
        }

        @Override
        public CdbAppender appender() {
            return cdb.appender();
        }
    }

    private record CdbRows(CdbResult result, boolean isDdl, int timestampIndex) implements Rows {

        @Override
        public int columnCount() {
            return result.columnCount();
        }

        @Override
        public String columnName(final int column) {
            return result.columnName(column);
        }

        @Override
        public CdbType type(final int column) {
            return result.type(column);
        }

        @Override
        public int rowCount() {
            return result.rowCount();
        }

        @Override
        public long updateCount() {
            return result.updateCount();
        }

        @Override
        public boolean isNull(final int row, final int column) {
            return result.isNull(row, column);
        }

        @Override
        public long getLong(final int row, final int column) {
            return result.getLong(row, column);
        }

        @Override
        public double getDouble(final int row, final int column) {
            return result.getDouble(row, column);
        }

        @Override
        public boolean getBoolean(final int row, final int column) {
            return result.getBoolean(row, column);
        }

        @Override
        public String getText(final int row, final int column) {
            return result.getText(row, column);
        }
    }

    /**
     * The server's executor: a virtual thread per request, at most {@link #MAX_REQUESTS} at once. A request over the
     * bound is refused before its thread exists; the JDK's server then closes its connection.
     */
    private static final class Admission implements Executor {

        /** Not measured: queries of the corpus answer in milliseconds; five seconds outlasts any of them. */
        private static final long DRAIN_MILLIS = 5_000L;

        private final Semaphore permits = new Semaphore(MAX_REQUESTS);
        private final ExecutorService threads =
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("cdb-http-", 0).factory());

        @Override
        public void execute(final Runnable exchange) {
            if (!permits.tryAcquire()) {
                LOG.log(Level.DEBUG, "refuse request over the limit [limit=" + MAX_REQUESTS + ']');
                throw new RejectedExecutionException("refuse request [limit=" + MAX_REQUESTS + ']');
            }
            try {
                threads.execute(() -> {
                    try {
                        exchange.run();
                    } finally {
                        permits.release();
                    }
                });
            } catch (final RuntimeException | Error e) {
                permits.release();
                throw e;
            }
        }

        /**
         * Cancels {@code cancel} once {@code deadlineMillis} pass, unless the returned action, the query's end, runs
         * first. A virtual thread waits for either; a request runs one query, so the watches are bounded by the
         * requests' permits. Once the server is closing, the query is cancelled at once rather than run past the close.
         */
        Runnable watch(final CdbCancel cancel, final long deadlineMillis) {
            final CountDownLatch ended = new CountDownLatch(1);
            try {
                threads.execute(() -> {
                    Thread.currentThread().setName("cdb-http-deadline");
                    try {
                        if (!ended.await(deadlineMillis, TimeUnit.MILLISECONDS)) {
                            cancel.cancel();
                        }
                    } catch (final InterruptedException _) {
                        // the close stops the watch; its request ends with the close
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (final RejectedExecutionException _) {
                cancel.cancel();
            }
            return ended::countDown;
        }

        void close() {
            threads.shutdown();
            try {
                if (!threads.awaitTermination(DRAIN_MILLIS, TimeUnit.MILLISECONDS)) {
                    LOG.log(Level.WARNING, "requests still running at close [waitedMillis=" + DRAIN_MILLIS + ']');
                    threads.shutdownNow();
                }
            } catch (final InterruptedException _) {
                threads.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * The {@code /write} body buffers, each {@link Limits#POOLED_BODY_BYTES}, at most {@link #POOLED_BODIES} made: a
     * request takes a free one, or a new one while fewer were made, else none, and gives it back once answered. A
     * buffer is not cleared, as a body is read only to its own length. A queue rather than a thread-local cache: each
     * request runs on a virtual thread of its own.
     */
    private static final class BodyBuffers {

        // never full, as it holds no more buffers than were made
        private final ArrayBlockingQueue<byte[]> free = new ArrayBlockingQueue<>(POOLED_BODIES);
        private final AtomicInteger made = new AtomicInteger();

        // null when every buffer is in use and the bound is reached
        byte[] take() {
            final byte[] buffer = free.poll();
            if (buffer != null) {
                return buffer;
            }
            if (made.getAndUpdate(n -> n < POOLED_BODIES ? n + 1 : n) >= POOLED_BODIES) {
                return null;
            }
            try {
                return new byte[Limits.POOLED_BODY_BYTES];
            } catch (final OutOfMemoryError e) {
                // the buffer was never made: its slot is free for a later request
                made.decrementAndGet();
                throw e;
            }
        }

        void give(final byte[] buffer) {
            free.offer(buffer);
        }

        int pooled() {
            return free.size();
        }
    }

    /** Takes a body's rows until the refused one, and refuses it with the store's reason. */
    private static final class RefusingAppender implements CdbAppender {

        private final CommitQueue.Refusal refusal;
        private int rows;

        RefusingAppender(final CommitQueue.Refusal refusal) {
            this.refusal = refusal;
        }

        @Override
        public CdbAppender table(final String table) {
            if (rows++ == refusal.row()) {
                throw refusal.cause();
            }
            return this;
        }

        @Override
        public CdbAppender symbol(final String column, final String value) {
            return this;
        }

        @Override
        public CdbAppender stringColumn(final String column, final String value) {
            return this;
        }

        @Override
        public CdbAppender longColumn(final String column, final long value) {
            return this;
        }

        @Override
        public CdbAppender doubleColumn(final String column, final double value) {
            return this;
        }

        @Override
        public CdbAppender booleanColumn(final String column, final boolean value) {
            return this;
        }

        @Override
        public CdbAppender timestampColumn(final String column, final long micros) {
            return this;
        }

        @Override
        public void at(final long micros) {
            // the queue wrote the rows
        }

        @Override
        public void discard() {
            // the queue wrote the rows; nothing is held
        }

        @Override
        public void flush() {
            // nothing is written here
        }

        @Override
        public void close() {
            // nothing is held
        }
    }
}
