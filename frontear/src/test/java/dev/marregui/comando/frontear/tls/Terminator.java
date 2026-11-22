package dev.marregui.comando.frontear.tls;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;

/// A TLS server for tests on `127.0.0.1`, at a port of the system's choosing, that asks each client for its
/// certificate and, after the handshake, copies bytes both ways to a plain gateway; it keeps each session's protocol
/// and the client's fingerprint, or the handshake's failure.
public final class Terminator implements AutoCloseable {

    /// How long a handshake or the connect to the gateway may take: both are on this machine.
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final SSLServerSocket listening;
    private final int gatewayPort;
    private final ExecutorService accepting = Threads.strand("tls-terminator-accept");
    private final List<ExecutorService> pumps = new CopyOnWriteArrayList<>();
    private final List<Socket> open = new CopyOnWriteArrayList<>();
    private final List<String> protocols = new CopyOnWriteArrayList<>();
    private final List<String> peers = new CopyOnWriteArrayList<>();
    private final List<IOException> failures = new CopyOnWriteArrayList<>();

    /// A terminator with `context`'s key and pins in front of the gateway at `gatewayPort`, accepting at once.
    public Terminator(final SSLContext context, final int gatewayPort) throws IOException {
        this.gatewayPort = gatewayPort;
        try {
            listening = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 50,
                    InetAddress.getLoopbackAddress());
            listening.setNeedClientAuth(true);
        } catch (final IOException e) {
            ExecutorStop.now("tls-terminator", accepting);
            throw e;
        }
        accepting.execute(this::accept);
    }

    /// The port clients connect to.
    public int port() {
        return listening.getLocalPort();
    }

    /// The protocol of each session whose handshake finished, in order.
    public List<String> protocols() {
        return List.copyOf(protocols);
    }

    /// The fingerprint of each client whose handshake finished, in order.
    public List<String> peers() {
        return List.copyOf(peers);
    }

    /// What each failed handshake threw, in order.
    public List<IOException> failures() {
        return List.copyOf(failures);
    }

    /// Stops listening and closes every connection.
    @Override
    public void close() throws IOException {
        try {
            listening.close();
        } finally {
            for (final Socket s : open) {
                quietly(s);
            }
            ExecutorStop.now("tls-terminator", accepting);
            for (final ExecutorService e : pumps) {
                ExecutorStop.now("tls-terminator", e);
            }
        }
    }

    private void accept() {
        while (!listening.isClosed()) {
            final SSLSocket client;
            try {
                client = (SSLSocket) listening.accept();
            } catch (final IOException _) {
                return;
            }
            open.add(client);
            try {
                client.setSoTimeout((int) WAIT.toMillis());
                client.startHandshake();
                client.setSoTimeout(0);
                protocols.add(client.getSession().getProtocol());
                peers.add(MachineKey.fingerprint(
                        ((X509Certificate) client.getSession().getPeerCertificates()[0]).getPublicKey()));
                final Socket gateway = new Socket();
                open.add(gateway);
                gateway.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), gatewayPort),
                        (int) WAIT.toMillis());
                pump(client, gateway, "tls-terminator-to-gateway");
                pump(gateway, client, "tls-terminator-to-client");
            } catch (final IOException e) {
                failures.add(e);
                quietly(client);
            }
        }
    }

    /// Copies `from` to `to` on a strand of its own until either side closes, then closes both.
    private void pump(final Socket from, final Socket to, final String name) {
        final ExecutorService strand = Threads.strand(name);
        pumps.add(strand);
        strand.execute(() -> {
            try {
                from.getInputStream().transferTo(to.getOutputStream());
            } catch (final IOException _) {
                // a side closed, or the terminator did: the link ends either way
            } finally {
                quietly(from);
                quietly(to);
            }
        });
    }

    private static void quietly(final Socket s) {
        try {
            s.close();
        } catch (final IOException _) {
            // closing is all that is left to do
        }
    }
}
