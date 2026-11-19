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
import dev.marregui.comando.cdb.exec.expr.InSet;
import dev.marregui.comando.cdb.exec.expr.VectorFn;
import dev.marregui.comando.cdb.plan.Bound;
import dev.marregui.comando.cdb.plan.ColumnUse;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.plan.PlanText;
import dev.marregui.comando.cdb.store.Store;

/** Builds the cursor tree of a query plan, and runs the plans that change the store. */
public final class Executor {

    /**
     * The most rows an IN subquery's set holds: the most a result holds (the server's row limit, which a test in its
     * package ties to this one), as the set is held whole in memory.
     */
    public static final int IN_SUBQUERY_MAX_ROWS = 1_000_000;

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
     * any other input it runs as before, on the calling thread. The query's IN subqueries run here, each once, before
     * the cursor is returned.
     */
    public static Cursor open(final Plan plan, final Store store, final long nowMicros, final QueryContext ctx) {
        final Plan ran = runSubqueries(plan, store, nowMicros, ctx);
        return build(ran, store, nowMicros, ColumnUse.of(ran), ctx);
    }

    /**
     * {@code plan} with the IN subquery of each of its filters run and replaced by the set of its values
     * ({@link InSet}), which every frame's compiled filter shares; {@code plan} itself when it has none. A subquery
     * runs in {@code ctx}, and runs its own subqueries first; past {@link #IN_SUBQUERY_MAX_ROWS} rows it fails.
     */
    private static Plan runSubqueries(
            final Plan plan,
            final Store store,
            final long nowMicros,
            final QueryContext ctx
    ) {
        return switch (plan) {
            case Plan.Scan _, Plan.Source _, Plan.Explain _, Plan.Change _ -> plan;
            case Plan.Filter(final Plan child, final Bound predicate) -> {
                final Plan c = runSubqueries(child, store, nowMicros, ctx);
                final Bound p = runSubqueries(predicate, store, nowMicros, ctx);
                yield c == child && p == predicate ? plan : new Plan.Filter(c, p);
            }
            case Plan.Project(final Plan child, final List<Bound> exprs, final List<Field> fields) -> {
                final Plan c = runSubqueries(child, store, nowMicros, ctx);
                yield c == child ? plan : new Plan.Project(c, exprs, fields);
            }
            case Plan.Aggregate(final Plan child, final List<Bound> keys, final List<Bound.Agg> aggs,
                                final List<Field> fields) -> {
                final Plan c = runSubqueries(child, store, nowMicros, ctx);
                yield c == child ? plan : new Plan.Aggregate(c, keys, aggs, fields);
            }
            case Plan.SampleBy(final Plan child, final int timestampColumn, final long count, final String unit,
                               final List<Bound> keys, final List<Bound.Agg> aggs, final List<String> fill,
                               final List<Field> fields) -> {
                final Plan c = runSubqueries(child, store, nowMicros, ctx);
                yield c == child ? plan : new Plan.SampleBy(c, timestampColumn, count, unit, keys, aggs, fill, fields);
            }
            case Plan.LatestOn(final Plan child, final int timestampColumn, final int[] partitionColumns,
                               final boolean newestFirst) -> {
                final Plan c = runSubqueries(child, store, nowMicros, ctx);
                yield c == child ? plan : new Plan.LatestOn(c, timestampColumn, partitionColumns, newestFirst);
            }
            case Plan.Distinct(final Plan child) -> {
                final Plan c = runSubqueries(child, store, nowMicros, ctx);
                yield c == child ? plan : new Plan.Distinct(c);
            }
            case Plan.Sort(final Plan child, final List<Plan.SortKey> keys, final long limit) -> {
                final Plan c = runSubqueries(child, store, nowMicros, ctx);
                yield c == child ? plan : new Plan.Sort(c, keys, limit);
            }
            case Plan.Limit(final Plan child, final long lo, final long hi) -> {
                final Plan c = runSubqueries(child, store, nowMicros, ctx);
                yield c == child ? plan : new Plan.Limit(c, lo, hi);
            }
            case Plan.SetOp(final String op, final Plan left, final Plan right) -> {
                final Plan l = runSubqueries(left, store, nowMicros, ctx);
                final Plan r = runSubqueries(right, store, nowMicros, ctx);
                yield l == left && r == right ? plan : new Plan.SetOp(op, l, r);
            }
            case Plan.Join(final Plan left, final Plan right, final String kind, final Bound on,
                           final List<Field> fields) -> {
                final Plan l = runSubqueries(left, store, nowMicros, ctx);
                final Plan r = runSubqueries(right, store, nowMicros, ctx);
                yield l == left && r == right ? plan : new Plan.Join(l, r, kind, on, fields);
            }
            case Plan.Window(final Plan child, final List<Bound.WindowCall> calls, final List<Field> fields) -> {
                final Plan c = runSubqueries(child, store, nowMicros, ctx);
                yield c == child ? plan : new Plan.Window(c, calls, fields);
            }
        };
    }

    /** {@code b} with each {@code in_query} call's subquery run into its set; {@code b} itself when it has none. */
    private static Bound runSubqueries(final Bound b, final Store store, final long nowMicros, final QueryContext ctx) {
        if (!(b instanceof Bound.Call(final String function, final List<Bound> args, final CdbType type))) {
            return b;
        }
        if ("in_query".equals(function)
                && args.getLast() instanceof Bound.Constant(final Bound.Subquery query, final CdbType valueType)) {
            // the operand may hold an IN subquery of its own
            final Bound operand = runSubqueries(args.getFirst(), store, nowMicros, ctx);
            final InSet set;
            try (Cursor rows = open(query.plan(), store, nowMicros, ctx)) {
                set = InSet.read(rows, operand.type(), nowMicros, IN_SUBQUERY_MAX_ROWS, query.pos());
            }
            return new Bound.Call(function, List.of(operand, new Bound.Constant(set, valueType)), type);
        }
        final List<Bound> ran = runSubqueries(args, store, nowMicros, ctx);
        return ran == args ? b : new Bound.Call(function, ran, type);
    }

    /** {@code bs} with each one's subqueries run; {@code bs} itself when none had any. */
    private static List<Bound> runSubqueries(
            final List<Bound> bs,
            final Store store,
            final long nowMicros,
            final QueryContext ctx
    ) {
        List<Bound> out = null;
        for (int i = 0; i < bs.size(); i++) {
            final Bound ran = runSubqueries(bs.get(i), store, nowMicros, ctx);
            if (ran != bs.get(i) && out == null) {
                out = new ArrayList<>(bs);
            }
            if (out != null) {
                out.set(i, ran);
            }
        }
        return out == null ? bs : List.copyOf(out);
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
            case Plan.DropColumn _, Plan.RenameColumn _, Plan.Backup _ ->
                    throw new CdbException("cannot query an ALTER TABLE or a BACKUP", -1);
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

    /**
     * Runs {@code plan}, a statement that changes the store; returns the rows it changed (0 for DDL). The IN
     * subqueries of an {@code UPDATE} or {@code DELETE} run first, each once, on snapshots they take before the
     * statement takes the table's writer: a commit between the two is not in the subquery's set.
     */
    public static long execute(final Plan.Change plan, final Store store, final long nowMicros) {
        final Plan.Change ran = runSubqueries(plan, store, nowMicros);
        return switch (ran) {
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
            case Plan.DropColumn d -> ColumnRunner.dropColumn(d, store);
            case Plan.RenameColumn r -> ColumnRunner.renameColumn(r, store);
            case Plan.Backup b -> BackupRunner.run(b, store);
        };
    }

    /** {@code change} with the IN subqueries of an {@code UPDATE}'s or {@code DELETE}'s expressions run. */
    private static Plan.Change runSubqueries(final Plan.Change change, final Store store, final long nowMicros) {
        final QueryContext ctx = QueryContext.uncancellable();
        return switch (change) {
            case Plan.Update(final String table, final int[] columns, final List<Bound> values, final Bound where) ->
                    new Plan.Update(table, columns, runSubqueries(values, store, nowMicros, ctx),
                            where == null ? null : runSubqueries(where, store, nowMicros, ctx));
            case Plan.Delete(final String table, final Bound where) ->
                    where == null ? change : new Plan.Delete(table, runSubqueries(where, store, nowMicros, ctx));
            case Plan.CreateTable _, Plan.Insert _, Plan.AddColumns _, Plan.SetTtl _, Plan.DropTable _,
                 Plan.Truncate _, Plan.DropPartitions _, Plan.DropColumn _, Plan.RenameColumn _, Plan.Backup _ ->
                    change;
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
            case Plan.Update(final String _, final int[] _, final List<Bound> values, final Bound where) -> {
                for (final Bound v : values) {
                    noteSubqueries(v, notes);
                }
                if (where != null) {
                    noteSubqueries(where, notes);
                }
            }
            case Plan.Delete(final String _, final Bound where) -> {
                if (where != null) {
                    noteSubqueries(where, notes);
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
                noteSubqueries(f.predicate(), reads, notes);
            }
            case Plan.Project p when FrameSource.isSegment(p) -> {
                notes.put(p, "per frame on the pool");
                noteQuery(p.child(), reads, notes);
            }
            case Plan.Source s -> notes.put(s, "serial");
            case Plan.Filter f -> {
                notes.put(f, "serial");
                noteQuery(f.child(), reads, notes);
                noteSubqueries(f.predicate(), reads, notes);
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

    /** Notes the plans of a statement expression's IN subqueries, each a statement of its own. */
    private static void noteSubqueries(final Bound b, final Map<Plan, String> notes) {
        for (final Bound.Subquery s : Bound.Subquery.of(b)) {
            noteStatement(s.plan(), notes);
        }
    }

    /** Notes the plans of a filter's IN subqueries, whose scans are in {@code reads} ({@link ColumnUse}). */
    private static void noteSubqueries(
            final Bound predicate,
            final Map<Plan.Scan, boolean[]> reads,
            final Map<Plan, String> notes
    ) {
        for (final Bound.Subquery s : Bound.Subquery.of(predicate)) {
            noteQuery(s.plan(), reads, notes);
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
