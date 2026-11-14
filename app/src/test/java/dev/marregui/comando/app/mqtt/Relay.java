package dev.marregui.comando.app.mqtt;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;

/// A TCP relay for tests between MQTT clients and a real broker: it listens on `127.0.0.1` at a port of the system's
/// choosing, opens a connection to the broker for each client that connects, and forwards whole packets both ways
/// unchanged, counting them by type. [#cut] closes every link at once, as a network that failed would: neither side
/// sends a DISCONNECT, so the broker publishes the will and the client sees its connection end.
public final class Relay implements AutoCloseable {

    /// How long the connect to the broker may take: a broker on this machine answers in milliseconds.
    private static final Duration CONNECT_WITHIN = Duration.ofSeconds(10);
    private static final int TYPES = 16;

    private final String host;
    private final int port;
    private final ServerSocket server;
    private final ExecutorService accepting = Threads.strand("relay-accept");
    private final List<ExecutorService> pumps = new CopyOnWriteArrayList<>();
    private final List<Socket> open = new CopyOnWriteArrayList<>();
    private final AtomicInteger links = new AtomicInteger();
    private final AtomicIntegerArray fromClient = new AtomicIntegerArray(TYPES);
    private final AtomicIntegerArray fromBroker = new AtomicIntegerArray(TYPES);

    /// A relay to the broker at `host`:`port`, accepting clients at once.
    public Relay(final String host, final int port) {
        this.host = host;
        this.port = port;
        try {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (final IOException e) {
            ExecutorStop.now("relay", accepting);
            throw new UncheckedIOException(e);
        }
        accepting.execute(this::accept);
    }

    /// The port clients connect to.
    public int port() {
        return server.getLocalPort();
    }

    /// The links opened so far, one per client connection the broker took.
    public int links() {
        return links.get();
    }

    /// The packets of control packet `type` (the fixed header's high four bits) forwarded from clients to the broker.
    public int fromClient(final int type) {
        return fromClient.get(type);
    }

    /// The packets of control packet `type` forwarded from the broker to clients.
    public int fromBroker(final int type) {
        return fromBroker.get(type);
    }

    /// Closes every open link, both of its sockets, without a word to either side; later clients are still relayed.
    public void cut() {
        for (final Socket s : open) {
            quietly(s);
        }
    }

    /// Stops listening and closes every link.
    @Override
    public void close() {
        try {
            server.close();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            cut();
            ExecutorStop.now("relay", accepting);
            for (final ExecutorService e : pumps) {
                ExecutorStop.now("relay", e);
            }
        }
    }

    private void accept() {
        while (!server.isClosed()) {
            final Socket client;
            try {
                client = server.accept();
            } catch (final IOException _) {
                return;
            }
            final Socket broker = new Socket();
            try {
                broker.connect(new InetSocketAddress(host, port), (int) CONNECT_WITHIN.toMillis());
            } catch (final IOException _) {
                quietly(client);
                quietly(broker);
                continue;
            }
            open.add(client);
            open.add(broker);
            links.incrementAndGet();
            pump(client, broker, fromClient, "relay-to-broker");
            pump(broker, client, fromBroker, "relay-to-client");
        }
    }

    /// Forwards whole packets from `from` to `to` on a strand of its own until either side closes, then closes both.
    private void pump(final Socket from, final Socket to, final AtomicIntegerArray counts, final String name) {
        final ExecutorService strand = Threads.strand(name);
        pumps.add(strand);
        strand.execute(() -> {
            try (InputStream in = new BufferedInputStream(from.getInputStream())) {
                final OutputStream out = to.getOutputStream();
                while (true) {
                    final int header = in.read();
                    if (header < 0) {
                        return;
                    }
                    final int length = Wire.readVbi(in);
                    final byte[] body = in.readNBytes(length);
                    if (body.length < length) {
                        return;
                    }
                    out.write(new Wire.Out().u8(header).vbi(length).bytes(body).toByteArray());
                    counts.incrementAndGet(header >>> 4);
                }
            } catch (final IOException _) {
                // a side closed, or the relay was cut: the link ends either way
            } finally {
                quietly(from);
                quietly(to);
                open.remove(from);
                open.remove(to);
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
