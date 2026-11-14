package dev.marregui.comando.cdb.serve;

/**
 * QuestDB's HTTP JSON, written by hand into one buffer: no value becomes a {@code String} of its own. Doubles print as
 * {@link Double#toString(double)} prints them, which QuestDB's answers match, and a non-finite one as {@code null};
 * timestamps as {@code yyyy-MM-ddTHH:mm:ss.ffffffZ}, always six fraction digits; text as JSON strings, non-ASCII kept
 * as it is.
 */
final class JsonWriter {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final long INITIAL_CAPACITY_LIMIT = 1 << 20;
    private static final long MICROS_PER_DAY = 86_400_000_000L;
    private static final long MICROS_PER_HOUR = 3_600_000_000L;
    private static final long MICROS_PER_MINUTE = 60_000_000L;
    private static final long MICROS_PER_SECOND = 1_000_000L;

    private JsonWriter() {
    }

    /**
     * The answer to {@code query}: {@code {"ddl":"OK"}} for DDL, {@code {"dml":"OK","updated":n}} for a statement that
     * changed rows, else the query's columns and rows.
     */
    static String result(final String query, final Rows rows) {
        if (rows.isDdl()) {
            return "{\"ddl\":\"OK\"}";
        }
        if (rows.updateCount() >= 0) {
            return "{\"dml\":\"OK\",\"updated\":" + rows.updateCount() + '}';
        }
        final int columns = rows.columnCount();
        final long estimate = 128L + query.length() + (long) rows.rowCount() * columns * 8L;
        final StringBuilder json = new StringBuilder((int) Math.min(estimate, INITIAL_CAPACITY_LIMIT));
        json.append("{\"query\":");
        string(json, query);
        json.append(",\"columns\":[");
        for (int column = 0; column < columns; column++) {
            if (column > 0) {
                json.append(',');
            }
            json.append("{\"name\":");
            string(json, rows.columnName(column));
            json.append(",\"type\":\"").append(rows.type(column).sqlName()).append("\"}");
        }
        json.append("],\"timestamp\":").append(rows.timestampIndex()).append(",\"dataset\":[");
        final int count = rows.rowCount();
        for (int row = 0; row < count; row++) {
            json.append(row > 0 ? ",[" : "[");
            for (int column = 0; column < columns; column++) {
                if (column > 0) {
                    json.append(',');
                }
                value(json, rows, row, column);
            }
            json.append(']');
        }
        return json.append("],\"count\":").append(count).append('}').toString();
    }

    /** A refused or failed query, as QuestDB answers it with status 400; {@code position} is -1 without one. */
    static String error(final String query, final String message, final int position) {
        final StringBuilder json = new StringBuilder(64 + query.length() + message.length());
        json.append("{\"query\":");
        string(json, query);
        json.append(",\"error\":");
        string(json, message);
        return json.append(",\"position\":").append(position).append('}').toString();
    }

    /** A refused {@code /write} body, in the keys of QuestDB's line-protocol error: code, message and line. */
    static String writeError(final String message, final int line) {
        final StringBuilder json = new StringBuilder(64 + message.length());
        json.append("{\"code\":\"invalid\",\"message\":");
        string(json, message);
        return json.append(",\"line\":").append(line).append('}').toString();
    }

    private static void value(final StringBuilder json, final Rows rows, final int row, final int column) {
        if (rows.isNull(row, column)) {
            json.append("null");
            return;
        }
        switch (rows.type(column)) {
            case LONG -> json.append(rows.getLong(row, column));
            case DOUBLE -> {
                final double value = rows.getDouble(row, column);
                if (Double.isFinite(value)) {
                    json.append(value);
                } else {
                    json.append("null");
                }
            }
            case TIMESTAMP -> timestamp(json, rows.getLong(row, column));
            case BOOLEAN -> json.append(rows.getBoolean(row, column));
            case SYMBOL, VARCHAR -> string(json, rows.getText(row, column));
        }
    }

    // the civil date of a day count is H. Hinnant's civil_from_days, exact for every day a long of micros reaches
    private static void timestamp(final StringBuilder json, final long micros) {
        final long days = Math.floorDiv(micros, MICROS_PER_DAY);
        final long ofDay = Math.floorMod(micros, MICROS_PER_DAY);
        final long shifted = days + 719_468L;
        final long era = Math.floorDiv(shifted, 146_097L);
        final long dayOfEra = shifted - era * 146_097L;
        final long yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L;
        final long dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L);
        final long monthIndex = (5L * dayOfYear + 2L) / 153L;
        final long day = dayOfYear - (153L * monthIndex + 2L) / 5L + 1L;
        final long month = monthIndex < 10L ? monthIndex + 3L : monthIndex - 9L;
        final long year = yearOfEra + era * 400L + (month <= 2L ? 1L : 0L);
        json.append('"');
        if (year >= 0L && year <= 9_999L) {
            digits(json, year, 4);
        } else {
            json.append(year);
        }
        json.append('-');
        digits(json, month, 2);
        json.append('-');
        digits(json, day, 2);
        json.append('T');
        digits(json, ofDay / MICROS_PER_HOUR, 2);
        json.append(':');
        digits(json, ofDay % MICROS_PER_HOUR / MICROS_PER_MINUTE, 2);
        json.append(':');
        digits(json, ofDay % MICROS_PER_MINUTE / MICROS_PER_SECOND, 2);
        json.append('.');
        digits(json, ofDay % MICROS_PER_SECOND, 6);
        json.append("Z\"");
    }

    private static void digits(final StringBuilder json, final long value, final int width) {
        long scale = 1L;
        for (int i = 1; i < width; i++) {
            scale *= 10L;
        }
        for (long rest = value; scale > 0L; scale /= 10L) {
            json.append((char) ('0' + rest / scale));
            rest %= scale;
        }
    }

    private static void string(final StringBuilder json, final String value) {
        json.append('"');
        final int length = value.length();
        for (int i = 0; i < length; i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append("\\u00").append(HEX[c >> 4]).append(HEX[c & 0xf]);
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }
}
