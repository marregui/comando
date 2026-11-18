package dev.marregui.comando.cdb.serve;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;

import static org.assertj.core.api.Assertions.assertThat;

/// The PostgreSQL wire byte by byte, as no driver sends it: a malformed or unknown message answered and the session
/// ready again, counts and lengths larger than their message refused before anything is sized by them, and a
/// connection silent before or after its startup closed with its place freed.
class PgProtocolTest {

    /// Loopback answers in milliseconds; five seconds only bounds a read from a hung server.
    private static final int READ_TIMEOUT_MILLIS = 5_000;
    /// Short enough that a test waits a fraction of a second for the server to close a silent connection.
    private static final int SHORT_TIMEOUT_MILLIS = 200;
    /// The longest pause between two attempts while freed places are still being released.
    private static final long MAX_BACKOFF_MILLIS = 200;
    private static final int PROTOCOL_3 = 196_608;
    private static final String SELECT = "select 1 x from long_sequence(1)";

    @TempDir
    private Path root;

    private Cdb cdb;

    /// A fresh cdb per test.
    @BeforeEach
    void open() {
        cdb = Cdb.open(root);
    }

    /// Closes the cdb.
    @AfterEach
    void close() {
        cdb.close();
    }

    /// A simple query whose text has no terminator is answered with an error and ReadyForQuery, and the next query
    /// on the same connection runs.
    @Test
    void answersAnUnterminatedQueryAndStaysReady() throws IOException {
        try (PgServer server = start(PgServer.STARTUP_TIMEOUT_MILLIS, PgServer.IDLE_TIMEOUT_MILLIS);
             Client client = new Client(server.port())) {
            client.startup();
            client.send('Q', "select 1".getBytes(StandardCharsets.UTF_8));
            assertThat(client.untilReady()).containsExactly('E', 'Z');
            client.query(SELECT);
            assertThat(client.untilReady()).containsExactly('T', 'D', 'C', 'Z');
        }
    }

    /// A message type the server does not take (`F`, a function call) is answered with an error and ReadyForQuery
    /// instead of waiting for a Sync that a simple-mode client never sends.
    @Test
    void answersAnUnknownMessageAndStaysReady() throws IOException {
        try (PgServer server = start(PgServer.STARTUP_TIMEOUT_MILLIS, PgServer.IDLE_TIMEOUT_MILLIS);
             Client client = new Client(server.port())) {
            client.startup();
            client.send('F', new byte[] {0, 0, 0, 1});
            assertThat(client.untilReady()).containsExactly('E', 'Z');
            client.query(SELECT);
            assertThat(client.untilReady()).containsExactly('T', 'D', 'C', 'Z');
        }
    }

    /// A placeholder past the protocol's 65535 parameters (`$70000`) is refused at Parse, the pipeline skipped to its
    /// Sync, and the connection usable after it.
    @Test
    void refusesAPlaceholderOverTheLimit() throws IOException {
        try (PgServer server = start(PgServer.STARTUP_TIMEOUT_MILLIS, PgServer.IDLE_TIMEOUT_MILLIS);
             Client client = new Client(server.port())) {
            client.startup();
            client.send('P', body(out -> {
                cstring(out, "");
                cstring(out, "select $70000 x from long_sequence(1)");
                out.writeShort(0);
            }));
            client.send('S', new byte[0]);
            assertThat(client.untilReady()).containsExactly('E', 'Z');
            assertThat(client.errorCode()).isEqualTo("42000");
            client.query(SELECT);
            assertThat(client.untilReady()).containsExactly('T', 'D', 'C', 'Z');
        }
    }

    /// A Bind whose parameter claims 1e9 bytes, or whose count claims 65535 parameters, in a message that holds a
    /// few is refused as malformed before anything is allocated for it; the connection is usable after its Sync.
    @Test
    void refusesABindLongerThanItsMessage() throws IOException {
        try (PgServer server = start(PgServer.STARTUP_TIMEOUT_MILLIS, PgServer.IDLE_TIMEOUT_MILLIS);
             Client client = new Client(server.port())) {
            client.startup();
            client.send('P', body(out -> {
                cstring(out, "");
                cstring(out, "select $1 x from long_sequence(1)");
                out.writeShort(0);
            }));
            client.send('B', body(out -> {
                cstring(out, "");
                cstring(out, "");
                out.writeShort(0);
                out.writeShort(1);
                out.writeInt(1_000_000_000);
                out.writeShort(0);
            }));
            client.send('S', new byte[0]);
            assertThat(client.untilReady()).containsExactly('1', 'E', 'Z');
            assertThat(client.errorCode()).isEqualTo("08P01");
            client.send('B', body(out -> {
                cstring(out, "");
                cstring(out, "");
                out.writeShort(0);
                out.writeShort(0xFFFF);
                out.writeInt(-1);
            }));
            client.send('S', new byte[0]);
            assertThat(client.untilReady()).containsExactly('E', 'Z');
            assertThat(client.errorCode()).isEqualTo("08P01");
            client.query(SELECT);
            assertThat(client.untilReady()).containsExactly('T', 'D', 'C', 'Z');
        }
    }

    /// Every place taken by a connection that never sends its startup packet is freed at the startup timeout: the
    /// server closes each, and a connection after them is served.
    @Test
    void closesConnectionsSilentBeforeTheirStartup() throws IOException, InterruptedException {
        try (PgServer server = start(SHORT_TIMEOUT_MILLIS, PgServer.IDLE_TIMEOUT_MILLIS)) {
            final List<Client> silent = new ArrayList<>();
            try {
                for (int i = 0; i < PgServer.MAX_CONNECTIONS; i++) {
                    silent.add(new Client(server.port()));
                }
                for (final Client client : silent) {
                    assertThat(client.read()).isEqualTo(-1);
                }
            } finally {
                closeAll(silent);
            }
            assertServedEventually(server.port());
        }
    }

    /// Every started connection that then sends nothing is closed at the idle timeout and its place freed: a
    /// connection after them is served.
    @Test
    void closesStartedConnectionsIdleTooLong() throws IOException, InterruptedException {
        try (PgServer server = start(PgServer.STARTUP_TIMEOUT_MILLIS, SHORT_TIMEOUT_MILLIS)) {
            final List<Client> idle = new ArrayList<>();
            try {
                for (int i = 0; i < PgServer.MAX_CONNECTIONS; i++) {
                    final Client client = new Client(server.port());
                    idle.add(client);
                    client.startup();
                }
                for (final Client client : idle) {
                    assertThat(client.read()).isEqualTo(-1);
                }
            } finally {
                closeAll(idle);
            }
            assertServedEventually(server.port());
        }
    }

    private PgServer start(final int startupTimeoutMillis, final int idleTimeoutMillis) {
        return PgServer.start(cdb, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                startupTimeoutMillis, idleTimeoutMillis, Limits.QUERY_DEADLINE_MILLIS, Limits.MAX_ROWS);
    }

    // a connection served end to end; the server closes a connection before it releases its place, so a client
    // that saw the close may be early, and tries again with backoff until the read timeout
    private static void assertServedEventually(final int port) throws IOException, InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READ_TIMEOUT_MILLIS);
        long backoffMillis = 10;
        while (true) {
            try (Client client = new Client(port)) {
                if (client.started()) {
                    client.query(SELECT);
                    assertThat(client.untilReady()).containsExactly('T', 'D', 'C', 'Z');
                    return;
                }
            }
            assertThat(System.nanoTime() - deadline).as("served before the deadline").isNegative();
            Thread.sleep(backoffMillis);
            backoffMillis = Math.min(backoffMillis * 2, MAX_BACKOFF_MILLIS);
        }
    }

    // every client closed, the first failure thrown with the others suppressed on it
    private static void closeAll(final List<Client> clients) throws IOException {
        IOException failure = null;
        for (final Client client : clients) {
            try {
                client.close();
            } catch (final IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static byte[] body(final Body body) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            body.write(out);
        }
        return bytes.toByteArray();
    }

    private static void cstring(final DataOutputStream out, final String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.writeByte(0);
    }

    /// Writes a message body.
    @FunctionalInterface
    private interface Body {

        void write(DataOutputStream out) throws IOException;
    }

    /// One connection that writes messages as given and reads the server's back by type, keeping the fields of the
    /// last ErrorResponse.
    private static final class Client implements AutoCloseable {

        private final DataInputStream in;
        private final DataOutputStream out;
        private final Socket socket;
        private Map<Character, String> error = Map.of();

        Client(final int port) throws IOException {
            socket = new Socket(InetAddress.getLoopbackAddress(), port);
            try {
                socket.setSoTimeout(READ_TIMEOUT_MILLIS);
                in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            } catch (final IOException | RuntimeException e) {
                socket.close();
                throw e;
            }
        }

        void startup() throws IOException {
            assertThat(started()).as("served").isTrue();
        }

        // protocol 3.0 with a user, then the greeting read up to its ReadyForQuery; false when the server closed
        // the connection unanswered
        boolean started() throws IOException {
            final byte[] parameters = body(body -> {
                cstring(body, "user");
                cstring(body, "admin");
                body.writeByte(0);
            });
            out.writeInt(8 + parameters.length);
            out.writeInt(PROTOCOL_3);
            out.write(parameters);
            out.flush();
            final int first = in.read();
            if (first < 0) {
                return false;
            }
            assertThat(messages(first)).startsWith('R').endsWith('Z');
            return true;
        }

        void query(final String sql) throws IOException {
            send('Q', body(body -> cstring(body, sql)));
        }

        void send(final char type, final byte[] body) throws IOException {
            out.writeByte(type);
            out.writeInt(body.length + 4);
            out.write(body);
            out.flush();
        }

        // the next byte from the server, -1 once it closed the connection
        int read() throws IOException {
            return in.read();
        }

        // the types of the messages up to and including the next ReadyForQuery
        List<Character> untilReady() throws IOException {
            return messages(in.read());
        }

        String errorCode() {
            return error.get('C');
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }

        // the messages from the one whose type byte was read as `first` up to and including the next ReadyForQuery
        private List<Character> messages(final int first) throws IOException {
            final List<Character> types = new ArrayList<>();
            int type = first;
            while (true) {
                assertThat(type).as("message type").isNotNegative();
                final byte[] body = in.readNBytes(in.readInt() - 4);
                types.add((char) type);
                if (type == 'E') {
                    error = fields(body);
                }
                if (type == 'Z') {
                    return types;
                }
                type = in.read();
            }
        }

        private static Map<Character, String> fields(final byte[] body) {
            final Map<Character, String> fields = new HashMap<>();
            int at = 0;
            while (at < body.length && body[at] != 0) {
                final char code = (char) body[at++];
                int end = at;
                while (body[end] != 0) {
                    end++;
                }
                fields.put(code, new String(body, at, end - at, StandardCharsets.UTF_8));
                at = end + 1;
            }
            return fields;
        }
    }
}
