package dev.marregui.comando.cdb.serve;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbResult;
import dev.marregui.comando.cdb.CdbType;

/**
 * cdb's types on the PostgreSQL wire: each {@link CdbType}'s OID and its text and binary value encodings, as QuestDB's
 * port 8812 describes them (TIMESTAMP as {@code timestamp}, OID 1114, so a JDBC client reads a {@code LocalDateTime}
 * in UTC), and a bound parameter of a client's type turned into the SQL literal cdb parses in its place.
 */
final class PgTypes {

    static final int BOOL = 16;
    static final int NAME = 19;
    static final int INT8 = 20;
    static final int INT2 = 21;
    static final int INT4 = 23;
    static final int TEXT = 25;
    static final int JSON = 114;
    static final int OID = 26;
    static final int FLOAT4 = 700;
    static final int FLOAT8 = 701;
    static final int UNSPECIFIED = 0;
    static final int BPCHAR = 1042;
    static final int VARCHAR = 1043;
    static final int DATE = 1082;
    static final int TIMESTAMP = 1114;
    static final int TIMESTAMPTZ = 1184;
    static final int NUMERIC = 1700;
    static final int UUID_OID = 2950;
    /** Microseconds from the Unix epoch to PostgreSQL's, 2000-01-01T00:00Z: binary timestamps count from there. */
    static final long PG_EPOCH_MICROS = 946_684_800_000_000L;
    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final long PG_EPOCH_DAYS = 10_957L;
    private static final DateTimeFormatter ISO_MICROS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");
    private static final DateTimeFormatter TEXT_MICROS = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS");
    // PostgreSQL's timestamp text as clients send it: a date, an optional time with up to nine fraction digits, an
    // optional zone (Z, +HH, +HHMM, +HH:MM, +HH:MM:SS)
    private static final Pattern TIMESTAMP_TEXT = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})(?:[ T](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,9}))?)?)?"
                    + "\\s*(Z|[+-]\\d{2}(?::?\\d{2}(?::?\\d{2})?)?)?");

    private PgTypes() {
    }

    /** The OID a column of {@code type} describes as. */
    static int oid(final CdbType type) {
        return switch (type) {
            case BOOLEAN -> BOOL;
            case LONG -> INT8;
            case DOUBLE -> FLOAT8;
            case TIMESTAMP -> TIMESTAMP;
            case SYMBOL, VARCHAR -> VARCHAR;
            case JSON -> JSON;
        };
    }

    /** The fixed width of {@code type}'s binary value, -1 for a variable one, as RowDescription carries it. */
    static short size(final CdbType type) {
        return switch (type) {
            case BOOLEAN -> 1;
            case LONG, DOUBLE, TIMESTAMP -> 8;
            case SYMBOL, VARCHAR, JSON -> -1;
        };
    }

    /**
     * Writes the value at {@code row, column} into {@code out} as a DataRow carries it: its byte length, then its
     * {@code format} (0 text, 1 binary) form, the bytes {@link #text} or {@link #binary} make; the length -1 for NULL.
     */
    static PgOutput value(
            final PgOutput out,
            final CdbResult result,
            final int row,
            final int column,
            final int format
    ) {
        if (result.isNull(row, column)) {
            return out.int32(-1);
        }
        if (format == 0) {
            return switch (result.type(column)) {
                case BOOLEAN -> out.text(result.getBoolean(row, column) ? "t" : "f");
                case LONG -> out.text(out.scratch().append(result.getLong(row, column)));
                // StringBuilder.append(double) writes what Double.toString does
                case DOUBLE -> out.text(out.scratch().append(result.getDouble(row, column)));
                case TIMESTAMP -> out.text(timestampText(out.scratch(), result.getLong(row, column)));
                case SYMBOL, VARCHAR, JSON -> out.text(result.getText(row, column));
            };
        }
        return switch (result.type(column)) {
            case BOOLEAN -> out.int32(1).int8(result.getBoolean(row, column) ? 1 : 0);
            case LONG -> out.int32(Long.BYTES).int64(result.getLong(row, column));
            case DOUBLE -> out.int32(Double.BYTES).float8(result.getDouble(row, column));
            case TIMESTAMP -> out.int32(Long.BYTES).int64(result.getLong(row, column) - PG_EPOCH_MICROS);
            case SYMBOL, VARCHAR, JSON -> out.text(result.getText(row, column));
        };
    }

    /** The value at {@code row, column} in PostgreSQL's text form, UTF-8; null for NULL. */
    @TestOnly
    static byte[] text(final CdbResult result, final int row, final int column) {
        if (result.isNull(row, column)) {
            return null;
        }
        final String text = switch (result.type(column)) {
            case BOOLEAN -> result.getBoolean(row, column) ? "t" : "f";
            case LONG -> Long.toString(result.getLong(row, column));
            // PostgreSQL spells the non-finite values Infinity and -Infinity, as Java does; NaN is cdb's NULL
            case DOUBLE -> Double.toString(result.getDouble(row, column));
            case TIMESTAMP -> timestampText(result.getLong(row, column));
            case SYMBOL, VARCHAR, JSON -> result.getText(row, column);
        };
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** The value at {@code row, column} in PostgreSQL's binary form; null for NULL. */
    @TestOnly
    static byte[] binary(final CdbResult result, final int row, final int column) {
        if (result.isNull(row, column)) {
            return null;
        }
        return switch (result.type(column)) {
            case BOOLEAN -> new byte[] {(byte) (result.getBoolean(row, column) ? 1 : 0)};
            case LONG -> ByteBuffer.allocate(Long.BYTES).putLong(result.getLong(row, column)).array();
            case DOUBLE -> ByteBuffer.allocate(Double.BYTES).putDouble(result.getDouble(row, column)).array();
            case TIMESTAMP -> ByteBuffer.allocate(Long.BYTES)
                    .putLong(result.getLong(row, column) - PG_EPOCH_MICROS).array();
            case SYMBOL, VARCHAR, JSON -> result.getText(row, column).getBytes(StandardCharsets.UTF_8);
        };
    }

    /** {@code micros} since the epoch as PostgreSQL writes a timestamp: {@code yyyy-MM-dd HH:mm:ss.ffffff}. */
    @TestOnly
    static String timestampText(final long micros) {
        return dateTime(micros).format(TEXT_MICROS);
    }

    /**
     * Appends {@code micros} to {@code sink} as {@link #timestampText(long)} writes it: the years 0 to 9999 through
     * {@link JsonWriter#dateTime}, the others through the formatter, as their sign and width are its own.
     */
    static StringBuilder timestampText(final StringBuilder sink, final long micros) {
        // JSON writes such a year as append(long) does, -1 and 10000; the formatter's uuuu pads and signs, -0001 and
        // +10000
        if (!JsonWriter.fourDigitYear(micros)) {
            TEXT_MICROS.formatTo(dateTime(micros), sink);
            return sink;
        }
        return JsonWriter.dateTime(sink, micros, ' ');
    }

    /**
     * The SQL literal cdb parses in place of a parameter of type {@code oid} sent as {@code value} in {@code format}
     * (0 text, 1 binary); {@code null} for a NULL value. A timestamp becomes the UTC ISO text QuestDB takes
     * ({@code '2026-11-15T10:00:00.000000Z'}); a number is checked and written as {@code BigDecimal} writes it (an exponent
     * for a large or small one), a negative one in parentheses so a minus before it cannot make a comment; text is quoted with {@code ''} for each quote. An unspecified type
     * sent as text that reads as a timestamp (a JDBC {@code setTimestamp}) is a timestamp, any other is text.
     */
    static String literal(final int oid, final int format, final byte[] value) {
        if (value == null) {
            return "null";
        }
        if (format == 1) {
            return binaryLiteral(oid, value);
        }
        final String text = new String(value, StandardCharsets.UTF_8);
        try {
            return switch (oid) {
                case INT2, INT4, INT8, OID -> number(Long.toString(Long.parseLong(text.strip())));
                case FLOAT4, FLOAT8, NUMERIC -> decimal(text.strip());
                case BOOL -> bool(text.strip());
                case TIMESTAMP, TIMESTAMPTZ, DATE -> timestamp(parseTimestamp(text));
                case UNSPECIFIED -> TIMESTAMP_TEXT.matcher(text.strip()).matches()
                        ? timestamp(parseTimestamp(text)) : quoted(text);
                default -> quoted(text);
            };
        } catch (final NumberFormatException _) {
            throw new CdbException("invalid parameter value [oid=" + oid + ", value=" + text + ']', -1);
        }
    }

    /** A literal of {@code oid} cdb can plan with, before the client binds a value: a typed zero, or null. */
    static String placeholder(final int oid) {
        return switch (oid) {
            case INT2, INT4, INT8, OID -> "0";
            case FLOAT4, FLOAT8, NUMERIC -> "0.0";
            case BOOL -> "false";
            case TIMESTAMP, TIMESTAMPTZ, DATE -> timestamp(0);
            case TEXT, VARCHAR, BPCHAR, NAME, UUID_OID, JSON -> "''";
            default -> "null";
        };
    }

    /** {@code micros} since the epoch as the UTC ISO literal QuestDB parses. */
    static String timestamp(final long micros) {
        return '\'' + dateTime(micros).format(ISO_MICROS) + '\'';
    }

    /** Microseconds since the epoch of PostgreSQL timestamp text; no zone is UTC. */
    static long parseTimestamp(final String text) {
        final Matcher m = TIMESTAMP_TEXT.matcher(text.strip());
        if (!m.matches()) {
            throw new CdbException("invalid timestamp parameter [value=" + text + ']', -1);
        }
        try {
            return micros(m);
        } catch (final DateTimeException e) {
            throw new CdbException("invalid timestamp parameter [value=" + text + ", error=" + e.getMessage() + ']',
                    -1);
        }
    }

    private static long micros(final Matcher m) {
        final LocalDate date = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)));
        final LocalTime time = m.group(4) == null ? LocalTime.MIDNIGHT : LocalTime.of(Integer.parseInt(m.group(4)),
                Integer.parseInt(m.group(5)), m.group(6) == null ? 0 : Integer.parseInt(m.group(6)),
                m.group(7) == null ? 0 : Integer.parseInt((m.group(7) + "00000000").substring(0, 9)));
        final ZoneOffset zone = m.group(8) == null ? ZoneOffset.UTC : offset(m.group(8));
        final long seconds = LocalDateTime.of(date, time).toEpochSecond(zone);
        return seconds * MICROS_PER_SECOND + time.getNano() / 1_000;
    }

    private static String binaryLiteral(final int oid, final byte[] value) {
        final ByteBuffer buffer = ByteBuffer.wrap(value);
        return switch (oid) {
            case INT2 -> number(Short.toString(fixed(buffer, Short.BYTES, oid).getShort()));
            case INT4, OID -> number(Integer.toString(fixed(buffer, Integer.BYTES, oid).getInt()));
            case INT8 -> number(Long.toString(fixed(buffer, Long.BYTES, oid).getLong()));
            case FLOAT4 -> decimal(Float.toString(fixed(buffer, Float.BYTES, oid).getFloat()));
            case FLOAT8 -> decimal(Double.toString(fixed(buffer, Double.BYTES, oid).getDouble()));
            case BOOL -> fixed(buffer, 1, oid).get() != 0 ? "true" : "false";
            case TIMESTAMP, TIMESTAMPTZ -> timestamp(fixed(buffer, Long.BYTES, oid).getLong() + PG_EPOCH_MICROS);
            case DATE -> timestamp((fixed(buffer, Integer.BYTES, oid).getInt() + PG_EPOCH_DAYS) * 86_400L
                    * MICROS_PER_SECOND);
            case UUID_OID -> {
                final ByteBuffer uuid = fixed(buffer, 2 * Long.BYTES, oid);
                yield quoted(new UUID(uuid.getLong(), uuid.getLong()).toString());
            }
            case TEXT, VARCHAR, BPCHAR, NAME, UNSPECIFIED, JSON -> quoted(new String(value, StandardCharsets.UTF_8));
            default -> throw new CdbException("unsupported binary parameter [oid=" + oid + ']', -1);
        };
    }

    private static ByteBuffer fixed(final ByteBuffer buffer, final int width, final int oid) {
        if (buffer.remaining() != width) {
            throw new CdbException("invalid binary parameter [oid=" + oid + ", bytes=" + buffer.remaining()
                    + ", expected=" + width + ']', -1);
        }
        return buffer;
    }

    private static String decimal(final String text) {
        switch (text.toLowerCase(Locale.ROOT)) {
            case "nan" -> {
                return "null";
            }
            case "infinity", "-infinity" -> throw new CdbException("unsupported parameter value [value=" + text + ']',
                    -1);
            default -> {
                return number(new BigDecimal(text).toString());
            }
        }
    }

    private static String number(final String plain) {
        return plain.startsWith("-") ? '(' + plain + ')' : plain;
    }

    private static String bool(final String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "t", "true", "1", "y", "yes", "on" -> "true";
            case "f", "false", "0", "n", "no", "off" -> "false";
            default -> throw new CdbException("invalid boolean parameter [value=" + text + ']', -1);
        };
    }

    private static String quoted(final String text) {
        return '\'' + text.replace("'", "''") + '\'';
    }

    private static ZoneOffset offset(final String zone) {
        if (zone.equals("Z")) {
            return ZoneOffset.UTC;
        }
        final String digits = zone.substring(1).replace(":", "");
        final int hours = Integer.parseInt(digits.substring(0, 2));
        final int minutes = digits.length() >= 4 ? Integer.parseInt(digits.substring(2, 4)) : 0;
        final int seconds = digits.length() >= 6 ? Integer.parseInt(digits.substring(4, 6)) : 0;
        final int sign = zone.charAt(0) == '-' ? -1 : 1;
        return ZoneOffset.ofHoursMinutesSeconds(sign * hours, sign * minutes, sign * seconds);
    }

    private static LocalDateTime dateTime(final long micros) {
        return LocalDateTime.ofEpochSecond(Math.floorDiv(micros, MICROS_PER_SECOND),
                (int) Math.floorMod(micros, MICROS_PER_SECOND) * 1_000, ZoneOffset.UTC);
    }
}
