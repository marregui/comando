package dev.marregui.comando.db;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.QueryPort;
import dev.marregui.comando.core.result.ResultRegistry;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One connection and the single thread that uses it. A JDBC connection runs one statement at a time,
 * so every run, open, close and health check of a connection is a task on its lane, in order.
 *
 * <p>Cancel crosses threads: the caller marks the request cancelled and then reads the running
 * statement; the lane publishes the running statement and then reads the mark. Either the lane sees the
 * mark before running, or the caller sees the statement and cancels it. A statement still running
 * {@code abortAfterMillis} after its cancel has its connection aborted, which unblocks a socket read
 * the server ignores.</p>
 */
final class Lane implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Lane.class.getName());

    /** The statement the lane runs now. */
    private record Running(long requestId, Statement statement, Connection connection) {
    }

    private final String name;
    private final IntentSink sink;
    private final ResultRegistry results;
    private final DbSettings settings;
    private final ScheduledExecutorService timer;
    private final ExecutorService cancellers;
    private final ExecutorService thread;
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();
    private volatile Running running;
    private volatile boolean closed;
    private Connection connection; // lane thread only

    Lane(String name, IntentSink sink, ResultRegistry results, DbSettings settings, ScheduledExecutorService timer,
         ExecutorService cancellers) {
        this.name = name;
        this.sink = sink;
        this.results = results;
        this.settings = settings;
        this.timer = timer;
        this.cancellers = cancellers;
        this.thread = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("db-" + name).daemon(true).unstarted(r));
    }

    void open(ConnectionSpec spec) {
        submit(() -> {
            try {
                ensureOpen(spec);
            } catch (SQLException e) {
                sink.dispatch(new Intent.ConnectionFailed(name, message(e)));
            }
        });
    }

    void execute(long requestId, ConnectionSpec spec, String sql) {
        submit(() -> run(requestId, spec, sql));
    }

    /** Runs {@code sql} for Claude on this lane, after the runs already queued; rows come back as text. */
    void query(ConnectionSpec spec, String sql, int maxRows, CompletableFuture<QueryPort.Rows> out) {
        submit(() -> {
            if (out.isDone()) {
                return; // the caller gave up while this waited behind other runs: it must not run later
            }
            try {
                ensureOpen(spec);
                try (Statement st = connection.createStatement()) {
                    st.setQueryTimeout(60);
                    st.setMaxRows(maxRows + 1);
                    if (!st.execute(sql)) {
                        out.complete(new QueryPort.Rows(List.of(), List.of(), st.getLargeUpdateCount(), false));
                        return;
                    }
                    try (ResultSet rs = st.getResultSet()) {
                        out.complete(textRows(rs, maxRows));
                    }
                }
            } catch (SQLException e) {
                out.completeExceptionally(new IllegalStateException(message(e), e));
            } catch (RuntimeException e) {
                out.completeExceptionally(e);
            }
        });
        if (closed) {
            out.completeExceptionally(new IllegalStateException("connection " + name + " is closed"));
        }
    }

    /**
     * Lists the columns of {@code table}: QuestDB's {@code SHOW COLUMNS} first, then the driver's metadata,
     * whose query QuestDB does not accept.
     */
    void describe(ConnectionSpec spec, String table, CompletableFuture<QueryPort.Rows> out) {
        submit(() -> {
            try {
                ensureOpen(spec);
            } catch (SQLException e) {
                out.completeExceptionally(new IllegalStateException(message(e), e));
                return;
            }
            SQLException first;
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SHOW COLUMNS FROM " + quote(table))) {
                out.complete(textRows(rs, 10_000));
                return;
            } catch (SQLException e) {
                first = e;
            }
            try (ResultSet rs = connection.getMetaData().getColumns(null, null, table, null)) {
                List<List<String>> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(List.of(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"), rs.getString("IS_NULLABLE")));
                }
                if (rows.isEmpty()) {
                    out.completeExceptionally(new IllegalStateException(message(first)));
                } else {
                    out.complete(new QueryPort.Rows(List.of("column", "type", "nullable"), rows, -1L, false));
                }
            } catch (SQLException e) {
                out.completeExceptionally(new IllegalStateException(message(first), first));
            }
        });
    }

    /** A double-quoted identifier; embedded quotes are doubled. */
    static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static QueryPort.Rows textRows(ResultSet rs, int maxRows) throws SQLException {
        ColumnarResult r = ColumnarResult.of(-1L, rs.getMetaData());
        boolean truncated = false;
        while (rs.next()) {
            if (r.rowCount() == maxRows) {
                truncated = true;
                break;
            }
            r.append(rs);
        }
        List<String> columns = new ArrayList<>(r.columnCount());
        for (int c = 0; c < r.columnCount(); c++) {
            columns.add(r.columnName(c));
        }
        List<List<String>> rows = new ArrayList<>(r.rowCount());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.rowCount(); i++) {
            List<String> row = new ArrayList<>(r.columnCount());
            for (int c = 0; c < r.columnCount(); c++) {
                sb.setLength(0);
                r.format(i, c, sb);
                row.add(sb.toString());
            }
            rows.add(row);
        }
        return new QueryPort.Rows(columns, rows, -1L, truncated);
    }

    void cancel(long requestId) {
        cancelled.add(requestId);
        Running r = running;
        if (r != null && r.requestId() == requestId) {
            cancelStatement(r);
        }
    }

    void disconnect() {
        submit(() -> {
            closeConnection();
            sink.dispatch(new Intent.ConnectionClosed(name));
        });
    }

    void checkHealth() {
        if (running != null) {
            return; // a statement in flight is its own liveness check
        }
        submit(() -> {
            if (connection == null) {
                return;
            }
            boolean valid;
            try {
                valid = connection.isValid(5);
            } catch (SQLException e) {
                valid = false;
            }
            if (!valid) {
                closeConnection();
                sink.dispatch(new Intent.ConnectionLost(name, "validity check failed"));
            }
        });
    }

    private void submit(Runnable task) {
        try {
            thread.execute(task);
        } catch (RejectedExecutionException e) {
            LOG.log(System.Logger.Level.DEBUG, "lane closed, task dropped [connection=" + name + ']');
        }
    }

    private void ensureOpen(ConnectionSpec spec) throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return;
        }
        LOG.log(System.Logger.Level.INFO, "connecting [connection=" + name + ", url=" + spec.jdbcUrl() + ']');
        Properties props = new Properties();
        props.setProperty("user", spec.user());
        props.setProperty("password", spec.password());
        props.setProperty("ApplicationName", "comando");
        props.setProperty("loginTimeout", "20");
        props.setProperty("tcpKeepAlive", "true");
        props.setProperty("sslmode", spec.sslMode());
        props.setProperty("cancelSignalTimeout", "2");
        try {
            connection = DriverManager.getConnection(spec.jdbcUrl(), props);
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot connect [connection=" + name + ", sqlState=" + e.getSQLState() + "]: " + e.getMessage());
            throw e;
        }
        connection.setAutoCommit(true);
        sink.dispatch(new Intent.ConnectionOpened(name));
        LOG.log(System.Logger.Level.INFO, "connected [connection=" + name + ']');
    }

    private void run(long requestId, ConnectionSpec spec, String sql) {
        SqlRunEvent event = new SqlRunEvent();
        event.begin();
        String outcome = "failed";
        try {
            outcome = runMeasured(requestId, spec, sql);
        } finally {
            event.end();
            if (event.shouldCommit()) {
                event.connection = name;
                event.requestId = requestId;
                event.sql = sql.length() > 200 ? sql.substring(0, 200) + "…" : sql;
                event.outcome = outcome;
                event.commit();
            }
        }
    }

    private String runMeasured(long requestId, ConnectionSpec spec, String sql) {
        long start = System.nanoTime();
        if (cancelled.remove(requestId)) {
            sink.dispatch(new Intent.SqlCancelled(requestId));
            return "cancelled";
        }
        try {
            ensureOpen(spec);
        } catch (SQLException e) {
            sink.dispatch(new Intent.ConnectionFailed(name, message(e)));
            sink.dispatch(new Intent.SqlFailed(requestId, message(e), millisSince(start)));
            return "failed";
        }
        Statement st = null;
        try {
            st = connection.createStatement();
            st.setQueryTimeout(settings.queryTimeoutSeconds());
            st.setFetchSize(settings.fetchSize());
            running = new Running(requestId, st, connection);
            if (cancelled.contains(requestId)) {
                sink.dispatch(new Intent.SqlCancelled(requestId));
                return "cancelled";
            }
            sink.dispatch(new Intent.SqlStarted(requestId));
            boolean hasRows = st.execute(sql);
            long execMillis = millisSince(start);
            if (!hasRows) {
                long updates = st.getLargeUpdateCount();
                long total = millisSince(start);
                sink.dispatch(new Intent.SqlCompleted(requestId, 0, updates, false, execMillis, total - execMillis, total));
                return "done";
            }
            return fetch(requestId, st.getResultSet(), start, execMillis);
        } catch (SQLException e) {
            boolean wasCancelled = cancelled.contains(requestId);
            if (wasCancelled) {
                sink.dispatch(new Intent.SqlCancelled(requestId));
            } else {
                sink.dispatch(new Intent.SqlFailed(requestId, message(e), millisSince(start)));
            }
            if (isConnectionError(e)) {
                closeConnection();
                sink.dispatch(new Intent.ConnectionLost(name, message(e)));
            }
            return wasCancelled ? "cancelled" : "failed";
        } finally {
            running = null;
            cancelled.remove(requestId);
            if (st != null) {
                try {
                    st.close();
                } catch (SQLException ignore) {
                    // the statement's connection may be gone; nothing left to release
                }
            }
        }
    }

    /** Fetches every row into the result; returns "done" or "cancelled". */
    private String fetch(long requestId, ResultSet rs, long start, long execMillis) throws SQLException {
        try (rs) {
            ColumnarResult result = ColumnarResult.of(requestId, rs.getMetaData());
            results.put(result);
            int batch = 100;
            int rows = 0;
            boolean truncated = false;
            long fetchStart = System.nanoTime();
            while (rs.next()) {
                if (rows == settings.maxRows()) {
                    truncated = true;
                    break;
                }
                result.append(rs);
                rows++;
                if (rows % batch == 0) {
                    if (cancelled.contains(requestId)) {
                        sink.dispatch(new Intent.SqlCancelled(requestId));
                        return "cancelled";
                    }
                    batch = Math.min(batch * 2, 50_000);
                    sink.dispatch(new Intent.SqlRows(requestId, rows, execMillis, millisSince(fetchStart), millisSince(start)));
                }
            }
            long total = millisSince(start);
            sink.dispatch(new Intent.SqlCompleted(requestId, rows, -1L, truncated, execMillis, millisSince(fetchStart), total));
            return "done";
        }
    }

    /**
     * Sends the driver's cancel off the caller's thread (pgjdbc waits for the server to close the cancel
     * socket) and arms the abort that unblocks a statement the server does not stop.
     */
    private void cancelStatement(Running r) {
        timer.schedule(() -> abortIfStillRunning(r), settings.abortAfterMillis(), TimeUnit.MILLISECONDS);
        try {
            cancellers.execute(() -> {
                try {
                    r.statement().cancel();
                } catch (SQLException e) {
                    LOG.log(System.Logger.Level.WARNING, "cancel failed [connection=" + name + ", request=" + r.requestId() + "]: " + e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.log(System.Logger.Level.DEBUG, "adapter closed, cancel left to the abort [connection=" + name + ']');
        }
    }

    private void abortIfStillRunning(Running r) {
        Running now = running;
        if (now == null || now.requestId() != r.requestId()) {
            return;
        }
        LOG.log(System.Logger.Level.WARNING, "statement ignored cancel, aborting connection [connection=" + name
                + ", request=" + r.requestId() + ']');
        try {
            now.connection().abort(Runnable::run);
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.WARNING, "abort failed [connection=" + name + "]: " + e.getMessage());
        }
    }

    private void closeConnection() {
        Connection c = connection;
        connection = null;
        if (c != null) {
            try {
                c.close();
            } catch (SQLException e) {
                LOG.log(System.Logger.Level.DEBUG, "close failed [connection=" + name + "]: " + e.getMessage());
            }
        }
    }

    private static boolean isConnectionError(SQLException e) {
        String state = e.getSQLState();
        return state != null && state.startsWith("08");
    }

    static String message(SQLException e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m.strip();
    }

    private static long millisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** Cancels the running statement, closes the connection on the lane, stops the thread. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Running r = running;
        if (r != null) {
            cancelled.add(r.requestId());
            cancelStatement(r);
        }
        submit(this::closeConnection);
        thread.shutdown();
        try {
            if (!thread.awaitTermination(3, TimeUnit.SECONDS)) {
                thread.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
