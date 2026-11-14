package dev.marregui.comando.cdb.serve;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbType;

import static org.assertj.core.api.Assertions.assertThat;

/// QuestDB's `/exec` JSON written from hand-built rows. The first six tests compare byte for byte with answers the live
/// QuestDB gave on 2026-11-14 to `curl -sG http://127.0.0.1:9000/exec --data-urlencode "query=<the query>"` (plus
/// `--data-urlencode limit=2` where the test says so); cdb has no INT or STRING, so every capture uses its types only.
class JsonWriterTest {

    private static final DateTimeFormatter ISO_MICROS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);
    private static final long MAX_MICROS = micros("9999-12-31T23:59:59.999999Z");
    private static final long MIN_MICROS = micros("0001-01-01T00:00:00Z");

    /// LONG and DOUBLE columns with `limit=2`: `select x, x * 0.5 h from long_sequence(5)`.
    @Test
    void longsAndDoublesMatchQuestDb() {
        final String query = "select x, x * 0.5 h from long_sequence(5)";
        final FakeRows rows = FakeRows.query(-1, List.of("x", CdbType.LONG, "h", CdbType.DOUBLE),
                List.of(row(1L, 0.5), row(2L, 1.0)));

        assertThat(JsonWriter.result(query, rows)).isEqualTo(
                "{\"query\":\"select x, x * 0.5 h from long_sequence(5)\",\"columns\":[{\"name\":\"x\","
                        + "\"type\":\"LONG\"},"
                        + "{\"name\":\"h\",\"type\":\"DOUBLE\"}],\"timestamp\":-1,\"dataset\":[[1,0.5],[2,1.0]],"
                        + "\"count\":2}");
    }

    /// NULLs of every nullable type, both booleans, an escaped string and a timestamp:
    /// `select x, cast(null as long) n, true t, false f, cast('a"b\c' as varchar) s,
    /// cast('2026-11-14T10:49:45.097165Z' as timestamp) ts, cast(null as timestamp) nts, cast(null as varchar) ns
    /// from long_sequence(2)`.
    @Test
    void nullsBooleansAndEscapesMatchQuestDb() {
        final String query = "select x, cast(null as long) n, true t, false f, cast('a\"b\\c' as varchar) s,"
                + " cast('2026-11-14T10:49:45.097165Z' as timestamp) ts, cast(null as timestamp) nts,"
                + " cast(null as varchar) ns from long_sequence(2)";
        final long ts = micros("2026-11-14T10:49:45.097165Z");
        final FakeRows rows = FakeRows.query(-1,
                List.of("x", CdbType.LONG, "n", CdbType.LONG, "t", CdbType.BOOLEAN, "f", CdbType.BOOLEAN,
                        "s", CdbType.VARCHAR, "ts", CdbType.TIMESTAMP, "nts", CdbType.TIMESTAMP, "ns", CdbType.VARCHAR),
                List.of(row(1L, null, true, false, "a\"b\\c", ts, null, null),
                        row(2L, null, true, false, "a\"b\\c", ts, null, null)));

        assertThat(JsonWriter.result(query, rows)).isEqualTo(
                "{\"query\":\"select x, cast(null as long) n, true t, false f, cast('a\\\"b\\\\c' as varchar) s,"
                        + " cast('2026-11-14T10:49:45.097165Z' as timestamp) ts, cast(null as timestamp) nts,"
                        + " cast(null as varchar) ns from long_sequence(2)\",\"columns\":[{\"name\":\"x\","
                        + "\"type\":\"LONG\"},{\"name\":\"n\",\"type\":\"LONG\"},{\"name\":\"t\",\"type\":\"BOOLEAN\"},"
                        + "{\"name\":\"f\",\"type\":\"BOOLEAN\"},{\"name\":\"s\",\"type\":\"VARCHAR\"},"
                        + "{\"name\":\"ts\","
                        + "\"type\":\"TIMESTAMP\"},{\"name\":\"nts\",\"type\":\"TIMESTAMP\"},{\"name\":\"ns\","
                        + "\"type\":\"VARCHAR\"}],\"timestamp\":-1,\"dataset\":[[1,null,true,false,\"a\\\"b\\\\c\","
                        + "\"2026-11-14T10:49:45.097165Z\",null,null],[2,null,true,false,\"a\\\"b\\\\c\","
                        + "\"2026-11-14T10:49:45.097165Z\",null,null]],\"count\":2}");
    }

    /// Doubles as Java prints them, a NULL (NaN) and an infinity as null, negative zero kept:
    /// `select 1.5 b, 0.1 c, 2.0 d, 1e20 e, 1e-5 f, 123456789.123 g, cast(null as double) h, 1.0/0 i, -0.0 k, 3.0/7 j
    /// from long_sequence(1)`.
    @Test
    void doublesMatchQuestDb() {
        final String query = "select 1.5 b, 0.1 c, 2.0 d, 1e20 e, 1e-5 f, 123456789.123 g, cast(null as double) h,"
                + " 1.0/0 i, -0.0 k, 3.0/7 j from long_sequence(1)";
        final List<Object> columns = new ArrayList<>();
        for (final String name : List.of("b", "c", "d", "e", "f", "g", "h", "i", "k", "j")) {
            columns.add(name);
            columns.add(CdbType.DOUBLE);
        }
        final FakeRows rows = FakeRows.query(-1, columns,
                List.of(row(1.5, 0.1, 2.0, 1e20, 1e-5, 123456789.123, null, Double.POSITIVE_INFINITY, -0.0, 3.0 / 7)));

        assertThat(JsonWriter.result(query, rows)).isEqualTo(
                "{\"query\":\"select 1.5 b, 0.1 c, 2.0 d, 1e20 e, 1e-5 f, 123456789.123 g, cast(null as double) h,"
                        + " 1.0/0 i, -0.0 k, 3.0/7 j from long_sequence(1)\",\"columns\":[{\"name\":\"b\","
                        + "\"type\":\"DOUBLE\"},{\"name\":\"c\",\"type\":\"DOUBLE\"},{\"name\":\"d\","
                        + "\"type\":\"DOUBLE\"},"
                        + "{\"name\":\"e\",\"type\":\"DOUBLE\"},{\"name\":\"f\",\"type\":\"DOUBLE\"},{\"name\":\"g\","
                        + "\"type\":\"DOUBLE\"},{\"name\":\"h\",\"type\":\"DOUBLE\"},{\"name\":\"i\","
                        + "\"type\":\"DOUBLE\"},"
                        + "{\"name\":\"k\",\"type\":\"DOUBLE\"},{\"name\":\"j\",\"type\":\"DOUBLE\"}],\"timestamp\":-1,"
                        + "\"dataset\":[[1.5,0.1,2.0,1.0E20,1.0E-5,1.23456789123E8,null,null,-0.0,"
                        + "0.42857142857142855]],"
                        + "\"count\":1}");
    }

    /// Non-ASCII text kept raw, a tab and a newline escaped, a slash not, in a SYMBOL and VARCHARs:
    /// `select cast('é€😀' as varchar) u, cast('tab<TAB>x' as varchar) v, cast('nl<LF>y' as varchar) w,
    /// cast('a/b' as symbol) s from long_sequence(1)`, with a real tab and line feed in the query.
    @Test
    void textMatchesQuestDb() {
        final String query = "select cast('é€😀' as varchar) u, cast('tab\tx' as varchar) v,"
                + " cast('nl\ny' as varchar) w, cast('a/b' as symbol) s from long_sequence(1)";
        final FakeRows rows = FakeRows.query(-1,
                List.of("u", CdbType.VARCHAR, "v", CdbType.VARCHAR, "w", CdbType.VARCHAR, "s", CdbType.SYMBOL),
                List.of(row("é€😀", "tab\tx", "nl\ny", "a/b")));

        assertThat(JsonWriter.result(query, rows)).isEqualTo(
                "{\"query\":\"select cast('é€😀' as varchar) u, cast('tab\\tx' as varchar) v,"
                        + " cast('nl\\ny' as varchar) w, cast('a/b' as symbol) s from long_sequence(1)\",\"columns\":["
                        + "{\"name\":\"u\",\"type\":\"VARCHAR\"},{\"name\":\"v\",\"type\":\"VARCHAR\"},{\"name\":\"w\","
                        + "\"type\":\"VARCHAR\"},{\"name\":\"s\",\"type\":\"SYMBOL\"}],\"timestamp\":-1,\"dataset\":"
                        + "[[\"é€😀\",\"tab\\tx\",\"nl\\ny\",\"a/b\"]],\"count\":1}");
    }

    /// Six fraction digits always, before the epoch and in year 1:
    /// `select cast('2026-11-14T10:49:45Z' as timestamp) a, cast('1969-12-31T23:59:59.999999Z' as timestamp) b,
    /// cast('2026-11-14T10:49:45.1Z' as timestamp) c, cast('0001-01-01T00:00:00Z' as timestamp) d
    /// from long_sequence(1)`.
    @Test
    void timestampsMatchQuestDb() {
        final String query = "select cast('2026-11-14T10:49:45Z' as timestamp) a,"
                + " cast('1969-12-31T23:59:59.999999Z' as timestamp) b, cast('2026-11-14T10:49:45.1Z' as timestamp) c,"
                + " cast('0001-01-01T00:00:00Z' as timestamp) d from long_sequence(1)";
        final FakeRows rows = FakeRows.query(-1,
                List.of("a", CdbType.TIMESTAMP, "b", CdbType.TIMESTAMP, "c", CdbType.TIMESTAMP, "d", CdbType.TIMESTAMP),
                List.of(row(micros("2026-11-14T10:49:45Z"), -1L, micros("2026-11-14T10:49:45.1Z"), MIN_MICROS)));

        assertThat(JsonWriter.result(query, rows)).isEqualTo(
                "{\"query\":\"select cast('2026-11-14T10:49:45Z' as timestamp) a,"
                        + " cast('1969-12-31T23:59:59.999999Z' as timestamp) b,"
                        + " cast('2026-11-14T10:49:45.1Z' as timestamp) c, cast('0001-01-01T00:00:00Z' as timestamp) d"
                        + " from long_sequence(1)\",\"columns\":[{\"name\":\"a\",\"type\":\"TIMESTAMP\"},"
                        + "{\"name\":\"b\","
                        + "\"type\":\"TIMESTAMP\"},{\"name\":\"c\",\"type\":\"TIMESTAMP\"},{\"name\":\"d\","
                        + "\"type\":\"TIMESTAMP\"}],\"timestamp\":-1,\"dataset\":[[\"2026-11-14T10:49:45.000000Z\","
                        + "\"1969-12-31T23:59:59.999999Z\",\"2026-11-14T10:49:45.100000Z\","
                        + "\"0001-01-01T00:00:00.000000Z\"]],"
                        + "\"count\":1}");
    }

    /// A table's designated timestamp as the `timestamp` index:
    /// `select ts, provider, window_name, pct, resets from comando_usage limit 1`.
    @Test
    void designatedTimestampMatchesQuestDb() {
        final String query = "select ts, provider, window_name, pct, resets from comando_usage limit 1";
        final FakeRows rows = FakeRows.query(0,
                List.of("ts", CdbType.TIMESTAMP, "provider", CdbType.SYMBOL, "window_name", CdbType.SYMBOL,
                        "pct", CdbType.DOUBLE, "resets", CdbType.VARCHAR),
                List.of(row(micros("2026-10-28T11:13:15.876Z"), "Claude", "Session", 0.0, "")));

        assertThat(JsonWriter.result(query, rows)).isEqualTo(
                "{\"query\":\"select ts, provider, window_name, pct, resets from comando_usage limit 1\",\"columns\":["
                        + "{\"name\":\"ts\",\"type\":\"TIMESTAMP\"},{\"name\":\"provider\",\"type\":\"SYMBOL\"},"
                        + "{\"name\":\"window_name\",\"type\":\"SYMBOL\"},{\"name\":\"pct\",\"type\":\"DOUBLE\"},"
                        + "{\"name\":\"resets\",\"type\":\"VARCHAR\"}],\"timestamp\":0,\"dataset\":["
                        + "[\"2026-10-28T11:13:15.876000Z\",\"Claude\",\"Session\",0.0,\"\"]],\"count\":1}");
    }

    /// The error answer, as QuestDB gave it with status 400 to `select nosuch from comando_usage`.
    @Test
    void errorMatchesQuestDb() {
        assertThat(JsonWriter.error("select nosuch from comando_usage", "Invalid column: nosuch", 7)).isEqualTo(
                "{\"query\":\"select nosuch from comando_usage\",\"error\":\"Invalid column: nosuch\",\"position\":7}");
    }

    /// DDL answers `{"ddl":"OK"}`, a statement that changed rows its count, even zero.
    @Test
    void ddlAndDmlAnswerTheirShortForms() {
        assertThat(JsonWriter.result("create table t (x long)", FakeRows.ddl())).isEqualTo("{\"ddl\":\"OK\"}");
        assertThat(JsonWriter.result("update t set x = 1", FakeRows.updated(3))).isEqualTo(
                "{\"dml\":\"OK\",\"updated\":3}");
        assertThat(JsonWriter.result("update t set x = 1", FakeRows.updated(0))).isEqualTo(
                "{\"dml\":\"OK\",\"updated\":0}");
    }

    /// A query with no rows keeps its columns and an empty dataset.
    @Test
    void emptyResultKeepsItsColumns() {
        final FakeRows rows = FakeRows.query(-1, List.of("x", CdbType.LONG), List.of());

        assertThat(JsonWriter.result("q", rows)).isEqualTo(
                "{\"query\":\"q\",\"columns\":[{\"name\":\"x\",\"type\":\"LONG\"}],\"timestamp\":-1,\"dataset\":[],"
                        + "\"count\":0}");
    }

    /// The control characters JSON reserves: short escapes where JSON has them, a six-character hex escape for the
    /// rest; DEL is no control character to JSON and stays raw.
    @Test
    void controlCharactersAreEscaped() {
        final FakeRows rows = FakeRows.query(-1, List.of("s", CdbType.VARCHAR),
                List.of(row("\b\f\r\u0001\u001f\u007f")));

        assertThat(JsonWriter.result("q", rows)).isEqualTo(
                "{\"query\":\"q\",\"columns\":[{\"name\":\"s\",\"type\":\"VARCHAR\"}],\"timestamp\":-1,\"dataset\":"
                        + "[[\"\\b\\f\\r\\u0001\\u001f\u007f\"]],\"count\":1}");
    }

    /// The write error carries the message and the line.
    @Test
    void writeErrorCarriesTheLine() {
        assertThat(JsonWriter.writeError("missing fields [line=2, offset=5]", 2)).isEqualTo(
                "{\"code\":\"invalid\",\"message\":\"missing fields [line=2, offset=5]\",\"line\":2}");
    }

    /// Timestamps across years 1 to 9999 print as `java.time` prints them. Seed in the output; replay with
    /// `-Dcdb.serve.seed=<seed>`.
    @Test
    void timestampsMatchJavaTimeOnRandomValues() {
        final long seed = Long.getLong("cdb.serve.seed", System.nanoTime());
        System.out.println("timestampsMatchJavaTimeOnRandomValues seed=" + seed);
        final Random random = new Random(seed);
        final List<List<Object>> cells = new ArrayList<>();
        final StringBuilder expected = new StringBuilder();
        for (final long edge : new long[] {MIN_MICROS, MAX_MICROS, -1L, 0L, 1L, micros("2000-02-29T12:00:00Z")}) {
            add(cells, expected, edge);
        }
        for (int i = 0; i < 10_000; i++) {
            add(cells, expected, MIN_MICROS + Math.floorMod(random.nextLong(), MAX_MICROS - MIN_MICROS + 1));
        }
        final String json = JsonWriter.result("q", FakeRows.query(-1, List.of("ts", CdbType.TIMESTAMP), cells));

        assertThat(json).isEqualTo("{\"query\":\"q\",\"columns\":[{\"name\":\"ts\",\"type\":\"TIMESTAMP\"}],"
                + "\"timestamp\":-1,\"dataset\":[" + expected + "],\"count\":" + cells.size() + '}');
    }

    private static void add(final List<List<Object>> cells, final StringBuilder expected, final long micros) {
        if (!cells.isEmpty()) {
            expected.append(',');
        }
        cells.add(row(micros));
        final Instant instant = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                Math.floorMod(micros, 1_000_000L) * 1_000L);
        expected.append("[\"").append(ISO_MICROS.format(instant)).append("\"]");
    }

    private static List<Object> row(final Object... cells) {
        return Arrays.asList(cells);
    }

    private static long micros(final String iso) {
        final Instant instant = Instant.parse(iso);
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000;
    }
}
