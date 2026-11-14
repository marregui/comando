package dev.marregui.comando.cdb.exec;

import java.util.List;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.exec.expr.Functions;
import dev.marregui.comando.cdb.exec.expr.VectorFn;
import dev.marregui.comando.cdb.plan.Bound;

/**
 * inner, left and cross joins: the left's columns, then the right's; on null for cross. The first {@link #next()}
 * reads the whole right input and closes it; the left streams. Rows come out in left order, and for one left row in
 * right order. A left row with no match is padded with NULLs in a left join. {@link #open} hash-joins an {@code on}
 * that is one {@code =} between a left and a right column, NULL matching NULL as in QuestDB; every other {@code on}
 * is evaluated per left row over each page of the right.
 */
final class JoinCursor implements Cursor {

    private final Cursor left;
    private final Cursor right;
    private final Kind kind;
    /** The predicate of a nested-loop join; null for a hash join and for every pair. */
    private final VectorFn on;
    /** The key columns of a hash join, in the left's and in the right's columns; -1 for a nested loop. */
    private final int leftKey;
    private final int rightKey;
    private final List<Field> fields;
    private final int leftWidth;
    private final RowsPacker packer;
    private RowsBuffer rows;
    /** Hash join: bucket heads and the chain to the next row, both as row index + 1, 0 at the end. */
    private int[] heads;
    private int[] chain;
    private int mask;
    /** Nested loop: the left row repeated, then the right page's columns, as the predicate's input. */
    private Page probe;
    private boolean[] matches;
    private Page page;
    private int row;
    private boolean rowStarted;
    private boolean matched;
    /** Hash join: the next candidate row + 1 of the current left row's chain. */
    private int candidate;
    /** Nested loop: the right page and row the current left row resumes at. */
    private int rightPage;
    private int rightRow;
    private boolean evaluated;
    private boolean done;

    JoinCursor(final Cursor left, final Cursor right, final String kind, final VectorFn on, final List<Field> fields) {
        this(left, right, kind, on, -1, -1, fields);
    }

    private JoinCursor(
            final Cursor left,
            final Cursor right,
            final String kind,
            final VectorFn on,
            final int leftKey,
            final int rightKey,
            final List<Field> fields
    ) {
        this.left = left;
        this.right = right;
        this.on = on;
        this.leftKey = leftKey;
        this.rightKey = rightKey;
        this.fields = fields;
        leftWidth = left.fields().size();
        try {
            this.kind = kind(kind);
            if (fields.size() != leftWidth + right.fields().size()) {
                throw new CdbException("cannot join into a different number of columns [fields=" + fields.size()
                        + ", left=" + leftWidth + ", right=" + right.fields().size() + "]", -1);
            }
        } catch (final RuntimeException e) {
            Rows.close(left, right);
            throw e;
        }
        packer = new RowsPacker(fields);
    }

    /**
     * The join of {@code left} and {@code right}: a hash join when {@code on} is one {@code =} between a column of
     * each side whose values are stored alike, else a nested loop over {@code on} compiled.
     */
    static JoinCursor open(
            final Cursor left,
            final Cursor right,
            final String kind,
            final Bound on,
            final long nowMicros,
            final List<Field> fields
    ) {
        final int width = left.fields().size();
        if (on instanceof Bound.Call(final String function, final List<Bound> args, _)
                && "=".equals(function) && args.size() == 2
                && args.get(0) instanceof Bound.Column(final int a, _)
                && args.get(1) instanceof Bound.Column(final int b, _)
                && (a < width) != (b < width)) {
            final int l = Math.min(a, b);
            final int r = Math.max(a, b) - width;
            if (r < right.fields().size()
                    && Rows.sameStorage(left.fields().get(l).type(), right.fields().get(r).type())) {
                return new JoinCursor(left, right, kind, null, l, r, fields);
            }
        }
        final VectorFn compiled;
        try {
            compiled = on == null ? null : Functions.compile(on, nowMicros);
        } catch (final RuntimeException e) {
            Rows.close(left, right);
            throw e;
        }
        return new JoinCursor(left, right, kind, compiled, fields);
    }

    @Override
    public List<Field> fields() {
        return fields;
    }

    @Override
    public Page next() {
        if (done) {
            return null;
        }
        if (rows == null) {
            build();
            if (rows.rowCount() == 0 && kind != Kind.LEFT) {
                return finish();
            }
        }
        while (true) {
            if (page == null) {
                page = left.next();
                if (page == null) {
                    return finish();
                }
                row = 0;
                rowStarted = false;
            }
            final Page in = page;
            while (row < in.size) {
                if (!rowStarted) {
                    startRow();
                }
                if (leftKey >= 0 ? hashRow() : loopRow()) {
                    if (!matched && kind == Kind.LEFT) {
                        packer.appendJoined(in, row, null, 0);
                    }
                    row++;
                    rowStarted = false;
                }
                final Page full = packer.full();
                if (full != null) {
                    if (row == in.size) {
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
        done = true;
        page = null;
        rows = null;
        Rows.close(left, right);
    }

    private static Kind kind(final String kind) {
        return switch (kind) {
            case "inner" -> Kind.INNER;
            case "left" -> Kind.LEFT;
            case "cross" -> Kind.CROSS;
            default -> throw new CdbException("cannot run an unknown join [kind=" + kind + "]", -1);
        };
    }

    private Page finish() {
        done = true;
        page = null;
        rows = null;
        Rows.close(left, right);
        return packer.flush();
    }

    private void build() {
        final RowsBuffer buffer = new RowsBuffer(right.fields());
        Page in;
        while ((in = right.next()) != null) {
            buffer.addAll(in);
        }
        right.close();
        rows = buffer;
        final int n = buffer.rowCount();
        if (leftKey >= 0) {
            int capacity = 2;
            while (capacity < 2L * n && capacity < 1 << 30) {
                capacity <<= 1;
            }
            heads = new int[capacity];
            chain = new int[n];
            mask = capacity - 1;
            // from the last row back, so a chain lists its rows in right order
            for (int r = n - 1; r >= 0; r--) {
                final int bucket = Rows.mix(Rows.hash(buffer.pageOf(r).columns[rightKey], r & Rows.ROW_MASK)) & mask;
                chain[r] = heads[bucket];
                heads[bucket] = r + 1;
            }
        } else if (on != null) {
            final Vector[] columns = new Vector[fields.size()];
            for (int c = 0; c < leftWidth; c++) {
                columns[c] = Vector.of(fields.get(c).type(), Page.CAPACITY);
            }
            probe = new Page(columns, 0);
            matches = new boolean[Page.CAPACITY];
        }
    }

    private void startRow() {
        rowStarted = true;
        matched = false;
        if (leftKey >= 0) {
            final int bucket = Rows.mix(Rows.hash(page.columns[leftKey], row)) & mask;
            candidate = heads[bucket];
            return;
        }
        rightPage = 0;
        rightRow = 0;
        evaluated = false;
        if (probe != null) {
            final int n = Math.min(rows.rowCount(), Page.CAPACITY);
            for (int c = 0; c < leftWidth; c++) {
                final Vector dst = probe.columns[c];
                final Vector src = page.columns[c];
                for (int i = 0; i < n; i++) {
                    dst.copy(src, row, i);
                }
            }
        }
    }

    /** Emits the current left row's matches until the packer fills; true once the row has no more. */
    private boolean hashRow() {
        final Vector key = page.columns[leftKey];
        while (candidate != 0) {
            final int r = candidate - 1;
            candidate = chain[r];
            final Page rp = rows.pageOf(r);
            final int rr = r & Rows.ROW_MASK;
            if (Rows.same(key, row, rp.columns[rightKey], rr)) {
                packer.appendJoined(page, row, rp, rr);
                matched = true;
                if (packer.isFull()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Emits the current left row's matches until the packer fills; true once the row has no more. */
    private boolean loopRow() {
        while (rightPage < rows.pageCount()) {
            final Page rp = rows.page(rightPage);
            if (!evaluated) {
                evaluate(rp);
                evaluated = true;
                rightRow = 0;
            }
            while (rightRow < rp.size) {
                final int r = rightRow++;
                if (on == null || matches[r]) {
                    packer.appendJoined(page, row, rp, r);
                    matched = true;
                    if (packer.isFull()) {
                        return false;
                    }
                }
            }
            rightPage++;
            evaluated = false;
        }
        return true;
    }

    private void evaluate(final Page rp) {
        if (on == null) {
            return;
        }
        for (int c = leftWidth; c < probe.columns.length; c++) {
            probe.columns[c] = rp.columns[c - leftWidth];
        }
        probe.size = rp.size;
        final Vector result = on.eval(probe);
        if (result.booleans == null) {
            throw new CdbException("cannot join on a condition that is not BOOLEAN [type=" + result.type + "]", -1);
        }
        System.arraycopy(result.booleans, 0, matches, 0, rp.size);
    }

    private enum Kind {
        INNER,
        LEFT,
        CROSS
    }
}
