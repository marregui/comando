package dev.marregui.comando.app.lab;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.marregui.comando.core.loop.Errands;
import dev.marregui.comando.core.port.LabPort;
import dev.marregui.comando.frontear.gateway.Gateway;
import dev.marregui.comando.frontear.octopus.LinkState;
import dev.marregui.comando.frontear.watch.ForceRound;
import dev.marregui.comando.frontear.watch.LinkStep;
import dev.marregui.comando.frontear.watch.LogView;
import dev.marregui.comando.frontear.watch.NodeSnapshot;
import dev.marregui.comando.frontear.watch.Observation;
import dev.marregui.comando.frontear.watch.Published;

/**
 * This instance's node as the frontear tab and the {@code frontear_read} tool read it (ADR 0187 §2.3). Once
 * {@link #open}ed, one errand reads the node's watch every {@link #EVERY} (at once again while a read comes back
 * full) and its snapshot every {@link #SNAPSHOT_EVERY}, keeps the packets in a {@link LabWindow}, hands each to the
 * {@link PacketSink}, and tells the listener on the EDT, one run waiting at a time. The errand never touches Swing,
 * and the readers never wait on the node: they read what the errand copied. The sink is the caller's, closed after
 * this.
 */
public final class LabDrain implements LabPort, AutoCloseable {

    /** How often the errand reads the watch (ADR 0187 §2.3): a tab that looks live, four reads a second. */
    static final Duration EVERY = Duration.ofMillis(250);
    /** How often the errand reads the node's snapshot (ADR 0187 §2.3). */
    static final Duration SNAPSHOT_EVERY = Duration.ofSeconds(1);
    /** The events one read of the watch takes at most (ADR 0187 §2.3): a quarter of the node's ring. */
    static final int READ_MAX = 4_096;
    /** The span the rates cover, in buckets of {@link #BUCKET_MILLIS} (ADR 0187 §2.3: the last five seconds). */
    static final int BUCKETS = 20;
    static final long BUCKET_MILLIS = 250L;
    /** The force rounds of a log the percentiles cover (ADR 0187 §2.3). */
    static final int ROUNDS_KEPT = 200;
    private static final double SPAN_SECONDS = BUCKETS * BUCKET_MILLIS / 1_000.0;
    private static final double NANOS_PER_MILLI = 1_000_000.0;
    private static final System.Logger LOG = System.getLogger(LabDrain.class.getName());

    private final LabSource source;
    private final Executor edt;
    private final PacketSink sink;
    private final LabWindow window;
    private final Errands errands = new Errands("frontear-lab");
    private final AtomicBoolean opened = new AtomicBoolean();
    /** Set by the errand when it posts a run to the EDT, cleared by that run before it calls the listener. */
    private final AtomicBoolean pending = new AtomicBoolean();
    /** Written by whoever {@link #listen}s (the EDT); read by the EDT run. */
    private volatile Runnable listener = () -> {
    };
    /** Written by the errand at each snapshot; read by {@link #read} on the EDT and the tool's threads. */
    private volatile Node node = Node.NONE;
    /** The events the node's ring overwrote before the errand read them. Written by the errand only; read by all. */
    private volatile long ringMissed;
    // the errand's only, from here on
    private final Map<String, Rate> sentRates = new HashMap<>();
    private final Map<String, Marks> marks = new HashMap<>();
    private final Map<String, Rounds> rounds = new HashMap<>();
    private long last;
    private long snapshotAtNanos;
    private boolean snapshotOnce;
    private boolean failureLogged;

    /** A drain of {@code source}, posting to {@code edt}, handing packets to {@code sink}; idle until opened. */
    public LabDrain(final LabSource source, final Executor edt, final PacketSink sink) {
        this(source, edt, sink, new LabWindow());
    }

    LabDrain(final LabSource source, final Executor edt, final PacketSink sink, final LabWindow window) {
        this.source = source;
        this.edt = edt;
        this.sink = sink;
        this.window = window;
    }

    @Override
    public void open() {
        if (opened.compareAndSet(false, true)) {
            errands.start("frontear-lab-drain", this::run);
        }
    }

    @Override
    public void listen(final Runnable changed) {
        listener = changed;
    }

    @Override
    public Frame read(final long after, final int max) {
        final Node n = node;
        final Counts counts = new Counts(window.dropped(), ringMissed, sink.written(), sink.droppedFull(),
                sink.droppedDown(), sink.sampled(), sink.tableFault());
        final LabWindow.Read r = window.read(after, max);
        return new Frame(new Node(n.name(), n.port(), n.atMillis(), n.up(), n.totals(), n.links(), n.logs(), counts),
                r.packets(), r.missed(), r.last());
    }

    /** Stops the errand and waits for it, a bounded time; the sink stays open. Idempotent. */
    @Override
    public void close() {
        errands.close();
    }

    /** The errand: a step, then a wait unless the watch has more, until the close interrupts it. */
    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            boolean full = false;
            try {
                full = step();
                failureLogged = false;
            } catch (final RuntimeException e) {
                if (!failureLogged) {
                    failureLogged = true;
                    LOG.log(System.Logger.Level.WARNING, "cannot read the frontear node, trying again [every="
                            + EVERY + ']', e);
                }
            }
            if (!full) {
                try {
                    Thread.sleep(EVERY);
                } catch (final InterruptedException _) {
                    return; // the close
                }
            }
        }
    }

    /** One read of the snapshot when it is due and of the watch; whether the watch's read came back full. */
    private boolean step() {
        boolean changed = false;
        final long nowNanos = System.nanoTime();
        if (!snapshotOnce || nowNanos - snapshotAtNanos >= SNAPSHOT_EVERY.toNanos()) {
            snapshotOnce = true;
            snapshotAtNanos = nowNanos;
            node = node(source.snapshot());
            changed = true;
        }
        final LabSource.Chunk chunk = source.since(last, READ_MAX);
        if (chunk.restarted()) {
            sentRates.clear();
            marks.clear();
            rounds.clear();
        }
        last = chunk.last();
        if (chunk.missed() > 0) {
            ringMissed += chunk.missed(); // the errand is the only writer
            changed = true;
        }
        final String name = node.name();
        for (final Observation o : chunk.events()) {
            final Packet p = take(o);
            if (p != null) {
                sink.offer(name, window.add(p));
                changed = true;
            }
        }
        if (changed) {
            post();
        }
        return chunk.events().size() + chunk.missed() >= READ_MAX;
    }

    /** One run on the EDT at a time: a burst of changes waiting for the EDT is one run. */
    private void post() {
        if (pending.compareAndSet(false, true)) {
            edt.execute(() -> {
                // cleared before the listener reads, so a change after this read posts again
                pending.set(false);
                listener.run();
            });
        }
    }

    /** The packet of {@code o}, its link's figures kept; null for a force round, which only feeds its log's. */
    private Packet take(final Observation o) {
        return switch (o) {
            case final Published p -> published(p);
            case LinkStep(long _, long at, String link, LinkStep.Kind kind, long offset, String topic, int bytes,
                    String detail) -> {
                final String why = detail == null ? "" : detail;
                switch (kind) {
                    case SENT -> sentRates.computeIfAbsent(link, _ -> new Rate()).add(at, bytes);
                    case CONNECTED -> marks.computeIfAbsent(link, _ -> new Marks()).up(at, why);
                    case DISCONNECTED -> marks.computeIfAbsent(link, _ -> new Marks()).down(at, why);
                    case APPENDED, ARRIVED, DROPPED -> {
                        // only the packet row
                    }
                }
                yield new Packet(0L, at, kind(kind), "", link, topic == null ? "" : topic, -1, false, bytes, -1,
                        offset, "", "", why);
            }
            case ForceRound(long _, long at, String log, int records, long bytes, long nanos) -> {
                rounds.computeIfAbsent(log, _ -> new Rounds()).add(at, records, bytes, nanos);
                yield null;
            }
        };
    }

    /** The packet of publish {@code p}, its payload decoded here, on the errand, never on the publisher's thread. */
    private static Packet published(final Published p) {
        final byte[] correlation = p.correlation();
        return new Packet(0L, p.atMillis(), Kind.PUBLISHED, p.origin(), "", p.topic(), p.qos(), p.retain(), p.bytes(),
                p.hops(), -1L, correlation == null ? "" : HexFormat.of().formatHex(correlation), p.head(), "",
                p.text());
    }

    private static Kind kind(final LinkStep.Kind kind) {
        return switch (kind) {
            case APPENDED -> Kind.APPENDED;
            case SENT -> Kind.SENT;
            case ARRIVED -> Kind.ARRIVED;
            case DROPPED -> Kind.DROPPED;
            case CONNECTED -> Kind.CONNECTED;
            case DISCONNECTED -> Kind.DISCONNECTED;
        };
    }

    /** The node of {@code s}, its links' rates and its logs' rounds as of {@code s}'s time; NONE when it is null. */
    private Node node(final NodeSnapshot s) {
        if (s == null) {
            return Node.NONE;
        }
        final long nowMillis = s.atMillis();
        final List<Link> links = new ArrayList<>(s.links().size());
        for (final LinkState l : s.links()) {
            final Rate rate = sentRates.get(l.id());
            final Marks m = marks.getOrDefault(l.id(), Marks.NONE);
            links.add(new Link(l.id(), l.connected(), l.queued(), l.cursor(), l.tail(), l.sent(), l.dropped(),
                    l.reordered(), l.queuedDeliveries(), l.queueMax(),
                    rate == null ? 0.0 : rate.perSecond(nowMillis, false),
                    rate == null ? 0.0 : rate.perSecond(nowMillis, true), m.upMillis, m.downMillis, m.detail,
                    l.refused(), l.coalesced(), l.backlogBytes(), l.maxBacklogBytes(), l.backlogGrowing()));
        }
        final List<Log> logs = new ArrayList<>(s.logs().size());
        for (final LogView v : s.logs()) {
            final Rounds r = rounds.get(v.link());
            logs.add(r == null
                    ? new Log(v.link(), v.cursor(), v.tail(), v.bytes(), v.segments(), 0.0, 0, 0L, 0.0, 0.0, 0.0)
                    : new Log(v.link(), v.cursor(), v.tail(), v.bytes(), v.segments(),
                            r.rate.perSecond(nowMillis, false), r.lastRecords, r.lastBytes,
                            r.lastNanos / NANOS_PER_MILLI, r.percentileMillis(0.50), r.percentileMillis(0.99)));
        }
        return new Node(s.node(), s.port(), nowMillis, true, totals(s.gateway()), List.copyOf(links),
                List.copyOf(logs), Counts.NONE);
    }

    private static Totals totals(final Gateway.Stats b) {
        return new Totals(b.sessions(), b.connections(), b.retained(), b.retainedBytes(), b.droppedFull(),
                b.droppedExpired(), b.droppedOversize(),
                b.refusedRetained() + b.refusedTopics() + b.refusedSubscriptions(), b.backlogDisconnects());
    }

    /** Events and their bytes per {@link #BUCKET_MILLIS} bucket of the event's time, the last {@link #BUCKETS}. */
    static final class Rate {

        private final long[] stamps = new long[BUCKETS];
        private final long[] counts = new long[BUCKETS];
        private final long[] bytes = new long[BUCKETS];

        Rate() {
            Arrays.fill(stamps, Long.MIN_VALUE);
        }

        void add(final long atMillis, final long size) {
            final long k = Math.floorDiv(atMillis, BUCKET_MILLIS);
            final int i = Math.floorMod(k, BUCKETS);
            if (stamps[i] != k) {
                stamps[i] = k;
                counts[i] = 0L;
                bytes[i] = 0L;
            }
            counts[i]++;
            bytes[i] += size;
        }

        /** Events, or their bytes, per second over the {@link #BUCKETS} buckets up to {@code nowMillis}'s. */
        double perSecond(final long nowMillis, final boolean ofBytes) {
            final long k = Math.floorDiv(nowMillis, BUCKET_MILLIS);
            long sum = 0L;
            for (int i = 0; i < BUCKETS; i++) {
                if (stamps[i] <= k && stamps[i] > k - BUCKETS) {
                    sum += ofBytes ? bytes[i] : counts[i];
                }
            }
            return sum / SPAN_SECONDS;
        }
    }

    /** A link's last connect and disconnect, and the detail the last of them gave. */
    private static final class Marks {

        private static final Marks NONE = new Marks();

        private long upMillis;
        private long downMillis;
        private String detail = "";

        void up(final long atMillis, final String why) {
            upMillis = atMillis;
            detail = why;
        }

        void down(final long atMillis, final String why) {
            downMillis = atMillis;
            detail = why;
        }
    }

    /** A log's force rounds: their rate, the last one, and the times of the last {@link #ROUNDS_KEPT}. */
    static final class Rounds {

        private final Rate rate = new Rate();
        private final long[] nanos = new long[ROUNDS_KEPT];
        private int kept;
        private int next;
        private int lastRecords;
        private long lastBytes;
        private long lastNanos;

        void add(final long atMillis, final int records, final long bytes, final long tookNanos) {
            rate.add(atMillis, bytes);
            nanos[next] = tookNanos;
            next = (next + 1) % ROUNDS_KEPT;
            kept = Math.min(kept + 1, ROUNDS_KEPT);
            lastRecords = records;
            lastBytes = bytes;
            lastNanos = tookNanos;
        }

        /** The nearest-rank {@code p} percentile of the kept rounds' times, in milliseconds; 0 with none. */
        double percentileMillis(final double p) {
            if (kept == 0) {
                return 0.0;
            }
            final long[] sorted = Arrays.copyOf(nanos, kept);
            Arrays.sort(sorted);
            final int rank = (int) Math.ceil(p * kept);
            return sorted[Math.clamp(rank - 1, 0, kept - 1)] / NANOS_PER_MILLI;
        }
    }
}
