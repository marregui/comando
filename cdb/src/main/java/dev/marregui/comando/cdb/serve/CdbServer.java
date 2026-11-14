package dev.marregui.comando.cdb.serve;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbResult;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.sql.Ast;
import dev.marregui.comando.cdb.sql.Parser;

/**
 * cdb over HTTP with QuestDB's wire formats, so the kit's clients work unchanged: {@code GET /exec?query=…} answers
 * QuestDB's JSON ({@code query}, {@code columns} with {@code name} and {@code type}, {@code timestamp},
 * {@code dataset}, {@code count}; an error as {@code {"query","error","position"}} with status 400), and
 * {@code POST /write} takes line protocol (204 on success). Started and stopped by its owner; one per Cdb.
 *
 * <p>{@code /exec} takes {@code limit=n} (at most n rows) and accepts and ignores {@code count} and {@code nm}. A
 * {@code /write} body is parsed whole before its first row reaches the store, so a malformed line (400, with its line
 * and offset) writes nothing; a row the store refuses (400) leaves the rows before it written. Each request runs on a
 * virtual thread of its own; past {@link #MAX_REQUESTS} in flight, a new connection is closed unanswered.
 */
public final class CdbServer implements AutoCloseable {

    /** Not measured: the kit's clients send one request at a time per process; a few dozen run at once at most. */
    static final int MAX_REQUESTS = 64;
    /** The kit's {@code qdb} sends at most 1,000 lines a request, a few hundred bytes each; not measured beyond it. */
    static final int MAX_BODY_BYTES = 16 << 20;
    private static final String JSON = "application/json; charset=utf-8";
    private static final System.Logger LOG = System.getLogger(CdbServer.class.getName());
    /** Not measured: a query of the corpus runs in milliseconds; one second lets the ones in flight answer. */
    private static final int STOP_DELAY_SECONDS = 1;
    /**
     * One {@code /write} body at a time reaches the store: an appender keeps each table's writer it touched until it
     * closes, so two bodies touching the same tables in opposite orders would each wait on the other's writer. The
     * writes are small (the machine's metrics and the kit's runs), so serialising them costs nothing measurable.
     */
    private static final ReentrantLock WRITES = new ReentrantLock();

    private final Admission admission;
    private final int port;
    private final HttpServer server;
    private boolean closed;

    private CdbServer(final HttpServer server, final Admission admission) {
        this.server = server;
        this.admission = admission;
        this.port = server.getAddress().getPort();
    }

    /** Serves {@code cdb} on {@code address} until {@link #close()}. */
    public static CdbServer start(final Cdb cdb, final InetSocketAddress address) {
        return start(new CdbBackend(cdb), address);
    }

    /** The port served, the real one when started on port 0. */
    public int port() {
        return port;
    }

    /** Stops serving; idempotent. Requests in flight get {@link #STOP_DELAY_SECONDS} to answer. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            server.stop(STOP_DELAY_SECONDS);
        } finally {
            admission.close();
        }
        LOG.log(Level.INFO, "stopped serving cdb [port=" + port + ']');
    }

    static CdbServer start(final Backend backend, final InetSocketAddress address) {
        final Admission admission = new Admission();
        HttpServer server = null;
        try {
            server = HttpServer.create(address, 0);
            server.setExecutor(admission);
            server.createContext("/exec", exchange -> handle(exchange, () -> exec(backend, exchange)));
            server.createContext("/write", exchange -> handle(exchange, () -> write(backend, exchange)));
            server.start();
            LOG.log(Level.INFO, "serving cdb [address=" + server.getAddress() + ']');
            return new CdbServer(server, admission);
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

    // every exchange is answered and closed: a handler that throws would leave the client without a status
    private static void handle(final HttpExchange exchange, final Handler handler) throws IOException {
        try {
            handler.run();
        } catch (final RuntimeException e) {
            LOG.log(Level.WARNING, "could not answer request [uri=" + exchange.getRequestURI() + ']', e);
            send(exchange, 500, JsonWriter.error("", "could not answer request [error=" + e + ']', -1));
        } finally {
            exchange.close();
        }
    }

    private static void exec(final Backend backend, final HttpExchange exchange) throws IOException {
        if (refused(exchange, "/exec", "GET")) {
            return;
        }
        final String query = parameter(exchange, "query");
        final String limit = parameter(exchange, "limit");
        if (query == null || query.isBlank()) {
            send(exchange, 400, JsonWriter.error("", "missing query", -1));
            return;
        }
        final int maxRows = maxRows(limit);
        if (maxRows < 0) {
            send(exchange, 400, JsonWriter.error(query, "invalid limit [limit=" + limit + ']', -1));
            return;
        }
        final Rows rows;
        try {
            rows = backend.run(query, maxRows);
        } catch (final CdbException e) {
            send(exchange, 400, JsonWriter.error(query, e.getMessage(), e.position()));
            return;
        }
        send(exchange, 200, JsonWriter.result(query, rows));
    }

    /**
     * The row bound of QuestDB's {@code limit} parameter: {@code n}, or {@code 0,n} as the kit's {@code qdb} sends it;
     * 0 when absent; -1 when it is no such form, a row offset other than 0 included (not served yet).
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
            final long offset = Long.parseLong(limit.substring(0, comma).strip());
            final int hi = Integer.parseInt(limit.substring(comma + 1).strip());
            return offset == 0 ? Math.max(-1, hi) : -1;
        } catch (final NumberFormatException _) {
            return -1;
        }
    }

    private static void write(final Backend backend, final HttpExchange exchange) throws IOException {
        if (refused(exchange, "/write", "POST")) {
            return;
        }
        final Precision precision;
        try {
            precision = Precision.of(parameter(exchange, "precision"));
        } catch (final IllegalArgumentException e) {
            send(exchange, 400, JsonWriter.writeError(e.getMessage(), 0));
            return;
        }
        final byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            send(exchange, 413, JsonWriter.writeError("refuse body over the limit [limit=" + MAX_BODY_BYTES + ']', 0));
            return;
        }
        final String body = new String(bytes, StandardCharsets.UTF_8);
        final long nowMicros = nowMicros();
        final int rows;
        try {
            IlpParser.parse(body, DiscardAppender.INSTANCE, precision, nowMicros);
            WRITES.lock();
            try (CdbAppender appender = backend.appender()) {
                rows = IlpParser.parse(body, appender, precision, nowMicros);
                appender.flush();
            } finally {
                WRITES.unlock();
            }
        } catch (final IlpException e) {
            send(exchange, 400, JsonWriter.writeError(e.getMessage(), e.line()));
            return;
        } catch (final CdbException e) {
            send(exchange, 400, JsonWriter.writeError(e.getMessage(), 0));
            return;
        }
        LOG.log(Level.DEBUG, () -> "wrote rows [rows=" + rows + ", bytes=" + bytes.length + ']');
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

        /** Runs {@code sql}, at most {@code maxRows} rows of a query (no bound when not positive). */
        Rows run(String sql, int maxRows);

        /** A line-protocol appender; the caller closes it. */
        CdbAppender appender();
    }

    @FunctionalInterface
    private interface Handler {
        void run() throws IOException;
    }

    private record CdbBackend(Cdb cdb) implements Backend {

        // a statement that changed the store is DDL when it parses as CREATE TABLE; a query's designated timestamp is
        // taken as its first TIMESTAMP column, as the result does not carry it
        @Override
        public Rows run(final String sql, final int maxRows) {
            final CdbResult result = cdb.run(sql, maxRows);
            final boolean ddl = result.updateCount() >= 0 && Parser.parse(sql) instanceof Ast.CreateTable;
            int timestamp = -1;
            for (int column = 0; column < result.columnCount() && timestamp < 0; column++) {
                if (result.type(column) == CdbType.TIMESTAMP) {
                    timestamp = column;
                }
            }
            return new CdbRows(result, ddl, timestamp);
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

    private static final class DiscardAppender implements CdbAppender {

        static final DiscardAppender INSTANCE = new DiscardAppender();

        @Override
        public CdbAppender table(final String table) {
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
            // a parse that only validates writes nothing
        }

        @Override
        public void flush() {
            // nothing was written
        }

        @Override
        public void close() {
            // nothing is held
        }
    }
}
