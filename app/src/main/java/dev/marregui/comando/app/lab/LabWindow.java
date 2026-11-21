package dev.marregui.comando.app.lab;

import java.util.ArrayList;
import java.util.List;

import dev.marregui.comando.core.port.LabPort.Packet;

/**
 * The last {@link #CAPACITY} packets the drain read, numbered by this window from 1 without gaps; when it is full the
 * oldest goes, counted in {@link #dropped}. Written by the drain's thread, read by the tab's and the tool's; each call
 * holds the window's lock for one copy.
 */
final class LabWindow {

    /** The packets kept (ADR 0187 §2.3): a minute of a busy fabric's traffic, a few MiB of rows. */
    static final int CAPACITY = 10_000;

    private final Packet[] ring;
    // guarded by this
    private long next = 1;
    private int size;
    private long dropped;

    LabWindow() {
        this(CAPACITY);
    }

    LabWindow(final int capacity) {
        this.ring = new Packet[capacity];
    }

    /** Keeps {@code p} as the next packet, its {@code seq} this window's; returns the packet kept. */
    synchronized Packet add(final Packet p) {
        final long seq = next++;
        final Packet kept = new Packet(seq, p.atMillis(), p.kind(), p.origin(), p.link(), p.topic(), p.qos(),
                p.retain(), p.bytes(), p.hops(), p.offset(), p.correlation(), p.head(), p.detail());
        ring[(int) (seq % ring.length)] = kept;
        if (size < ring.length) {
            size++;
        } else {
            dropped++;
        }
        return kept;
    }

    /** The packets let go of since the start. */
    synchronized long dropped() {
        return dropped;
    }

    /**
     * The packets after {@code after}, oldest first, at most {@code max}; how many after {@code after} were let go of;
     * the highest seq returned, else the larger of {@code after} and the last seq let go of.
     */
    synchronized Read read(final long after, final int max) {
        if (max <= 0) {
            return new Read(List.of(), 0L, after);
        }
        final long first = next - size;
        final long from = Math.max(after + 1, first);
        final long missed = Math.max(0L, from - (after + 1));
        final long to = Math.min(next - 1, from + max - 1);
        final List<Packet> out = new ArrayList<>((int) Math.max(0L, to - from + 1));
        for (long s = from; s <= to; s++) {
            out.add(ring[(int) (s % ring.length)]);
        }
        return new Read(out, missed, out.isEmpty() ? Math.max(after, from - 1) : to);
    }

    /** One {@link #read}. */
    record Read(List<Packet> packets, long missed, long last) {
    }
}
