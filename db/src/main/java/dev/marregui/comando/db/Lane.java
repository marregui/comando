package dev.marregui.comando.db;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.Threads;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One connection and the single thread that uses it. A JDBC connection runs one statement at a time,
 * so every run, open, close and health check of a connection is a task on its lane, in order.
 *
 * <p>Cancel crosses threads: the caller marks the request cancelled and then reads the running
 * statement; the lane publishes the running statement and then reads the mark. Either the lane sees the
 * mark before running, or the caller sees the statement and cancels it. A statement still running
 * {@code abortAfterMillis} after its cancel has its connection aborted, which unblocks a socket read
 * the server ignores.</p>
 *
 * <p>A lane lives while it holds a connection or has work queued: once its last task ends with no connection
 * (closed, lost, never opened), it stops its thread and leaves the adapter, so a closed, removed or renamed
 * connection keeps no thread. A task offered to a stopped lane is refused, and the adapter starts a new lane for
 * it; every task the old lane accepted has ended by then, so a connection's intents keep their order.</p>
 */
final class Lane implements AutoCloseable {

    /** Work the connection's close, removal or change overtook before its turn came: it did not run. */
    static final class Overtaken extends IllegalStateException {
        Overtaken(String connection) {
            super("connection " + connection + " was closed or changed before this ran");
        }
    }

    private static final System.Logger LOG = System.getLogger(Lane.class.getName());
    /** The bound on a table description, as long as Claude waits for one. */
    private static final int DESCRIBE_SECONDS = 60;
    /** How long a close waits for the lane thread to end its task and close the connection. */
    static final long CLOSE_WAIT_NANOS = TimeUnit.SECONDS.toNanos(3);

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
    private final Consumer<Lane> onRetired;
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();
    private volatile Running running;
    private volatile boolean closed;
    /** Set when close runs the queued tasks on its own thread: the lane thread is still busy, and owns the connection. */
    private volatile boolean drained;
    private int pending; // guarded by this: tasks accepted and not yet ended
    private boolean retired; // guarded by this
    private Connection connection; // lane thread only

    Lane(String name, IntentSink sink, ResultRegistry results, DbSettings settings, ScheduledExecutorService timer,
         ExecutorService cancellers, Consumer<Lane> onRetired) {
        this.name = name;
        this.sink = sink;
        this.results = results;
        this.settings = settings;
        this.timer = timer;
        this.cancellers = cancellers;
        this.onRetired = onRetired;
        this.thread = Threads.strand("db-" + name);
    }

    /** False when the lane has stopped; so for every method that queues work. */
    boolean open(ConnectionSpec spec) {
        return work(() -> {
            try {
                ensureOpen(spec);
            } catch (SQLException e) {
                sink.dispatch(new Intent.ConnectionFailed(name, message(e)));
            }
        });
    }

    boolean execute(long requestId, ConnectionSpec spec, String sql) {
        return work(() -> run(requestId, spec, sql), () -> sink.dispatch(new Intent.SqlCancelled(requestId)));
    }

    /**
     * Runs {@code sql} on this lane, after the runs already queued; rows come back as text. {@code wanted} is asked
     * when the turn comes: false (the work was overtaken, such as a schema load the user's Disconnect followed) ends
     * it with {@link Overtaken} without opening the connection again.
     */
    boolean query(ConnectionSpec spec, String sql, int maxRows, CompletableFuture<QueryPort.Rows> out, BooleanSupplier wanted) {
        boolean accepted = work(() -> {
            if (out.isDone()) {
                return; // the caller gave up while this waited behind other runs: it must not run later
            }
            if (!wanted.getAsBoolean()) {
                out.completeExceptionally(new Overtaken(name));
                return;
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
        }, () -> out.completeExceptionally(closing()));
        if (accepted && closed) {
            out.completeExceptionally(new IllegalStateException("connection " + name + " is closed"));
        }
        return accepted;
    }

    /**
     * Lists the columns of {@code table}: QuestDB's {@code SHOW COLUMNS} first, then the driver's metadata,
     * whose query QuestDB does not accept.
     */
    boolean describe(ConnectionSpec spec, String table, CompletableFuture<QueryPort.Rows> out, BooleanSupplier wanted) {
        return work(() -> {
            if (!wanted.getAsBoolean()) {
                out.completeExceptionally(new Overtaken(name));
                return;
            }
            try {
                ensureOpen(spec);
            } catch (SQLException e) {
                out.completeExceptionally(new IllegalStateException(message(e), e));
                return;
            }
            SQLException first;
            try (Statement st = connection.createStatement()) {
                st.setQueryTimeout(DESCRIBE_SECONDS);
                try (ResultSet rs = st.executeQuery("SHOW COLUMNS FROM " + quote(table))) {
                    out.complete(textRows(rs, 10_000));
                    return;
                }
            } catch (SQLException e) {
                first = e;
            }
            // the driver's metadata takes no statement timeout: a network timeout bounds it, so a stalled server cannot
            // hold the lane, and the next run opens the connection again if the timeout broke it
            boolean bounded = networkTimeout(DESCRIBE_SECONDS * 1000);
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
            } finally {
                if (bounded) {
                    networkTimeout(0);
                }
            }
        }, () -> out.completeExceptionally(closing()));
    }

    /** Sets the connection's network timeout, 0 for none; false when the driver does not take one. */
    private boolean networkTimeout(int millis) {
        try {
            connection.setNetworkTimeout(Runnable::run, millis);
            return true;
        } catch (SQLException | RuntimeException e) {
            return false;
        }
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

    boolean disconnect() {
        return submit(() -> {
            closeConnection();
            sink.dispatch(new Intent.ConnectionClosed(name));
        });
    }

    void checkHealth() {
        if (running != null) {
            return; // a statement in flight is its own liveness check
        }
        work(() -> {
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

    /** Queues work that runs only while the lane is open: once it closes, a queued task does nothing. */
    private boolean work(Runnable task) {
        return work(task, () -> {
        });
    }

    /**
     * Queues work that runs only while the lane is open: a task still queued when the lane closes runs {@code
     * whenClosed} instead, so a statement queued before the user quit is not run during the exit.
     */
    private boolean work(Runnable task, Runnable whenClosed) {
        return submit(() -> {
            if (closed) {
                whenClosed.run();
            } else {
                task.run();
            }
        });
    }

    private IllegalStateException closing() {
        return new IllegalStateException("connection " + name + " is closing; the statement did not run");
    }

    /**
     * Queues {@code task}; false when the lane has stopped and the caller must use a new lane. A task offered after
     * {@link #close()} is dropped and counts as accepted.
     */
    private synchronized boolean submit(Runnable task) {
        if (retired) {
            return false;
        }
        pending++;
        try {
            thread.execute(() -> {
                try {
                    task.run();
                } finally {
                    ended();
                }
            });
        } catch (RejectedExecutionException e) {
            pending--;
            LOG.log(System.Logger.Level.DEBUG, "lane closed, task dropped [connection=" + name + ']');
        }
        return true;
    }

    /** On the lane thread, after each task: the last task to end with no connection stops the lane. */
    private void ended() {
        boolean retire;
        synchronized (this) {
            pending--;
            retire = pending == 0 && connection == null && !closed;
            if (retire) {
                retired = true;
                thread.shutdown();
            }
        }
        if (retire) {
            onRetired.accept(this);
        }
    }

    /**
     * The driver properties of {@code spec}, the one place they are built: the connection test must reach the server
     * the way the lane does, TLS mode included.
     */
    static Properties driverProperties(ConnectionSpec spec) {
        Properties props = new Properties();
        props.setProperty("user", spec.user());
        props.setProperty("password", spec.password());
        props.setProperty("ApplicationName", "comando");
        props.setProperty("loginTimeout", "20");
        props.setProperty("tcpKeepAlive", "true");
        props.setProperty("sslmode", spec.sslMode());
        props.setProperty("cancelSignalTimeout", "2");
        return props;
    }

    private void ensureOpen(ConnectionSpec spec) throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return;
        }
        LOG.log(System.Logger.Level.INFO, "connecting [connection=" + name + ", url=" + spec.jdbcUrl() + ']');
        try {
            connection = DriverManager.getConnection(spec.jdbcUrl(), driverProperties(spec));
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot connect [connection=" + name + ", sqlState=" + e.getSQLState() + ']', e);
            throw e;
        }
        connection.setAutoCommit(true);
        sink.dispatch(new Intent.ConnectionOpened(name));
        LOG.log(System.Logger.Level.INFO, "connected [connection=" + name + ']');
    }

    private void run(long requestId, ConnectionSpec spec, String sql) {
        long start = System.nanoTime();
        if (cancelled.remove(requestId)) {
            sink.dispatch(new Intent.SqlCancelled(requestId));
            return;
        }
        try {
            ensureOpen(spec);
        } catch (SQLException e) {
            sink.dispatch(new Intent.ConnectionFailed(name, message(e)));
            sink.dispatch(new Intent.SqlFailed(requestId, message(e), millisSince(start)));
            return;
        }
        Statement st = null;
        try {
            st = connection.createStatement();
            st.setQueryTimeout(settings.queryTimeoutSeconds());
            st.setFetchSize(settings.fetchSize());
            running = new Running(requestId, st, connection);
            if (cancelled.contains(requestId)) {
                sink.dispatch(new Intent.SqlCancelled(requestId));
                return;
            }
            sink.dispatch(new Intent.SqlStarted(requestId));
            boolean hasRows = st.execute(sql);
            long execMillis = millisSince(start);
            if (!hasRows) {
                long updates = st.getLargeUpdateCount();
                long total = millisSince(start);
                sink.dispatch(new Intent.SqlCompleted(requestId, 0, updates, false, execMillis, total - execMillis, total));
                reportMoreResults(requestId, st);
                return;
            }
            if (fetch(requestId, st.getResultSet(), start, execMillis)) {
                reportMoreResults(requestId, st);
            }
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

    /**
     * Says so when the text held more than one statement: the driver runs them all and the Results tab shows
     * the first one's result only, which would otherwise pass without a word.
     */
    private void reportMoreResults(long requestId, Statement st) {
        int more = 0;
        try {
            while (st.getMoreResults() || st.getLargeUpdateCount() != -1) {
                more++;
            }
        } catch (SQLException e) {
            sink.dispatch(new Intent.SqlWarning(requestId, true, "statement " + (more + 2) + " of the block failed: " + message(e)));
            return;
        }
        if (more > 0) {
            sink.dispatch(new Intent.SqlWarning(requestId, false, (more + 1) + " statements ran; this is the first one's result"));
        }
    }

    /** Fetches every row into the result; false when the run was cancelled on the way. */
    private boolean fetch(long requestId, ResultSet rs, long start, long execMillis) throws SQLException {
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
                        return false;
                    }
                    batch = Math.min(batch * 2, 50_000);
                    sink.dispatch(new Intent.SqlRows(requestId, rows, execMillis, millisSince(fetchStart), millisSince(start)));
                }
            }
            long total = millisSince(start);
            sink.dispatch(new Intent.SqlCompleted(requestId, rows, -1L, truncated, execMillis, millisSince(fetchStart), total));
            return true;
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
                    LOG.log(System.Logger.Level.WARNING, "cancel failed [connection=" + name + ", request=" + r.requestId() + ']', e);
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
            LOG.log(System.Logger.Level.WARNING, "abort failed [connection=" + name + ']', e);
        }
    }

    private void closeConnection() {
        Connection c = connection;
        connection = null;
        if (c != null) {
            try {
                c.close();
            } catch (SQLException e) {
                LOG.log(System.Logger.Level.DEBUG, "close failed [connection=" + name + ']', e);
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
        beginClose();
        finishClose(System.nanoTime() + CLOSE_WAIT_NANOS);
    }

    /**
     * The first half of {@link #close}, which returns at once: cancels the running statement, arming its abort, and
     * queues the connection's close behind the queued tasks. Idempotent. Closing many lanes, each begins first, so that
     * their statements are cancelled together and the waits of {@link #finishClose} overlap.
     */
    void beginClose() {
        if (closed) {
            return;
        }
        closed = true;
        Running r = running;
        if (r != null) {
            cancelled.add(r.requestId());
            cancelStatement(r);
        }
        // the connection is the lane thread's: closed there, never by the tasks drained below on this thread
        submit(() -> {
            if (!drained) {
                closeConnection();
            }
        });
        thread.shutdown();
    }

    /**
     * The second half of {@link #close}: waits for the lane thread until {@code deadlineNanos} ({@link System#nanoTime});
     * past it, the tasks still queued are run on the caller as closed. Begins the close first when nobody has.
     */
    void finishClose(long deadlineNanos) {
        beginClose();
        if (drained) {
            return;
        }
        try {
            if (!thread.awaitTermination(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                // the tasks still queued are run here as closed: each answers its caller, none runs its statement
                drained = true;
                thread.shutdownNow().forEach(Runnable::run);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
