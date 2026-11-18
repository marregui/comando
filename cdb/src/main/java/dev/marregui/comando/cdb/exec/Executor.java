package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.catalog.Catalog;
import dev.marregui.comando.cdb.exec.expr.Functions;
import dev.marregui.comando.cdb.exec.expr.VectorFn;
import dev.marregui.comando.cdb.plan.Bound;
import dev.marregui.comando.cdb.plan.ColumnUse;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.plan.PlanText;
import dev.marregui.comando.cdb.store.Store;

/** Builds the cursor tree of a query plan, and runs the plans that change the store. */
public final class Executor {

    private Executor() {
    }

    /**
     * The rows of {@code plan}, a query; the caller closes the cursor. Each scan reads only the columns the plan above
     * it reads ({@link ColumnUse}).
     */
    public static Cursor open(final Plan plan, final Store store, final long nowMicros) {
        return open(plan, store, nowMicros, QueryContext.uncancellable());
    }

    /**
     * As {@link #open(Plan, Store, long)}, the query's frames running in {@code ctx}: on its pool, given up once its
     * cancel is cancelled. An operator whose input is a segment ({@link FrameSource#isSegment}) works per frame; over
     * any other input it runs as before, on the calling thread.
     */
    public static Cursor open(final Plan plan, final Store store, final long nowMicros, final QueryContext ctx) {
        return build(plan, store, nowMicros, ColumnUse.of(plan), ctx);
    }

    /**
     * The rows of segment {@code segment} read frame after frame on the calling thread, in serial scan order: the input
     * a test gives a serial operator to compare with the operator's parallel answer.
     */
    @TestOnly
    static Cursor serialSegment(
            final Plan segment,
            final Store store,
            final long nowMicros,
            final Map<Plan.Scan, boolean[]> reads
    ) {
        return new ExchangeCursor(FrameSource.of(segment, store, nowMicros, reads, new QueryContext(null,
                QueryPool.of(0))));
    }

    private static Cursor build(
            final Plan plan,
            final Store store,
            final long nowMicros,
            final Map<Plan.Scan, boolean[]> reads,
            final QueryContext ctx
    ) {
        return switch (plan) {
            case Plan.Scan s -> exchange(s, store, nowMicros, reads, ctx);
            case Plan.Filter f when FrameSource.isSegment(f) -> exchange(f, store, nowMicros, reads, ctx);
            case Plan.Project p when FrameSource.isSegment(p) -> exchange(p, store, nowMicros, reads, ctx);
            case Plan.Source s -> Catalog.open(s, store, ctx);
            case Plan.Filter f -> over(build(f.child(), store, nowMicros, reads, ctx), c -> filter(f, c, nowMicros));
            case Plan.Project p -> over(build(p.child(), store, nowMicros, reads, ctx), c -> project(p, c, nowMicros));
            case Plan.Aggregate a -> parallel(a.child(), splittable(a.keys(), a.aggs()), store, nowMicros, reads, ctx,
                    src -> GroupByCursor.parallel(src, a, nowMicros),
                    c -> new GroupByCursor(c, a, nowMicros));
            case Plan.SampleBy s -> parallel(s.child(), splittable(s.keys(), s.aggs()), store, nowMicros, reads, ctx,
                    src -> SampleByCursor.parallel(src, s, nowMicros),
                    c -> new SampleByCursor(c, s, nowMicros));
            case Plan.LatestOn l -> parallel(l.child(), true, store, nowMicros, reads, ctx,
                    src -> LatestOnCursor.newestFirst(src, l),
                    c -> new LatestOnCursor(c, l));
            case Plan.Distinct d -> parallel(d.child(), true, store, nowMicros, reads, ctx,
                    DistinctCursor::parallel,
                    DistinctCursor::new);
            case Plan.Sort s when LateReadCursor.applies(s, reads) ->
                    LateReadCursor.open(s, store, nowMicros, reads, ctx);
            case Plan.Sort s -> parallel(s.child(), true, store, nowMicros, reads, ctx,
                    src -> SortCursor.parallel(src, s.keys(), s.limit()),
                    c -> new SortCursor(c, s.keys(), s.limit()));
            case Plan.Limit l -> parallel(l.child(), true, store, nowMicros, reads, ctx,
                    src -> LimitCursor.parallel(src, l.lo(), l.hi()),
                    c -> new LimitCursor(c, l.lo(), l.hi()));
            case Plan.SetOp s -> over(build(s.right(), store, nowMicros, reads, ctx),
                    r -> parallel(s.left(), true, store, nowMicros, reads, ctx,
                            src -> SetOpCursor.parallel(s.op(), src, r),
                            l -> new SetOpCursor(s.op(), l, r)));
            case Plan.Join j -> over(build(j.right(), store, nowMicros, reads, ctx),
                    r -> parallel(j.left(), j.on() == null || Functions.splittable(j.on()), store, nowMicros, reads,
                            ctx,
                            src -> JoinCursor.parallel(src, r, j.kind(), j.on(), nowMicros, j.fields()),
                            l -> JoinCursor.open(l, r, j.kind(), j.on(), nowMicros, j.fields())));
            case Plan.Window w -> parallel(w.child(), Functions.splittable(w.calls()), store, nowMicros, reads, ctx,
                    src -> WindowCursor.parallel(src, w, nowMicros),
                    c -> new WindowCursor(c, w, nowMicros));
            case Plan.CreateTable c -> throw new CdbException("cannot query a CREATE TABLE", -1);
            case Plan.Update u -> throw new CdbException("cannot query an UPDATE", -1);
            case Plan.Insert _ -> throw new CdbException("cannot query an INSERT", -1);
            case Plan.AddColumns _ -> throw new CdbException("cannot query an ALTER TABLE", -1);
            case Plan.SetTtl _ -> throw new CdbException("cannot query an ALTER TABLE", -1);
            case Plan.DropTable _, Plan.Truncate _, Plan.DropPartitions _, Plan.Delete _ ->
                    throw new CdbException("cannot query a DROP, TRUNCATE or DELETE", -1);
            case Plan.Explain(final Plan inner, final List<Field> fields) ->
                    new LinesCursor(fields, explain(inner, store, nowMicros).split("\n"));
        };
    }

    /** Segment {@code segment}'s rows, frame after frame, the frames ahead of the one read running on the pool. */
    private static Cursor exchange(
            final Plan segment,
            final Store store,
            final long nowMicros,
            final Map<Plan.Scan, boolean[]> reads,
            final QueryContext ctx
    ) {
        return new ExchangeCursor(FrameSource.of(segment, store, nowMicros, reads, ctx));
    }

    /**
     * The operator over {@code child}: built by {@code perFrame} from a {@link FrameSource} when {@code child} is a
     * segment and {@code splittable} says the operator's own expressions may run once per frame
     * ({@link Functions#splittable}), else by {@code serial} over the child's cursor, so a generator in them keeps the
     * operator on the calling thread over the child's rows in serial order. A failure to build closes what was opened
     * below.
     */
    private static Cursor parallel(
            final Plan child,
            final boolean splittable,
            final Store store,
            final long nowMicros,
            final Map<Plan.Scan, boolean[]> reads,
            final QueryContext ctx,
            final Function<FrameSource, Cursor> perFrame,
            final Function<Cursor, Cursor> serial
    ) {
        if (perFrame(splittable, child)) {
            final FrameSource source = FrameSource.of(child, store, nowMicros, reads, ctx);
            try {
                return perFrame.apply(source);
            } catch (final RuntimeException | Error e) {
                try {
                    source.close();
                } catch (final RuntimeException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
                throw e;
            }
        }
        return over(build(child, store, nowMicros, reads, ctx), serial);
    }

    /**
     * True when the operator over {@code child} runs per frame on the query's pool: {@code child} is a segment and the
     * operator's own expressions are {@code splittable}; else it runs over the child's cursor on the calling thread.
     */
    private static boolean perFrame(final boolean splittable, final Plan child) {
        return splittable && FrameSource.isSegment(child);
    }

    private static boolean splittable(final List<Bound> keys, final List<Bound.Agg> aggs) {
        return Functions.splittable(keys) && Functions.splittable(aggs);
    }

    /** Runs {@code plan}, a statement that changes the store; returns the rows it changed (0 for DDL). */
    public static long execute(final Plan.Change plan, final Store store, final long nowMicros) {
        return switch (plan) {
            case Plan.CreateTable c -> {
                store.create(c.def(), c.ifNotExists());
                yield 0;
            }
            case Plan.Update u -> UpdateRunner.run(u, store, nowMicros);
            case Plan.Insert i -> InsertRunner.run(i, store, nowMicros);
            case Plan.AddColumns a -> AlterRunner.addColumns(a, store);
            case Plan.SetTtl t -> AlterRunner.setTtl(t, store);
            case Plan.DropTable d -> DropRunner.dropTable(d, store);
            case Plan.Truncate t -> DropRunner.truncate(t, store);
            case Plan.DropPartitions d -> DropRunner.dropPartitions(d, store);
            case Plan.Delete d -> DeleteRunner.run(d, store, nowMicros);
        };
    }

    /**
     * {@code plan}'s text ({@link PlanText}) with the executor's choices as {@code -- } notes on each node's line: the
     * columns each scan reads ({@link ColumnUse}), whether each operator runs per frame on the query's pool or serially
     * on the calling thread, a sort read late ({@link LateReadCursor#applies}) and a join's build side. Nothing is
     * opened or run, a change included; every choice is made from the plan alone, so {@code store} and
     * {@code nowMicros} are not read.
     */
    public static String explain(final Plan plan, final Store store, final long nowMicros) {
        final Map<Plan, String> notes = new IdentityHashMap<>();
        noteStatement(plan, notes);
        return PlanText.of(plan, notes::get);
    }

    /** Notes a statement: a query, or the queries an {@code INSERT} reads, each with its own column use. */
    private static void noteStatement(final Plan plan, final Map<Plan, String> notes) {
        switch (plan) {
            case Plan.Explain(final Plan inner, final List<Field> _) -> noteStatement(inner, notes);
            case Plan.Insert(final String _, final int[] _, final List<Plan> sources) -> {
                for (final Plan source : sources) {
                    noteStatement(source, notes);
                }
            }
            case Plan.Change _ -> {
                // its runner reads the table itself
            }
            case Plan.Scan _, Plan.Source _, Plan.Filter _, Plan.Project _, Plan.Aggregate _, Plan.SampleBy _,
                 Plan.LatestOn _, Plan.Distinct _, Plan.Sort _, Plan.Limit _, Plan.SetOp _, Plan.Join _,
                 Plan.Window _ -> noteQuery(plan, ColumnUse.of(plan), notes);
        }
    }

    /** Notes {@code plan}'s nodes with the choices {@link #build} makes for them. */
    private static void noteQuery(
            final Plan plan,
            final Map<Plan.Scan, boolean[]> reads,
            final Map<Plan, String> notes
    ) {
        switch (plan) {
            case Plan.Scan s -> notes.put(s, "reads " + names(s.fields(), reads.get(s))
                    + (s.terms().isEmpty() ? "" : ", selects on " + names(s.fields(), termColumns(s)))
                    + ", frames on the pool");
            case Plan.Filter f when FrameSource.isSegment(f) -> {
                notes.put(f, "per frame on the pool");
                noteQuery(f.child(), reads, notes);
            }
            case Plan.Project p when FrameSource.isSegment(p) -> {
                notes.put(p, "per frame on the pool");
                noteQuery(p.child(), reads, notes);
            }
            case Plan.Source s -> notes.put(s, "serial");
            case Plan.Filter f -> {
                notes.put(f, "serial");
                noteQuery(f.child(), reads, notes);
            }
            case Plan.Project p -> {
                notes.put(p, "serial");
                noteQuery(p.child(), reads, notes);
            }
            case Plan.Aggregate a -> noteOperator(a, a.child(), splittable(a.keys(), a.aggs()), "", reads, notes);
            case Plan.SampleBy s -> noteOperator(s, s.child(), splittable(s.keys(), s.aggs()), "", reads, notes);
            case Plan.LatestOn l -> noteOperator(l, l.child(), true, "", reads, notes);
            case Plan.Distinct d -> noteOperator(d, d.child(), true, "", reads, notes);
            case Plan.Sort s when LateReadCursor.applies(s, reads) -> {
                notes.put(s, "parallel over frames, read late: the columns the keys and the filter do not need are"
                        + " read for the kept rows only");
                noteQuery(s.child(), reads, notes);
            }
            case Plan.Sort s -> noteOperator(s, s.child(), true, "", reads, notes);
            case Plan.Limit l -> noteOperator(l, l.child(), true, "", reads, notes);
            case Plan.SetOp s -> {
                noteOperator(s, s.left(), true, ", right read first", reads, notes);
                noteQuery(s.right(), reads, notes);
            }
            case Plan.Join j -> {
                noteOperator(j, j.left(), j.on() == null || Functions.splittable(j.on()),
                        ", build side right: read whole first, the left streams", reads, notes);
                noteQuery(j.right(), reads, notes);
            }
            case Plan.Window w -> noteOperator(w, w.child(), Functions.splittable(w.calls()), "", reads, notes);
            case Plan.Explain _, Plan.Change _ -> {
                // a statement, never below a query
            }
        }
    }

    private static void noteOperator(
            final Plan operator,
            final Plan child,
            final boolean splittable,
            final String more,
            final Map<Plan.Scan, boolean[]> reads,
            final Map<Plan, String> notes
    ) {
        notes.put(operator, (perFrame(splittable, child) ? "parallel over frames" : "serial") + more);
        noteQuery(child, reads, notes);
    }

    /** The names of {@code fields} where {@code pick} is true, all of them when it is null: {@code [ts, v]}. */
    private static String names(final List<Field> fields, final boolean[] pick) {
        final StringBuilder sink = new StringBuilder().append('[');
        for (int i = 0; i < fields.size(); i++) {
            if (pick == null || pick[i]) {
                sink.append(sink.length() > 1 ? ", " : "").append(fields.get(i).name());
            }
        }
        return sink.append(']').toString();
    }

    /** The columns {@code scan}'s pre-filter terms select on, read from the partition for the selection only. */
    private static boolean[] termColumns(final Plan.Scan scan) {
        final boolean[] columns = new boolean[scan.fields().size()];
        for (final Plan.ScanTerm term : scan.terms()) {
            columns[term.column()] = true;
        }
        return columns;
    }

    /** True when {@code plan} changes the store rather than returning rows. */
    public static boolean changesStore(final Plan plan) {
        return plan instanceof Plan.Change;
    }

    /**
     * The cursor {@code build} makes over {@code child}; when building fails, {@code child} is closed before the
     * failure propagates, so a table reader opened below never outlives a query that could not be planned to the end.
     */
    static Cursor over(final Cursor child, final Function<Cursor, Cursor> build) {
        try {
            return build.apply(child);
        } catch (final RuntimeException | Error e) {
            try {
                child.close();
            } catch (final RuntimeException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    /** {@code filter} over {@code child}, its predicate compiled for this cursor alone. */
    static Cursor filter(final Plan.Filter filter, final Cursor child, final long nowMicros) {
        return new FilterCursor(child, Functions.compile(filter.predicate(), nowMicros));
    }

    /** {@code project} over {@code child}, its expressions compiled for this cursor alone. */
    static Cursor project(final Plan.Project project, final Cursor child, final long nowMicros) {
        return new ProjectCursor(child, compileAll(project.exprs(), nowMicros), project.fields());
    }

    private static List<VectorFn> compileAll(final List<Bound> exprs, final long nowMicros) {
        final List<VectorFn> out = new ArrayList<>(exprs.size());
        for (final Bound e : exprs) {
            out.add(Functions.compile(e, nowMicros));
        }
        return out;
    }

    /** One VARCHAR row per line, in pages of at most {@link Page#CAPACITY} rows: the rows of an {@code EXPLAIN}. */
    private static final class LinesCursor implements Cursor {

        private final List<Field> fields;
        private final String[] lines;
        private int next;

        LinesCursor(final List<Field> fields, final String[] lines) {
            this.fields = fields;
            this.lines = lines;
        }

        @Override
        public List<Field> fields() {
            return fields;
        }

        @Override
        public Page next() {
            if (next >= lines.length) {
                return null;
            }
            final int size = Math.min(Page.CAPACITY, lines.length - next);
            final Vector column = Vector.of(CdbType.VARCHAR, size);
            for (int i = 0; i < size; i++) {
                column.setText(i, lines[next + i]);
            }
            next += size;
            return new Page(new Vector[] {column}, size);
        }

        @Override
        public void close() {
            next = lines.length;
        }
    }
}
