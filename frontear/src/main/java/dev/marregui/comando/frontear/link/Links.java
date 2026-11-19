package dev.marregui.comando.frontear.link;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import dev.marregui.comando.frontear.broker.Broker;
import dev.marregui.comando.frontear.mqtt.Topics;
import dev.marregui.comando.frontear.queue.LogOptions;

/**
 * A broker's links to its peers, each with its log in a folder of {@code dataDir} named by its id. Any thread may
 * call any method; changes are serialised. Removing a link keeps its log, so putting it back resumes where it was.
 */
public final class Links implements Mesh, AutoCloseable {

    /**
     * A link id names a folder: letters, digits, dot, dash and underscore, and never a dot alone or two; 64 of them
     * fit every file system's name limit with room to spare.
     */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int MAX_PORT = 65_535;

    private final Broker broker;
    private final String localId;
    private final Path dataDir;
    private final LogOptions log;
    private final LinkOptions options;
    private final InstantSource clock;
    private final Object lock = new Object();
    /** Written under {@link #lock}; read from any thread by {@link #state}. */
    private final Map<String, Link> links = new ConcurrentHashMap<>();
    /** Guarded by {@link #lock}. */
    private boolean closed;

    /**
     * Links of {@code broker}, connecting to their peers as {@code localId}, with their logs under {@code dataDir},
     * bounded by {@link LinkOptions#defaults}.
     */
    public Links(final Broker broker, final String localId, final Path dataDir, final LogOptions log) {
        this(broker, localId, dataDir, log, LinkOptions.defaults());
    }

    /** As above, each link bounded by {@code options}. */
    public Links(final Broker broker, final String localId, final Path dataDir, final LogOptions log,
            final LinkOptions options) {
        this(broker, localId, dataDir, log, options, InstantSource.system());
    }

    /** As above, with {@code clock} to time how long a message waited in a log. */
    Links(final Broker broker, final String localId, final Path dataDir, final LogOptions log,
            final LinkOptions options, final InstantSource clock) {
        this.broker = Objects.requireNonNull(broker, "broker");
        this.localId = Objects.requireNonNull(localId, "localId");
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
        this.log = Objects.requireNonNull(log, "log");
        this.options = Objects.requireNonNull(options, "options");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** {@inheritDoc} Fails with {@link UncheckedIOException} when a new link's log cannot be opened. */
    @Override
    public void put(final LinkSpec spec) {
        check(spec);
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("links closed [link=" + spec.id() + ']');
            }
            final Link link = links.get(spec.id());
            if (link != null) {
                link.update(spec);
                return;
            }
            final Link added;
            try {
                added = Link.open(spec.id(), localId, broker, dataDir.resolve(spec.id()), log, options, clock);
            } catch (final IOException e) {
                throw new UncheckedIOException("link log not opened [link=" + spec.id() + ", dir="
                        + dataDir.resolve(spec.id()) + ']', e);
            }
            try {
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
    }

    @Override
    public void remove(final String id) {
        // closed under the lock: a put of the same id must not open its log while this link still holds it
        synchronized (lock) {
            final Link link = links.remove(id);
            if (link != null) {
                link.close();
            }
        }
    }

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
            switch (direction) {
                case OUT -> link.out(copy);
                case IN -> link.in(copy);
            }
        }
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

    /** Stops every link, each even when one before it failed; the first failure is thrown with the others on it. */
    @Override
    public void close() {
        RuntimeException failure = null;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
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
        if (!ID.matcher(id).matches() || ".".equals(id) || "..".equals(id)) {
            throw new IllegalArgumentException("link id not a folder name [link=" + id + ']');
        }
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

    private static void checkFilters(final String id, final Set<String> filters) {
        for (final String f : filters) {
            if (!Topics.validFilter(f)) {
                throw new IllegalArgumentException("link filter not valid [link=" + id + ", filter=" + f + ']');
            }
        }
    }
}
