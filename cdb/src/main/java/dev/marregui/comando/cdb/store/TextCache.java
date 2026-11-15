package dev.marregui.comando.cdb.store;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The values one VARCHAR column of a {@link Version} decoded, so a column of few distinct values reads each as one
 * shared String, decoded once. Keyed by the value's UTF-8 bytes, compared in full; open addressing over a fixed table
 * of {@link #SLOTS} slots, never resized or cleared. Bounded: past {@link #LIMIT} values, or for a value over
 * {@link #MAX_BYTES} bytes, a value is decoded as before and not kept. Every reader thread of the version shares one
 * cache: an entry is immutable and enters an empty slot by a CAS, so a reader that finds it sees it whole.
 */
final class TextCache {

    /**
     * The values kept, set by the design (1,024); with {@link #MAX_BYTES} it bounds a cache near 0.4 MB (1,024 x
     * (128 key bytes + 128 String bytes + about 120 of headers)), computed, not measured.
     */
    static final int LIMIT = 1_024;
    /** The longest value kept, in UTF-8 bytes; not measured: a value as long as this decodes in well under its read. */
    static final int MAX_BYTES = 128;

    // twice the limit: at most half the slots fill, so a probe always meets an empty slot and stays short
    private static final int SLOTS = 2 * LIMIT;
    private static final long GOLDEN = 0x9E37_79B9_7F4A_7C15L;
    private static final ValueLayout.OfShort SHORT_LE =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final AtomicReferenceArray<Entry> slots = new AtomicReferenceArray<>(SLOTS);
    // reader threads raise it to claim an entry before its CAS, and read it to stop at the limit; never lowered
    private final AtomicInteger claimed = new AtomicInteger();

    /** The value of the {@code length} UTF-8 bytes of {@code data} at {@code start}, which the caller checked fit. */
    String text(final MemorySegment data, final long start, final int length) {
        if (length > MAX_BYTES) {
            return decode(data, start, length);
        }
        final int hash = hash(data, start, length);
        final int mask = SLOTS - 1;
        int slot = hash & mask;
        Entry entry = slots.get(slot);
        while (entry != null) {
            if (entry.matches(hash, data, start, length)) {
                return entry.value();
            }
            slot = (slot + 1) & mask;
            entry = slots.get(slot);
        }
        if (claimed.get() >= LIMIT) {
            return decode(data, start, length);
        }
        final byte[] bytes = new byte[length];
        MemorySegment.copy(data, ValueLayout.JAVA_BYTE, start, bytes, 0, length);
        final String value = new String(bytes, StandardCharsets.UTF_8);
        if (claimed.incrementAndGet() > LIMIT) {
            return value;
        }
        final Entry fresh = new Entry(bytes, Version.tailWord(bytes), hash, value);
        while (!slots.compareAndSet(slot, null, fresh)) {
            // another reader filled the slot first: it may hold this very value, else probe on to the next empty slot
            final Entry other = slots.get(slot);
            if (other.matches(hash, data, start, length)) {
                return other.value();
            }
            slot = (slot + 1) & mask;
        }
        return value;
    }

    private static String decode(final MemorySegment data, final long start, final int length) {
        final byte[] bytes = new byte[length];
        MemorySegment.copy(data, ValueLayout.JAVA_BYTE, start, bytes, 0, length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** A hash of the bytes, read 8 at a time, each word mixed by a multiply. */
    private static int hash(final MemorySegment data, final long start, final int length) {
        long h = length * GOLDEN;
        long at = start;
        final long end = start + length;
        for (; at + Long.BYTES <= end; at += Long.BYTES) {
            h = (h ^ data.get(Version.LONG_LE, at)) * GOLDEN;
        }
        long tail = 0L;
        int shift = 0;
        if (at + Integer.BYTES <= end) {
            tail = data.get(Version.INT_LE, at) & 0xFFFF_FFFFL;
            at += Integer.BYTES;
            shift = Integer.SIZE;
        }
        if (at + Short.BYTES <= end) {
            tail |= (data.get(SHORT_LE, at) & 0xFFFFL) << shift;
            at += Short.BYTES;
            shift += Short.SIZE;
        }
        if (at < end) {
            tail |= (data.get(ValueLayout.JAVA_BYTE, at) & 0xFFL) << shift;
        }
        h = (h ^ tail) * GOLDEN;
        return (int) (h ^ (h >>> 32));
    }

    /** One decoded value and its UTF-8 bytes. */
    private record Entry(byte[] bytes, long tail, int hash, String value) {

        boolean matches(final int otherHash, final MemorySegment data, final long start, final int length) {
            return hash == otherHash && bytes.length == length && Version.equalBytes(data, start, bytes, tail);
        }
    }
}
