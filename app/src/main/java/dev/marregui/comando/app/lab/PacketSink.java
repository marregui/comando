package dev.marregui.comando.app.lab;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.loop.Errands;
import dev.marregui.comando.core.port.LabPort.Kind;
import dev.marregui.comando.core.port.LabPort.Packet;
import dev.marregui.comando.frontear.watch.Watch;

/**
 * The lab's packets on their way to table {@code frontear_packet} of the machine's cdb (record 187), as lines of its
 * line protocol, each publish with its payload as the watch keeps it: whole up to {@link Watch#PAYLOAD_BYTES}, else
 * its first that many bytes. {@link #offer} never blocks: a packet joins a queue of {@link #CAPACITY}, or, the queue
 * full or the sink closed, is dropped and counted. One errand turns the queue into lines and writes them in batches of
 * at most {@link #BATCH_LINES}, so the payload's JSON check costs the drain nothing. Delivery is at most once: the
 * lines of a batch the writer fails that had not landed are dropped and counted, and the lines that come while the
 * writer is backed off (from {@link #BACKOFF_FIRST}, doubling to {@link #BACKOFF_MOST}) are dropped and counted too,
 * so a cdb that is down costs the drain nothing. A node whose packets come faster than {@link #SAMPLE_ABOVE} a second
 * is sampled before its packets are queued: 1 in N kept, N its last second's rate over the limit rounded up, the others
 * counted as {@link #sampled}, not as dropped.
 */
public final class PacketSink implements AutoCloseable {

    /** Lines a write carries at most: the metrics sink's batch (record 146). */
    static final int BATCH_LINES = 1_000;
    /** How long the errand waits for a line before it reads the halt flag again: the metrics sink's batch wait. */
    static final Duration BATCH_WAIT = Duration.ofMillis(200);
    /** Lines held while a batch is written (ADR 0187 §2.3): about two seconds of a busy node's packets. */
    static final int CAPACITY = 8_192;
    /** The first wait after a write failed, doubling at each failure to {@link #BACKOFF_MOST}: not measured. */
    static final Duration BACKOFF_FIRST = Duration.ofSeconds(1);
    /** The longest wait between writes while cdb fails: the metrics sink's once a minute (record 146). */
    static final Duration BACKOFF_MOST = Duration.ofMinutes(1);
    /**
     * Packets a second of one node the sink takes whole; above it, 1 in N. Set after the load test of 2026-11-22, where
     * the lab's lines at 40,000 packets a second ran cdb out of memory: a bound on what one node costs cdb, not cdb's
     * measured limit.
     */
    static final int SAMPLE_ABOVE = 2_000;
    /** The span a node's rate is counted over: the unit of {@link #SAMPLE_ABOVE}. */
    static final Duration SAMPLE_WINDOW = Duration.ofSeconds(1);
    private static final System.Logger LOG = System.getLogger(PacketSink.class.getName());

    /** Where a batch goes: one write, which may block for its bounded time. */
    public interface Writer extends AutoCloseable {

        /**
         * Writes {@code lines} and returns how many landed; the others it dropped, each too long for one request. A
         * {@link PartialWrite} when a request failed after {@link PartialWrite#landed} of them had landed, another
         * {@link IOException} when none had.
         */
        int write(List<String> lines) throws IOException, InterruptedException;

        /** Ends what the writer holds; nothing by default. */
        @Override
        default void close() {
        }
    }

    /**
     * A write that cdb answered with a refusal, or that failed after some of its lines had landed: they did, the others
     * did not.
     */
    public static final class PartialWrite extends IOException {

        private final int landed;
        private final String tableFault;

        /** {@code cause} is null when the failure was an answer, not an exception. */
        public PartialWrite(final String message, final int landed, final Throwable cause) {
            this(message, landed, cause, "");
        }

        /** As above, for an answer that says the table takes no writes, with {@code tableFault} its reason. */
        public PartialWrite(final String message, final int landed, final Throwable cause, final String tableFault) {
            super(message, cause);
            this.landed = landed;
            this.tableFault = tableFault;
        }

        /** The lines that landed before the failure. */
        public int landed() {
            return landed;
        }

        /** Why cdb said the table takes no writes, such as a suspended table; empty when it did not say so. */
        public String tableFault() {
            return tableFault;
        }
    }

    /** A packet of {@code node}, queued until the errand makes it a line. */
    private record Entry(String node, Packet packet) {
    }

    /**
     * One node's rate, counted over windows of {@link #SAMPLE_WINDOW}: the stride a window keeps is the last window's
     * count over the limit, rounded up, raised as soon as this window's count passes the limit times the stride.
     */
    private static final class Sampler {

        private long windowStartNanos;
        private long count;
        private long every = 1L;

        Sampler(final long nowNanos) {
            this.windowStartNanos = nowNanos;
        }

        /** Whether the packet counted at {@code nowNanos} is kept. */
        boolean keep(final long nowNanos, final int limit) {
            final long windowNanos = SAMPLE_WINDOW.toNanos();
            final long elapsedNanos = nowNanos - windowStartNanos;
            if (elapsedNanos >= windowNanos) {
                // a window that ended more than one window ago says nothing of the rate now
                every = elapsedNanos < 2L * windowNanos ? Math.max(1L, Math.ceilDiv(count, limit)) : 1L;
                windowStartNanos = nowNanos;
                count = 0L;
            }
            count++;
            if (count > every * limit) {
                every = Math.ceilDiv(count, limit);
            }
            return count % every == 0L;
        }
    }

    /** Null for a sink that is off: it keeps and counts nothing. */
    private final Writer writer;
    private final ArrayBlockingQueue<Entry> queue;
    private final Errands errands = new Errands("frontear-lab-sink");
    /** Written by {@link #close} before it interrupts the errand; read by the errand and by {@link #offer}. */
    private final AtomicBoolean halt = new AtomicBoolean();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong droppedFull = new AtomicLong();
    private final AtomicLong droppedDown = new AtomicLong();
    private final AtomicLong sampled = new AtomicLong();
    private final int sampleAbove;
    private final LongSupplier nanoClock;
    /**
     * Why cdb last said the table takes no writes, empty since a write landed or when it never did. Written by the
     * errand, read by the thread that reads the counters.
     */
    private volatile String tableFault = "";
    /** The caller of {@link #offer}'s only: one sampler per node it offered packets of. */
    private final Map<String, Sampler> samplers = new HashMap<>();
    // the errand's only, from here on
    private boolean down;
    private long backoffNanos;
    private long retryAtNanos;

    /** Starts the errand that writes the lines to {@code writer}, which it closes when it closes. */
    public PacketSink(final Writer writer) {
        this(writer, CAPACITY);
    }

    PacketSink(final Writer writer, final int capacity) {
        this(writer, capacity, SAMPLE_ABOVE, System::nanoTime);
    }

    /** As above, sampling a node above {@code sampleAbove} packets a second, the time read from {@code nanoClock}. */
    PacketSink(final Writer writer, final int capacity, final int sampleAbove, final LongSupplier nanoClock) {
        this.writer = writer;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.sampleAbove = sampleAbove;
        this.nanoClock = nanoClock;
        if (writer != null) {
            errands.start("frontear-lab-sink", this::drain);
        }
    }

    /** A sink that writes nothing and counts nothing: the lab without cdb (tests, {@code -Dcomando.peers=false}). */
    public static PacketSink off() {
        return new PacketSink(null, 1);
    }

    /**
     * Queues {@code p} of node {@code node}, or skips it as sampled, or drops and counts it. Never blocks, never
     * throws. Called by one thread only, the lab's drain.
     */
    void offer(final String node, final Packet p) {
        if (writer == null) {
            return;
        }
        final long nowNanos = nanoClock.getAsLong();
        if (!samplers.computeIfAbsent(node, _ -> new Sampler(nowNanos)).keep(nowNanos, sampleAbove)) {
            sampled.incrementAndGet();
        } else if (halt.get() || !queue.offer(new Entry(node, p))) {
            droppedFull.incrementAndGet();
        }
    }

    /** The lines cdb took. */
    long written() {
        return written.get();
    }

    /** The packets left out by sampling: a node's rate was above {@link #SAMPLE_ABOVE}. */
    long sampled() {
        return sampled.get();
    }

    /** Why cdb last said the table takes no writes, such as a suspended table; empty when it takes them. */
    String tableFault() {
        return tableFault;
    }

    /** The lines dropped at a full queue or after the close. */
    long droppedFull() {
        return droppedFull.get();
    }

    /**
     * The lines dropped because a write failed before they landed, because one was too long for a request, or because
     * they came while the writer was backed off.
     */
    long droppedDown() {
        return droppedDown.get();
    }

    /** The lines queued now. */
    @TestOnly
    int queued() {
        return queue.size();
    }

    /** Stops taking lines, interrupts the errand, waits for it a bounded time, then closes the writer. Idempotent. */
    @Override
    public void close() {
        if (!halt.compareAndSet(false, true)) {
            return;
        }
        errands.close();
        if (writer != null) {
            writer.close();
            LOG.log(System.Logger.Level.INFO, "frontear packet sink stopped [written=" + written.get()
                    + ", droppedFull=" + droppedFull.get() + ", droppedDown=" + droppedDown.get() + ", sampled="
                    + sampled.get() + ", unsent=" + queue.size() + ']');
        }
    }

    /**
     * The line of {@code p}: tags {@code node}, {@code kind}, {@code origin} and {@code link} (each left out when
     * empty), then its fields; {@code offset} left out when the packet has none, {@code correlation} and the payload
     * when they are empty. The payload goes to {@code payload} when {@link #json} says so, else to
     * {@code payload_text}. {@code code} says why a DROPPED packet was dropped, where the link says it in a known
     * form: 0 otherwise.
     */
    static String line(final String node, final Packet p) {
        final StringBuilder out = new StringBuilder(160 + p.payload().length()).append("frontear_packet");
        tag(out, "node", node);
        tag(out, "kind", p.kind().name().toLowerCase(Locale.ROOT));
        tag(out, "origin", p.origin());
        tag(out, "link", p.link());
        out.append(" topic=");
        string(out, p.topic());
        out.append(",qos=").append(p.qos()).append("i,retain=").append(p.retain() ? 't' : 'f').append(",bytes=")
                .append(p.bytes()).append("i,hops=").append(p.hops()).append('i');
        if (p.offset() >= 0) {
            out.append(",offset=").append(p.offset()).append('i');
        }
        if (!p.correlation().isEmpty()) {
            out.append(",correlation=");
            string(out, p.correlation());
        }
        if (!p.payload().isEmpty()) {
            out.append(json(p) ? ",payload=" : ",payload_text=");
            string(out, p.payload());
        }
        out.append(",code=").append(code(p)).append("i ").append(Math.multiplyExact(p.atMillis(), 1_000_000L));
        return out.toString();
    }

    /**
     * Whether the payload of {@code p} is for the JSON column: kept whole, so no larger than
     * {@link Watch#PAYLOAD_BYTES}, its first character after whitespace one that opens an object or an array, and one
     * JSON document to comando's parser. A bare number, string or literal goes to text: most payloads are not JSON, and
     * the first character settles them without a parse. cdb's JSON type takes one RFC 8259 text and refuses the line
     * otherwise; the parser reads the same grammar and limits nesting and number length, which cdb does not, so where
     * the two differ it is the parser that refuses, and the payload goes to text. A refusal makes no exception.
     */
    static boolean json(final Packet p) {
        return p.bytes() <= Watch.PAYLOAD_BYTES && opensContainer(p.payload()) && Json.tryReadTree(p.payload()) != null;
    }

    /**
     * The reason of a DROPPED packet as a number, from the details the link gives (its texts, so a new one reads 0):
     * 1 the link's queue is full, 2 past the hop limit, 3 the link's log is full, 4 the peer refused it, 5 expired in
     * the link's log, 6 larger than the peer accepts, 7 the record is unreadable.
     */
    static int code(final Packet p) {
        if (p.kind() != Kind.DROPPED) {
            return 0;
        }
        final String d = p.detail();
        if (d.startsWith("the link's queue is full")) {
            return 1;
        } else if (d.startsWith("past the hop limit")) {
            return 2;
        } else if (d.startsWith("the link's log is full")) {
            return 3;
        } else if (d.startsWith("the peer refused it")) {
            return 4;
        } else if (d.startsWith("expired in the link's log")) {
            return 5;
        } else if (d.startsWith("larger than the peer accepts")) {
            return 6;
        } else if (d.startsWith("the record is unreadable")) {
            return 7;
        }
        return 0;
    }

    /** The errand: a batch of what is queued, made lines and written, until the close. */
    private void drain() {
        final List<Entry> taken = new ArrayList<>(BATCH_LINES);
        final List<String> batch = new ArrayList<>(BATCH_LINES);
        while (!halt.get() && !Thread.currentThread().isInterrupted()) {
            taken.clear();
            batch.clear();
            try {
                final Entry first = queue.poll(BATCH_WAIT.toNanos(), TimeUnit.NANOSECONDS);
                if (first == null) {
                    continue; // nothing yet: the halt flag is read again
                }
                taken.add(first);
            } catch (final InterruptedException _) {
                return; // the close
            }
            queue.drainTo(taken, BATCH_LINES - 1);
            for (final Entry e : taken) {
                batch.add(line(e.node(), e.packet()));
            }
            deliver(batch);
        }
    }

    private void deliver(final List<String> batch) {
        final long nowNanos = nanoClock.getAsLong();
        if (down && nowNanos - retryAtNanos < 0) {
            droppedDown.addAndGet(batch.size());
            return;
        }
        try {
            final int landed = writer.write(batch);
            tableFault = ""; // before the counts: a reader that sees them sees the fault they go with
            count(batch.size(), landed);
            if (down) {
                down = false;
                LOG.log(System.Logger.Level.INFO, "frontear packets reach cdb again [droppedDown=" + droppedDown.get()
                        + ']');
            }
        } catch (final IOException | RuntimeException e) {
            if (e instanceof final PartialWrite partial) {
                if (partial.getCause() == null) {
                    tableFault = partial.tableFault(); // cdb answered, so it said whether the table is the cause
                }
                count(batch.size(), partial.landed());
            } else {
                count(batch.size(), 0);
            }
            backoffNanos = down ? Math.min(backoffNanos * 2, BACKOFF_MOST.toNanos()) : BACKOFF_FIRST.toNanos();
            retryAtNanos = nowNanos + backoffNanos;
            if (!down) {
                LOG.log(System.Logger.Level.WARNING, "cannot write frontear packets to cdb, dropping them until it"
                        + " answers [lines=" + batch.size() + ", retryInMillis="
                        + TimeUnit.NANOSECONDS.toMillis(backoffNanos) + ']', e);
            }
            down = true;
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt(); // the close; the loop ends on it
            droppedDown.addAndGet(batch.size());
        }
    }

    /** Counts {@code landed} of a batch of {@code lines} written, the others dropped. */
    private void count(final int lines, final int landed) {
        assert landed >= 0 && landed <= lines : "landed out of range [landed=" + landed + ", lines=" + lines + ']';
        written.addAndGet(landed);
        droppedDown.addAndGet(lines - landed);
    }

    /** {@code ,name=value} with the line protocol's tag escapes; nothing when {@code value} is empty. */
    private static void tag(final StringBuilder out, final String name, final String value) {
        if (value.isEmpty()) {
            return;
        }
        out.append(',').append(name).append('=');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '\\', ',', ' ', '=' -> out.append('\\').append(c);
                case '\n', '\r' -> out.append("\\ ");
                default -> out.append(c);
            }
        }
    }

    /** A string field: quoted, a backslash before {@code \ "} and before a line feed or carriage return. */
    private static void string(final StringBuilder out, final String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '\\' || c == '"' || c == '\n' || c == '\r') {
                out.append('\\');
            }
            out.append(c);
        }
        out.append('"');
    }

    /** Whether the first character of {@code text} that is not JSON whitespace opens an object or an array. */
    private static boolean opensContainer(final String text) {
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return c == '{' || c == '[';
            }
        }
        return false;
    }
}
