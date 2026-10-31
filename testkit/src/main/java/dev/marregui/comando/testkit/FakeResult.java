package dev.marregui.comando.testkit;

import java.util.List;

import dev.marregui.comando.core.result.ColumnKind;
import dev.marregui.comando.core.result.ResultView;

/** A small result held as rows of objects: {@code Long}, {@code Double}, {@code Boolean}, {@code String}, or null. */
public record FakeResult(long requestId, List<String> names, List<ColumnKind> kinds, List<List<Object>> rows) implements ResultView {

    @Override
    public int columnCount() {
        return names.size();
    }

    @Override
    public String columnName(final int column) {
        return names.get(column);
    }

    @Override
    public ColumnKind kind(final int column) {
        return kinds.get(column);
    }

    @Override
    public String typeName(final int column) {
        return kinds.get(column).name();
    }

    @Override
    public int rowCount() {
        return rows.size();
    }

    @Override
    public boolean isNull(final int row, final int column) {
        return rows.get(row).get(column) == null;
    }

    @Override
    public long getLong(final int row, final int column) {
        return ((Number) rows.get(row).get(column)).longValue();
    }

    @Override
    public double getDouble(final int row, final int column) {
        return ((Number) rows.get(row).get(column)).doubleValue();
    }

    @Override
    public boolean getBoolean(final int row, final int column) {
        return (Boolean) rows.get(row).get(column);
    }

    @Override
    public String getText(final int row, final int column) {
        return String.valueOf(rows.get(row).get(column));
    }
}
