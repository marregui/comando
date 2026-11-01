package dev.marregui.comando.core.loop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * The depth of comando's unbounded queues, measured before any is bounded: the reducer's intents, every strand's tasks
 * (a session's strand among them, and a timed strand's waiting and delayed tasks) and each {@code claude} process's
 * stdin. A queue counts what it holds and the most it held since the last {@link #read}; the application writes the
 * readings to the metrics log when they change, so a bound can later be set from how each queue is used. Counting is
 * two atomic operations a task; a timed strand's queue, which the JDK keeps, is sampled instead: its size when a task
 * joins it and when it is read. Nothing is logged on the path that queues.
 *
 * <p>Each queue has its own gauge, held by identity, not by name: a queue made again under a name while the one before
 * still ends (a session's strand after a restart, a connection's lane, the next process's stdin) is read from its own
 * gauge, and the old one's end drops only the old one's.
 */
public final class Queues {

    /** Every live queue's gauge; a queue that ends drops its own. */
    private static final Set<Gauge> GAUGES = ConcurrentHashMap.newKeySet();
    /** The order gauges are made in, so the newest of a name is the one read. */
    private static final AtomicLong MADE = new AtomicLong();

    private Queues() {
    }

    /** One queue's count: what it holds now, and the most since the last read. */
    public static final class Gauge {

        private final String name;
        private final long made;
        private final AtomicInteger depth = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();
        /** The queue's own size, for a queue that is sampled rather than counted; null for a counted one. */
        private final IntSupplier size;

        private Gauge(final String name, final long made, final IntSupplier size) {
            this.name = name;
            this.made = made;
            this.size = size;
        }

        /** An element joined the queue. */
        public void added() {
            final int d = depth.incrementAndGet();
            peak.accumulateAndGet(d, Math::max);
        }

        /** An element left the queue. */
        public void removed() {
            depth.decrementAndGet();
        }

        /** A sampled queue's size is taken into its peak: call it after an element joined. */
        public void sample() {
            peak.accumulateAndGet(depth(), Math::max);
        }

        /** What the queue holds now. */
        public int depth() {
            return size == null ? depth.get() : size.getAsInt();
        }

        /** The most it held since the last read: the read starts the next interval at the depth it found. */
        int takePeak() {
            final int d = depth();
            return Math.max(d, peak.getAndSet(d));
        }
    }

    /**
     * A queue's reading.
     *
     * @param depth what it held when read
     * @param peak  the most it held since the reading before
     */
    public record Reading(String name, int depth, int peak) {
    }

    /** A new gauge for a queue made under {@code name}; read under that name while it is the newest of the name. */
    public static Gauge gauge(final String name) {
        return add(new Gauge(name, MADE.incrementAndGet(), null));
    }

    /**
     * A new gauge for a queue made under {@code name} that counts itself, {@code size} answering what it holds: its
     * peak is the most {@link Gauge#sample} and the reads found. For a queue comando cannot count as tasks join and
     * leave it (a timed strand's, which the JDK keeps and empties itself on cancel and shutdown).
     */
    public static Gauge sampled(final String name, final IntSupplier size) {
        return add(new Gauge(name, MADE.incrementAndGet(), Objects.requireNonNull(size, "size")));
    }

    private static Gauge add(final Gauge g) {
        GAUGES.add(g);
        return g;
    }

    /** Queue {@code name} has ended: its own gauge goes; a queue made since under the same name keeps its own. */
    public static void drop(final String name, final Gauge gauge) {
        GAUGES.remove(gauge);
    }

    /**
     * Every queue's reading, by name; each peak starts again from the depth read. Of the queues of one name, the newest
     * is read: the one before it is ending and takes no more work.
     */
    public static List<Reading> read() {
        final Map<String, Gauge> newest = new HashMap<>();
        for (final Gauge g : GAUGES) {
            newest.merge(g.name, g, (a, b) -> a.made > b.made ? a : b);
        }
        final List<Reading> out = new ArrayList<>(newest.size());
        newest.forEach((name, g) -> out.add(new Reading(name, g.depth(), g.takePeak())));
        out.sort(Comparator.comparing(Reading::name));
        return List.copyOf(out);
    }
}
