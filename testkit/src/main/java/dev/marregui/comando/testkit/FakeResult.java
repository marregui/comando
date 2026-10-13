package dev.marregui.comando.testkit;

import dev.marregui.comando.core.result.ColumnKind;
import dev.marregui.comando.core.result.ResultView;

import java.util.List;

/** A small result held as rows of objects: {@code Long}, {@code Double}, {@code Boolean}, {@code String}, or null. */
public record FakeResult(long requestId, List<String> names, List<ColumnKind> kinds, List<List<Object>> rows) implements ResultView {

    @Override
    public int columnCount() {
        return names.size();
    }

    @Override
    public String columnName(int column) {
        return names.get(column);
    }

    @Override
    public ColumnKind kind(int column) {
        return kinds.get(column);
    }

    @Override
    public String typeName(int column) {
        return kinds.get(column).name();
    }

    @Override
    public int rowCount() {
        return rows.size();
    }

    @Override
    public boolean isNull(int row, int column) {
        return rows.get(row).get(column) == null;
    }

    @Override
    public long getLong(int row, int column) {
        return ((Number) rows.get(row).get(column)).longValue();
    }

    @Override
    public double getDouble(int row, int column) {
        return ((Number) rows.get(row).get(column)).doubleValue();
    }

    @Override
    public boolean getBoolean(int row, int column) {
        return (Boolean) rows.get(row).get(column);
    }

    @Override
    public String getText(int row, int column) {
        return String.valueOf(rows.get(row).get(column));
    }
}
