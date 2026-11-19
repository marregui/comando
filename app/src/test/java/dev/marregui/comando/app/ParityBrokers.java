package dev.marregui.comando.app;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.frontear.Frontear;
import dev.marregui.comando.frontear.mqtt.Codec;
import dev.marregui.comando.frontear.mqtt.Packet;

import static org.awaitility.Awaitility.await;

/// The two brokers a parity test compares, side by side on loopback: the HiveMQ Edge release comando pins
/// ([EmbeddedEdge#RELEASE], its zip checked against [EmbeddedEdge#RELEASE_SHA256] and kept once in
/// `build/parity-cache`), started by [EmbeddedEdge] in a folder of the test's own, on free ports of its own and with a
/// registry of its own, so neither the user's comando Edge nor `~/.comando-edge.json` is read or touched; and a
/// frontear node on a free port. [#close] stops both.
final class ParityBrokers implements AutoCloseable {

    /// comando's Edge's own MQTT port, which a test never takes (G-11.8).
    private static final int COMANDO_EDGE_MQTT = EmbeddedEdge.Ports.DEFAULT.mqtt();
    private static final System.Logger LOG = System.getLogger(ParityBrokers.class.getName());
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    /// How long a readiness probe waits to connect and for the CONNACK: a broker on loopback that is up answers in
    /// milliseconds, and one that is not refuses at once.
    private static final int PROBE_CONNECT_MILLIS = 1_000;
    private static final int PROBE_READ_MILLIS = 2_000;
    /// How long a start may take once the zip is here; [EmbeddedEdge#READY_WITHIN] is comando's own.
    private static final Duration READY_WITHIN = Duration.ofSeconds(120);
    /// The first run downloads the release (359 MB; under a minute on the machine this was written on, 2026-11-19),
    /// then a start took 2.2 s and 15.7 s in two runs; the bound leaves room for a slow network.
    private static final Duration UP_WITHIN = Duration.ofMinutes(5);

    private final EmbeddedEdge edge;
    private final Duration edgeStart;
    private final Frontear frontear;
    private final EmbeddedEdge.Ports ports;

    private ParityBrokers(final EmbeddedEdge edge, final EmbeddedEdge.Ports ports, final Frontear frontear,
            final Duration edgeStart) {
        this.edge = edge;
        this.ports = ports;
        this.frontear = frontear;
        this.edgeStart = edgeStart;
    }

    @Override
    public void close() {
        try {
            frontear.close();
        } finally {
            edge.close();
        }
    }

    /// Starts both brokers under `root`, the release's zip from `cache`, downloaded there the first time.
    static ParityBrokers start(final Path root, final Path cache) throws IOException {
        final List<Intent.Notify> notices = new CopyOnWriteArrayList<>();
        final EmbeddedEdge.Located zip = EmbeddedEdge.locate(null, null, cache, EmbeddedEdge.RELEASE_ZIP,
                EmbeddedEdge.RELEASE_SHA256, i -> {
                    if (i instanceof final Intent.Notify n) {
                        notices.add(n);
                    }
                });
        if (zip.zip() == null) {
            throw new IllegalStateException("no HiveMQ Edge distribution [problem=" + zip.problem() + ']');
        }
        final EmbeddedEdge.Ports ports = freePorts();
        final EmbeddedEdge edge = new EmbeddedEdge(root.resolve("edge"), root.resolve("owner"), "parity", i -> {
            if (i instanceof final Intent.Notify n) {
                notices.add(n);
            }
        }, root.resolve("comando-edge.json"), ports, () -> zip, ParityBrokers::answers, READY_WITHIN);
        Frontear node = null;
        try {
            final long startedNanos = System.nanoTime();
            final String asked = edge.want(true);
            LOG.log(System.Logger.Level.INFO, "starting HiveMQ Edge [release=" + EmbeddedEdge.RELEASE + ", mqtt="
                    + ports.mqtt() + ", answer=" + asked + ']');
            await().atMost(UP_WITHIN).until(() -> failed(notices) || edge.running() && answers(ports));
            if (failed(notices)) {
                throw new IllegalStateException("HiveMQ Edge did not start [notices=" + notices + ']');
            }
            final Duration edgeStart = Duration.ofNanos(System.nanoTime() - startedNanos);
            Files.createDirectories(root.resolve("frontear"));
            node = Frontear.start("parity", 0, root.resolve("frontear"), Set.of(), InstantSource.system());
            LOG.log(System.Logger.Level.INFO, "brokers up [edgeMqtt=" + ports.mqtt() + ", edgeStartMillis="
                    + edgeStart.toMillis() + ", frontear=" + node.port() + ']');
            return new ParityBrokers(edge, ports, node, edgeStart);
        } catch (final IOException | RuntimeException e) {
            try {
                if (node != null) {
                    node.close();
                }
            } catch (final RuntimeException c) {
                e.addSuppressed(c);
            }
            try {
                edge.close();
            } catch (final RuntimeException c) {
                e.addSuppressed(c);
            }
            throw e;
        }
    }

    /// Where HiveMQ Edge listens for MQTT.
    InetSocketAddress edge() {
        return new InetSocketAddress(LOOPBACK, ports.mqtt());
    }

    /// Where frontear listens.
    InetSocketAddress frontear() {
        return new InetSocketAddress(LOOPBACK, frontear.port());
    }

    /// How long HiveMQ Edge took from the start asked to its first CONNACK, the zip already here.
    Duration edgeStart() {
        return edgeStart;
    }

    private static boolean failed(final List<Intent.Notify> notices) {
        return notices.stream().anyMatch(n -> n.level() == Notice.Level.WARN || n.level() == Notice.Level.ERROR);
    }

    /// Four distinct free loopback ports, none of them comando's Edge's.
    private static EmbeddedEdge.Ports freePorts() throws IOException {
        final Set<Integer> taken = new HashSet<>();
        final int[] got = new int[4];
        for (int i = 0; i < got.length; ) {
            try (final ServerSocket s = new ServerSocket(0, 1, LOOPBACK)) {
                final int port = s.getLocalPort();
                if (port != COMANDO_EDGE_MQTT && taken.add(port)) {
                    got[i++] = port;
                }
            }
        }
        return new EmbeddedEdge.Ports(got[0], got[1], got[2], got[3]);
    }

    /// Whether an MQTT 5 broker answers a CONNECT on `ports`' MQTT port with a successful CONNACK.
    private static boolean answers(final EmbeddedEdge.Ports ports) {
        try (final Socket s = new Socket()) {
            s.connect(new InetSocketAddress(LOOPBACK, ports.mqtt()), PROBE_CONNECT_MILLIS);
            s.setSoTimeout(PROBE_READ_MILLIS);
            s.getOutputStream().write(Codec.encode(ParityProbe.clean("parity-ready-" + System.nanoTime())));
            final InputStream in = new BufferedInputStream(s.getInputStream());
            final int header = in.read();
            return header >= 0 && Codec.read(in, header, Packet.PROTOCOL_MAXIMUM_PACKET_SIZE)
                    instanceof final Packet.ConnAck a && a.reasonCode() == Packet.SUCCESS;
        } catch (final IOException _) {
            return false;
        }
    }
}
