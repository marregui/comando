package dev.marregui.comando.cdb.serve;

import java.util.ArrayList;
import java.util.List;

import dev.marregui.comando.cdb.CdbType;

/// Rows built by hand: a cell is a `Long` (LONG, TIMESTAMP), a `Double`, a `Boolean`, a `String` or null for NULL.
record FakeRows(List<String> names, List<CdbType> types, List<List<Object>> cells, long updateCount, boolean isDdl,
                int timestampIndex) implements Rows {

    /// A query's rows: `columns` alternates a name and its type, each row lists one cell per column.
    static FakeRows query(final int timestampIndex, final List<Object> columns, final List<List<Object>> rows) {
        final List<String> names = new ArrayList<>();
        final List<CdbType> types = new ArrayList<>();
        for (int i = 0; i < columns.size(); i += 2) {
            names.add((String) columns.get(i));
            types.add((CdbType) columns.get(i + 1));
        }
        return new FakeRows(names, types, rows, -1L, false, timestampIndex);
    }

    /// A statement that changed `count` rows.
    static FakeRows updated(final long count) {
        return new FakeRows(List.of(), List.of(), List.of(), count, false, -1);
    }

    /// A DDL statement's answer.
    static FakeRows ddl() {
        return new FakeRows(List.of(), List.of(), List.of(), 0L, true, -1);
    }

    @Override
    public int columnCount() {
        return names.size();
    }

    @Override
    public String columnName(final int column) {
        return names.get(column);
    }

    @Override
    public CdbType type(final int column) {
        return types.get(column);
    }

    @Override
    public int rowCount() {
        return cells.size();
    }

    @Override
    public boolean isNull(final int row, final int column) {
        return cells.get(row).get(column) == null;
    }

    @Override
    public long getLong(final int row, final int column) {
        return (Long) cells.get(row).get(column);
    }

    @Override
    public double getDouble(final int row, final int column) {
        return (Double) cells.get(row).get(column);
    }

    @Override
    public boolean getBoolean(final int row, final int column) {
        return (Boolean) cells.get(row).get(column);
    }

    @Override
    public String getText(final int row, final int column) {
        return (String) cells.get(row).get(column);
    }
}
