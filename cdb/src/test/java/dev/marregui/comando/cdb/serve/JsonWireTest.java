package dev.marregui.comando.cdb.serve;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.util.PGobject;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/// The JSON type over the wire, against a cdb under a temporary folder: ILP `/write` string fields into a JSON
/// column checked as SQL writes are, `/exec` naming the type `JSON` with each value a JSON string, and the PostgreSQL
/// wire describing the column as `json` (OID 114) with the text as its value.
class JsonWireTest {

    private static final String CREATE = "create table fp (payload json, topic symbol, ts timestamp) timestamp(ts)"
            + " partition by day";
    private static final long T0_NANOS = 1_700_000_000_000_000_000L;

    @TempDir
    private Path root;

    private Cdb cdb;
    private CdbServer http;
    private PgServer pg;
    private Http client;

    /// A fresh cdb with the table, both servers on free loopback ports, and an HTTP client.
    @BeforeEach
    void start() {
        cdb = Cdb.open(root);
        cdb.run(CREATE);
        http = CdbServer.start(cdb, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        pg = PgServer.start(cdb, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        client = new Http();
    }

    /// Closes the client, the servers, then the cdb.
    @AfterEach
    void stop() {
        client.close();
        pg.close();
        http.close();
        cdb.close();
    }

    /// `/write` string fields that are JSON texts are written into the JSON column as sent, escapes of ILP undone.
    @Test
    void writeAcceptsJsonTextsIntoAJsonColumn() throws IOException, InterruptedException {
        final String body = line("question", "{\\\"slug\\\":\\\"comando\\\",\\\"busy\\\":true}", 0)
                + line("reply", "[1, 2]", 1) + line("reply", "\\\"text\\\"", 2);
        assertThat(client.post(http.port(), "/write?precision=n", body).statusCode()).isEqualTo(204);
        assertThat(texts("select payload from fp")).containsExactly("{\"slug\":\"comando\",\"busy\":true}",
                "[1, 2]", "\"text\"");
        assertThat(texts("select payload ->> 'slug' from fp where topic = 'question'")).containsExactly("comando");
    }

    /// A body with a line whose string field is not one JSON text answers 400 with the error a lone parse of that
    /// body into the cdb gives, and writes none of its rows, the valid line before it included.
    @Test
    void writeRefusesAnInvalidJsonText() throws IOException, InterruptedException {
        assertThat(client.post(http.port(), "/write?precision=n", line("question", "{}", 0)).statusCode())
                .isEqualTo(204);
        final String refused = line("question", "[1]", 1) + line("reply", "{\\\"a\\\":", 2);
        final HttpResponse<String> answer = client.post(http.port(), "/write?precision=n", refused);

        final IlpException refusedAlone;
        try (CdbAppender appender = cdb.appender()) {
            refusedAlone = catchThrowableOfType(IlpException.class,
                    () -> IlpParser.parse(refused, appender, Precision.NANOS, 0L));
            appender.discard();
        }
        assertThat(refusedAlone).isNotNull();
        assertThat(refusedAlone.line()).isEqualTo(2);
        assertThat(answer.statusCode()).isEqualTo(400);
        assertThat(answer.body()).isEqualTo(JsonWriter.writeError(refusedAlone.getMessage(), refusedAlone.line()));
        assertThat(texts("select payload from fp")).containsExactly("{}");
    }

    /// `/exec` names a JSON column's type `JSON` and gives each value as a JSON string holding the text; `->>` is
    /// `VARCHAR`, `->` is `JSON`.
    @Test
    void execNamesTheTypeJson() throws IOException, InterruptedException {
        cdb.run("insert into fp values ('{\"a\":\"x\",\"n\":[1]}', 'q', '2023-11-14T22:13:20.000000Z')");
        final String sql = "select payload, payload ->> 'a' a, payload -> 'n' n from fp";
        final HttpResponse<String> read = client.exec(http.port(), sql, "");
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body()).isEqualTo("{\"query\":\"" + sql + "\",\"columns\":["
                + "{\"name\":\"payload\",\"type\":\"JSON\"},{\"name\":\"a\",\"type\":\"VARCHAR\"},"
                + "{\"name\":\"n\",\"type\":\"JSON\"}],\"timestamp\":-1,\"dataset\":["
                + "[\"{\\\"a\\\":\\\"x\\\",\\\"n\\\":[1]}\",\"x\",\"[1]\"]],\"count\":1}");
    }

    /// The PostgreSQL wire describes a JSON column as `json`, which pgjdbc maps from OID 114 to `Types.OTHER` and a
    /// `PGobject` of type `json`; `getString` gives the text as stored, and NULL stays NULL.
    @Test
    void pgDescribesJsonAsOid114() throws SQLException {
        cdb.run("insert into fp values ('{\"a\": [1, \"b\"]}', 'q', '2023-11-14T22:13:20.000000Z'),"
                + " (null, 'r', '2023-11-14T22:13:21.000000Z')");
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select payload, payload ->> 'a' a from fp")) {
            final ResultSetMetaData meta = rows.getMetaData();
            assertThat(meta.getColumnTypeName(1)).isEqualTo("json");
            assertThat(meta.getColumnType(1)).isEqualTo(Types.OTHER);
            assertThat(meta.getColumnTypeName(2)).isEqualTo("varchar");
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("{\"a\": [1, \"b\"]}");
            assertThat(rows.getObject(1)).isInstanceOfSatisfying(PGobject.class, json -> {
                assertThat(json.getType()).isEqualTo("json");
                assertThat(json.getValue()).isEqualTo("{\"a\": [1, \"b\"]}");
            });
            assertThat(rows.getString(2)).isEqualTo("[1, \"b\"]");
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isNull();
            assertThat(rows.wasNull()).isTrue();
            assertThat(rows.next()).isFalse();
        }
    }

    /// A text bound over the PostgreSQL wire into a JSON column is checked: a JSON text is written, anything else
    /// is refused with an error and writes nothing.
    @Test
    void pgChecksBoundTextsIntoJson() throws SQLException {
        try (Connection connection = connect();
             PreparedStatement insert = connection.prepareStatement(
                     "insert into fp values (?, 'q', '2023-11-14T22:13:20.000000Z')")) {
            insert.setString(1, "{\"ok\":true}");
            assertThat(insert.executeUpdate()).isEqualTo(1);
            insert.setString(1, "{\"ok\":");
            assertThatThrownBy(insert::executeUpdate).isInstanceOf(SQLException.class);
        }
        assertThat(texts("select payload from fp")).containsExactly("{\"ok\":true}");
    }

    // one ILP line into fp: topic as the symbol, payload as the string field (already ILP-escaped)
    private static String line(final String topic, final String payload, final long offset) {
        return "fp,topic=" + topic + " payload=\"" + payload + "\" " + (T0_NANOS + offset) + '\n';
    }

    // the first column of every row, NULL as null
    private List<String> texts(final String sql) {
        final CdbResult result = cdb.run(sql);
        final List<String> texts = new ArrayList<>(result.rowCount());
        for (int r = 0; r < result.rowCount(); r++) {
            texts.add(result.isNull(r, 0) ? null : result.getText(r, 0));
        }
        return texts;
    }

    private Connection connect() throws SQLException {
        final Properties properties = new Properties();
        properties.setProperty("user", "admin");
        properties.setProperty("password", "quest");
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + pg.port() + "/qdb", properties);
    }
}
