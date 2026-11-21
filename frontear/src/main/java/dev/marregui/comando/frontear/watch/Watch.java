package dev.marregui.comando.frontear.watch;

import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

import dev.marregui.comando.frontear.octopus.Links;
import dev.marregui.comando.frontear.protocol.Packet;

/**
 * What a node does, kept for whoever watches it: a ring of a power-of-two number of slots, written by sequence from
 * the broker's, the links' and the logs' threads, and read by pollers through {@link #since}. Off until
 * {@link #on(boolean)} turns it on; while off, a hook costs one volatile read and builds nothing.
 *
 * <p>While on, a hook claims the next sequence, builds its event and stores it in the slot the sequence masks to,
 * with a release store. It never locks and never waits: a poller that falls a ring behind loses the oldest events,
 * counted in {@link Batch#missed}, and the publisher does not slow down for it. A writer that finds its slot already
 * holding a later event (one claimed a whole ring after its own, stored first) leaves it there; its own event is lost
 * and counted missed.</p>
 */
public final class Watch {

    /**
     * The correlation data a {@link Published} copies: 16 bytes hold a UUID, the widest id comando's request and
     * reply flows put there (ADR 0187 §2.2).
     */
    public static final int CORRELATION_BYTES = 16;
    /**
     * The payload bytes a {@link Published} decodes as its head: enough for a line of a JSON message in a watch view
     * (ADR 0187 §2.2). The ring holds each payload as {@link #PAYLOAD_BYTES} says: by reference up to that bound, a
     * copy of its first that many bytes past it, so while it is on it keeps at most its capacity times that alive.
     */
    public static final int HEAD_BYTES = 256;
    /**
     * The payload bytes a {@link Published} keeps: a payload this long or shorter by reference, a longer one as a copy
     * of its first this many bytes, so the ring holds at most its capacity times this (16,384 slots, 128 MiB) however
     * large the messages are; comando's presence, about 6 KB, fits whole.
     */
    public static final int PAYLOAD_BYTES = 8 * 1024;

    private final AtomicReferenceArray<Observation> slots;
    private final int mask;
    private final InstantSource clock;
    /** The next sequence a hook claims; every hook's thread takes from it, {@link #since} reads it. */
    private final AtomicLong sequence = new AtomicLong(1);
    /** Written by the watch's owner (the app's view); read by every hook's thread and by {@link #since}. */
    private volatile boolean on;

    /**
     * A watch of {@code capacity} slots, off, its events timed by the system clock.
     *
     * @throws IllegalArgumentException when {@code capacity} is not a power of two
     */
    public Watch(final int capacity) {
        this(capacity, InstantSource.system());
    }

    /**
     * As above, its events timed by {@code clock}: the node's, so a snapshot and its events read the same time.
     *
     * @throws IllegalArgumentException when {@code capacity} is not a power of two
     */
    public Watch(final int capacity, final InstantSource clock) {
        if (capacity <= 0 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("watch capacity not a power of two [capacity=" + capacity + ']');
        }
        this.slots = new AtomicReferenceArray<>(capacity);
        this.mask = capacity - 1;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Whether the hooks keep events. */
    public boolean on() {
        return on;
    }

    /** Starts or stops keeping events; those kept stay readable, and the sequence goes on where it was. */
    public void on(final boolean keep) {
        on = keep;
    }

    /**
     * The events after sequence {@code after}, oldest first, at most {@code max}. The batch stops before an event
     * whose writer has claimed its sequence and not stored it yet, so a later call returns it; every sequence in
     * {@code (after, last]} is either returned or counted in {@code missed}, overwritten before this read.
     *
     * @throws IllegalArgumentException when {@code max} is negative
     */
    public Batch since(final long after, final int max) {
        if (max < 0) {
            throw new IllegalArgumentException("negative watch batch [max=" + max + ']');
        }
        final long high = sequence.get() - 1;
        if (after >= high || max == 0) {
            return new Batch(List.of(), 0, after);
        }
        // sequences start at 1; a ring holds at most its capacity of the latest, those before were overwritten
        final long first = Math.max(after, 0) + 1;
        final long from = Math.max(first, high - mask);
        long missed = from - first;
        final List<Observation> events = new ArrayList<>((int) Math.min(max, high - from + 1));
        long s = from;
        for (; s <= high && events.size() < max; s++) {
            // acquire: pairs with the writer's release store, so the event's fields are seen whole
            final Observation e = slots.getAcquire((int) (s & mask));
            if (e == null || e.seq() < s) {
                break;
            }
            if (e.seq() == s) {
                events.add(e);
            } else {
                missed++;
            }
        }
        return new Batch(List.copyOf(events), missed, s - 1);
    }

    /**
     * Keeps a {@link Published} for {@code message} from {@code origin}, routed to {@code matches} subscriptions. From
     * the broker's threads.
     */
    public void published(final String origin, final Packet.Publish message, final int matches) {
        if (!on) {
            return;
        }
        final byte[] whole = message.payload();
        // the copy is of a payload past the bound only, at most PAYLOAD_BYTES, so the ring's memory has a bound
        final byte[] payload = whole.length <= PAYLOAD_BYTES ? whole : Arrays.copyOf(whole, PAYLOAD_BYTES);
        final byte[] c = message.correlation();
        final byte[] correlation = c == null ? null : Arrays.copyOf(c, Math.min(c.length, CORRELATION_BYTES));
        final long seq = sequence.getAndIncrement();
        // within the bound, by reference: a publish's payload is never written after it is built (Packet.Publish)
        store(new Published(seq, clock.millis(), origin, message.topic(), message.qos(), message.retain(),
                whole.length, matches, correlation, payload, Links.hops(message)));
    }

    /**
     * Keeps a {@link LinkStep}: {@code offset} -1 when the step has none, {@code topic} null for a connection change,
     * {@code detail} empty or the reason. From the links' strands and their logs' forcers.
     */
    public void step(final String link, final LinkStep.Kind kind, final long offset, final String topic,
            final int bytes, final String detail) {
        if (!on) {
            return;
        }
        final long seq = sequence.getAndIncrement();
        store(new LinkStep(seq, clock.millis(), link, kind, offset, topic, bytes, detail));
    }

    /** Keeps a {@link ForceRound} of log {@code log}. From the log's forcer. */
    public void round(final String log, final int records, final long bytes, final long nanos) {
        if (!on) {
            return;
        }
        final long seq = sequence.getAndIncrement();
        store(new ForceRound(seq, clock.millis(), log, records, bytes, nanos));
    }

    /**
     * Stores {@code event} in its slot unless a later event holds it already. A failed exchange means another writer
     * stored first; the loop ends once the slot holds this event or a later one, a few rounds at most since only the
     * writers a whole ring apart share a slot.
     */
    private void store(final Observation event) {
        final int i = (int) (event.seq() & mask);
        Observation seen = slots.getAcquire(i);
        while (seen == null || seen.seq() < event.seq()) {
            // release: a poller's acquire load of the slot sees the event's fields whole
            final Observation witness = slots.compareAndExchangeRelease(i, seen, event);
            if (witness == seen) {
                return;
            }
            seen = witness;
        }
    }

    /**
     * What {@link #since} read: {@code events} oldest first; {@code missed}, the sequences in {@code (after, last]}
     * overwritten before the read; {@code last}, the highest sequence returned or counted missed, {@code after} when
     * there is none: the {@code after} of the next call.
     */
    public record Batch(List<Observation> events, long missed, long last) {
    }
}
