package dev.marregui.comando.db;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.port.QueryPort;
import dev.marregui.comando.core.result.ColumnKind;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.result.ResultView;
import dev.marregui.comando.core.schema.Schema;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;

/// [CdbAdapter] over the whole engine, on a database seeded through the appender. Skipped while the engine cannot
/// open a database.
/// - A query's result is the engine's own, read through the result view with each cdb type's kind and value.
/// - DDL reports the count it changed and keeps no result; a failing statement reports the engine's message.
/// - Claude's query and describe answer text rows; the schema load lists the tables with their columns.
/// - Two connections on one folder share one database.
class CdbAdapterIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    // 2024-01-01T00:00:00Z in microseconds
    private static final long T0 = 1_704_067_200_000_000L;

    @TempDir
    Path home;

    private final ResultRegistry results = new ResultRegistry();
    private final SchemaRegistry schemas = new SchemaRegistry();
    private final RecordingSink sink = new RecordingSink();
    private CdbAdapter cdb;
    private ConnectionSpec spec;

    @BeforeEach
    void setUp() {
        final Path folder = home.resolve("db");
        try (final Cdb seed = Cdb.open(folder); final CdbAppender rows = seed.appender()) {
            rows.table("trades").symbol("sym", "AAPL").stringColumn("note", "first").longColumn("qty", 3L)
                    .doubleColumn("price", 1.5).booleanColumn("buy", true).at(T0);
            rows.table("trades").symbol("sym", "MSFT").stringColumn("note", "second").longColumn("qty", 4L)
                    .doubleColumn("price", 2.25).booleanColumn("buy", false).at(T0 + 1_000_000L);
        } catch (final UnsupportedOperationException e) {
            Assumptions.abort("the engine cannot open a database yet: " + e.getMessage());
        }
        cdb = new CdbAdapter(sink, results, schemas, DbSettings.DEFAULTS, home);
        spec = new ConnectionSpec("local", "cdb:db", ConnectionSpec.DEFAULT_PORT, "", "", "", false);
    }

    @AfterEach
    void tearDown() {
        if (cdb != null) {
            cdb.close();
        }
    }

    /// A select completes with its two rows, kept as the engine's result: symbol and varchar as text, qty as long,
    /// price as double, buy as boolean, the timestamp in microseconds and as UTC text.
    @Test
    void aQueryKeepsTheEnginesResultWithEachTypesKind() {
        cdb.execute(1L, spec, "SELECT sym, note, qty, price, buy, timestamp FROM trades ORDER BY timestamp");

        final Intent.SqlCompleted done = sink.await(Intent.SqlCompleted.class, c -> c.requestId() == 1L, WAIT);
        assertThat(done.rows()).isEqualTo(2);
        assertThat(done.updateCount()).isEqualTo(-1L);
        final ResultView view = results.get(1L);
        assertThat(view.columnCount()).isEqualTo(6);
        assertThat(List.of(view.kind(0), view.kind(1), view.kind(2), view.kind(3), view.kind(4), view.kind(5)))
                .containsExactly(ColumnKind.TEXT, ColumnKind.TEXT, ColumnKind.LONG, ColumnKind.DOUBLE,
                        ColumnKind.BOOLEAN, ColumnKind.TIMESTAMP);
        assertThat(view.getText(0, 0)).isEqualTo("AAPL");
        assertThat(view.getText(1, 1)).isEqualTo("second");
        assertThat(view.getLong(1, 2)).isEqualTo(4L);
        assertThat(view.getDouble(0, 3)).isEqualTo(1.5);
        assertThat(view.getBoolean(0, 4)).isTrue();
        assertThat(view.getMicros(1, 5)).isEqualTo(T0 + 1_000_000L);
        assertThat(view.getText(0, 5)).startsWith("2024-01-01T00:00:00");
        assertThat(sink.of(Intent.ConnectionOpened.class)).hasSize(1);
    }

    /// A CREATE TABLE completes with no rows and keeps no result; a select from a missing table fails with the
    /// engine's message and leaves the connection open.
    @Test
    void ddlKeepsNoResultAndAFailureCarriesTheEnginesMessage() {
        cdb.execute(1L, spec, "CREATE TABLE fresh (ts TIMESTAMP, v DOUBLE) TIMESTAMP(ts) PARTITION BY DAY");
        final Intent.SqlCompleted done = sink.await(Intent.SqlCompleted.class, c -> c.requestId() == 1L, WAIT);
        cdb.execute(2L, spec, "SELECT * FROM missing_table");
        final Intent.SqlFailed failed = sink.await(Intent.SqlFailed.class, f -> f.requestId() == 2L, WAIT);

        assertThat(done.rows()).isZero();
        assertThat(done.updateCount()).isGreaterThanOrEqualTo(0L);
        assertThat(results.get(1L)).isNull();
        assertThat(failed.message()).contains("missing_table");
        assertThat(sink.of(Intent.ConnectionFailed.class)).isEmpty();
    }

    /// Claude's query answers the cells as text, and describe lists each column with its type.
    @Test
    void claudesQueryAndDescribeAnswerTextRows() throws Exception {
        final QueryPort.Rows rows = cdb.query(spec, "SELECT sym, qty FROM trades ORDER BY timestamp", 10)
                .get(WAIT.toSeconds(), TimeUnit.SECONDS);
        final QueryPort.Rows columns = cdb.describe(spec, "trades").get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(rows.columns()).containsExactly("sym", "qty");
        assertThat(rows.rows()).containsExactly(List.of("AAPL", "3"), List.of("MSFT", "4"));
        assertThat(columns.columns()).startsWith("column", "type");
        assertThat(columns.rows()).extracting(r -> r.get(0) + " " + r.get(1))
                .contains("sym SYMBOL", "note VARCHAR", "qty LONG", "price DOUBLE", "buy BOOLEAN",
                        "timestamp TIMESTAMP");
    }

    /// The schema load keeps the seeded table with its six columns and shows it as a tree.
    @Test
    void theSchemaLoadListsTheTablesWithTheirColumns() {
        cdb.loadSchema(spec, true);

        sink.await(Intent.ShowView.class, WAIT);
        final Schema schema = schemas.of("local");
        assertThat(schema.tables()).extracting(Schema.Table::name).contains("trades");
        assertThat(schema.tables().stream().filter(t -> t.name().equals("trades")).findFirst().orElseThrow().columns())
                .extracting(Schema.Column::name)
                .containsExactlyInAnyOrder("sym", "note", "qty", "price", "buy", "timestamp");
    }

    /// Two connections on the same folder both open, read the same rows, and closing one leaves the other working.
    @Test
    void twoConnectionsOnOneFolderShareTheDatabase() throws Exception {
        final ConnectionSpec other = new ConnectionSpec("other", "cdb:" + home.resolve("db"),
                ConnectionSpec.DEFAULT_PORT, "", "", "", false);
        cdb.open(spec);
        cdb.open(other);
        sink.await(Intent.ConnectionOpened.class, o -> o.name().equals("other"), WAIT);
        cdb.close("local");
        sink.await(Intent.ConnectionClosed.class, c -> c.name().equals("local"), WAIT);

        final QueryPort.Rows rows = cdb.query(other, "SELECT count() FROM trades", 1)
                .get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(rows.rows()).containsExactly(List.of("2"));
    }
}
