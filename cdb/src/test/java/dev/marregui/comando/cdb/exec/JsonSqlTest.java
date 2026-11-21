package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbResult;
import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.exec.SqlRows.columns;
import static dev.marregui.comando.cdb.exec.SqlRows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The JSON type end to end through SQL: its DDL and listings, the check of every write path (INSERT VALUES,
/// INSERT SELECT, UPDATE, the appender's string fields, casts), `json_extract`, `json_exists`, `->` and `->>` with
/// their precedence, NULLs, VARCHAR and SYMBOL arguments, comparing, grouping and sorting as text, a reopen, and the
/// frontear packet log's queries.
class JsonSqlTest {

    private static final String CREATE = "create table t (doc json, ts timestamp) timestamp(ts) partition by day";
    private static final String T1 = "'2026-11-21T10:00:00.000000Z'";
    private static final String T2 = "'2026-11-21T10:00:01.000000Z'";
    private static final String T3 = "'2026-11-21T10:00:02.000000Z'";
    private static final String T4 = "'2026-11-21T10:00:03.000000Z'";
    private static final String T5 = "'2026-11-21T10:00:04.000000Z'";
    private static final long T1_MICROS = 1_763_719_200_000_000L;
    // every value kind; escapes in a string; a number and an object written as no formatter would; a repeated name
    private static final String DOC = "{\"s\":\"a\\nb\\u00e9\\uD83D\\uDE00\\\"\",\"n\":1.50e3,\"t\":true,\"f\":false,"
            + "\"z\":null,\"o\":{\"x\": [1,  2]},\"a\":[10,\"y\"],\"k\":1,\"k\":2,\"q\\\"\":\"quoted\",\"e\":\"\"}";
    private static final String DECODED_S = "a\nbé" + new String(Character.toChars(0x1F600)) + '"';

    @TempDir
    private Path root;

    /// `json` is a column type of CREATE TABLE and ALTER TABLE ADD COLUMN: `table_columns` and the result name it
    /// `JSON`, and `SHOW CREATE TABLE` prints it.
    @Test
    void jsonIsAColumnType() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(CREATE);
            assertThat(rows(cdb.run("select \"column\", type from table_columns('t')")))
                    .containsExactly("doc|JSON", "ts|TIMESTAMP");
            assertThat(rows(cdb.run("show create table t"))).containsExactly(
                    "CREATE TABLE 't' ( \n\tdoc JSON,\n\tts TIMESTAMP\n) timestamp(ts) PARTITION BY DAY;");
            cdb.run("alter table t add column extra json");
            assertThat(rows(cdb.run("select \"column\", type from table_columns('t')")))
                    .containsExactly("doc|JSON", "ts|TIMESTAMP", "extra|JSON");
            cdb.run("insert into t values ('{\"a\":1}', " + T1 + ", '[true]')");
            final CdbResult result = cdb.run("select doc, extra from t");
            assertThat(columns(result)).containsExactly("doc:JSON", "extra:JSON");
            assertThat(result.type(0).isText()).isTrue();
            assertThat(result.type(0).isVarchar()).isTrue();
            assertThat(rows(result)).containsExactly("{\"a\":1}|[true]");
            assertThatThrownBy(() -> cdb.run("insert into t values ('{\"a\":1}', " + T2 + ", '[true')"))
                    .isInstanceOf(CdbException.class);
            assertThat(count(cdb, "t")).isEqualTo(1);
        }
        assertThat(CdbType.JSON.isText()).isTrue();
        assertThat(CdbType.JSON.isVarchar()).isTrue();
    }

    /// INSERT VALUES takes any one JSON text, the whitespace around it kept, and NULL; the text is read back as
    /// written.
    @Test
    void insertValuesTakesEveryJsonText() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(CREATE);
            final List<String> texts = List.of("{\"a\":1}", "[1,2]", "\"s\"", "12.5", "-0", "true", "false", "null",
                    " {\"w\" : 1} ", "{}", "[]", DOC);
            for (int i = 0; i < texts.size(); i++) {
                cdb.run("insert into t values ('" + texts.get(i) + "', " + (T1_MICROS + i) + ")");
            }
            cdb.run("insert into t values (null, " + T1 + ")");
            final CdbResult result = cdb.run("select doc from t where doc is not null");
            for (int i = 0; i < texts.size(); i++) {
                assertThat(result.getText(i, 0)).isEqualTo(texts.get(i));
            }
            assertThat(count(cdb, "t")).isEqualTo(texts.size() + 1);
            assertThat(cdb.run("select doc from t where doc is null").rowCount()).isEqualTo(1);
        }
    }

    /// INSERT VALUES of a text that is not one JSON text is refused with a `CdbException`, and none of the
    /// statement's rows is written, the valid ones before and after it included.
    @ParameterizedTest
    @ValueSource(strings = {"{", "", "   ", "01", "[1,]", "{\"a\" 1}", "tru", "abc", "{\"a\":1} x", "\"open",
            "{\"a\":1}{\"b\":2}", "\\\"s\\\""})
    void insertValuesRefusesAnInvalidText(final String text) {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(CREATE);
            assertThatThrownBy(() -> cdb.run("insert into t values ('{\"a\":1}', " + T1 + "), ('" + text + "', "
                    + T2 + "), ('[2]', " + T3 + ")")).isInstanceOf(CdbException.class);
            assertThat(count(cdb, "t")).isZero();
        }
    }

    /// INSERT SELECT checks every row it writes: one invalid text among valid ones refuses the statement and writes
    /// none of them; the valid rows alone are written.
    @Test
    void insertSelectChecksEveryRow() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(CREATE);
            cdb.run("create table src (s varchar, ts timestamp) timestamp(ts) partition by day");
            cdb.run("insert into src values ('{\"a\":1}', " + T1 + "), ('bad', " + T2 + "), ('[3]', " + T3 + "),"
                    + " (null, " + T4 + ")");
            assertThatThrownBy(() -> cdb.run("insert into t select s, ts from src"))
                    .isInstanceOf(CdbException.class);
            assertThat(count(cdb, "t")).isZero();
            assertThat(cdb.run("insert into t select s, ts from src where s != 'bad' or s is null").updateCount())
                    .isEqualTo(3);
            assertThat(rows(cdb.run("select doc from t"))).containsExactly("{\"a\":1}", "[3]", "");
        }
    }

    /// UPDATE checks the new texts: one invalid text, on any row, refuses the statement and changes no row; a valid
    /// text and NULL are set.
    @Test
    void updateChecksTheNewTexts() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("create table u (doc json, raw varchar, ts timestamp) timestamp(ts) partition by day");
            cdb.run("insert into u values ('{\"a\":1}', '[1]', " + T1 + "), ('{\"a\":2}', '[2', " + T2 + ")");
            assertThatThrownBy(() -> cdb.run("update u set doc = '{\"a\":'")).isInstanceOf(CdbException.class);
            assertThatThrownBy(() -> cdb.run("update u set doc = raw")).isInstanceOf(CdbException.class);
            assertThat(rows(cdb.run("select doc from u"))).containsExactly("{\"a\":1}", "{\"a\":2}");
            cdb.run("update u set doc = raw where raw = '[1]'");
            cdb.run("update u set doc = null where raw = '[2'");
            assertThat(rows(cdb.run("select doc from u"))).containsExactly("[1]", "");
            cdb.run("update u set doc = '{\"b\": [true]}'");
            assertThat(rows(cdb.run("select doc from u"))).containsExactly("{\"b\": [true]}", "{\"b\": [true]}");
        }
    }

    /// The appender's string field into an existing JSON column is checked: an invalid text refuses that row with a
    /// `CdbException`, and the rows around it are written.
    @Test
    void appenderChecksStringFieldsIntoJson() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("create table a (doc json, timestamp timestamp) timestamp(timestamp) partition by day");
            try (final CdbAppender appender = cdb.appender()) {
                appender.table("a").stringColumn("doc", "{\"n\":1}").at(T1_MICROS);
                assertThatThrownBy(() -> appender.table("a").stringColumn("doc", "{\"n\":").at(T1_MICROS + 1L))
                        .isInstanceOf(CdbException.class);
                appender.table("a").stringColumn("doc", " [2] ").at(T1_MICROS + 2L);
                appender.flush();
            }
            assertThat(rows(cdb.run("select doc from a"))).containsExactly("{\"n\":1}", " [2] ");
            assertThat(rows(cdb.run("select \"column\", type from table_columns('a')")))
                    .containsExactly("doc|JSON", "timestamp|TIMESTAMP");
        }
    }

    /// `cast(x as json)` and `x::json` check the text and give a JSON value; JSON cast to VARCHAR keeps the text;
    /// NULL casts to NULL.
    @Test
    void castToJsonChecksTheText() {
        try (final Cdb cdb = Cdb.open(root)) {
            final CdbResult cast = cdb.run("select cast('{\"a\": 1}' as json) c, '[1,2]'::json d,"
                    + " cast(null as json) n");
            assertThat(columns(cast)).containsExactly("c:JSON", "d:JSON", "n:JSON");
            assertThat(cast.getText(0, 0)).isEqualTo("{\"a\": 1}");
            assertThat(cast.getText(0, 1)).isEqualTo("[1,2]");
            assertThat(cast.isNull(0, 2)).isTrue();
            assertThatThrownBy(() -> cdb.run("select cast('{' as json)")).isInstanceOf(CdbException.class);
            assertThatThrownBy(() -> cdb.run("select 'x'::json")).isInstanceOf(CdbException.class);
            assertThatThrownBy(() -> cdb.run("select cast('' as json)")).isInstanceOf(CdbException.class);

            cdb.run(CREATE);
            cdb.run("insert into t values ('{\"a\":  [1]}', " + T1 + ")");
            final CdbResult back = cdb.run("select cast(doc as varchar) v, doc::varchar w from t");
            assertThat(columns(back)).containsExactly("v:VARCHAR", "w:VARCHAR");
            assertThat(rows(back)).containsExactly("{\"a\":  [1]}|{\"a\":  [1]}");

            cdb.run("create table src (s varchar, ts timestamp) timestamp(ts) partition by day");
            cdb.run("insert into src values ('[1]', " + T1 + "), ('[1', " + T2 + ")");
            assertThatThrownBy(() -> cdb.run("select cast(s as json) from src")).isInstanceOf(CdbException.class);
            assertThat(rows(cdb.run("select s::json from src where s = '[1]'"))).containsExactly("[1]");
        }
    }

    /// `json_extract` gives a string's decoded contents and any other value's text exactly as in the document; JSON
    /// `null` and a missing path give NULL; an object member is the first of its name; every path form works.
    @Test
    void jsonExtractGivesDecodedStringsAndRawText() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(CREATE);
            cdb.run("insert into t values ('" + DOC + "', " + T1 + ")");
            final CdbResult r = cdb.run("select json_extract(doc, '$.s') s, json_extract(doc, '$.n') n,"
                    + " json_extract(doc, '$.t') t, json_extract(doc, '$.f') f, json_extract(doc, '$.z') z,"
                    + " json_extract(doc, '$.o') o, json_extract(doc, '$.o.x[1]') x1, json_extract(doc, '$.a[1]') a1,"
                    + " json_extract(doc, '$.k') k, json_extract(doc, '$[\"q\\\"\"]') q,"
                    + " json_extract(doc, '$.missing') m, json_extract(doc, '.n') bare, json_extract(doc, '$') whole,"
                    + " json_extract(doc, '$.a[2]') past, json_extract(doc, '$.e') e from t");
            assertThat(r.type(0)).isEqualTo(CdbType.VARCHAR);
            assertThat(r.getText(0, 0)).isEqualTo(DECODED_S);
            assertThat(r.getText(0, 1)).isEqualTo("1.50e3");
            assertThat(r.getText(0, 2)).isEqualTo("true");
            assertThat(r.getText(0, 3)).isEqualTo("false");
            assertThat(r.isNull(0, 4)).isTrue();
            assertThat(r.getText(0, 5)).isEqualTo("{\"x\": [1,  2]}");
            assertThat(r.getText(0, 6)).isEqualTo("2");
            assertThat(r.getText(0, 7)).isEqualTo("y");
            assertThat(r.getText(0, 8)).isEqualTo("1");
            assertThat(r.getText(0, 9)).isEqualTo("quoted");
            assertThat(r.isNull(0, 10)).isTrue();
            assertThat(r.getText(0, 11)).isEqualTo("1.50e3");
            assertThat(r.getText(0, 12)).isEqualTo(DOC);
            assertThat(r.isNull(0, 13)).isTrue();
            assertThat(r.isNull(0, 14)).isFalse();
            assertThat(r.getText(0, 14)).isEmpty();
        }
    }

    /// `json_extract` of a NULL input, or of input that is not JSON where the path leads, is NULL.
    @Test
    void jsonExtractOfNullOrBrokenInputIsNull() {
        try (final Cdb cdb = Cdb.open(root)) {
            final CdbResult r = cdb.run("select json_extract(cast(null as varchar), '$.a') n,"
                    + " json_extract('not json', '$') w, json_extract('{\"x\":tru,\"a\":1}', '$.a') b,"
                    + " json_extract('{\"a\":\"open', '$.a') o, json_extract('[1,', '$[1]') c from long_sequence(1)");
            for (int c = 0; c < r.columnCount(); c++) {
                assertThat(r.isNull(0, c)).as(r.columnName(c)).isTrue();
            }
        }
    }

    /// `json_extract` and `json_exists` read VARCHAR and SYMBOL columns as JSON texts.
    @Test
    void jsonFunctionsTakeVarcharAndSymbol() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("create table v (sym symbol, txt varchar, ts timestamp) timestamp(ts) partition by day");
            cdb.run("insert into v values ('{\"a\":\"s1\"}', '{\"a\":[7]}', " + T1 + "),"
                    + " ('nope', '{\"b\":1}', " + T2 + ")");
            assertThat(rows(cdb.run("select json_extract(sym, '$.a'), json_extract(txt, '$.a[0]'),"
                    + " json_exists(sym, '$.a'), json_exists(txt, '$.b') from v")))
                    .containsExactly("s1|7|true|false", "||false|true");
            assertThat(rows(cdb.run("select sym ->> 'a', txt -> 'a' from v"))).containsExactly("s1|[7]", "|");
        }
    }

    /// A path that is not a constant, or does not parse, is refused with a `CdbException`.
    @Test
    void jsonExtractRefusesABadOrVaryingPath() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("create table p (doc json, path varchar, ts timestamp) timestamp(ts) partition by day");
            cdb.run("insert into p values ('{\"a\":1}', '$.a', " + T1 + ")");
            assertThatThrownBy(() -> cdb.run("select json_extract(doc, path) from p"))
                    .isInstanceOf(CdbException.class);
            assertThatThrownBy(() -> cdb.run("select json_exists(doc, path) from p"))
                    .isInstanceOf(CdbException.class);
            for (final String bad : List.of("$[", "a", "$..a", "$[x]", "$[\"a\"")) {
                assertThatThrownBy(() -> cdb.run("select json_extract(doc, '" + bad + "') from p")).as(bad)
                        .isInstanceOf(CdbException.class);
                assertThatThrownBy(() -> cdb.run("select json_exists(doc, '" + bad + "') from p")).as(bad)
                        .isInstanceOf(CdbException.class);
            }
        }
    }

    /// `json_exists` is true when the path names a value, JSON `null` included, and false for a missing path, a NULL
    /// input or input that is not JSON.
    @Test
    void jsonExistsTellsWhetherAPathNamesAValue() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(CREATE);
            cdb.run("insert into t values ('" + DOC + "', " + T1 + "), (null, " + T2 + ")");
            final CdbResult r = cdb.run("select json_exists(doc, '$.z') z, json_exists(doc, '$.a[1]') a1,"
                    + " json_exists(doc, '$.missing') m, json_exists(doc, '$.a[2]') past, json_exists(doc, '$') whole"
                    + " from t");
            assertThat(r.type(0)).isEqualTo(CdbType.BOOLEAN);
            assertThat(rows(r)).containsExactly("true|true|false|false|true", "false|false|false|false|false");
            assertThat(cdb.run("select json_exists('not json', '$') from long_sequence(1)").getBoolean(0, 0))
                    .isFalse();
            assertThat(cdb.run("select json_exists('[1, oops', '$[1]') from long_sequence(1)").getBoolean(0, 0))
                    .isFalse();
        }
    }

    /// `a -> k` gives the value's JSON text, typed JSON: a string keeps its quotes and escapes, JSON `null` is the
    /// text `null`; a string key names a member, an integer key an element, a negative or missing one nothing.
    @Test
    void arrowGivesTheValuesJsonText() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(CREATE);
            cdb.run("insert into t values ('" + DOC + "', " + T1 + ")");
            final CdbResult r = cdb.run("select doc -> 's' s, doc -> 'z' z, doc -> 'o' o, doc -> 'a' -> 1 a1,"
                    + " doc -> 'a' -> 5 a5, doc -> 'a' -> -1 neg, doc -> 'missing' m, doc -> 'n' n, doc -> 'k' k,"
                    + " doc -> 0 idx from t");
            assertThat(r.type(0)).isEqualTo(CdbType.JSON);
            assertThat(r.type(3)).isEqualTo(CdbType.JSON);
            assertThat(r.getText(0, 0)).isEqualTo("\"a\\nb\\u00e9\\uD83D\\uDE00\\\"\"");
            assertThat(r.isNull(0, 1)).isFalse();
            assertThat(r.getText(0, 1)).isEqualTo("null");
            assertThat(r.getText(0, 2)).isEqualTo("{\"x\": [1,  2]}");
            assertThat(r.getText(0, 3)).isEqualTo("\"y\"");
            assertThat(r.isNull(0, 4)).isTrue();
            assertThat(r.isNull(0, 5)).isTrue();
            assertThat(r.isNull(0, 6)).isTrue();
            assertThat(r.getText(0, 7)).isEqualTo("1.50e3");
            assertThat(r.getText(0, 8)).isEqualTo("1");
            assertThat(r.isNull(0, 9)).isTrue();
        }
    }

    /// `a ->> k` gives VARCHAR as `json_extract` gives it for one step: a string decoded, JSON `null` as NULL.
    @Test
    void doubleArrowGivesText() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(CREATE);
            cdb.run("insert into t values ('" + DOC + "', " + T1 + "), (null, " + T2 + ")");
            final CdbResult r = cdb.run("select doc ->> 's' s, doc ->> 'z' z, doc -> 'o' ->> 'x' x,"
                    + " doc -> 'a' ->> 0 a0, doc -> 'a' ->> 1 a1, doc ->> 'n' n, doc ->> 'missing' m from t");
            assertThat(r.type(0)).isEqualTo(CdbType.VARCHAR);
            assertThat(r.getText(0, 0)).isEqualTo(DECODED_S);
            assertThat(r.isNull(0, 1)).isTrue();
            assertThat(r.getText(0, 2)).isEqualTo("[1,  2]");
            assertThat(r.getText(0, 3)).isEqualTo("10");
            assertThat(r.getText(0, 4)).isEqualTo("y");
            assertThat(r.getText(0, 5)).isEqualTo("1.50e3");
            assertThat(r.isNull(0, 6)).isTrue();
            for (int c = 0; c < r.columnCount(); c++) {
                assertThat(r.isNull(1, c)).as("NULL doc, " + r.columnName(c)).isTrue();
            }
        }
    }

    /// `->` and `->>` bind as `||` does: left to right among themselves and with `||`, above a comparison, so
    /// `doc ->> 'k' = 'v'` filters and `'x' || doc ->> 'n'` reads `n` of the concatenation.
    @Test
    void arrowsBindAsConcatenation() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(CREATE);
            cdb.run("insert into t values ('{\"status\":\"ok\",\"n\":1}', " + T1 + "),"
                    + " ('{\"status\":\"down\",\"n\":2}', " + T2 + ")");
            assertThat(rows(cdb.run("select doc ->> 'n' from t where doc ->> 'status' = 'ok'")))
                    .containsExactly("1");
            assertThat(rows(cdb.run("select doc ->> 'n' from t where doc ->> 'n' != '1'"))).containsExactly("2");
            assertThat(rows(cdb.run("select doc ->> 'status' || '!' from t"))).containsExactly("ok!", "down!");
            final CdbResult concat = cdb.run("select 'x' || doc ->> 'n' from t");
            assertThat(concat.isNull(0, 0)).isTrue();
            assertThat(concat.isNull(1, 0)).isTrue();
            assertThat(rows(cdb.run("select doc ->> 'n' = '2' from t"))).containsExactly("false", "true");
        }
    }

    /// A JSON value compares, groups, sorts and is distinct as its text: two texts of one meaning but other
    /// whitespace are two values.
    @Test
    void jsonComparesGroupsAndSortsAsText() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(CREATE);
            cdb.run("insert into t values ('{\"b\":1}', " + T1 + "), ('{\"a\":1}', " + T2 + "),"
                    + " ('{\"a\":1}', " + T3 + "), ('[]', " + T4 + "), ('{\"a\": 1}', " + T5 + ")");
            assertThat(rows(cdb.run("select doc, count() from t group by doc order by doc")))
                    .containsExactly("[]|1", "{\"a\": 1}|1", "{\"a\":1}|2", "{\"b\":1}|1");
            assertThat(rows(cdb.run("select doc from t order by doc desc limit 2")))
                    .containsExactly("{\"b\":1}", "{\"a\":1}");
            assertThat(cdb.run("select count() from t where doc = '{\"a\":1}'").getLong(0, 0)).isEqualTo(2);
            assertThat(cdb.run("select count() from t where doc != '{\"a\":1}'").getLong(0, 0)).isEqualTo(3);
            assertThat(cdb.run("select count() from t where doc < '{'").getLong(0, 0)).isEqualTo(1);
            assertThat(cdb.run("select distinct doc from t").rowCount()).isEqualTo(4);
            assertThat(rows(cdb.run("select doc ->> 'a' a, count() from t group by doc ->> 'a' order by a")))
                    .containsExactly("|2", "1|3");
        }
    }

    /// The JSON type, its values and its check survive a close and a reopen of the database.
    @Test
    void jsonSurvivesAReopen() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run(CREATE);
            cdb.run("insert into t values ('" + DOC + "', " + T1 + ")");
        }
        try (final Cdb cdb = Cdb.open(root)) {
            assertThat(rows(cdb.run("select \"column\", type from table_columns('t')")))
                    .containsExactly("doc|JSON", "ts|TIMESTAMP");
            final CdbResult r = cdb.run("select doc, doc ->> 's' from t");
            assertThat(r.type(0)).isEqualTo(CdbType.JSON);
            assertThat(r.getText(0, 0)).isEqualTo(DOC);
            assertThat(r.getText(0, 1)).isEqualTo(DECODED_S);
            assertThatThrownBy(() -> cdb.run("insert into t values ('{', " + T2 + ")"))
                    .isInstanceOf(CdbException.class);
            cdb.run("insert into t values ('[]', " + T2 + ")");
            assertThat(count(cdb, "t")).isEqualTo(2);
        }
    }

    /// The frontear packet log's three queries: a filter on one payload member projecting two others, a self-join
    /// of questions to replies on the correlation member, and a count per status.
    @Test
    void frontearPacketQueries() {
        try (final Cdb cdb = Cdb.open(root)) {
            cdb.run("create table frontear_packet (ts timestamp, topic symbol, payload json) timestamp(ts)"
                    + " partition by day");
            cdb.run("insert into frontear_packet values"
                    + " (" + T1 + ", 'question', '" + packet("comando", true, "editing", "ok", "c1") + "'),"
                    + " (" + T2 + ", 'reply', '" + packet("kit", false, "idle", "ok", "c1") + "'),"
                    + " (" + T3 + ", 'question', '" + packet("comando", false, "reviewing", "error", "c2") + "'),"
                    + " (" + T4 + ", 'reply', '" + packet("edge", true, "building", "ok", "c2") + "'),"
                    + " (" + T5 + ", 'question', '" + packet("other", false, "idle", "pending", "c3") + "')");

            final CdbResult mine = cdb.run("select ts, payload->>'busy' busy, payload->>'activity' activity"
                    + " from frontear_packet where payload->>'slug' = 'comando'");
            assertThat(columns(mine)).containsExactly("ts:TIMESTAMP", "busy:VARCHAR", "activity:VARCHAR");
            assertThat(rows(mine)).containsExactlyInAnyOrder("2026-11-21T10:00:00Z|true|editing",
                    "2026-11-21T10:00:02Z|false|reviewing");

            final CdbResult pairs = cdb.run("select q.ts, r.ts from frontear_packet q join frontear_packet r"
                    + " on q.payload->>'correlation' = r.payload->>'correlation'"
                    + " where q.topic = 'question' and r.topic = 'reply'");
            assertThat(rows(pairs)).containsExactlyInAnyOrder("2026-11-21T10:00:00Z|2026-11-21T10:00:01Z",
                    "2026-11-21T10:00:02Z|2026-11-21T10:00:03Z");

            assertThat(rows(cdb.run("select payload->>'status' status, count() from frontear_packet"
                    + " group by payload->>'status' order by status")))
                    .containsExactly("error|1", "ok|3", "pending|1");
        }
    }

    private static String packet(final String slug, final boolean busy, final String activity, final String status,
            final String correlation) {
        return "{\"slug\":\"" + slug + "\",\"busy\":" + busy + ",\"activity\":\"" + activity + "\",\"status\":\""
                + status + "\",\"correlation\":\"" + correlation + "\"}";
    }

    private static long count(final Cdb cdb, final String table) {
        return cdb.run("select count() from " + table).getLong(0, 0);
    }
}
