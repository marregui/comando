package dev.marregui.comando.cdb.serve;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;

/**
 * QuestDB's line protocol, one row per line: {@code table,tag=v,tag=v field=1i,field=2.5,field="s",field=t ts}. A
 * backslash takes the next character as it is ({@code \ }, {@code \,}, {@code \=} in names and tags, {@code \"} and
 * {@code \\} in a quoted string, where a line break may also stand). Field values: {@code 1i} LONG, a number DOUBLE,
 * {@code t}, {@code T}, {@code true}, {@code True}, {@code TRUE} (and {@code f} alike) BOOLEAN, a quoted string
 * VARCHAR, {@code 1t} TIMESTAMP in microseconds. The row's timestamp is in the request's {@link Precision}; a line
 * without one takes the time the request arrived. Blank lines are skipped. One parser per body; not thread-safe.
 */
final class IlpParser {

    private static final String[] FALSE_WORDS = {"f", "F", "false", "False", "FALSE"};
    private static final String[] TRUE_WORDS = {"t", "T", "true", "True", "TRUE"};

    private final String body;
    private final CdbAppender sink;
    private final Precision precision;
    private final long nowMicros;
    private int position;
    private int line;
    private int lineStart;

    private IlpParser(final String body, final CdbAppender sink, final Precision precision, final long nowMicros) {
        this.body = body;
        this.sink = sink;
        this.precision = precision;
        this.nowMicros = nowMicros;
    }

    /**
     * Parses every line of {@code body} into {@code sink}, a row per line, and returns the rows; does not flush. Stops
     * at the first malformed line or the first row the sink refuses, with an {@link IlpException}: the rows of the
     * lines before it are already in the sink.
     */
    static int parse(final String body, final CdbAppender sink, final Precision precision, final long nowMicros) {
        return new IlpParser(body, sink, precision, nowMicros).lines();
    }

    private int lines() {
        int rows = 0;
        while (position < body.length()) {
            line++;
            lineStart = position;
            if (!atLineEnd()) {
                try {
                    row();
                } catch (final CdbException e) {
                    throw new IlpException(line, e);
                }
                rows++;
            }
            if (!atLineEnd()) {
                throw error("unexpected character after timestamp");
            }
            if (position < body.length()) {
                position += body.charAt(position) == '\r' ? 2 : 1;
            }
        }
        return rows;
    }

    private void row() {
        final String table = name(false);
        if (table.isEmpty()) {
            throw error("missing table name");
        }
        sink.table(table);
        while (peek() == ',') {
            position++;
            final String tag = key("missing tag name");
            final String value = name(false);
            if (value.isEmpty()) {
                throw error("missing tag value");
            }
            sink.symbol(tag, value);
        }
        if (peek() != ' ') {
            throw error("missing fields");
        }
        position++;
        field();
        while (peek() == ',') {
            position++;
            field();
        }
        if (atLineEnd()) {
            sink.at(nowMicros);
            return;
        }
        if (peek() != ' ') {
            throw error("unexpected character after field");
        }
        position++;
        sink.at(timestamp());
    }

    private void field() {
        final String name = key("missing field name");
        if (peek() == '"') {
            sink.stringColumn(name, quoted());
            return;
        }
        final int start = position;
        while (!atLineEnd() && peek() != ',' && peek() != ' ') {
            position++;
        }
        final int end = position;
        if (start == end) {
            position = start;
            throw error("missing field value");
        }
        final char last = body.charAt(end - 1);
        if (isOneOf(start, end, TRUE_WORDS)) {
            sink.booleanColumn(name, true);
        } else if (isOneOf(start, end, FALSE_WORDS)) {
            sink.booleanColumn(name, false);
        } else if (last == 'i') {
            sink.longColumn(name, integer(start, end - 1, "could not parse integer field"));
        } else if (last == 't') {
            sink.timestampColumn(name, integer(start, end - 1, "could not parse timestamp field"));
        } else {
            sink.doubleColumn(name, number(start, end));
        }
    }

    private long timestamp() {
        final int start = position;
        while (!atLineEnd() && peek() != ' ' && peek() != ',') {
            position++;
        }
        final long value = integer(start, position, "could not parse timestamp");
        try {
            return precision.toMicros(value);
        } catch (final ArithmeticException _) {
            position = start;
            throw error("timestamp out of range");
        }
    }

    // a name or tag value, up to an unescaped comma, space or line end; an '=' ends a key
    private String name(final boolean key) {
        final StringBuilder text = new StringBuilder();
        while (!atLineEnd()) {
            final char c = body.charAt(position);
            if (c == ',' || c == ' ' || key && c == '=') {
                break;
            }
            if (c == '\\') {
                if (position + 1 >= body.length()) {
                    throw error("unfinished escape");
                }
                position++;
            }
            text.append(body.charAt(position));
            position++;
        }
        return text.toString();
    }

    private String key(final String missing) {
        final String key = name(true);
        if (key.isEmpty()) {
            throw error(missing);
        }
        if (peek() != '=') {
            throw error("missing '='");
        }
        position++;
        return key;
    }

    private String quoted() {
        final int start = position;
        position++;
        final StringBuilder text = new StringBuilder();
        while (position < body.length()) {
            final char c = body.charAt(position);
            if (c == '"') {
                position++;
                return text.toString();
            }
            if (c == '\\' && position + 1 < body.length()) {
                position++;
            }
            text.append(body.charAt(position));
            position++;
        }
        position = start;
        throw error("unclosed string");
    }

    private boolean isOneOf(final int start, final int end, final String[] words) {
        for (final String word : words) {
            if (word.length() == end - start && body.startsWith(word, start)) {
                return true;
            }
        }
        return false;
    }

    private long integer(final int start, final int end, final String reason) {
        try {
            return Long.parseLong(body, start, end, 10);
        } catch (final NumberFormatException _) {
            position = start;
            throw error(reason);
        }
    }

    // Double.parseDouble also takes "1d", hex and surrounding blanks, which a line-protocol float is not
    private double number(final int start, final int end) {
        boolean digit = false;
        for (int i = start; i < end; i++) {
            final char c = body.charAt(i);
            if (c >= '0' && c <= '9') {
                digit = true;
            } else if (c != '.' && c != '-' && c != '+' && c != 'e' && c != 'E') {
                digit = false;
                break;
            }
        }
        try {
            if (digit || body.startsWith("NaN", start) && end - start == 3) {
                return Double.parseDouble(body.substring(start, end));
            }
        } catch (final NumberFormatException _) {
            // fall through to the error below, at the value's start
        }
        position = start;
        throw error("could not parse field value");
    }

    private char peek() {
        return position < body.length() ? body.charAt(position) : '\n';
    }

    private boolean atLineEnd() {
        if (position >= body.length()) {
            return true;
        }
        final char c = body.charAt(position);
        return c == '\n' || c == '\r' && (position + 1 >= body.length() || body.charAt(position + 1) == '\n');
    }

    private IlpException error(final String reason) {
        return new IlpException(reason, line, position - lineStart);
    }
}
