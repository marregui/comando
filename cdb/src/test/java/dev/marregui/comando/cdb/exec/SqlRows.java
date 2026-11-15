package dev.marregui.comando.cdb.exec;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import dev.marregui.comando.cdb.CdbResult;

/// Test helper: a statement's result as one text line per row, its cells joined by `|`, a NULL cell empty and a
/// TIMESTAMP as its ISO instant.
final class SqlRows {

    private SqlRows() {
    }

    /// Every row of `result`.
    static List<String> rows(final CdbResult result) {
        final List<String> rows = new ArrayList<>(result.rowCount());
        for (int row = 0; row < result.rowCount(); row++) {
            final StringBuilder sb = new StringBuilder();
            for (int column = 0; column < result.columnCount(); column++) {
                if (column > 0) {
                    sb.append('|');
                }
                if (!result.isNull(row, column)) {
                    sb.append(switch (result.type(column)) {
                        case BOOLEAN -> String.valueOf(result.getBoolean(row, column));
                        case LONG -> String.valueOf(result.getLong(row, column));
                        case DOUBLE -> String.valueOf(result.getDouble(row, column));
                        case TIMESTAMP -> Instant.EPOCH.plus(result.getLong(row, column), ChronoUnit.MICROS).toString();
                        case SYMBOL, VARCHAR -> result.getText(row, column);
                    });
                }
            }
            rows.add(sb.toString());
        }
        return rows;
    }

    /// The column names and types of `result`, each as `name:TYPE`.
    static List<String> columns(final CdbResult result) {
        final List<String> columns = new ArrayList<>(result.columnCount());
        for (int column = 0; column < result.columnCount(); column++) {
            columns.add(result.columnName(column) + ':' + result.type(column));
        }
        return columns;
    }
}
