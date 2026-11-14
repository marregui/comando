package dev.marregui.comando.cdb.catalog;

import java.util.List;
import java.util.Locale;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.exec.Cursor;
import dev.marregui.comando.cdb.exec.Field;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.Store;

/**
 * The table functions and listings: {@code tables()} (QuestDB's 45 columns, its runtime statistics as it reports them
 * for a table written once), {@code table_columns(t)} and {@code SHOW COLUMNS FROM t} (QuestDB's 11 columns,
 * {@code column} to {@code indexInclude}), {@code SHOW CREATE TABLE t} (column {@code ddl}), {@code SHOW TABLES},
 * {@code long_sequence(n)} (column {@code x}, 1 to n) and {@code one_row}.
 */
public final class Catalog {

    private Catalog() {
    }

    /** The output fields of {@code function}, for the planner. */
    public static List<Field> fields(final String function, final List<String> args) {
        return switch (function.toLowerCase(Locale.ROOT)) {
            case "tables" -> {
                requireArgs(function, args, 0);
                yield Listings.TABLES;
            }
            case "table_columns", "show_columns" -> {
                requireArgs(function, args, 1);
                yield Listings.COLUMNS;
            }
            case "show_tables" -> {
                requireArgs(function, args, 0);
                yield Listings.SHOW_TABLES;
            }
            case "show_create_table" -> {
                requireArgs(function, args, 1);
                yield Listings.SHOW_CREATE_TABLE;
            }
            case "long_sequence" -> {
                sequenceLength(args);
                yield SequenceCursor.FIELDS;
            }
            case "one_row" -> {
                requireArgs(function, args, 0);
                yield List.of();
            }
            default -> throw new CdbException("unknown table function [function=" + function + "]", -1);
        };
    }

    /** The rows of {@code source}. */
    public static Cursor open(final Plan.Source source, final Store store) {
        final String function = source.function();
        final List<String> args = source.args();
        fields(function, args);
        return switch (function.toLowerCase(Locale.ROOT)) {
            case "tables" -> Listings.tables(store);
            case "table_columns", "show_columns" -> Listings.columns(store, args.get(0));
            case "show_tables" -> Listings.showTables(store);
            case "show_create_table" -> Listings.showCreateTable(store, args.get(0));
            case "long_sequence" -> new SequenceCursor(sequenceLength(args));
            default -> Listings.oneRow();
        };
    }

    private static void requireArgs(final String function, final List<String> args, final int expected) {
        if (args.size() != expected) {
            throw new CdbException(
                    "wrong argument count [function=" + function + ", expected=" + expected + ", got=" + args.size() + "]",
                    -1
            );
        }
    }

    /** The count of {@code long_sequence(n)}; a negative count gives no rows, as in QuestDB. */
    private static long sequenceLength(final List<String> args) {
        requireArgs("long_sequence", args, 1);
        try {
            return Long.parseLong(args.get(0).trim());
        } catch (final NumberFormatException e) {
            throw new CdbException("count is not a number [function=long_sequence, count=" + args.get(0) + "]", e);
        }
    }
}
