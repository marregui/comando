package dev.marregui.comando.db;

import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.port.QueryPort;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.testkit.RecordingSink;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [Lane] bounds the queries it runs for Claude and the schema loads, so a server that stops answering cannot hold
/// the connection's lane.
class LaneTest {

    /// The statement timeout the lane under test gives a query, in place of the 60 s it gives in use.
    private static final int QUERY_SECONDS = 1;

    /// A server that completes the startup, then never answers the first query and ignores its cancel: the query fails
    /// within its statement timeout plus the margin, and the lane runs the next query on a new connection.
    @Test
    void aQueryToAServerThatStopsAnsweringEndsAtItsBoundAndTheLaneTakesTheNextRun() throws Exception {
        try (PgStub server = new PgStub()) {
            final ScheduledExecutorService timer = Threads.timedStrand("lane-test-timer");
            final ExecutorService cancellers = Threads.strand("lane-test-cancel");
            final DbSettings settings = new DbSettings(0, 1_000, 500, 60, 1_000L);
            final Lane lane = new Lane("stalled", new RecordingSink(), new ResultRegistry(), settings, timer,
                    cancellers, stopped -> {
                    }, QUERY_SECONDS);
            final ConnectionSpec spec =
                    new ConnectionSpec("stalled", "127.0.0.1", server.port(), "db", "u", "p", false, "disable");
            try {
                final long bound = QUERY_SECONDS + Lane.QUERY_MARGIN_SECONDS;
                final CompletableFuture<QueryPort.Rows> first = new CompletableFuture<>();
                final long start = System.nanoTime();
                assertThat(lane.query(spec, "SELECT 1", 10, first, () -> true)).isTrue();
                assertThatThrownBy(() -> first.get(bound + 5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
                final long took = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
                assertThat(took).isBetween((long) QUERY_SECONDS, bound + 1);
                assertThat(server.stalled()).isNotEmpty();

                final CompletableFuture<QueryPort.Rows> second = new CompletableFuture<>();
                assertThat(lane.query(spec, "SELECT 2", 10, second, () -> true)).isTrue();
                assertThat(second.get(10, TimeUnit.SECONDS).rows()).isEmpty();
                assertThat(server.startups()).hasValue(2);
            } finally {
                lane.close();
                timer.shutdownNow();
                cancellers.shutdownNow();
            }
        }
    }

    /**
     * A PostgreSQL server on the wire protocol that completes every startup and answers setup statements; on the first
     * connection it leaves the first query unanswered, and it ignores cancel requests, as a server the network lost
     * does. Later connections answer every query with no rows.
     */
    private static final class PgStub implements AutoCloseable {

        private static final int SSL_REQUEST = 80877103;
        private static final int GSS_REQUEST = 80877104;
        private static final int CANCEL_REQUEST = 80877102;

        private final ServerSocket socket;
        private final AtomicInteger startups = new AtomicInteger();
        private final List<String> stalled = new CopyOnWriteArrayList<>();
        private final List<Socket> accepted = new CopyOnWriteArrayList<>();

        PgStub() throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Threads.fork("pg-stub", this::accept);
        }

        int port() {
            return socket.getLocalPort();
        }

        AtomicInteger startups() {
            return startups;
        }

        /** The messages of the stalled query left unanswered, by type. */
        List<String> stalled() {
            return stalled;
        }

        private void accept() {
            while (!socket.isClosed()) {
                try {
                    final Socket s = socket.accept();
                    accepted.add(s);
                    Threads.fork("pg-stub-conn", () -> serve(s));
                } catch (final IOException e) {
                    return; // closed
                }
            }
        }

        private void serve(final Socket s) {
            try (s) {
                final DataInputStream in = new DataInputStream(s.getInputStream());
                final DataOutputStream out = new DataOutputStream(s.getOutputStream());
                int len = in.readInt();
                int code = in.readInt();
                while (code == SSL_REQUEST || code == GSS_REQUEST) {
                    in.readNBytes(len - 8);
                    out.writeByte('N');
                    out.flush();
                    len = in.readInt();
                    code = in.readInt();
                }
                in.readNBytes(len - 8);
                if (code == CANCEL_REQUEST) {
                    return; // ignored, as by a server the cancel never reaches
                }
                final boolean stall = startups.incrementAndGet() == 1;
                out.writeByte('R');
                out.writeInt(8);
                out.writeInt(0);
                param(out, "server_version", "16.0");
                param(out, "client_encoding", "UTF8");
                param(out, "DateStyle", "ISO, MDY");
                param(out, "integer_datetimes", "on");
                param(out, "standard_conforming_strings", "on");
                out.writeByte('K');
                out.writeInt(12);
                out.writeInt(42);
                out.writeInt(7);
                ready(out);
                out.flush();
                while (true) {
                    final int type = in.read();
                    if (type < 0) {
                        return;
                    }
                    final byte[] body = in.readNBytes(in.readInt() - 4);
                    if (type == 'Q') { // a setup statement during connect
                        complete(out);
                        ready(out);
                    } else if (stall) {
                        stalled.add(String.valueOf((char) type));
                    } else {
                        answer(out, type, body);
                    }
                    out.flush();
                }
            } catch (final IOException e) {
                // the client went
            }
        }

        /** The extended protocol's answers for a statement that returns no rows. */
        private static void answer(final DataOutputStream out, final int type, final byte[] body) throws IOException {
            switch (type) {
                case 'P' -> empty(out, '1');
                case 'B' -> empty(out, '2');
                case 'C' -> empty(out, '3');
                case 'D' -> {
                    if (body.length > 0 && body[0] == 'S') {
                        out.writeByte('t');
                        out.writeInt(6);
                        out.writeShort(0);
                    }
                    empty(out, 'n');
                }
                case 'E' -> complete(out);
                case 'S' -> ready(out);
                default -> {
                    // Flush and the rest need no answer
                }
            }
        }

        private static void empty(final DataOutputStream out, final char type) throws IOException {
            out.writeByte(type);
            out.writeInt(4);
        }

        private static void complete(final DataOutputStream out) throws IOException {
            final byte[] tag = "SET".getBytes(StandardCharsets.UTF_8);
            out.writeByte('C');
            out.writeInt(4 + tag.length + 1);
            out.write(tag);
            out.writeByte(0);
        }

        private static void ready(final DataOutputStream out) throws IOException {
            out.writeByte('Z');
            out.writeInt(5);
            out.writeByte('I');
        }

        private static void param(final DataOutputStream out, final String key, final String value) throws IOException {
            final byte[] k = key.getBytes(StandardCharsets.UTF_8);
            final byte[] v = value.getBytes(StandardCharsets.UTF_8);
            out.writeByte('S');
            out.writeInt(4 + k.length + 1 + v.length + 1);
            out.write(k);
            out.writeByte(0);
            out.write(v);
            out.writeByte(0);
        }

        @Override
        public void close() throws IOException {
            socket.close();
            for (final Socket s : accepted) {
                s.close();
            }
        }
    }
}
