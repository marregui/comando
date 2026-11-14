package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.agg.Values;
import dev.marregui.comando.cdb.plan.Plan;

/**
 * LATEST ON … PARTITION BY: per distinct value of the partition columns, the row with the largest timestamp (the later
 * row on a tie), output in timestamp order. Reads the whole input on the first {@link #next}.
 */
final class LatestOnCursor implements Cursor {

    private final Cursor child;
    private final List<Field> fields;
    private final AggKeys partitions;
    private final int[] partitionColumns;
    private final Values[] rows;
    private final int timestampColumn;
    private long[] bestMicros = new long[16];
    private boolean closed;
    private int emitted;
    private int[] order;
    private long[] sequence = new long[16];

    LatestOnCursor(final Cursor child, final Plan.LatestOn spec) {
        this.child = child;
        try {
            fields = child.fields();
            timestampColumn = spec.timestampColumn();
            partitionColumns = spec.partitionColumns().clone();
            final List<CdbType> keyTypes = new ArrayList<>();
            for (final int column : partitionColumns) {
                keyTypes.add(fields.get(column).type());
            }
            partitions = new AggKeys(keyTypes);
            rows = new Values[fields.size()];
            for (int c = 0; c < rows.length; c++) {
                rows[c] = new Values(fields.get(c).type());
            }
        } catch (final RuntimeException e) {
            child.close();
            throw e;
        }
    }

    @Override
    public List<Field> fields() {
        return fields;
    }

    @Override
    public Page next() {
        if (order == null) {
            readAll();
        }
        if (emitted >= order.length) {
            return null;
        }
        final Page out = Page.allocate(fields);
        int row = 0;
        while (row < Page.CAPACITY && emitted < order.length) {
            final int group = order[emitted++];
            for (int c = 0; c < rows.length; c++) {
                rows[c].write(group, out.columns[c], row);
            }
            row++;
        }
        out.size = row;
        return out;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            child.close();
        }
    }

    private void readAll() {
        final Vector[] keys = new Vector[partitionColumns.length];
        long seen = 0;
        Page page;
        while ((page = child.next()) != null) {
            for (int k = 0; k < keys.length; k++) {
                keys[k] = page.columns[partitionColumns[k]];
            }
            final long[] micros = page.columns[timestampColumn].longs;
            for (int i = 0; i < page.size; i++) {
                final int before = partitions.size();
                final int group = partitions.groupOf(keys, i);
                if (group == before) {
                    grow(group + 1);
                }
                if (group == before || micros[i] >= bestMicros[group]) {
                    bestMicros[group] = micros[i];
                    sequence[group] = seen;
                    for (int c = 0; c < rows.length; c++) {
                        rows[c].set(group, page.columns[c], i);
                    }
                }
                seen++;
            }
        }
        order = AggSort.sorted(partitions.size(), (a, b) -> {
            final int byTime = Long.compare(bestMicros[a], bestMicros[b]);
            return byTime != 0 ? byTime : Long.compare(sequence[a], sequence[b]);
        });
    }

    private void grow(final int groups) {
        if (groups > bestMicros.length) {
            bestMicros = Arrays.copyOf(bestMicros, bestMicros.length * 2);
            sequence = Arrays.copyOf(sequence, sequence.length * 2);
        }
        for (final Values column : rows) {
            column.ensure(groups);
        }
    }
}
