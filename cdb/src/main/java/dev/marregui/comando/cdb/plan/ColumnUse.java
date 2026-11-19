package dev.marregui.comando.cdb.plan;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Field;

/**
 * Which columns of each {@link Plan.Scan} a query reads: a pass from the root down, each node asking its child for the
 * columns it passes on that its parent reads, plus the ones its own expressions, keys and orderings read. A column no
 * ancestor reads is not read from the store; its values in the scan's pages are left unset. Nodes whose output
 * depends on every input column (DISTINCT, set operations, joins, windows, sources) ask for all of them, and so does
 * the root. The scans of a filter's IN subqueries are in the answer too, read as the subquery's own query reads them.
 */
public final class ColumnUse {

    private ColumnUse() {
    }

    /** Per scan of {@code plan} (by identity), its positions read, {@code true} where a column is read. */
    public static Map<Plan.Scan, boolean[]> of(final Plan plan) {
        final Map<Plan.Scan, boolean[]> reads = new IdentityHashMap<>();
        visit(plan, all(plan.fields().size()), reads);
        return reads;
    }

    private static void visit(final Plan plan, final boolean[] used, final Map<Plan.Scan, boolean[]> reads) {
        switch (plan) {
            case Plan.Scan s -> {
                // a scan reached twice reads what either of its parents reads
                final boolean[] seen = reads.get(s);
                if (seen != null) {
                    for (int i = 0; i < used.length; i++) {
                        seen[i] |= used[i];
                    }
                } else {
                    reads.put(s, used.clone());
                }
            }
            case Plan.Filter(final Plan child, final Bound predicate) -> {
                final boolean[] need = used.clone();
                mark(predicate, need);
                visit(child, need, reads);
                subqueries(predicate, reads);
            }
            case Plan.Project(final Plan child, final List<Bound> exprs, final List<Field> _) -> {
                // every expression is evaluated, read or not, so every one of them reads its columns
                final boolean[] need = new boolean[child.fields().size()];
                mark(exprs, need);
                visit(child, need, reads);
            }
            case Plan.Aggregate(final Plan child, final List<Bound> keys, final List<Bound.Agg> aggs,
                                final List<Field> _) -> {
                final boolean[] need = new boolean[child.fields().size()];
                mark(keys, need);
                mark(aggs, need);
                visit(child, need, reads);
            }
            case Plan.SampleBy s -> {
                final boolean[] need = new boolean[s.child().fields().size()];
                need[s.timestampColumn()] = true;
                mark(s.keys(), need);
                mark(s.aggs(), need);
                visit(s.child(), need, reads);
            }
            case Plan.LatestOn(final Plan child, final int timestampColumn, final int[] partitionColumns,
                               final boolean _) -> {
                final boolean[] need = used.clone();
                need[timestampColumn] = true;
                for (final int c : partitionColumns) {
                    need[c] = true;
                }
                visit(child, need, reads);
            }
            case Plan.Sort(final Plan child, final List<Plan.SortKey> keys, final long _) -> {
                final boolean[] need = used.clone();
                for (final Plan.SortKey k : keys) {
                    need[k.column()] = true;
                }
                visit(child, need, reads);
            }
            case Plan.Limit(final Plan child, final long _, final long _) -> visit(child, used, reads);
            case Plan.Distinct(final Plan child) -> visit(child, all(child.fields().size()), reads);
            case Plan.SetOp(final String _, final Plan left, final Plan right) -> {
                visit(left, all(left.fields().size()), reads);
                visit(right, all(right.fields().size()), reads);
            }
            case Plan.Join(final Plan left, final Plan right, final String _, final Bound _, final List<Field> _) -> {
                visit(left, all(left.fields().size()), reads);
                visit(right, all(right.fields().size()), reads);
            }
            case Plan.Window(final Plan child, final List<Bound.WindowCall> _, final List<Field> _) ->
                    visit(child, all(child.fields().size()), reads);
            case Plan.Source _, Plan.CreateTable _, Plan.Update _, Plan.Insert _, Plan.AddColumns _,
                 Plan.SetTtl _, Plan.DropTable _, Plan.Truncate _, Plan.DropPartitions _, Plan.Delete _,
                 Plan.DropColumn _, Plan.RenameColumn _, Plan.Backup _, Plan.Explain _ -> {
                // no scan below, or a change whose runner reads what it needs itself
            }
        }
    }

    /** Visits the plan of each IN subquery {@code b} holds, from its root, which reads its one column. */
    private static void subqueries(final Bound b, final Map<Plan.Scan, boolean[]> reads) {
        for (final Bound.Subquery s : Bound.Subquery.of(b)) {
            visit(s.plan(), all(s.plan().fields().size()), reads);
        }
    }

    private static void mark(final List<? extends Bound> exprs, final boolean[] need) {
        for (final Bound b : exprs) {
            mark(b, need);
        }
    }

    private static void mark(final Bound bound, final boolean[] need) {
        switch (bound) {
            case Bound.Column(final int index, final CdbType _) -> need[index] = true;
            case Bound.Constant _ -> {
            }
            case Bound.Call(final String _, final List<Bound> args, final CdbType _) -> mark(args, need);
            case Bound.Agg(final String _, final List<Bound> args, final boolean _, final CdbType _) -> mark(args, need);
            case Bound.WindowCall w -> {
                mark(w.args(), need);
                mark(w.partitionBy(), need);
                for (final Plan.SortKey k : w.orderBy()) {
                    need[k.column()] = true;
                }
            }
        }
    }

    private static boolean[] all(final int n) {
        final boolean[] used = new boolean[n];
        Arrays.fill(used, true);
        return used;
    }

}
