package dev.marregui.comando.cdb.serve;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbCancel;
import dev.marregui.comando.cdb.CdbException;

/**
 * cdb over the PostgreSQL wire (protocol 3.0), as QuestDB serves its port 8812, so pgjdbc and Grafana's PostgreSQL
 * data source query it unchanged: the protocol subset is {@link PgSession}'s. Each connection runs on a virtual thread
 * of its own; past {@link #MAX_CONNECTIONS} open at once, a new connection is closed unanswered. A connection that
 * sends no startup packet within {@link #STARTUP_TIMEOUT_MILLIS}, or nothing for {@link #IDLE_TIMEOUT_MILLIS} once
 * started, is closed and its place freed. A cancel request naming a started session's process id and secret key
 * cancels the statement that session runs, as PostgreSQL's does. Served with {@link Secrets}, a client's password is
 * the owner's or the read-only secret, else it is refused; served without, any client is the owner. A query is
 * cancelled at {@link Limits#QUERY_DEADLINE_MILLIS} (a change takes no cancel and runs to its end) and a result
 * refused past {@link Limits#MAX_ROWS} rows. Started and stopped by its owner, beside {@link CdbServer}; one per Cdb.
 */
public final class PgServer implements AutoCloseable {

    /** Not measured: Cosa holds a few connections and Grafana a pool of a few more; the same bound as HTTP's. */
    static final int MAX_CONNECTIONS = CdbServer.MAX_REQUESTS;
    /**
     * A client sends its startup packet, and its password when asked, as soon as it connects; ten seconds frees a
     * place held by a connection that never does (PostgreSQL's {@code authentication_timeout} is 60 seconds).
     */
    static final int STARTUP_TIMEOUT_MILLIS = 10_000;
    /** QuestDB's {@code pg.net.connection.timeout} default: a started connection silent this long is closed. */
    static final int IDLE_TIMEOUT_MILLIS = 300_000;
    private static final System.Logger LOG = System.getLogger(PgServer.class.getName());
    /** Not measured: a query of the corpus runs in milliseconds; five seconds outlasts any of them. */
    private static final long DRAIN_MILLIS = 5_000L;

    private final Cdb cdb;
    private final int idleTimeoutMillis;
    // the secret key of each session's BackendKeyData, which a cancel request must repeat
    private final SecureRandom keys = new SecureRandom();
    // the started sessions by process id: added and removed by each session's thread, read by a cancel request's
    private final ConcurrentHashMap<Integer, PgSession> live = new ConcurrentHashMap<>();
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private final Semaphore permits = new Semaphore(MAX_CONNECTIONS);
    private final PgSession.Policy policy;
    private final int port;
    private final AtomicInteger sessions = new AtomicInteger();
    private final ServerSocket socket;
    private final int startupTimeoutMillis;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    // written by the owner's close, read by the accepting thread to tell a close from a failure
    private volatile boolean closed;

    private PgServer(
            final Cdb cdb,
            final ServerSocket socket,
            final int startupTimeoutMillis,
            final int idleTimeoutMillis,
            final PgSession.Policy policy) {
        this.cdb = cdb;
        this.socket = socket;
        this.port = socket.getLocalPort();
        this.startupTimeoutMillis = startupTimeoutMillis;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.policy = policy;
    }

    /** Serves {@code cdb} on {@code address} until {@link #close()}, to any client, as the owner. */
    public static PgServer start(final Cdb cdb, final InetSocketAddress address) {
        return start(cdb, address, null);
    }

    /**
     * Serves {@code cdb} on {@code address} until {@link #close()}, to a client whose password is one of
     * {@code secrets}; {@code secrets} null serves any client, as the owner.
     */
    public static PgServer start(final Cdb cdb, final InetSocketAddress address, final Secrets secrets) {
        return start(cdb, address, secrets, STARTUP_TIMEOUT_MILLIS, IDLE_TIMEOUT_MILLIS,
                Limits.QUERY_DEADLINE_MILLIS, Limits.MAX_ROWS);
    }

    // the timeouts and the bounds are the tests' to shorten
    static PgServer start(
            final Cdb cdb,
            final InetSocketAddress address,
            final Secrets secrets,
            final int startupTimeoutMillis,
            final int idleTimeoutMillis,
            final long deadlineMillis,
            final int maxRows) {
        final PgSession.Policy policy = new PgSession.Policy(secrets, deadlineMillis, maxRows);
        final ServerSocket socket;
        try {
            socket = new ServerSocket();
            try {
                socket.setReuseAddress(true);
                socket.bind(address);
            } catch (final IOException | RuntimeException e) {
                socket.close();
                throw e;
            }
        } catch (final IOException e) {
            throw new CdbException("could not serve cdb over pg wire [address=" + address + ']', e);
        }
        final PgServer server = new PgServer(cdb, socket, startupTimeoutMillis, idleTimeoutMillis, policy);
        try {
            server.threads.execute(server::accept);
        } catch (final RuntimeException | Error e) {
            server.close();
            throw e;
        }
        LOG.log(Level.INFO, "serving cdb over pg wire [address=" + socket.getLocalSocketAddress() + ']');
        return server;
    }

    /** The port served, the real one when started on port 0. */
    public int port() {
        return port;
    }

    /** Stops serving; idempotent. Open connections are closed; their threads get a bounded time to end. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            socket.close();
        } catch (final IOException e) {
            LOG.log(Level.WARNING, "could not close the pg listener [port=" + port + ']', e);
        }
        for (final Socket connection : open) {
            quietly(connection);
        }
        threads.shutdown();
        try {
            if (!threads.awaitTermination(DRAIN_MILLIS, TimeUnit.MILLISECONDS)) {
                LOG.log(Level.WARNING, "pg sessions still running at close [waitedMillis=" + DRAIN_MILLIS + ']');
                threads.shutdownNow();
            }
        } catch (final InterruptedException _) {
            threads.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOG.log(Level.INFO, "stopped serving cdb over pg wire [port=" + port + ']');
    }

    private void accept() {
        Thread.currentThread().setName("cdb-pg-accept-" + port);
        while (!closed) {
            final Socket connection;
            try {
                connection = socket.accept();
            } catch (final IOException e) {
                if (!closed) {
                    LOG.log(Level.WARNING, "stopped accepting pg connections [port=" + port + ']', e);
                }
                return;
            }
            admit(connection);
        }
    }

    // the permit is taken before the connection's thread exists and released when the thread ends, on every path
    private void admit(final Socket connection) {
        if (!permits.tryAcquire()) {
            LOG.log(Level.DEBUG, "refuse pg connection over the limit [limit=" + MAX_CONNECTIONS + ']');
            quietly(connection);
            return;
        }
        final int id = sessions.incrementAndGet();
        try {
            open.add(connection);
            threads.execute(() -> {
                try {
                    serve(connection, id);
                } finally {
                    open.remove(connection);
                    quietly(connection);
                    permits.release();
                }
            });
        } catch (final RejectedExecutionException e) {
            open.remove(connection);
            quietly(connection);
            permits.release();
            if (!closed) {
                throw e;
            }
        } catch (final RuntimeException | Error e) {
            open.remove(connection);
            quietly(connection);
            permits.release();
            throw e;
        }
    }

    private void serve(final Socket connection, final int id) {
        Thread.currentThread().setName("cdb-pg-session-" + id);
        try {
            connection.setTcpNoDelay(true);
            connection.setSoTimeout(startupTimeoutMillis);
            final DataInputStream in = new DataInputStream(new BufferedInputStream(connection.getInputStream()));
            final PgSession session = new PgSession(cdb, in, new PgOutput(connection.getOutputStream()), id,
                    keys.nextInt(), policy, this::cancel, cancel -> watch(id, cancel));
            if (session.startup()) {
                connection.setSoTimeout(idleTimeoutMillis);
                live.put(id, session);
                try {
                    session.run();
                } finally {
                    live.remove(id);
                }
            }
        } catch (final SocketTimeoutException _) {
            LOG.log(Level.DEBUG, () -> "close silent pg connection [session=" + id + ", startupTimeoutMillis="
                    + startupTimeoutMillis + ", idleTimeoutMillis=" + idleTimeoutMillis + ']');
        } catch (final SocketException | EOFException e) {
            LOG.log(Level.DEBUG, () -> "pg connection ended [session=" + id + ", reason=" + e.getMessage() + ']');
        } catch (final IOException e) {
            LOG.log(Level.DEBUG, () -> "pg connection failed [session=" + id + ']', e);
        }
    }

    // a cancel request's connection names the session to cancel; an unknown process id or a wrong key does nothing,
    // as in PostgreSQL, and the request's connection is closed either way
    private void cancel(final int processId, final int secretKey) {
        final PgSession session = live.get(processId);
        if (session != null) {
            session.cancel(secretKey);
        }
    }

    // a statement's deadline: a virtual thread waits for the statement to end and cancels it when the deadline comes
    // first; a session runs one statement at a time, so the watches are bounded by the connections' permits. Once the
    // server is closing, a new statement is cancelled at once rather than run past the close.
    private Runnable watch(final int id, final CdbCancel cancel) {
        final CountDownLatch ended = new CountDownLatch(1);
        try {
            threads.execute(() -> {
                Thread.currentThread().setName("cdb-pg-deadline-" + id);
                try {
                    if (!ended.await(policy.deadlineMillis(), TimeUnit.MILLISECONDS)) {
                        cancel.cancel();
                    }
                } catch (final InterruptedException _) {
                    // the close stops the watch; the statement's session ends with the close
                    Thread.currentThread().interrupt();
                }
            });
        } catch (final RejectedExecutionException _) {
            cancel.cancel();
        }
        return ended::countDown;
    }

    private static void quietly(final Socket connection) {
        try {
            connection.close();
        } catch (final IOException e) {
            LOG.log(Level.DEBUG, () -> "could not close pg connection [remote="
                    + connection.getRemoteSocketAddress() + ']', e);
        }
    }
}
