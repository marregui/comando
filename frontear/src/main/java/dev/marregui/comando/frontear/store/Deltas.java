package dev.marregui.comando.frontear.store;

import java.util.Arrays;

/**
 * A record as the bytes it differs by from the record before it in its segment, so what a link's records repeat (the
 * topic, the packet id, the properties a link stamps) takes no room on disk. A delta is the record's length, then
 * one or more steps: a run copied from the previous record (where it starts there, how long it is), then a run of
 * the record's own bytes (how long it is, the bytes); every number an unsigned LEB128 varint. The runs copied may
 * start anywhere in the previous record, so a length field that grew by a byte or a changed digit costs a short run of
 * the record's own and the copy goes on after it.
 */
final class Deltas {

    // a delta is kept only when it saves this many bytes or more over the record whole; below it, the record is
    // written whole, so it reads without its neighbour. Unmeasured: a tenth of the smallest record it was made for,
    // the PUBLISH of a 128-byte payload on a 35-character topic a link logs (226 bytes)
    private static final int MIN_SAVING = 16;
    // after a difference, the longest run of the record's own bytes the encoder looks past for the copy to go on:
    // a PUBLISH's remaining length (up to 4 bytes) or the changed digits of a timestamp; past it the rest is the
    // record's own, as a payload is
    private static final int LOOK_AHEAD = 8;
    // how far the copy may move in the previous record when it goes on: a remaining length that grew or shrank by up
    // to 3 bytes, the variable byte integer's whole range
    private static final int MAX_SHIFT = 3;
    // the bytes that must match for the copy to go on: fewer cost more in the step's numbers than they save
    private static final int MIN_MATCH = 4;
    // the most steps in a delta, which bounds the encoder's search per record
    private static final int MAX_STEPS = 8;
    private static final int[] SHIFTS = {0, -1, 1, -2, 2, -3, 3};

    private Deltas() {
    }

    /**
     * {@code record} as a delta of {@code previous}, null when that would not save {@link #MIN_SAVING} bytes over
     * the record whole.
     */
    static byte[] encode(final byte[] previous, final byte[] record) {
        final int limit = record.length - MIN_SAVING;
        if (limit <= 0) {
            return null;
        }
        final byte[] out = new byte[limit];
        int at = put(out, 0, record.length);
        int i = 0;
        int j = 0;
        for (int step = 0; i < record.length; step++) {
            final int copy = common(record, i, previous, j);
            final int from = j;
            i += copy;
            j += copy;
            int own = record.length - i;
            int next = -1;
            if (own > 0 && step < MAX_STEPS - 1) {
                search:
                for (int k = 0; k <= LOOK_AHEAD && i + k + MIN_MATCH <= record.length; k++) {
                    for (final int shift : SHIFTS) {
                        final int to = j + k + shift;
                        if ((k > 0 || shift != 0) && to >= 0 && common(record, i + k, previous, to) >= MIN_MATCH) {
                            own = k;
                            next = to;
                            break search;
                        }
                    }
                }
            }
            if (at + size(from) + size(copy) + size(own) + own > limit) {
                return null;
            }
            at = put(out, put(out, put(out, at, from), copy), own);
            System.arraycopy(record, i, out, at, own);
            at += own;
            i += own;
            j = next;
        }
        return Arrays.copyOf(out, at);
    }

    /** The record {@code delta} makes of {@code previous}, null when the delta does not fit it or is malformed. */
    static byte[] decode(final byte[] previous, final byte[] delta) {
        final Reader in = new Reader(delta);
        final int length = in.next();
        // no delta the encoder writes makes more than its steps' copies of the previous record and its own bytes
        if (length < 0 || length > (long) previous.length * MAX_STEPS + delta.length) {
            return null;
        }
        final byte[] record = new byte[length];
        int i = 0;
        for (int step = 0; in.at < delta.length; step++) {
            if (step == MAX_STEPS) {
                return null;
            }
            final int from = in.next();
            final int copy = in.next();
            final int own = in.next();
            if (from < 0 || copy < 0 || own < 0 || copy > previous.length - from || copy > length - i
                    || own > length - i - copy || own > delta.length - in.at) {
                return null;
            }
            System.arraycopy(previous, from, record, i, copy);
            i += copy;
            System.arraycopy(delta, in.at, record, i, own);
            i += own;
            in.at += own;
        }
        return i == length ? record : null;
    }

    // how many bytes a and b have in common from a[i] and b[j] on
    private static int common(final byte[] a, final int i, final byte[] b, final int j) {
        if (j < 0 || i >= a.length || j >= b.length) {
            return 0;
        }
        final int n = Math.min(a.length - i, b.length - j);
        final int mismatch = Arrays.mismatch(a, i, i + n, b, j, j + n);
        return mismatch < 0 ? n : mismatch;
    }

    // value as a varint at out[at], which has room for it; where it ends
    private static int put(final byte[] out, final int at, final int value) {
        int v = value;
        int p = at;
        while ((v & ~0x7F) != 0) {
            out[p++] = (byte) (v & 0x7F | 0x80);
            v >>>= 7;
        }
        out[p++] = (byte) v;
        return p;
    }

    // the bytes value takes as a varint
    private static int size(final int value) {
        return value < 1 << 7 ? 1 : value < 1 << 14 ? 2 : value < 1 << 21 ? 3 : value < 1 << 28 ? 4 : 5;
    }

    /** Reads a delta's varints from the front. */
    private static final class Reader {

        private final byte[] bytes;
        private int at;

        Reader(final byte[] bytes) {
            this.bytes = bytes;
        }

        // the next varint, -1 when it runs past the end, past an int's range or is not the shortest form
        int next() {
            long value = 0L;
            for (int shift = 0; shift < 35; shift += 7) {
                if (at >= bytes.length) {
                    return -1;
                }
                final int b = bytes[at++] & 0xFF;
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value > Integer.MAX_VALUE || b == 0 && shift > 0 ? -1 : (int) value;
                }
            }
            return -1;
        }
    }
}
