package dev.marregui.comando.frontear;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.frontear.broker.Broker;
import dev.marregui.comando.frontear.broker.BrokerOptions;
import dev.marregui.comando.frontear.control.ControlPlane;
import dev.marregui.comando.frontear.link.Links;
import dev.marregui.comando.frontear.queue.LogOptions;

/**
 * One frontear node: an MQTT 5 broker, the links to its peers and the control plane that changes them, started
 * together and closed in reverse. Configuration arrives only as signed control messages; the start takes the node's
 * name, its port, its data folder (the links' logs) and the control keys it trusts.
 */
public final class Frontear implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Frontear.class.getName());
    /**
     * How long the shutdown hook holds the JVM for the close; the links' logs force within it. Unmeasured: room for one
     * force of each link log; timing the close of a node with its links on a slow disk would settle it.
     */
    private static final long CLOSE_WAIT_SECONDS = 10;
    private static final int EXIT_USAGE = 2;
    /**
     * The links' logs: 64 MiB segments, 1 GiB per link before publishers get 0x97, forced at once (the measured
     * durable append is about 4 ms on an Apple SSD, so a group forms behind each force).
     */
    static final LogOptions LOG_OPTIONS = new LogOptions(64L << 20, 1L << 30, Duration.ZERO);

    private final Broker broker;
    private final Links links;
    private final ControlPlane control;

    private Frontear(final Broker broker, final Links links, final ControlPlane control) {
        this.broker = broker;
        this.links = links;
        this.control = control;
    }

    /**
     * Starts node {@code node} on loopback port {@code port} (0 for any), its links' logs under {@code dataDir},
     * trusting control messages signed by {@code trusted}. A part that fails to start closes the parts before it.
     */
    public static Frontear start(final String node, final int port, final Path dataDir, final Set<PublicKey> trusted,
            final InstantSource clock) throws IOException {
        Objects.requireNonNull(node, "node");
        final BrokerOptions defaults = BrokerOptions.defaults(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                port));
        final Broker broker = Broker.start(new BrokerOptions(defaults.bind(), defaults.receiveMaximum(),
                defaults.maximumPacketSize(), defaults.maxSessions(), clock, defaults.maxQueued()));
        Links links = null;
        try {
            links = new Links(broker, node, dataDir, LOG_OPTIONS);
            final ControlPlane control = new ControlPlane(broker, links, node, trusted, clock);
            LOG.log(System.Logger.Level.INFO, "frontear started [node=" + node + ", port=" + broker.port()
                    + ", dataDir=" + dataDir + ", keys=" + trusted.size() + ']');
            return new Frontear(broker, links, control);
        } catch (final RuntimeException e) {
            if (links != null) {
                links.close();
            }
            broker.close();
            throw e;
        }
    }

    /**
     * {@code <node> <port> <dataDir> <key>...}: serves node {@code node} on loopback until the JVM ends. Each
     * {@code key} is a file holding a trusted Ed25519 public key, base64 of its X.509 encoding. v1 binds loopback
     * only: it has no TLS and no client authentication.
     */
    public static void main(final String[] args) throws InterruptedException, IOException {
        if (args.length < 4) {
            System.err.println("usage: frontear <node> <port> <dataDir> <trusted-key-file>...");
            System.exit(EXIT_USAGE);
            return;
        }
        final Set<PublicKey> trusted = new HashSet<>();
        for (final String file : List.of(args).subList(3, args.length)) {
            trusted.add(readKey(Path.of(file)));
        }
        final CountDownLatch stopped = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        // the JVM owns shutdown-hook threads; this one releases main, then holds the exit until main has closed
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopped.countDown();
            try {
                closed.await(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (final InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }, "frontear-shutdown"));
        try (Frontear node = start(args[0], Integer.parseInt(args[1]), Path.of(args[2]), trusted,
                InstantSource.system())) {
            System.out.println("frontear serving [node=" + args[0] + ", port=" + node.port() + ']');
            stopped.await();
        } finally {
            closed.countDown();
        }
    }

    /** The broker's port. */
    public int port() {
        return broker.port();
    }

    /** The broker, for a host that runs frontear in its own process (comando). */
    public Broker broker() {
        return broker;
    }

    /**
     * Stops taking control messages, then the broker (no client can publish after it, so no message a link's filter
     * covers is published once its link is gone), then the links (their logs force). A message a link takes from its
     * peer after the broker closed is refused, and the peer sends it again later. Idempotent.
     */
    @Override
    public void close() {
        RuntimeException failure = null;
        for (final AutoCloseable part : List.<AutoCloseable>of(control, broker, links)) {
            try {
                part.close();
            } catch (final Exception e) {
                if (failure == null) {
                    failure = new IllegalStateException("frontear close failed [part=" + part.getClass()
                            .getSimpleName() + ']', e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** The Ed25519 public key in {@code file}: base64 of its X.509 encoding, surrounding whitespace ignored. */
    static PublicKey readKey(final Path file) throws IOException {
        final String text = Mapped.text(file).strip();
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder()
                    .decode(text)));
        } catch (final GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("not an Ed25519 public key [file=" + file + ']', e);
        }
    }

}
