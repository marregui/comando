package dev.marregui.comando.cdb.catalog;

import java.util.ArrayList;
import java.util.List;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Cursor;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;

/** Test helper: drains a cursor into boxed rows, a null for a NULL cell. */
final class Rows {

    private Rows() {
    }

    /** Every row of {@code cursor}, then closes it. */
    static List<List<Object>> drain(final Cursor cursor) {
        final List<List<Object>> rows = new ArrayList<>();
        try (cursor) {
            Page page;
            while ((page = cursor.next()) != null) {
                for (int r = 0; r < page.size; r++) {
                    final List<Object> row = new ArrayList<>();
                    for (final Vector column : page.columns) {
                        row.add(cell(column, r));
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /** The sizes of the pages {@code cursor} returns, then closes it. */
    static List<Integer> pageSizes(final Cursor cursor) {
        final List<Integer> sizes = new ArrayList<>();
        try (cursor) {
            Page page;
            while ((page = cursor.next()) != null) {
                sizes.add(page.size);
            }
        }
        return sizes;
    }

    private static Object cell(final Vector column, final int row) {
        if (column.isNull(row)) {
            return null;
        }
        return switch (column.type) {
            case LONG, TIMESTAMP -> column.longs[row];
            case DOUBLE -> column.doubles[row];
            case BOOLEAN -> column.booleans[row];
            case SYMBOL, VARCHAR -> column.texts[row];
        };
    }

    /** The cdb type that stands for a QuestDB column type: INT is LONG, STRING and CHAR are VARCHAR. */
    static CdbType mapQuestDbType(final String questDbType) {
        return switch (questDbType) {
            case "INT", "LONG" -> CdbType.LONG;
            case "STRING", "CHAR" -> CdbType.VARCHAR;
            default -> CdbType.valueOf(questDbType);
        };
    }
}
