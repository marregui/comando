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

/// Test helper: the serial cursor tree of a query plan, every operator built over its child's cursor on the calling
/// thread as before frames existed, each scan reading the whole table in one pass. The oracle the parallel answers
/// are compared with; kept apart from [Executor] so a fault in its routing cannot hide in the oracle.
final class SerialPlans {

    private SerialPlans() {
    }

    /// The serial rows of `plan`; the caller closes the cursor.
    static Cursor open(final Plan plan, final Store store, final long nowMicros) {
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
            case Plan.Aggregate a -> over(build(a.child(), store, nowMicros, reads),
                    c -> new GroupByCursor(c, a, nowMicros));
            case Plan.SampleBy s -> over(build(s.child(), store, nowMicros, reads),
                    c -> new SampleByCursor(c, s, nowMicros));
            case Plan.LatestOn l -> over(build(l.child(), store, nowMicros, reads), c -> new LatestOnCursor(c, l));
            case Plan.Distinct d -> over(build(d.child(), store, nowMicros, reads), DistinctCursor::new);
            case Plan.Sort s -> over(build(s.child(), store, nowMicros, reads),
                    c -> new SortCursor(c, s.keys(), s.limit()));
            case Plan.Limit l -> over(build(l.child(), store, nowMicros, reads),
                    c -> new LimitCursor(c, l.lo(), l.hi()));
            case Plan.SetOp s -> over(build(s.right(), store, nowMicros, reads),
                    r -> over(build(s.left(), store, nowMicros, reads), l -> new SetOpCursor(s.op(), l, r)));
            case Plan.Join j -> over(build(j.right(), store, nowMicros, reads),
                    r -> over(build(j.left(), store, nowMicros, reads),
                            l -> JoinCursor.open(l, r, j.kind(), j.on(), nowMicros, j.fields())));
            case Plan.Window w -> over(build(w.child(), store, nowMicros, reads),
                    c -> new WindowCursor(c, w, nowMicros));
            case Plan.CreateTable _, Plan.Update _, Plan.Insert _, Plan.AddColumns _, Plan.SetTtl _,
                 Plan.DropTable _, Plan.Truncate _, Plan.DropPartitions _, Plan.Delete _ ->
                    throw new CdbException("cannot query a statement that changes the store", -1);
            case Plan.Explain _ -> throw new CdbException("cannot query an EXPLAIN serially", -1);
        };
    }

    // the cursor over child; a failure to build it closes child
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
