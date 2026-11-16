package dev.marregui.comando.cdb.exec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
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
    /** The right input, read whole at the first {@link #next()}; null when the right was built by the caller. */
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
    /** The right built by the caller, shared with other cursors; null when this cursor builds its own. */
    private final Table shared;
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
        this(left, right, null, kind, on, -1, -1, fields);
    }

    private JoinCursor(
            final Cursor left,
            final Cursor right,
            final Table shared,
            final String kind,
            final VectorFn on,
            final int leftKey,
            final int rightKey,
            final List<Field> fields
    ) {
        this.left = left;
        this.right = right;
        this.shared = shared;
        this.on = on;
        this.leftKey = leftKey;
        this.rightKey = rightKey;
        this.fields = fields;
        leftWidth = left.fields().size();
        try {
            this.kind = kind(kind);
            if (right != null) {
                checkWidths(fields, leftWidth, right.fields().size());
            }
        } catch (final RuntimeException e) {
            closeInputs();
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
        final int[] keys = hashKeys(left.fields(), right.fields(), on);
        if (keys != null) {
            return new JoinCursor(left, right, null, kind, null, keys[0], keys[1], fields);
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

    /**
     * A join over {@code source}'s frames: the right input read once on the consumer and shared read-only, each left
     * frame joined on the pool by a cursor of its own, rows out in left frame order. The answer is the serial
     * operator's, the same bits whatever the pool's size; pages end at frame ends, so a page before the last may be
     * short. Owns and closes the source and {@code right}.
     */
    static Cursor parallel(
            final FrameSource source,
            final Cursor right,
            final String kind,
            final Bound on,
            final long nowMicros,
            final List<Field> fields
    ) {
        try {
            final int[] keys = hashKeys(source.fields(), right.fields(), on);
            if (keys == null && on != null) {
                // compiled here only so a condition that cannot run fails as the serial join's does, before a frame
                Functions.compile(on, nowMicros);
            }
            final boolean leftJoin = kind(kind) == Kind.LEFT;
            checkWidths(fields, source.fields().size(), right.fields().size());
            return keys == null
                    ? new FrameJoin(source, right, kind, leftJoin, on, -1, -1, nowMicros, fields)
                    : new FrameJoin(source, right, kind, leftJoin, null, keys[0], keys[1], nowMicros, fields);
        } catch (final RuntimeException e) {
            FrameJoin.close(null, right, source);
            throw e;
        }
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
            adopt(shared != null ? shared : build(right, rightKey));
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
        closeInputs();
    }

    private static Kind kind(final String kind) {
        return switch (kind) {
            case "inner" -> Kind.INNER;
            case "left" -> Kind.LEFT;
            case "cross" -> Kind.CROSS;
            default -> throw new CdbException("cannot run an unknown join [kind=" + kind + "]", -1);
        };
    }

    private static void checkWidths(final List<Field> fields, final int leftWidth, final int rightWidth) {
        if (fields.size() != leftWidth + rightWidth) {
            throw new CdbException("cannot join into a different number of columns [fields=" + fields.size()
                    + ", left=" + leftWidth + ", right=" + rightWidth + "]", -1);
        }
    }

    /**
     * The left's and the right's key column of a hash join when {@code on} is one {@code =} between a column of each
     * side whose values are stored alike; else null.
     */
    private static int[] hashKeys(final List<Field> leftFields, final List<Field> rightFields, final Bound on) {
        final int width = leftFields.size();
        if (on instanceof Bound.Call(final String function, final List<Bound> args, _)
                && "=".equals(function) && args.size() == 2
                && args.get(0) instanceof Bound.Column(final int a, _)
                && args.get(1) instanceof Bound.Column(final int b, _)
                && (a < width) != (b < width)) {
            final int l = Math.min(a, b);
            final int r = Math.max(a, b) - width;
            if (r < rightFields.size() && Rows.sameStorage(leftFields.get(l).type(), rightFields.get(r).type())) {
                return new int[] {l, r};
            }
        }
        return null;
    }

    /** Reads the whole of {@code right} and closes it; for a hash join ({@code rightKey >= 0}) chains its rows. */
    private static Table build(final Cursor right, final int rightKey) {
        final RowsBuffer buffer = new RowsBuffer(right.fields());
        Page in;
        while ((in = right.next()) != null) {
            buffer.addAll(in);
        }
        right.close();
        if (rightKey < 0) {
            return new Table(buffer, null, null, 0);
        }
        final int n = buffer.rowCount();
        int capacity = 2;
        while (capacity < 2L * n && capacity < 1 << 30) {
            capacity <<= 1;
        }
        final int[] heads = new int[capacity];
        final int[] chain = new int[n];
        final int mask = capacity - 1;
        // from the last row back, so a chain lists its rows in right order
        for (int r = n - 1; r >= 0; r--) {
            final int bucket = Rows.mix(Rows.hash(buffer.pageOf(r).columns[rightKey], r & Rows.ROW_MASK)) & mask;
            chain[r] = heads[bucket];
            heads[bucket] = r + 1;
        }
        return new Table(buffer, heads, chain, mask);
    }

    private void adopt(final Table table) {
        rows = table.rows();
        heads = table.heads();
        chain = table.chain();
        mask = table.mask();
        if (on != null) {
            final Vector[] columns = new Vector[fields.size()];
            for (int c = 0; c < leftWidth; c++) {
                columns[c] = Vector.of(fields.get(c).type(), Page.CAPACITY);
            }
            probe = new Page(columns, 0);
            matches = new boolean[Page.CAPACITY];
        }
    }

    private void closeInputs() {
        if (right == null) {
            left.close();
        } else {
            Rows.close(left, right);
        }
    }

    private Page finish() {
        done = true;
        page = null;
        rows = null;
        closeInputs();
        return packer.flush();
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
        if (result.type != CdbType.BOOLEAN) {
            throw new CdbException("cannot join on a condition that is not BOOLEAN [type=" + result.type + "]", -1);
        }
        for (int i = 0, n = rp.size; i < n; i++) {
            matches[i] = result.getBoolean(i);
        }
    }

    private enum Kind {
        INNER,
        LEFT,
        CROSS
    }

    /**
     * The right's rows and, for a hash join, their buckets ({@code heads} and {@code chain} null for a nested loop).
     * Written once by the thread that builds it, then only read, by any number of frame cursors.
     */
    private record Table(RowsBuffer rows, int[] heads, int[] chain, int mask) {
    }

    /**
     * The join over a segment's frames. The first {@link #next()} builds the right on the consumer; then each left
     * frame is joined by a {@link JoinCursor} of its own over the shared {@link Table}, on the pool, and at most
     * {@link #FRAME_PAGES} of its pages are kept until the consumer reaches the frame. A frame that joins to more is
     * dropped by its worker and joined again by the consumer when its turn comes, a page at a time: a join that
     * multiplies rows (a cross join, a key many right rows share) holds no more than the bound per frame, and a
     * consumer that stops early (a LIMIT above) stops it. Frames are submitted one job each, a bounded window ahead of
     * the one being emitted. Every page out checks the query's cancel. Used by one consumer thread.
     */
    static final class FrameJoin implements Cursor {

        /**
         * Frames joined ahead of the one being emitted, per pool thread: the exchange's read-ahead bound, two per
         * worker, so a worker that finishes a frame finds the next one queued. Not measured for joins; a join
         * benchmark at pool sizes 1 to 11 would settle it.
         */
        private static final int AHEAD_PER_THREAD = 2;

        /**
         * The most pages a frame's job keeps for the consumer: 64 pages of {@link Page#CAPACITY} rows, a frame of the
         * left at one match a row. Set with the window ahead to bound what a fan-out join holds, not measured; a
         * frame past it costs the consumer the join of those 64 pages again.
         */
        static final int FRAME_PAGES = 64;

        private final FrameSource source;
        private final Cursor right;
        private final String kind;
        private final boolean leftJoin;
        /** A nested loop's condition, compiled per frame: a compiled function reuses its vectors between calls. */
        private final Bound on;
        private final int leftKey;
        private final int rightKey;
        private final long nowMicros;
        private final List<Field> fields;
        private final int ahead;
        /** The frames submitted and not yet emitted, in frame order. */
        private final ArrayDeque<Job<Output>> jobs = new ArrayDeque<>();
        private Table table;
        private List<Page> pages;
        private int emitted;
        /** The frame the consumer joins itself, a page at a time, when its job gave up at the bound; else null. */
        private Cursor streaming;
        private int submitted;
        private int mostHeld;
        private boolean done;
        private boolean closed;

        FrameJoin(
                final FrameSource source,
                final Cursor right,
                final String kind,
                final boolean leftJoin,
                final Bound on,
                final int leftKey,
                final int rightKey,
                final long nowMicros,
                final List<Field> fields
        ) {
            this.source = source;
            this.right = right;
            this.kind = kind;
            this.leftJoin = leftJoin;
            this.on = on;
            this.leftKey = leftKey;
            this.rightKey = rightKey;
            this.nowMicros = nowMicros;
            this.fields = fields;
            ahead = AHEAD_PER_THREAD * Math.max(1, source.context().pool().threads());
        }

        /** Closes {@code first} when not null, then {@code right}, then the source; a failure skips no other close. */
        static void close(final Cursor first, final Cursor right, final FrameSource source) {
            RuntimeException failure = null;
            if (first != null) {
                try {
                    first.close();
                } catch (final RuntimeException e) {
                    failure = e;
                }
            }
            try {
                right.close();
            } catch (final RuntimeException e) {
                failure = merge(failure, e);
            }
            try {
                source.close();
            } catch (final RuntimeException e) {
                failure = merge(failure, e);
            }
            if (failure != null) {
                throw failure;
            }
        }

        private static RuntimeException merge(final RuntimeException failure, final RuntimeException next) {
            if (failure == null) {
                return next;
            }
            failure.addSuppressed(next);
            return failure;
        }

        @Override
        public List<Field> fields() {
            return fields;
        }

        @Override
        public Page next() {
            while (!done) {
                if (table == null) {
                    table = build(right, rightKey);
                    if (table.rows().rowCount() == 0 && !leftJoin) {
                        // as the serial join: an inner or cross join with an empty right reads no left row
                        close();
                        return null;
                    }
                    submit();
                }
                if (streaming != null) {
                    source.context().check();
                    final Page out = streaming.next();
                    if (out != null) {
                        return out;
                    }
                    final Cursor finished = streaming;
                    streaming = null;
                    finished.close();
                } else if (pages != null && emitted < pages.size()) {
                    source.context().check();
                    final Page out = pages.get(emitted);
                    pages.set(emitted++, null);
                    return out;
                } else {
                    final Job<Output> job = jobs.peekFirst();
                    if (job == null) {
                        close();
                        return null;
                    }
                    // the job's await hands over the pages its frame wrote on a worker, published by the job's
                    // completion
                    final Output output = job.await(0);
                    jobs.pollFirst();
                    job.close();
                    mostHeld = Math.max(mostHeld, output.held());
                    pages = output.pages();
                    emitted = 0;
                    if (pages == null) {
                        streaming = frame(table, output.frame());
                    }
                    submit();
                }
            }
            return null;
        }

        /** Stops the frames in flight and waits for them, then closes the right and the source; idempotent. */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            done = true;
            // every frame stopped before the source: a frame reads the source's snapshot
            Job<Output> job;
            while ((job = jobs.pollFirst()) != null) {
                job.close();
            }
            final Cursor frame = streaming;
            streaming = null;
            pages = null;
            table = null;
            close(frame, right, source);
        }

        /** The most pages one frame held at once, on its worker or for the consumer. */
        @TestOnly
        int mostPagesHeld() {
            return mostHeld;
        }

        /** Submits frames until the window ahead is full or every frame is submitted. */
        private void submit() {
            final QueryContext ctx = source.context();
            // the table is complete before the first submit, and the pool's hand-off of a job publishes it to the
            // worker that runs the frame; frames only read it
            final Table built = table;
            while (jobs.size() < ahead && submitted < source.frames()) {
                final int frame = submitted++;
                jobs.addLast(ctx.pool().submit(1, _ -> join(built, frame), ctx));
            }
        }

        /** Frame {@code frame}'s rows joined to {@code built}, a page at a time, on the calling thread. */
        private Cursor frame(final Table built, final int frame) {
            final VectorFn compiled = on == null ? null : Functions.compile(on, nowMicros);
            return new JoinCursor(source.open(frame), null, built, kind, compiled, leftKey, rightKey, fields);
        }

        /** Frame {@code frame}'s rows joined to {@code built}, on any thread; no pages past {@link #FRAME_PAGES}. */
        private Output join(final Table built, final int frame) {
            final QueryContext ctx = source.context();
            final List<Page> out = new ArrayList<>();
            try (Cursor cursor = frame(built, frame)) {
                Page page;
                while ((page = cursor.next()) != null) {
                    ctx.check();
                    if (out.size() == FRAME_PAGES) {
                        // the page past the bound: dropped with the rest, the consumer joins the frame again
                        return new Output(frame, null, FRAME_PAGES + 1);
                    }
                    out.add(page);
                }
            }
            return new Output(frame, out, out.size());
        }

        /**
         * A frame's join as its job hands it to the consumer.
         *
         * @param frame the frame's index
         * @param pages its pages, or null when it joins to more than {@link #FRAME_PAGES} pages
         * @param held the most pages the job held at once
         */
        private record Output(int frame, List<Page> pages, int held) {
        }
    }
}
