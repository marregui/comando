package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.catalog.Catalog;
import dev.marregui.comando.cdb.exec.expr.Functions;
import dev.marregui.comando.cdb.exec.expr.VectorFn;
import dev.marregui.comando.cdb.plan.Bound;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.Store;

/** Builds the cursor tree of a query plan, and runs the plans that change the store. */
public final class Executor {

    private Executor() {
    }

    /** The rows of {@code plan}, a query; the caller closes the cursor. */
    public static Cursor open(final Plan plan, final Store store, final long nowMicros) {
        return switch (plan) {
            case Plan.Scan s -> new ScanCursor(s, store.reader(s.table()));
            case Plan.Source s -> Catalog.open(s, store);
            case Plan.Filter f -> over(open(f.child(), store, nowMicros),
                    c -> new FilterCursor(c, Functions.compile(f.predicate(), nowMicros)));
            case Plan.Project p -> over(open(p.child(), store, nowMicros),
                    c -> new ProjectCursor(c, compileAll(p.exprs(), nowMicros), p.fields()));
            case Plan.Aggregate a -> new GroupByCursor(open(a.child(), store, nowMicros), a, nowMicros);
            case Plan.SampleBy s -> new SampleByCursor(open(s.child(), store, nowMicros), s, nowMicros);
            case Plan.LatestOn l -> new LatestOnCursor(open(l.child(), store, nowMicros), l);
            case Plan.Distinct d -> new DistinctCursor(open(d.child(), store, nowMicros));
            case Plan.Sort s -> new SortCursor(open(s.child(), store, nowMicros), s.keys());
            case Plan.Limit l -> new LimitCursor(open(l.child(), store, nowMicros), l.lo(), l.hi());
            case Plan.SetOp s -> over(open(s.left(), store, nowMicros), l -> over(open(s.right(), store, nowMicros),
                    r -> new SetOpCursor(s.op(), l, r)));
            case Plan.Join j -> over(open(j.left(), store, nowMicros), l -> over(open(j.right(), store, nowMicros),
                    r -> JoinCursor.open(l, r, j.kind(), j.on(), nowMicros, j.fields())));
            case Plan.Window w -> new WindowCursor(open(w.child(), store, nowMicros), w, nowMicros);
            case Plan.CreateTable c -> throw new CdbException("cannot query a CREATE TABLE", -1);
            case Plan.Update u -> throw new CdbException("cannot query an UPDATE", -1);
        };
    }

    /** Runs {@code plan}, a statement that changes the store; returns the rows it changed (0 for DDL). */
    public static long execute(final Plan plan, final Store store, final long nowMicros) {
        return switch (plan) {
            case Plan.CreateTable c -> {
                store.create(c.def(), c.ifNotExists());
                yield 0;
            }
            case Plan.Update u -> UpdateRunner.run(u, store, nowMicros);
            default -> throw new CdbException("not a statement that changes the store", -1);
        };
    }

    /** True when {@code plan} changes the store rather than returning rows. */
    public static boolean changesStore(final Plan plan) {
        return plan instanceof Plan.CreateTable || plan instanceof Plan.Update;
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
