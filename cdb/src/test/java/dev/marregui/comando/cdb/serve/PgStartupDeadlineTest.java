package dev.marregui.comando.cdb.serve;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;

import static org.assertj.core.api.Assertions.assertThat;

/// The PG startup timeout bounds the whole startup, not each read: a client that trickles its startup packet a byte at
/// a time, each byte well inside the timeout, is still closed once the timeout has passed since it connected.
class PgStartupDeadlineTest {

    /// Short, so the test ends quickly; the trickle's gap is under half of it, so no single read waits it out.
    private static final int STARTUP_TIMEOUT_MILLIS = 500;
    private static final long GAP_MILLIS = 150L;
    /// Twenty times the timeout: a loaded machine delays the close, it does not take this long.
    private static final long GIVE_UP_MILLIS = 10_000L;

    /// A startup packet that declares 1,000 bytes and sends them a byte every 150 ms: the server closes the connection
    /// before the 10 s the trickle would last, where a per-read timeout keeps it open throughout.
    @Test
    void aTrickledStartupIsClosedAtTheDeadline(@TempDir final Path root) throws IOException, InterruptedException {
        try (Cdb cdb = Cdb.open(root);
                PgServer server = PgServer.start(cdb, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                        null, STARTUP_TIMEOUT_MILLIS, PgServer.IDLE_TIMEOUT_MILLIS, Limits.QUERY_DEADLINE_MILLIS,
                        Limits.MAX_ROWS);
                Socket client = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            client.setSoTimeout((int) GAP_MILLIS);
            final DataOutputStream out = new DataOutputStream(client.getOutputStream());
            out.writeInt(1_000);
            out.flush();
            final long startNanos = System.nanoTime();
            boolean closed = false;
            while (!closed && System.nanoTime() - startNanos < TimeUnit.MILLISECONDS.toNanos(GIVE_UP_MILLIS)) {
                closed = sendByte(out) || closedByPeer(client.getInputStream());
            }

            assertThat(closed).as("closed within %d ms", GIVE_UP_MILLIS).isTrue();
        }
    }

    // true when the write fails, as it does once the server has closed and reset the connection
    private static boolean sendByte(final OutputStream out) {
        try {
            out.write(0);
            out.flush();
            return false;
        } catch (final IOException _) {
            return true;
        }
    }

    // waits one gap for the server's end of stream; true when it came, or the connection was reset
    private static boolean closedByPeer(final InputStream in) throws IOException {
        try {
            return in.read() < 0;
        } catch (final SocketTimeoutException _) {
            return false;
        } catch (final SocketException _) {
            return true;
        }
    }
}
