package dev.marregui.comando.cdb.exec;

import java.util.ArrayDeque;
import java.util.List;

import dev.marregui.comando.cdb.CdbException;

/**
 * Rows [lo, hi) of the input; negative bounds count from the end, as QuestDB's {@code LIMIT} does: {@code LIMIT n} is
 * {@code (0, n)}, {@code LIMIT -n} is {@code (-n, 0)} (the last n rows; a {@code hi} of 0 after a negative {@code lo}
 * is the end), {@code LIMIT a, b} with {@code a > b} and both bounds non-negative or both negative is {@code (b, a)},
 * {@code LIMIT a, -b} ends b rows before the end, and a negative {@code lo} with a positive {@code hi} is refused.
 * With non-negative bounds the input is pulled only until row {@code hi}, then closed; otherwise it is read to its
 * end, keeping only the pages the window can still reach.
 */
final class LimitCursor implements Cursor {

    private final Cursor child;
    private final RowsPacker packer;
    private final boolean fromEnd;
    /** The rows to keep from the end while reading, for a negative {@code lo}; 0 otherwise. */
    private final long tail;
    private final ArrayDeque<Page> held = new ArrayDeque<>();
    private long lo;
    private long hi;
    private boolean resolved;
    private boolean drained;
    /** The input row index of the first row of the next page to slice. */
    private long base;
    private Page slicing;
    private int sliceFrom;
    private int sliceTo;

    LimitCursor(final Cursor child, final long lo, final long hi) {
        this.child = child;
        packer = new RowsPacker(child.fields());
        if (lo < 0 && hi > 0) {
            child.close();
            throw new CdbException("cannot LIMIT from a negative to a positive bound [lo=" + lo + ", hi=" + hi + "]",
                    -1);
        }
        long a = lo;
        long b = hi;
        if (a > b && (b >= 0 || a < 0)) {
            a = hi;
            b = lo;
        }
        this.lo = a;
        this.hi = b;
        fromEnd = a < 0 || b < 0;
        tail = a < 0 ? -a : 0;
        resolved = !fromEnd;
    }

    @Override
    public List<Field> fields() {
        return child.fields();
    }

    @Override
    public Page next() {
        if (!resolved) {
            readAll();
        }
        while (true) {
            if (slicing != null) {
                final Page in = slicing;
                if (sliceFrom == 0 && sliceTo == in.size && in.size == Page.CAPACITY && packer.isEmpty()) {
                    slicing = null;
                    return in;
                }
                sliceFrom += packer.append(in, sliceFrom, sliceTo - sliceFrom);
                if (sliceFrom == sliceTo) {
                    slicing = null;
                }
                final Page full = packer.full();
                if (full != null) {
                    return full;
                }
                continue;
            }
            final Page in = source();
            if (in == null) {
                return packer.flush();
            }
            final long start = base;
            base += in.size;
            final long from = Math.max(lo, start);
            final long to = Math.min(hi, base);
            if (from < to) {
                slicing = in;
                sliceFrom = (int) (from - start);
                sliceTo = (int) (to - start);
            }
        }
    }

    @Override
    public void close() {
        held.clear();
        slicing = null;
        child.close();
    }

    /** The next page to slice, or null once no later page can reach the window. */
    private Page source() {
        if (fromEnd) {
            return held.poll();
        }
        if (drained || base >= hi) {
            finishInput();
            return null;
        }
        final Page in = child.next();
        if (in == null) {
            finishInput();
        }
        return in;
    }

    /** Reads the whole input for bounds counted from the end, then resolves them to absolute rows. */
    private void readAll() {
        long dropped = 0;
        long total = 0;
        Page in;
        while ((in = child.next()) != null) {
            held.add(in);
            total += in.size;
            while (!held.isEmpty()) {
                final int first = held.peek().size;
                final boolean beforeTail = tail > 0 && total - dropped - first >= tail;
                final boolean beforeLo = tail == 0 && dropped + first <= lo;
                if (!beforeTail && !beforeLo) {
                    break;
                }
                held.poll();
                dropped += first;
            }
        }
        finishInput();
        lo = lo < 0 ? Math.max(0, total + lo) : lo;
        hi = hi < 0 ? Math.max(0, total + hi) : hi == 0 && tail > 0 ? total : hi;
        base = dropped;
        resolved = true;
    }

    private void finishInput() {
        if (!drained) {
            drained = true;
            child.close();
        }
    }
}
