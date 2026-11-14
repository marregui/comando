package dev.marregui.comando.cdb.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Field;
import dev.marregui.comando.cdb.sql.Ast;

/**
 * One SELECT, in QuestDB's order: FROM, WHERE (its designated-timestamp bounds also on the scan), LATEST ON, then
 * SAMPLE BY or GROUP BY (implicit: the selected expressions that read a column and hold no aggregate) or window calls,
 * the projection, DISTINCT, ORDER BY (an output name, a position, a selected expression, or any expression of the
 * input, projected and dropped after the sort), LIMIT.
 */
final class SelectPlanner {

    private static final Predicate<Ast.Call> AGGREGATE = c -> c.over() == null
            && (Signatures.isAggregate(c.name()) || c.star());
    private static final Predicate<Ast.Call> WINDOW = c -> c.over() != null;

    private final Planner planner;
    private final Ast.Select select;
    private final Map<String, Ast.QueryBody> ctes;

    SelectPlanner(final Planner planner, final Ast.Select select, final Map<String, Ast.QueryBody> ctes) {
        this.planner = planner;
        this.select = select;
        this.ctes = ctes;
    }

    Rel plan() {
        Rel in = select.from() == null
                ? new Rel(new Plan.Source("one_row", List.of(), List.of()), List.of(), -1)
                : planner.from(select.from(), ctes);
        if (select.where() != null) {
            in = where(in);
        }
        if (select.latestOn() != null) {
            in = latestOn(in);
        }
        final List<Item> items = items(in);
        final List<Ast.Expr> exprs = new ArrayList<>();
        for (final Item i : items) {
            exprs.add(i.expr());
        }
        for (final Ast.OrderItem o : select.orderBy()) {
            exprs.add(o.expr());
        }
        final boolean grouped = select.sampleBy() != null || !select.groupBy().isEmpty()
                || !calls(exprs, AGGREGATE).isEmpty();
        final List<Ast.Call> windows = calls(exprs, WINDOW);
        if (grouped && !windows.isEmpty()) {
            throw new CdbException("cannot mix window functions with aggregates", select.pos());
        }
        final Stage stage;
        if (grouped) {
            stage = aggregate(in, items, calls(exprs, AGGREGATE));
        } else if (!windows.isEmpty()) {
            stage = window(in, windows);
        } else {
            stage = new Stage(in, null);
        }
        return project(stage, items);
    }

    /** The predicate as a filter; on a table scan, its bounds on the designated timestamp also bound the scan. */
    private Rel where(final Rel in) {
        final Bound predicate = new Binder(in, null).bindValue(select.where());
        if (predicate.type() != CdbType.BOOLEAN) {
            throw new CdbException("cannot filter by non-boolean condition [type=" + predicate.type() + ']',
                    select.pos());
        }
        Plan child = in.plan();
        if (child instanceof Plan.Scan(final String table, final int[] columns, final long from, final long to,
                                       final List<Field> fields) && in.timestamp() >= 0) {
            final long[] range = {from, to};
            Pruning.narrow(range, predicate, in.timestamp(), planner.nowMicros());
            child = new Plan.Scan(table, columns, range[0], range[1], fields);
        }
        return new Rel(new Plan.Filter(child, predicate), in.cols(), in.timestamp());
    }

    private Rel latestOn(final Rel in) {
        final Ast.LatestOn latest = select.latestOn();
        final int ts = in.resolve(List.of(latest.timestamp()), select.pos());
        if (in.cols().get(ts).type() != CdbType.TIMESTAMP) {
            throw new CdbException("cannot take latest on non-timestamp column [column=" + latest.timestamp() + ']',
                    select.pos());
        }
        final int[] partition = new int[latest.partitionBy().size()];
        for (int i = 0; i < partition.length; i++) {
            partition[i] = in.resolve(List.of(latest.partitionBy().get(i)), select.pos());
        }
        return new Rel(new Plan.LatestOn(in.plan(), ts, partition), in.cols(), in.timestamp());
    }

    /** The select list, {@code *} and {@code t.*} expanded to the columns they cover. */
    private List<Item> items(final Rel in) {
        final List<Item> items = new ArrayList<>();
        for (final Ast.SelectItem s : select.items()) {
            if (s.expr() instanceof Ast.Star(final String qualifier, final int pos)) {
                final int before = items.size();
                for (final Rel.Col c : in.cols()) {
                    if (qualifier == null || qualifier.equalsIgnoreCase(c.qualifier())) {
                        final List<String> parts = c.qualifier() == null ? List.of(c.name())
                                : List.of(c.qualifier(), c.name());
                        items.add(new Item(new Ast.Ident(parts, pos), c.name(), false));
                    }
                }
                if (items.size() == before) {
                    throw new CdbException("cannot expand * of unknown table [table=" + qualifier + ']', pos);
                }
                continue;
            }
            final boolean aliased = s.alias() != null;
            items.add(new Item(s.expr(), aliased ? s.alias() : Names.of(s.expr()), aliased));
        }
        return items;
    }

    /**
     * {@code SAMPLE BY} or {@code GROUP BY}: keys, then each distinct aggregate call; above it the select list reads
     * them back by their text.
     */
    private Stage aggregate(final Rel in, final List<Item> items, final List<Ast.Call> calls) {
        final Ast.SampleBy sample = select.sampleBy();
        if (sample != null && in.timestamp() < 0) {
            throw new CdbException("cannot sample without designated timestamp", select.pos());
        }
        final Binder binder = new Binder(in, null);
        final List<Ast.Expr> keyExprs = keyExprs(items);
        final List<Bound> keys = new ArrayList<>();
        final List<String> keyTexts = new ArrayList<>();
        final List<Field> fields = new ArrayList<>();
        if (sample != null) {
            fields.add(new Field(in.cols().get(in.timestamp()).name(), CdbType.TIMESTAMP));
        }
        for (final Ast.Expr k : keyExprs) {
            final Bound b = binder.bindValue(k);
            if (sample != null && b instanceof Bound.Column(final int index, final CdbType _)
                    && index == in.timestamp()) {
                continue;
            }
            final String text = Names.text(k, true);
            if (keyTexts.contains(text)) {
                continue;
            }
            keys.add(b);
            keyTexts.add(text);
            fields.add(new Field(Names.text(k, false), b.type()));
        }
        final List<Bound.Agg> aggs = new ArrayList<>();
        final List<String> aggTexts = new ArrayList<>();
        for (final Ast.Call c : calls) {
            final String text = Names.text(c, true);
            if (aggTexts.contains(text)) {
                continue;
            }
            final Bound.Agg agg = agg(binder, c);
            aggs.add(agg);
            aggTexts.add(text);
            fields.add(new Field(Names.text(c, false), agg.type()));
        }
        final Plan plan = sample == null
                ? new Plan.Aggregate(in.plan(), List.copyOf(keys), List.copyOf(aggs), List.copyOf(fields))
                : new Plan.SampleBy(in.plan(), in.timestamp(), sample.count(), sample.unit(), List.copyOf(keys),
                List.copyOf(aggs), List.copyOf(sample.fill()), List.copyOf(fields));
        final int offset = sample == null ? 0 : 1;
        final Rel out = Rel.of(plan, null, sample == null ? -1 : 0);
        final Function<Ast.Expr, Bound> hook = expr -> {
            final String text = Names.text(expr, true);
            final int k = keyTexts.indexOf(text);
            if (k >= 0) {
                return new Bound.Column(offset + k, keys.get(k).type());
            }
            if (expr instanceof Ast.Call c && AGGREGATE.test(c)) {
                final int a = aggTexts.indexOf(text);
                return new Bound.Column(offset + keys.size() + a, aggs.get(a).type());
            }
            if (expr instanceof Ast.Ident(final List<String> parts, final int pos)) {
                final int j = in.find(parts, pos);
                if (sample != null && j == in.timestamp()) {
                    return new Bound.Column(0, CdbType.TIMESTAMP);
                }
                for (int i = 0; i < keys.size(); i++) {
                    if (keys.get(i) instanceof Bound.Column(final int index, final CdbType type) && index == j) {
                        return new Bound.Column(offset + i, type);
                    }
                }
                throw new CdbException("cannot select column outside GROUP BY [column=" + String.join(".", parts)
                        + ']', pos);
            }
            return null;
        };
        return new Stage(out, hook);
    }

    /** GROUP BY's items by alias, position or expression; without GROUP BY, the selected column expressions. */
    private List<Ast.Expr> keyExprs(final List<Item> items) {
        final List<Ast.Expr> keys = new ArrayList<>();
        if (select.groupBy().isEmpty()) {
            for (final Item i : items) {
                if (calls(List.of(i.expr()), AGGREGATE).isEmpty() && readsColumn(i.expr())) {
                    keys.add(i.expr());
                }
            }
            return keys;
        }
        for (final Ast.Expr g : select.groupBy()) {
            keys.add(groupItem(g, items));
        }
        return keys;
    }

    private static Ast.Expr groupItem(final Ast.Expr g, final List<Item> items) {
        if (g instanceof Ast.Literal(final Long n, final int pos)) {
            if (n < 1 || n > items.size()) {
                throw new CdbException("cannot group by position out of range [position=" + n + ']', pos);
            }
            return items.get((int) (n - 1)).expr();
        }
        if (g instanceof Ast.Ident(final List<String> parts, final int _) && parts.size() == 1) {
            for (final Item i : items) {
                if (i.aliased() && i.name().equalsIgnoreCase(parts.getFirst())) {
                    return i.expr();
                }
            }
        }
        return g;
    }

    private static Bound.Agg agg(final Binder binder, final Ast.Call c) {
        final String function = c.distinct() && "count".equals(c.name()) ? "count_distinct" : c.name();
        if (!Signatures.isAggregate(function)) {
            throw new CdbException("cannot call unknown aggregate [function=" + c.name() + ']', c.pos());
        }
        final List<Bound> args = new ArrayList<>(c.args().size());
        final List<CdbType> types = new ArrayList<>(c.args().size());
        if (!c.star()) {
            for (final Ast.Expr a : c.args()) {
                final Bound b = binder.bindValue(a);
                args.add(b);
                types.add(b.type());
            }
        }
        final CdbType type;
        try {
            type = Signatures.returnType(function, types);
        } catch (final CdbException e) {
            throw new CdbException(e.getMessage(), c.pos());
        }
        final boolean distinct = c.distinct() && !"count_distinct".equals(function);
        return new Bound.Agg(function, List.copyOf(args), distinct, type);
    }

    /** One window per distinct call: the input's columns, then the calls'; the select list reads them back. */
    private static Stage window(final Rel in, final List<Ast.Call> calls) {
        final Binder binder = new Binder(in, null);
        final List<Bound.WindowCall> bound = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        final List<Field> fields = new ArrayList<>(in.plan().fields());
        final List<Rel.Col> cols = new ArrayList<>(in.cols());
        for (final Ast.Call c : calls) {
            final String text = Names.text(c, true);
            if (texts.contains(text)) {
                continue;
            }
            final List<Bound> args = new ArrayList<>();
            final List<CdbType> types = new ArrayList<>();
            if (!c.star()) {
                for (final Ast.Expr a : c.args()) {
                    final Bound b = binder.bindValue(a);
                    args.add(b);
                    types.add(b.type());
                }
            }
            final List<Bound> partition = new ArrayList<>();
            for (final Ast.Expr p : c.over().partitionBy()) {
                partition.add(binder.bindValue(p));
            }
            final List<Plan.SortKey> order = new ArrayList<>();
            for (final Ast.OrderItem o : c.over().orderBy()) {
                if (!(binder.bindValue(o.expr()) instanceof Bound.Column(final int index, final CdbType _))) {
                    throw new CdbException("cannot order window by expression [function=" + c.name() + ']', c.pos());
                }
                order.add(new Plan.SortKey(index, o.descending()));
            }
            final CdbType type;
            try {
                type = Signatures.windowType(c.name(), types);
            } catch (final CdbException e) {
                throw new CdbException(e.getMessage(), c.pos());
            }
            bound.add(new Bound.WindowCall(c.name(), List.copyOf(args), List.copyOf(partition), List.copyOf(order),
                    type));
            texts.add(text);
            fields.add(new Field(Names.text(c, false), type));
            cols.add(new Rel.Col(null, Names.text(c, false), type));
        }
        final Rel out = new Rel(new Plan.Window(in.plan(), List.copyOf(bound), List.copyOf(fields)), cols,
                in.timestamp());
        final int width = in.width();
        final Function<Ast.Expr, Bound> hook = expr -> {
            if (expr instanceof Ast.Call c && WINDOW.test(c)) {
                final int k = texts.indexOf(Names.text(c, true));
                return new Bound.Column(width + k, bound.get(k).type());
            }
            return null;
        };
        return new Stage(out, hook);
    }

    /** The select list over the stage, then DISTINCT, ORDER BY and LIMIT, hidden sort columns dropped last. */
    private Rel project(final Stage stage, final List<Item> items) {
        final Binder binder = new Binder(stage.rel(), stage.hook());
        final List<String> raw = new ArrayList<>(items.size());
        final boolean[] aliased = new boolean[items.size()];
        final List<Bound> exprs = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            raw.add(items.get(i).name());
            aliased[i] = items.get(i).aliased();
            exprs.add(binder.bindValue(items.get(i).expr()));
        }
        final List<String> names = Names.unique(raw, aliased, select.pos());
        final List<Plan.SortKey> sort = new ArrayList<>();
        final List<String> hidden = new ArrayList<>();
        for (final Ast.OrderItem o : select.orderBy()) {
            int at = orderIndex(o.expr(), items, names);
            if (at < 0) {
                at = exprs.size();
                exprs.add(binder.bindValue(o.expr()));
                hidden.add("$order" + hidden.size());
            }
            sort.add(new Plan.SortKey(at, o.descending()));
        }
        if (select.distinct() && !hidden.isEmpty()) {
            throw new CdbException("cannot order DISTINCT rows by unselected expression", select.pos());
        }
        final List<Field> fields = new ArrayList<>(exprs.size());
        for (int i = 0; i < exprs.size(); i++) {
            fields.add(new Field(i < names.size() ? names.get(i) : hidden.get(i - names.size()), exprs.get(i).type()));
        }
        Plan plan = projection(stage.rel().plan(), exprs, fields);
        if (select.distinct()) {
            plan = new Plan.Distinct(plan);
        }
        if (!sort.isEmpty()) {
            plan = new Plan.Sort(plan, List.copyOf(sort));
        }
        plan = Planner.limit(plan, select.limit());
        if (!hidden.isEmpty()) {
            final List<Bound> keep = new ArrayList<>(names.size());
            for (int i = 0; i < names.size(); i++) {
                keep.add(new Bound.Column(i, fields.get(i).type()));
            }
            plan = new Plan.Project(plan, List.copyOf(keep), List.copyOf(fields.subList(0, names.size())));
        }
        int timestamp = -1;
        for (int i = 0; i < names.size() && stage.rel().timestamp() >= 0; i++) {
            if (exprs.get(i) instanceof Bound.Column(final int index, final CdbType _)
                    && index == stage.rel().timestamp() && timestamp < 0) {
                timestamp = i;
            }
        }
        final List<Rel.Col> cols = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            cols.add(new Rel.Col(null, names.get(i), fields.get(i).type()));
        }
        return new Rel(plan, cols, timestamp);
    }

    /**
     * The projection, or none when it would pass its input through unchanged; over an aggregate that only needs its
     * output renamed, the aggregate renamed.
     */
    private static Plan projection(final Plan input, final List<Bound> exprs, final List<Field> fields) {
        boolean identity = exprs.size() == input.fields().size();
        for (int i = 0; i < exprs.size() && identity; i++) {
            identity = exprs.get(i) instanceof Bound.Column(final int index, final CdbType _) && index == i;
        }
        if (identity) {
            if (input.fields().equals(fields)) {
                return input;
            }
            if (input instanceof Plan.Aggregate(final Plan child, final List<Bound> keys, final List<Bound.Agg> aggs,
                                                final List<Field> _)) {
                return new Plan.Aggregate(child, keys, aggs, List.copyOf(fields));
            }
            if (input instanceof Plan.SampleBy(final Plan child, final int ts, final long count, final String unit,
                                               final List<Bound> keys, final List<Bound.Agg> aggs,
                                               final List<String> fill, final List<Field> _)) {
                return new Plan.SampleBy(child, ts, count, unit, keys, aggs, fill, List.copyOf(fields));
            }
        }
        return new Plan.Project(input, List.copyOf(exprs), List.copyOf(fields));
    }

    /** The output an ORDER BY item names: a 1-based position, an output name, or a selected expression; else -1. */
    private static int orderIndex(final Ast.Expr expr, final List<Item> items, final List<String> names) {
        if (expr instanceof Ast.Literal(final Long n, final int pos)) {
            if (n < 1 || n > items.size()) {
                throw new CdbException("cannot order by position out of range [position=" + n + ']', pos);
            }
            return (int) (n - 1);
        }
        if (expr instanceof Ast.Ident(final List<String> parts, final int _) && parts.size() == 1) {
            for (int i = 0; i < names.size(); i++) {
                if (names.get(i).equalsIgnoreCase(parts.getFirst())) {
                    return i;
                }
            }
        }
        final String text = Names.text(expr, true);
        for (int i = 0; i < items.size(); i++) {
            if (Names.text(items.get(i).expr(), true).equals(text)) {
                return i;
            }
        }
        return -1;
    }

    /** The calls in {@code exprs} that {@code match}, outermost first, not looking inside a matched call. */
    private static List<Ast.Call> calls(final List<Ast.Expr> exprs, final Predicate<Ast.Call> match) {
        final List<Ast.Call> out = new ArrayList<>();
        for (final Ast.Expr e : exprs) {
            collect(e, match, out);
        }
        return out;
    }

    private static void collect(final Ast.Expr expr, final Predicate<Ast.Call> match, final List<Ast.Call> out) {
        if (expr instanceof Ast.Call c && match.test(c)) {
            out.add(c);
            return;
        }
        for (final Ast.Expr child : children(expr)) {
            collect(child, match, out);
        }
    }

    private static boolean readsColumn(final Ast.Expr expr) {
        if (expr instanceof Ast.Ident) {
            return true;
        }
        for (final Ast.Expr child : children(expr)) {
            if (readsColumn(child)) {
                return true;
            }
        }
        return false;
    }

    private static List<Ast.Expr> children(final Ast.Expr expr) {
        return switch (expr) {
            case Ast.Literal _, Ast.Ident _, Ast.Star _, Ast.ScalarQuery _ -> List.of();
            case Ast.Unary(final String _, final Ast.Expr operand, final int _) -> List.of(operand);
            case Ast.Binary(final String _, final Ast.Expr left, final Ast.Expr right, final int _) ->
                    List.of(left, right);
            case Ast.Call c -> c.args();
            case Ast.Cast(final Ast.Expr operand, final String _, final int _) -> List.of(operand);
            case Ast.Case(final Ast.Expr operand, final List<Ast.When> whens, final Ast.Expr otherwise,
                          final int _) -> {
                final List<Ast.Expr> out = new ArrayList<>();
                if (operand != null) {
                    out.add(operand);
                }
                for (final Ast.When w : whens) {
                    out.add(w.condition());
                    out.add(w.value());
                }
                if (otherwise != null) {
                    out.add(otherwise);
                }
                yield out;
            }
            case Ast.In(final Ast.Expr operand, final List<Ast.Expr> values, final Ast.QueryBody _,
                        final boolean _, final int _) -> {
                final List<Ast.Expr> out = new ArrayList<>(values);
                out.add(operand);
                yield out;
            }
            case Ast.Between(final Ast.Expr operand, final Ast.Expr low, final Ast.Expr high, final boolean _,
                             final int _) -> List.of(operand, low, high);
            case Ast.IsNull(final Ast.Expr operand, final boolean _, final int _) -> List.of(operand);
            case Ast.Like(final Ast.Expr operand, final Ast.Expr pattern, final boolean _, final boolean _,
                          final int _) -> List.of(operand, pattern);
        };
    }

    /** One select item: its expression, its output name, and whether that name is an alias. */
    private record Item(Ast.Expr expr, String name, boolean aliased) {
    }

    /** What the select list is bound over: the rows, and the hook that reads computed aggregates or windows. */
    private record Stage(Rel rel, Function<Ast.Expr, Bound> hook) {
    }
}
