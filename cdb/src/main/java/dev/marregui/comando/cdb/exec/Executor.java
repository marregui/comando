package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.catalog.Catalog;
import dev.marregui.comando.cdb.exec.expr.Functions;
import dev.marregui.comando.cdb.exec.expr.VectorFn;
import dev.marregui.comando.cdb.plan.Bound;
import dev.marregui.comando.cdb.plan.ColumnUse;
import dev.marregui.comando.cdb.plan.Plan;
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
        return build(plan, store, nowMicros, ColumnUse.of(plan));
    }

    private static Cursor build(
            final Plan plan,
            final Store store,
            final long nowMicros,
            final Map<Plan.Scan, boolean[]> reads
    ) {
        return switch (plan) {
            case Plan.Scan s -> new ScanCursor(s, store.reader(s.table()), reads.get(s));
            case Plan.Source s -> Catalog.open(s, store);
            case Plan.Filter f -> over(build(f.child(), store, nowMicros, reads),
                    c -> new FilterCursor(c, Functions.compile(f.predicate(), nowMicros)));
            case Plan.Project p -> over(build(p.child(), store, nowMicros, reads),
                    c -> new ProjectCursor(c, compileAll(p.exprs(), nowMicros), p.fields()));
            case Plan.Aggregate a -> new GroupByCursor(build(a.child(), store, nowMicros, reads), a, nowMicros);
            case Plan.SampleBy s -> new SampleByCursor(build(s.child(), store, nowMicros, reads), s, nowMicros);
            case Plan.LatestOn l -> new LatestOnCursor(build(l.child(), store, nowMicros, reads), l);
            case Plan.Distinct d -> new DistinctCursor(build(d.child(), store, nowMicros, reads));
            case Plan.Sort s -> new SortCursor(build(s.child(), store, nowMicros, reads), s.keys(), s.limit());
            case Plan.Limit l -> new LimitCursor(build(l.child(), store, nowMicros, reads), l.lo(), l.hi());
            case Plan.SetOp s -> over(build(s.left(), store, nowMicros, reads),
                    l -> over(build(s.right(), store, nowMicros, reads), r -> new SetOpCursor(s.op(), l, r)));
            case Plan.Join j -> over(build(j.left(), store, nowMicros, reads),
                    l -> over(build(j.right(), store, nowMicros, reads),
                            r -> JoinCursor.open(l, r, j.kind(), j.on(), nowMicros, j.fields())));
            case Plan.Window w -> new WindowCursor(build(w.child(), store, nowMicros, reads), w, nowMicros);
            case Plan.CreateTable c -> throw new CdbException("cannot query a CREATE TABLE", -1);
            case Plan.Update u -> throw new CdbException("cannot query an UPDATE", -1);
            case Plan.Insert _ -> throw new CdbException("cannot query an INSERT", -1);
            case Plan.AddColumns _ -> throw new CdbException("cannot query an ALTER TABLE", -1);
            case Plan.SetTtl _ -> throw new CdbException("cannot query an ALTER TABLE", -1);
        };
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
        };
    }

    /** True when {@code plan} changes the store rather than returning rows. */
    public static boolean changesStore(final Plan plan) {
        return plan instanceof Plan.Change;
    }

    /**
     * The cursor {@code build} makes over {@code child}; when building fails, {@code child} is closed before the
     * failure propagates, so a table reader opened below never outlives a query that could not be planned to the end.
     */
    private static Cursor over(final Cursor child, final Function<Cursor, Cursor> build) {
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

    private static List<VectorFn> compileAll(final List<Bound> exprs, final long nowMicros) {
        final List<VectorFn> out = new ArrayList<>(exprs.size());
        for (final Bound e : exprs) {
            out.add(Functions.compile(e, nowMicros));
        }
        return out;
    }
}
