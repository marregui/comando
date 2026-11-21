package dev.marregui.comando.cdb.serve;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;

import org.jetbrains.annotations.TestOnly;

/**
 * QuestDB's HTTP JSON, written by hand into a buffer: no value becomes a {@code String} of its own, and a result goes
 * to its {@link Writer} a chunk at a time, so no answer is held whole. Doubles print as
 * {@link Double#toString(double)} prints them, which QuestDB's answers match, and a non-finite one as {@code null};
 * timestamps as {@code yyyy-MM-ddTHH:mm:ss.ffffffZ}, always six fraction digits; text as JSON strings, non-ASCII kept
 * as it is.
 */
final class JsonWriter {

    /** A sizing hint: the characters buffered before a result's next chunk goes out, and its longest write. */
    static final int CHUNK_CHARS = 8_192;
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final long MICROS_PER_DAY = 86_400_000_000L;
    private static final long MICROS_PER_HOUR = 3_600_000_000L;
    private static final long MICROS_PER_MINUTE = 60_000_000L;
    private static final long MICROS_PER_SECOND = 1_000_000L;
    // 0000-01-01T00:00:00Z and 10000-01-01T00:00:00Z, in micros since the epoch
    private static final long MICROS_OF_YEAR_0 = -62_167_219_200_000_000L;
    private static final long MICROS_OF_YEAR_10000 = 253_402_300_800_000_000L;

    private JsonWriter() {
    }

    /**
     * The answer to {@code query}: {@code {"ddl":"OK"}} for DDL, {@code {"dml":"OK","updated":n}} for a statement that
     * changed rows, else the query's columns and rows.
     */
    @TestOnly
    static String result(final String query, final Rows rows) {
        return result(query, rows, 0L);
    }

    /**
     * {@link #result(String, Rows)} for rows served after {@code skipped} rows a {@code limit=lo,hi} passed over:
     * {@code count} is the skipped rows plus the served ones, as QuestDB counts them.
     */
    @TestOnly
    static String result(final String query, final Rows rows, final long skipped) {
        final StringWriter json = new StringWriter();
        try {
            result(query, rows, skipped, json);
        } catch (final IOException e) {
            throw new UncheckedIOException("could not write result to a string", e);
        }
        return json.toString();
    }

    /**
     * {@link #result(String, Rows, long)} written to {@code out} in writes of at most {@link #CHUNK_CHARS}
     * characters, buffering about one chunk and the row that crosses it; {@code out} is neither flushed nor closed. A
     * failure of {@code out} leaves part of the answer written.
     */
    static void result(final String query, final Rows rows, final long skipped, final Writer out)
            throws IOException {
        if (rows.isDdl()) {
            out.write("{\"ddl\":\"OK\"}");
            return;
        }
        if (rows.updateCount() >= 0) {
            out.write("{\"dml\":\"OK\",\"updated\":" + rows.updateCount() + '}');
            return;
        }
        // a chunk's worth plus the row that crosses it, before the buffer grows
        final StringBuilder json = new StringBuilder(2 * CHUNK_CHARS);
        final char[] chunk = new char[CHUNK_CHARS];
        final int columns = rows.columnCount();
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
            if (json.length() >= CHUNK_CHARS) {
                drain(json, out, chunk);
            }
        }
        json.append("],\"count\":").append(skipped + count).append('}');
        drain(json, out, chunk);
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

    /** QuestDB's answer, status 200, to a request with no query text: the error key, and the position as text. */
    static String emptyQuery(final String query) {
        final StringBuilder json = new StringBuilder(48 + query.length());
        json.append("{\"error\":\"empty query\",\"query\":");
        string(json, query);
        return json.append(",\"position\":\"0\"}").toString();
    }

    /** A refused {@code /write} body, in the keys of QuestDB's line-protocol error: code, message and line. */
    static String writeError(final String message, final int line) {
        final StringBuilder json = new StringBuilder(64 + message.length());
        json.append("{\"code\":\"invalid\",\"message\":");
        string(json, message);
        return json.append(",\"line\":").append(line).append('}').toString();
    }

    // hands the buffered characters to out through chunk, which a caller reuses, and empties the buffer; a surrogate
    // pair split between two writes is joined by out's encoder
    private static void drain(final StringBuilder json, final Writer out, final char[] chunk) throws IOException {
        final int length = json.length();
        for (int from = 0; from < length; from += chunk.length) {
            final int to = Math.min(length, from + chunk.length);
            json.getChars(from, to, chunk, 0);
            out.write(chunk, 0, to - from);
        }
        json.setLength(0);
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
            case TIMESTAMP -> dateTime(json.append('"'), rows.getLong(row, column), 'T').append("Z\"");
            case BOOLEAN -> json.append(rows.getBoolean(row, column));
            case SYMBOL, VARCHAR, JSON -> string(json, rows.getText(row, column));
        }
    }

    /**
     * Appends {@code micros} since the epoch, in UTC, as {@code yyyy-MM-dd}, {@code separator},
     * {@code HH:mm:ss.ffffff}: the year in four digits from 0 to 9999 ({@link #fourDigitYear(long)}), else as
     * {@link StringBuilder#append(long)} writes it, sign and all.
     */
    static StringBuilder dateTime(final StringBuilder sink, final long micros, final char separator) {
        // the civil date of a day count is H. Hinnant's civil_from_days, exact for every day a long of micros reaches
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
        if (fourDigitYear(micros)) {
            digits(sink, year, 4);
        } else {
            sink.append(year);
        }
        sink.append('-');
        digits(sink, month, 2).append('-');
        digits(sink, day, 2).append(separator);
        digits(sink, ofDay / MICROS_PER_HOUR, 2).append(':');
        digits(sink, ofDay % MICROS_PER_HOUR / MICROS_PER_MINUTE, 2).append(':');
        digits(sink, ofDay % MICROS_PER_MINUTE / MICROS_PER_SECOND, 2).append('.');
        return digits(sink, ofDay % MICROS_PER_SECOND, 6);
    }

    /** Whether {@code micros} since the epoch falls in the years 0 to 9999, in UTC. */
    static boolean fourDigitYear(final long micros) {
        return micros >= MICROS_OF_YEAR_0 && micros < MICROS_OF_YEAR_10000;
    }

    /** Appends {@code value}, {@code 0 <= value < 10^width}, in {@code width} digits with leading zeros. */
    static StringBuilder digits(final StringBuilder sink, final long value, final int width) {
        long scale = 1L;
        for (int i = 1; i < width; i++) {
            scale *= 10L;
        }
        for (long rest = value; scale > 0L; scale /= 10L) {
            sink.append((char) ('0' + rest / scale));
            rest %= scale;
        }
        return sink;
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
