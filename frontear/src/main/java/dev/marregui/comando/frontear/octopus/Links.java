package dev.marregui.comando.frontear.octopus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;

import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.frontear.gateway.Gateway;
import dev.marregui.comando.frontear.protocol.Packet;
import dev.marregui.comando.frontear.protocol.Topics;
import dev.marregui.comando.frontear.store.LogOptions;
import dev.marregui.comando.frontear.store.WholeFile;
import dev.marregui.comando.frontear.watch.LogView;
import dev.marregui.comando.frontear.watch.Watch;

/**
 * A broker's links to its peers, each with its log in a folder of {@code dataDir} named by its id. Any thread may
 * call any method; changes are serialised. Removing a link keeps its log, so putting it back resumes where it was.
 *
 * <p>Each link's spec is kept beside its log ({@link LinkFile}), written and forced before a put or a filter change
 * takes effect and deleted before a removal does, so a change that returned survives a restart: links made on the
 * same {@code dataDir} start every link whose spec they find, and drain its log. A spec that cannot be read is
 * logged and left where it is; its link stays down until a put replaces it. The writes and forces run on the
 * caller's thread, which for a node is its control plane's, at the rate control requests come.</p>
 *
 * <p>A link whose spec asks for TLS connects over the context {@link #tls} gives for its id; until one is given, or
 * while it gives none for that id, the link stays down, its state saying why.</p>
 */
public final class Links implements Octopus, AutoCloseable {

    /**
     * A link id names a folder: letters, digits, dot, dash, underscore and {@code @} (a link to another machine's
     * node is {@code <node>@<machine>}), and never a dot alone or two; 64 of them fit every file system's name limit
     * with room to spare.
     */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._@-]{1,64}");
    private static final System.Logger LOG = System.getLogger(Links.class.getName());
    private static final int MAX_PORT = 65_535;

    private final Gateway gateway;
    private final String localId;
    private final Path dataDir;
    private final LogOptions log;
    private final LinkOptions options;
    private final InstantSource clock;
    private final Watch watch;
    private final Object lock = new Object();
    /** Written under {@link #lock}; read from any thread by {@link #state}. */
    private final Map<String, Link> links = new ConcurrentHashMap<>();
    /** Told each client the broker accepts: a peer's link to this node wakes this node's link to that peer. */
    private final Consumer<String> connectListener = this::peerConnected;
    /**
     * Each link's TLS context by its id, null until {@link #tls} gives one: written by the caller of {@link #tls}
     * under {@link #lock}, read by the caller of {@link #put} before it takes the lock, to ask it outside the lock.
     */
    private volatile Function<String, SSLContext> tls;
    /** Guarded by {@link #lock}. */
    private boolean closed;

    /**
     * Links of {@code gateway}, connecting to their peers as {@code localId}, with their logs under {@code dataDir},
     * bounded by {@link LinkOptions#defaults}.
     */
    public Links(final Gateway gateway, final String localId, final Path dataDir, final LogOptions log) {
        this(gateway, localId, dataDir, log, LinkOptions.defaults());
    }

    /** As above, each link bounded by {@code options}. */
    public Links(final Gateway gateway, final String localId, final Path dataDir, final LogOptions log,
            final LinkOptions options) {
        this(gateway, localId, dataDir, log, options, InstantSource.system());
    }

    /** As the first, each link and its log keeping its steps in {@code watch} while that is on. */
    public Links(final Gateway gateway, final String localId, final Path dataDir, final LogOptions log,
            final Watch watch) {
        this(gateway, localId, dataDir, log, LinkOptions.defaults(), InstantSource.system(), watch);
    }

    /**
     * As above, with {@code clock} to time how long a message waited in a log.
     *
     * @throws UncheckedIOException when {@code dataDir} exists and cannot be listed; the links it started are closed
     */
    Links(final Gateway gateway, final String localId, final Path dataDir, final LogOptions log,
            final LinkOptions options, final InstantSource clock) {
        this(gateway, localId, dataDir, log, options, clock, new Watch(1));
    }

    /**
     * As above, each link and its log keeping its steps in {@code watch} while that is on.
     *
     * @throws UncheckedIOException when {@code dataDir} exists and cannot be listed; the links it started are closed
     */
    Links(final Gateway gateway, final String localId, final Path dataDir, final LogOptions log,
            final LinkOptions options, final InstantSource clock, final Watch watch) {
        this.watch = Objects.requireNonNull(watch, "watch");
        this.gateway = Objects.requireNonNull(gateway, "broker");
        this.localId = Objects.requireNonNull(localId, "localId");
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
        this.log = Objects.requireNonNull(log, "log");
        this.options = Objects.requireNonNull(options, "options");
        this.clock = Objects.requireNonNull(clock, "clock");
        try {
            restore();
            gateway.addConnectListener(connectListener);
        } catch (final RuntimeException | Error e) {
            try {
                close();
            } catch (final RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /**
     * {@inheritDoc} The spec is kept before it takes effect. Fails with {@link UncheckedIOException}, the link and its
     * kept spec as they were, when the spec cannot be kept or a new link's log cannot be opened.
     */
    @Override
    public void put(final LinkSpec spec) {
        check(spec);
        while (true) {
            final Function<String, SSLContext> contextOf = tls;
            final SSLContext context = contextOf == null ? null : contextOf.apply(spec.id());
            synchronized (lock) {
                if (closed) {
                    throw new IllegalStateException("links closed [link=" + spec.id() + ']');
                }
                if (contextOf == tls) {
                    put(spec, context);
                    return;
                }
            }
        }
    }

    /** {@link #put} with the context the links' function gives the spec's id now; the caller holds {@link #lock}. */
    private void put(final LinkSpec spec, final SSLContext context) {
        final Link link = links.get(spec.id());
        final byte[] before = kept(spec.id());
        keep(spec);
        if (link != null) {
            link.update(spec);
            return;
        }
        try {
            start(spec, context);
        } catch (final RuntimeException e) {
            putBack(spec.id(), before, e);
            throw e;
        }
    }

    /**
     * {@inheritDoc} Its spec is deleted first, so it does not come back after a restart; its log stays. Refuses an id
     * that is not a folder name; fails with {@link UncheckedIOException}, the link still running, when the spec
     * cannot be deleted.
     */
    @Override
    public void remove(final String id) {
        checkId(id);
        // closed under the lock: a put of the same id must not open its log while this link still holds it
        synchronized (lock) {
            final Path file = dataDir.resolve(id).resolve(LinkFile.NAME);
            if (Files.exists(file)) {
                try {
                    WholeFile.delete(file);
                } catch (final IOException e) {
                    throw new UncheckedIOException("link spec not deleted [link=" + id + ", file=" + file + ']', e);
                }
            }
            final Link link = links.remove(id);
            if (link != null) {
                link.close();
            }
        }
    }

    /**
     * {@inheritDoc} The changed spec is kept before the filters change. Fails with {@link UncheckedIOException}, the
     * filters as they were, when it cannot be kept.
     */
    @Override
    public void filters(final String id, final Direction direction, final Set<String> filters) {
        Objects.requireNonNull(direction, "direction");
        final Set<String> copy = Set.copyOf(filters);
        checkFilters(id, copy);
        synchronized (lock) {
            final Link link = links.get(id);
            if (link == null) {
                throw new IllegalArgumentException("no such link [link=" + id + ']');
            }
            final LinkSpec s = link.spec();
            switch (direction) {
                case OUT -> {
                    keep(new LinkSpec(s.id(), s.host(), s.port(), copy, s.in(), s.hopLimit(), s.tls()));
                    link.out(copy);
                }
                case IN -> {
                    keep(new LinkSpec(s.id(), s.host(), s.port(), s.out(), copy, s.hopLimit(), s.tls()));
                    link.in(copy);
                }
            }
        }
    }

    /**
     * Takes {@code contextOf} as the TLS context of each link by its id, for the links whose spec asks for TLS, those
     * running now and those put later: a TLS link waiting for one connects, one connected over another context
     * connects again over its new one, and one {@code contextOf} gives null for stays down, its state saying why.
     * {@code contextOf} is asked on the caller's thread, of this call or of a {@link #put}, never under the links'
     * lock; of two calls at once, the later one's function holds.
     *
     * @throws IllegalStateException when the links are closed
     */
    public void tls(final Function<String, SSLContext> contextOf) {
        Objects.requireNonNull(contextOf, "contextOf");
        final List<String> ids;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("links closed");
            }
            tls = contextOf;
            ids = List.copyOf(links.keySet());
        }
        // a null context is a value here: the link stays down
        final Map<String, SSLContext> contexts = new HashMap<>();
        for (final String id : ids) {
            contexts.put(id, contextOf.apply(id));
        }
        synchronized (lock) {
            // a link put since asked the function itself; a later call's function replaces this one's
            if (closed || contextOf != tls) {
                return;
            }
            for (final Link link : links.values()) {
                if (contexts.containsKey(link.id())) {
                    link.tls(contexts.get(link.id()));
                }
            }
        }
        LOG.log(System.Logger.Level.INFO, "links TLS contexts set [links=" + ids.size() + ']');
    }

    @Override
    public List<LinkState> state() {
        final List<LinkState> states = new ArrayList<>();
        for (final Link link : links.values()) {
            states.add(link.state());
        }
        states.sort(Comparator.comparing(LinkState::id));
        return states;
    }

    /** Every link's log now, by link id; from any thread, as {@link #state}. */
    public List<LogView> logs() {
        final List<LogView> views = new ArrayList<>();
        for (final Link link : links.values()) {
            views.add(link.logView());
        }
        views.sort(Comparator.comparing(LogView::link));
        return views;
    }

    /**
     * The hops {@code message} has made between brokers, as a link counts them: 0 when it carries no count, -1 when
     * its count is not one decimal number.
     */
    public static int hops(final Packet.Publish message) {
        return Forwarding.hops(message);
    }

    /** Stops every link, each even when one before it failed; the first failure is thrown with the others on it. */
    @Override
    public void close() {
        RuntimeException failure = null;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            gateway.removeConnectListener(connectListener);
            for (final Link link : List.copyOf(links.values())) {
                links.remove(link.id());
                try {
                    link.close();
                } catch (final RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void check(final LinkSpec spec) {
        final String id = spec.id();
        checkId(id);
        if (spec.host().isBlank()) {
            throw new IllegalArgumentException("link host empty [link=" + id + ']');
        }
        if (spec.port() < 1 || spec.port() > MAX_PORT) {
            throw new IllegalArgumentException("link port out of range [link=" + id + ", port=" + spec.port() + ']');
        }
        if (spec.hopLimit() < 1) {
            throw new IllegalArgumentException("link hop limit below 1 [link=" + id + ", hopLimit="
                    + spec.hopLimit() + ']');
        }
        checkFilters(id, spec.out());
        checkFilters(id, spec.in());
    }

    private static void checkId(final String id) {
        if (!ID.matcher(id).matches() || ".".equals(id) || "..".equals(id)) {
            throw new IllegalArgumentException("link id not a folder name [link=" + id + ']');
        }
    }

    private static void checkFilters(final String id, final Set<String> filters) {
        for (final String f : filters) {
            if (!Topics.validFilter(f)) {
                throw new IllegalArgumentException("link filter not valid [link=" + id + ", filter=" + f + ']');
            }
        }
    }

    /**
     * Client {@code clientId} connected to this node's broker: when it is a peer's link to this node,
     * {@code <peer>~<localId>}, the peer runs again, so this node's link {@code <peer>} reconnects now rather than at
     * the end of its backoff. On the connection's reader; never blocks.
     */
    private void peerConnected(final String clientId) {
        final int mark = clientId.length() - localId.length() - 1;
        if (mark <= 0 || clientId.charAt(mark) != '~' || !clientId.endsWith(localId)) {
            return;
        }
        final Link link = links.get(clientId.substring(0, mark));
        if (link != null) {
            link.wake();
        }
    }

    /**
     * Starts every link whose spec is kept under {@link #dataDir}, in id order. A spec that cannot be read or is out
     * of range, or whose link cannot start, is logged and skipped; the others start.
     */
    private void restore() {
        if (!Files.isDirectory(dataDir)) {
            return;
        }
        final List<Path> folders;
        try (Stream<Path> entries = Files.list(dataDir)) {
            folders = entries.filter(Files::isDirectory).sorted().toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("links folder not listed [dir=" + dataDir + ']', e);
        }
        for (final Path folder : folders) {
            final Path file = folder.resolve(LinkFile.NAME);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                final LinkSpec spec = LinkFile.read(Mapped.bytes(file));
                if (!spec.id().equals(folder.getFileName().toString())) {
                    throw new IllegalArgumentException("link spec names another link [link=" + spec.id() + ']');
                }
                check(spec);
                synchronized (lock) {
                    // at construction: no TLS context yet
                    start(spec, null);
                }
                LOG.log(System.Logger.Level.INFO, "link restored [link=" + spec.id() + ", host=" + spec.host()
                        + ", port=" + spec.port() + ", file=" + file + ']');
            } catch (final IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "link not restored, it stays down until a put replaces its spec"
                        + " [file=" + file + ']', e);
            }
        }
    }

    /** Opens and starts the link {@code spec} names, over {@code context} if it asks for TLS; under {@link #lock}. */
    private void start(final LinkSpec spec, final SSLContext context) {
        final Link added;
        try {
            added = Link.open(spec.id(), localId, gateway, dataDir.resolve(spec.id()), log, options, clock, watch);
        } catch (final IOException e) {
            throw new UncheckedIOException("link log not opened [link=" + spec.id() + ", dir="
                    + dataDir.resolve(spec.id()) + ']', e);
        }
        try {
            added.tls(context);
            added.start(spec);
        } catch (final RuntimeException e) {
            try {
                added.close();
            } catch (final RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        links.put(spec.id(), added);
    }

    /** The bytes of link {@code id}'s kept spec, null when it has none; the caller holds {@link #lock}. */
    private byte[] kept(final String id) {
        final Path file = dataDir.resolve(id).resolve(LinkFile.NAME);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Mapped.bytes(file);
        } catch (final IOException e) {
            throw new UncheckedIOException("link spec not read [link=" + id + ", file=" + file + ']', e);
        }
    }

    /**
     * Writes {@code spec} durably into its link's folder, which is made, and made durable, when missing; the caller
     * holds {@link #lock}.
     */
    private void keep(final LinkSpec spec) {
        final Path folder = dataDir.resolve(spec.id());
        try {
            if (!Files.isDirectory(folder)) {
                WholeFile.createFolder(folder);
            }
            WholeFile.replace(folder.resolve(LinkFile.NAME), LinkFile.write(spec));
        } catch (final IOException e) {
            throw new UncheckedIOException("link spec not kept [link=" + spec.id() + ", dir=" + folder + ']', e);
        }
    }

    /**
     * Puts back the spec link {@code id} had, {@code before}, or deletes it when it had none, after its start failed
     * with {@code failure}; a failure here joins it.
     */
    private void putBack(final String id, final byte[] before, final RuntimeException failure) {
        final Path file = dataDir.resolve(id).resolve(LinkFile.NAME);
        try {
            if (before == null) {
                WholeFile.delete(file);
            } else {
                WholeFile.replace(file, before);
            }
        } catch (final IOException | RuntimeException e) {
            failure.addSuppressed(e);
        }
    }
}
