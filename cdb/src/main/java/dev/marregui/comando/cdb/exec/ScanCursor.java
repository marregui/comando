package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.PartitionReader;
import dev.marregui.comando.cdb.store.TableReader;

/**
 * The rows of a table scan: the snapshot's kept partitions, in time order, a page at a time. A partition is kept when
 * its rows' time range meets {@code [fromMicros, toMicros)}. Pages are full across partition ends, the last may be
 * shorter. The cursor owns the reader and closes it after the last page, or on {@link #close()}.
 */
final class ScanCursor implements Cursor {

    private final List<Field> fields;
    private final int[] columns;
    private final CdbType[] types;
    private final List<PartitionReader> partitions;
    private TableReader reader;
    private Page scratch;
    private int partition;
    private long row;

    ScanCursor(final Plan.Scan scan, final TableReader reader) {
        this.reader = reader;
        try {
            fields = scan.fields();
            columns = scan.columns().clone();
            types = new CdbType[columns.length];
            for (int i = 0; i < columns.length; i++) {
                types[i] = fields.get(i).type();
            }
            partitions = new ArrayList<>();
            for (final PartitionReader p : reader.partitions()) {
                if (p.rowCount() > 0 && p.maxTimestamp() >= scan.fromMicros() && p.minTimestamp() < scan.toMicros()) {
                    partitions.add(p);
                }
            }
        } catch (final RuntimeException | Error e) {
            close();
            throw e;
        }
    }

    @Override
    public List<Field> fields() {
        return fields;
    }

    @Override
    public Page next() {
        if (reader == null) {
            return null;
        }
        Page out = null;
        while (partition < partitions.size()) {
            final PartitionReader p = partitions.get(partition);
            final long remaining = p.rowCount() - row;
            if (remaining <= 0) {
                partition++;
                row = 0;
                continue;
            }
            if (out == null) {
                out = Page.allocate(fields);
            }
            final int n = (int) Math.min(Page.CAPACITY - out.size, remaining);
            if (out.size == 0) {
                read(p, n, out);
            } else {
                if (scratch == null) {
                    scratch = Page.allocate(fields);
                }
                read(p, n, scratch);
                for (int c = 0; c < columns.length; c++) {
                    Rows.copyRange(scratch.columns[c], 0, out.columns[c], out.size, n);
                }
            }
            out.size += n;
            row += n;
            if (out.size == Page.CAPACITY) {
                return out;
            }
        }
        close();
        return out;
    }

    @Override
    public void close() {
        final TableReader r = reader;
        reader = null;
        scratch = null;
        if (r != null) {
            r.close();
        }
    }

    private void read(final PartitionReader p, final int n, final Page dst) {
        for (int c = 0; c < columns.length; c++) {
            final Vector v = dst.columns[c];
            switch (types[c]) {
                case LONG, TIMESTAMP -> p.longs(columns[c], row, n, v.longs);
                case DOUBLE -> p.doubles(columns[c], row, n, v.doubles);
                case BOOLEAN -> p.booleans(columns[c], row, n, v.booleans);
                case SYMBOL, VARCHAR -> p.texts(columns[c], row, n, v.texts);
            }
        }
    }
}
