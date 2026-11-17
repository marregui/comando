package dev.marregui.comando.cdb.exec;

import java.util.List;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.expr.VectorFn;

/**
 * The input's rows where the predicate is true (NULL drops the row), packed into full pages; the last may be shorter.
 * A full input page whose every row is kept, while no row waits to be packed, passes through uncopied.
 */
final class FilterCursor implements Cursor {

    private final Cursor child;
    private final VectorFn predicate;
    private final RowsPacker packer;
    private final boolean[] keep = new boolean[Page.CAPACITY];
    private Page page;
    private int kept;
    private int pos;
    // set by each call: borrow borrows the child's pages too, and the packer's page last lent is refilled next call
    private boolean lending;
    private Page lent;
    // the child's page load returned last
    private Page loaded;

    FilterCursor(final Cursor child, final VectorFn predicate) {
        this.child = child;
        this.predicate = predicate;
        packer = new RowsPacker(child.fields());
    }

    @Override
    public List<Field> fields() {
        return child.fields();
    }

    @Override
    public Page next() {
        // per call: a next after a borrow reads the child with next, so a page passed through is the caller's
        lending = false;
        return pack();
    }

    /** {@link #next}'s rows, the child's pages borrowed and the packer's page refilled on each call. */
    @Override
    public Page borrow() {
        lending = true;
        if (lent != null) {
            packer.giveBack(lent);
            lent = null;
        }
        final Page out = pack();
        if (out != null && out != loaded) {
            // a page of the packer's, not the child's passed through
            lent = out;
        }
        return out;
    }

    private Page pack() {
        while (true) {
            if (page == null) {
                final Page in = load();
                if (in == null) {
                    return packer.flush();
                }
                if (kept == Page.CAPACITY && packer.isEmpty()) {
                    return in;
                }
                page = in;
                pos = 0;
            }
            final Page in = page;
            final int size = in.size;
            while (pos < size) {
                if (!keep[pos]) {
                    pos++;
                    continue;
                }
                int end = pos + 1;
                while (end < size && keep[end]) {
                    end++;
                }
                pos += packer.append(in, pos, end - pos);
                final Page full = packer.full();
                if (full != null) {
                    if (pos == size) {
                        page = null;
                    }
                    return full;
                }
            }
            page = null;
        }
    }

    @Override
    public void close() {
        child.close();
    }

    /**
     * The next input page that keeps a row, its mask in {@link #keep} and its count in {@link #kept}; null at the
     * end.
     */
    private Page load() {
        while (true) {
            final Page in = lending ? child.borrow() : child.next();
            if (in == null) {
                return null;
            }
            final Vector mask = predicate.eval(in);
            if (mask.type != CdbType.BOOLEAN) {
                throw new CdbException("cannot filter on a predicate that is not BOOLEAN [type=" + mask.type + "]", -1);
            }
            final int size = in.size;
            int count = 0;
            for (int i = 0; i < size; i++) {
                final boolean k = mask.getBoolean(i);
                keep[i] = k;
                if (k) {
                    count++;
                }
            }
            if (count > 0) {
                kept = count;
                loaded = in;
                return in;
            }
        }
    }
}
