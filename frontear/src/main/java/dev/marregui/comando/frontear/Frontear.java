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
import dev.marregui.comando.frontear.alexander.ControlPlane;
import dev.marregui.comando.frontear.gateway.Broker;
import dev.marregui.comando.frontear.gateway.BrokerOptions;
import dev.marregui.comando.frontear.octopus.Links;
import dev.marregui.comando.frontear.store.LogOptions;
import dev.marregui.comando.frontear.watch.NodeSnapshot;
import dev.marregui.comando.frontear.watch.Watch;

/**
 * One frontear node: an MQTT 5 broker, the links to its peers and the control plane that changes them, started
 * together and closed in reverse. Configuration arrives only as signed control messages; the start takes the node's
 * name, its port, its data folder (the links' logs) and the control keys it trusts.
 *
 * <p>Its {@link #watch()} keeps, while on, what the broker routes, what its links do and their logs' force rounds;
 * {@link #snapshot()} reads its parts' state now. Neither makes a publisher wait.</p>
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
    /**
     * The events the watch keeps: a poller that reads every 250 ms (the lab's drain, ADR 0187 §2.3) loses none below
     * about 65 000 events a second, past the stress harness's highest step at four events a message.
     */
    public static final int WATCH_CAPACITY = 16_384;

    private final String node;
    private final InstantSource clock;
    private final Watch watch;
    private final Broker broker;
    private final Links links;
    private final ControlPlane control;

    private Frontear(final String node, final InstantSource clock, final Watch watch, final Broker broker,
            final Links links, final ControlPlane control) {
        this.node = node;
        this.clock = clock;
        this.watch = watch;
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
        return start(node, port, dataDir, trusted, clock, false);
    }

    /**
     * As above. With {@code linksNameNodes}, every link's id is the name of the node it reaches (ADR 0187 §2.1), so
     * this node's broker refuses with 0x85 a link client, client id {@code <peer>~<linkId>}, whose link id is not
     * {@code node}: a link aimed at another node's port connects to none and delivers nothing there.
     */
    public static Frontear start(final String node, final int port, final Path dataDir, final Set<PublicKey> trusted,
            final InstantSource clock, final boolean linksNameNodes) throws IOException {
        return start(node, port, dataDir, trusted, clock, linksNameNodes, false);
    }

    /**
     * As above. With {@code linksHeld}, which needs {@code linksNameNodes}, the broker refuses every link client 0x88
     * (Server unavailable) until {@link #openLinks()}: a peer's link that queued for this node while it was down
     * retries after its backoff, and drains once the host has subscribed what it reads, not before into no subscriber.
     *
     * @throws IllegalArgumentException when {@code linksHeld} is asked without {@code linksNameNodes}
     */
    public static Frontear start(final String node, final int port, final Path dataDir, final Set<PublicKey> trusted,
            final InstantSource clock, final boolean linksNameNodes, final boolean linksHeld) throws IOException {
        Objects.requireNonNull(node, "node");
        if (linksHeld && !linksNameNodes) {
            throw new IllegalArgumentException("links are held only on a node whose links name nodes [node=" + node
                    + ']');
        }
        final BrokerOptions defaults = BrokerOptions.defaults(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                port));
        final BrokerOptions options = new BrokerOptions(defaults.bind(), defaults.receiveMaximum(),
                defaults.maximumPacketSize(), defaults.maxSessions(), clock, defaults.maxQueued());
        final Watch watch = new Watch(WATCH_CAPACITY, clock);
        final Broker broker = linksNameNodes ? Broker.start(options, watch, node, linksHeld)
                : Broker.start(options, watch);
        Links links = null;
        try {
            links = new Links(broker, node, dataDir, LOG_OPTIONS, watch);
            final ControlPlane control = new ControlPlane(broker, links, node, trusted, clock);
            LOG.log(System.Logger.Level.INFO, "frontear started [node=" + node + ", port=" + broker.port()
                    + ", dataDir=" + dataDir + ", keys=" + trusted.size() + ", linksNameNodes=" + linksNameNodes
                    + ", linksHeld=" + linksHeld + ']');
            return new Frontear(node, clock, watch, broker, links, control);
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

    /** Lets the peers' links in, on a node started with its links held; nothing otherwise or when called again. */
    public void openLinks() {
        broker.openLinks();
    }

    /** The node's watch, of {@link #WATCH_CAPACITY} events; off until its owner turns it on. */
    public Watch watch() {
        return watch;
    }

    /**
     * The node now: the broker's figures and connections, the links' states and their logs'. Each part is read on its
     * own, under no lock a publisher holds for longer than a copy.
     */
    public NodeSnapshot snapshot() {
        return new NodeSnapshot(node, broker.port(), clock.millis(), broker.stats(), broker.connections(),
                links.state(), links.logs());
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
