package dev.marregui.comando.app.lab;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.core.loop.Errands;
import dev.marregui.comando.core.port.LabPort.Kind;
import dev.marregui.comando.core.port.LabPort.Packet;

/**
 * The lab's packets on their way to table {@code frontear_packet} of the machine's cdb (record 187), as lines of its
 * line protocol, payloads left out. {@link #offer} never blocks: a line joins a queue of {@link #CAPACITY}, or, the
 * queue full or the sink closed, is dropped and counted. One errand writes the queue in batches of at most
 * {@link #BATCH_LINES} lines. Delivery is at most once: a batch the writer fails is dropped and counted, and the lines
 * that come while the writer is backed off (from {@link #BACKOFF_FIRST}, doubling to {@link #BACKOFF_MOST}) are
 * dropped and counted too, so a cdb that is down costs the drain nothing.
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
    private static final System.Logger LOG = System.getLogger(PacketSink.class.getName());

    /** Where a batch goes: one write, which may block for its bounded time. */
    public interface Writer extends AutoCloseable {

        /** Writes {@code lines}; an {@link IOException} when they did not all land. */
        void write(List<String> lines) throws IOException, InterruptedException;

        /** Ends what the writer holds; nothing by default. */
        @Override
        default void close() {
        }
    }

    /** Null for a sink that is off: it keeps and counts nothing. */
    private final Writer writer;
    private final ArrayBlockingQueue<String> queue;
    private final Errands errands = new Errands("frontear-lab-sink");
    /** Written by {@link #close} before it interrupts the errand; read by the errand and by {@link #offer}. */
    private final AtomicBoolean halt = new AtomicBoolean();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong droppedFull = new AtomicLong();
    private final AtomicLong droppedDown = new AtomicLong();
    // the errand's only, from here on
    private boolean down;
    private long backoffNanos;
    private long retryAtNanos;

    /** Starts the errand that writes the lines to {@code writer}, which it closes when it closes. */
    public PacketSink(final Writer writer) {
        this(writer, CAPACITY);
    }

    PacketSink(final Writer writer, final int capacity) {
        this.writer = writer;
        this.queue = new ArrayBlockingQueue<>(capacity);
        if (writer != null) {
            errands.start("frontear-lab-sink", this::drain);
        }
    }

    /** A sink that writes nothing and counts nothing: the lab without cdb (tests, {@code -Dcomando.peers=false}). */
    public static PacketSink off() {
        return new PacketSink(null, 1);
    }

    /** Queues {@code p} of node {@code node} as a line, or drops and counts it. Never blocks, never throws. */
    void offer(final String node, final Packet p) {
        if (writer == null) {
            return;
        }
        if (halt.get() || !queue.offer(line(node, p))) {
            droppedFull.incrementAndGet();
        }
    }

    /** The lines cdb took. */
    long written() {
        return written.get();
    }

    /** The lines dropped at a full queue or after the close. */
    long droppedFull() {
        return droppedFull.get();
    }

    /** The lines dropped because a write failed, or came while the writer was backed off. */
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
                    + ", droppedFull=" + droppedFull.get() + ", droppedDown=" + droppedDown.get() + ", unsent="
                    + queue.size() + ']');
        }
    }

    /**
     * The line of {@code p}: tags {@code node}, {@code kind}, {@code origin} and {@code link} (each left out when
     * empty), then its fields; {@code offset} left out when the packet has none, {@code correlation} when it is
     * empty. {@code code} says why a DROPPED packet was dropped, where the link says it in a known form: 0 otherwise.
     */
    static String line(final String node, final Packet p) {
        final StringBuilder out = new StringBuilder(160).append("frontear_packet");
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
        out.append(",code=").append(code(p)).append("i ").append(Math.multiplyExact(p.atMillis(), 1_000_000L));
        return out.toString();
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

    /** The errand: a batch of what is queued, written, until the close. */
    private void drain() {
        final List<String> batch = new ArrayList<>(BATCH_LINES);
        while (!halt.get() && !Thread.currentThread().isInterrupted()) {
            batch.clear();
            try {
                final String first = queue.poll(BATCH_WAIT.toNanos(), TimeUnit.NANOSECONDS);
                if (first == null) {
                    continue; // nothing yet: the halt flag is read again
                }
                batch.add(first);
            } catch (final InterruptedException _) {
                return; // the close
            }
            queue.drainTo(batch, BATCH_LINES - 1);
            deliver(batch);
        }
    }

    private void deliver(final List<String> batch) {
        final long nowNanos = System.nanoTime();
        if (down && nowNanos - retryAtNanos < 0) {
            droppedDown.addAndGet(batch.size());
            return;
        }
        try {
            writer.write(batch);
            written.addAndGet(batch.size());
            if (down) {
                down = false;
                LOG.log(System.Logger.Level.INFO, "frontear packets reach cdb again [droppedDown=" + droppedDown.get()
                        + ']');
            }
        } catch (final IOException | RuntimeException e) {
            droppedDown.addAndGet(batch.size());
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
}
