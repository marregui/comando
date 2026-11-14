package dev.marregui.comando.cdb.store;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

/**
 * The store's line-protocol appender. A row's fields are buffered until {@link #at}, so a row is either written whole
 * or refused whole; the appender keeps each touched table's writer until {@link #close}.
 */
final class IlpAppender implements CdbAppender {

    // the designated timestamp of a table the appender creates, and its partitioning, as QuestDB's ILP creates one
    private static final String TIMESTAMP = "timestamp";
    // how long the appender waits for a table's writer held by another
    private static final long WRITER_WAIT_MILLIS = 30_000L;
    private static final int INITIAL_FIELDS = 16;

    private final Store store;
    private final Map<String, TableWriter> writers = new LinkedHashMap<>();
    private final List<TableWriter> touched = new ArrayList<>();
    private String table;
    private int fields;
    private String[] names = new String[INITIAL_FIELDS];
    private CdbType[] types = new CdbType[INITIAL_FIELDS];
    private long[] longs = new long[INITIAL_FIELDS];
    private double[] doubles = new double[INITIAL_FIELDS];
    private String[] texts = new String[INITIAL_FIELDS];
    private boolean closed;

    IlpAppender(final Store store) {
        this.store = store;
    }

    @Override
    public CdbAppender table(final String name) {
        checkOpen();
        TableFiles.checkName(name, "table");
        table = name;
        fields = 0;
        return this;
    }

    @Override
    public CdbAppender symbol(final String column, final String value) {
        add(column, CdbType.SYMBOL).texts[fields - 1] = value;
        return this;
    }

    @Override
    public CdbAppender stringColumn(final String column, final String value) {
        add(column, CdbType.VARCHAR).texts[fields - 1] = value;
        return this;
    }

    @Override
    public CdbAppender longColumn(final String column, final long value) {
        add(column, CdbType.LONG).longs[fields - 1] = value;
        return this;
    }

    @Override
    public CdbAppender doubleColumn(final String column, final double value) {
        add(column, CdbType.DOUBLE).doubles[fields - 1] = value;
        return this;
    }

    @Override
    public CdbAppender booleanColumn(final String column, final boolean value) {
        add(column, CdbType.BOOLEAN).longs[fields - 1] = value ? 1L : 0L;
        return this;
    }

    @Override
    public CdbAppender timestampColumn(final String column, final long micros) {
        add(column, CdbType.TIMESTAMP).longs[fields - 1] = micros;
        return this;
    }

    @Override
    public void at(final long micros) {
        checkOpen();
        if (table == null) {
            throw new CdbException("refuse row without table", -1);
        }
        final String name = table;
        table = null;
        final TableWriter writer = writer(name);
        final int[] columns = new int[fields];
        for (int f = 0; f < fields; f++) {
            columns[f] = column(writer, f);
        }
        writer.newRow(micros);
        for (int f = 0; f < fields; f++) {
            final int c = columns[f];
            switch (writer.def().columns().get(c).type()) {
                case LONG, TIMESTAMP -> writer.putLong(c, longs[f]);
                case DOUBLE -> writer.putDouble(c, types[f] == CdbType.LONG ? (double) longs[f] : doubles[f]);
                case BOOLEAN -> writer.putBoolean(c, longs[f] != 0L);
                case SYMBOL, VARCHAR -> writer.putText(c, texts[f]);
            }
        }
        writer.endRow();
        fields = 0;
        if (!touched.contains(writer)) {
            touched.add(writer);
        }
    }

    @Override
    public void flush() {
        checkOpen();
        try {
            for (final TableWriter writer : touched) {
                writer.commit();
            }
        } finally {
            touched.clear();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        CdbException failure = null;
        try {
            flush();
        } catch (final CdbException e) {
            failure = e;
        }
        closed = true;
        final List<TableWriter> open = new ArrayList<>(writers.values());
        writers.clear();
        for (final TableWriter writer : open) {
            try {
                writer.close();
            } catch (final RuntimeException e) {
                if (failure == null) {
                    failure = new CdbException("cannot close appender's writers", e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private IlpAppender add(final String column, final CdbType type) {
        checkOpen();
        if (table == null) {
            throw new CdbException("refuse field before table [column=" + column + ']', -1);
        }
        TableFiles.checkName(column, "column");
        if (fields == names.length) {
            final int grown = fields * 2;
            names = Arrays.copyOf(names, grown);
            types = Arrays.copyOf(types, grown);
            longs = Arrays.copyOf(longs, grown);
            doubles = Arrays.copyOf(doubles, grown);
            texts = Arrays.copyOf(texts, grown);
        }
        names[fields] = column;
        types[fields] = type;
        fields++;
        return this;
    }

    private TableWriter writer(final String name) {
        final String key = TableState.key(name);
        final TableWriter open = writers.get(key);
        if (open != null) {
            return open;
        }
        if (store.def(name) == null) {
            final List<ColumnDef> columns = new ArrayList<>(fields + 1);
            for (int f = 0; f < fields; f++) {
                if (indexOf(columns, names[f]) < 0) {
                    columns.add(new ColumnDef(names[f], types[f]));
                }
            }
            if (indexOf(columns, TIMESTAMP) < 0) {
                columns.add(new ColumnDef(TIMESTAMP, CdbType.TIMESTAMP));
            }
            final int ts = indexOf(columns, TIMESTAMP);
            if (columns.get(ts).type() != CdbType.TIMESTAMP) {
                throw new CdbException("refuse field named as the designated timestamp [table=" + name + ", column="
                        + TIMESTAMP + ", type=" + columns.get(ts).type() + ']', -1);
            }
            store.create(new TableDef(name, columns, ts, PartitionBy.DAY, List.of(), 0L), true);
        }
        final TableWriter writer = store.writer(name, WRITER_WAIT_MILLIS);
        writers.put(key, writer);
        return writer;
    }

    /** The column of field {@code f}, added to the table when missing; a field of a type it cannot take fails. */
    private int column(final TableWriter writer, final int f) {
        final int c = writer.def().columnIndex(names[f]);
        if (c < 0) {
            return writer.addColumn(new ColumnDef(names[f], types[f]));
        }
        final CdbType column = writer.def().columns().get(c).type();
        final CdbType field = types[f];
        final boolean fits = column == field
                || column == CdbType.DOUBLE && field == CdbType.LONG
                || column == CdbType.VARCHAR && field == CdbType.SYMBOL
                || column == CdbType.SYMBOL && field == CdbType.VARCHAR;
        if (!fits) {
            throw new CdbException("refuse field of another type than its column [table=" + writer.def().name()
                    + ", column=" + names[f] + ", columnType=" + column + ", fieldType=" + field + ']', -1);
        }
        return c;
    }

    private static int indexOf(final List<ColumnDef> columns, final String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    private void checkOpen() {
        if (closed) {
            throw new CdbException("refuse use of closed appender", -1);
        }
    }
}
