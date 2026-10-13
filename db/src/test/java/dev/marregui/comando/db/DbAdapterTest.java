package dev.marregui.comando.db;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.result.ColumnKind;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.result.ResultView;
import dev.marregui.comando.core.schema.Schema;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.RecordingSink;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DbAdapterTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @TempDir
    static Path root;
    private static EmbeddedQuestDb questDb;
    private static ConnectionSpec spec;

    private final RecordingSink sink = new RecordingSink();
    private final ResultRegistry results = new ResultRegistry();
    private DbAdapter db;

    @BeforeAll
    static void startQuestDb() throws Exception {
        questDb = new EmbeddedQuestDb(root);
        spec = new ConnectionSpec("local", "127.0.0.1", questDb.pgPort(), "qdb", "admin", "quest", false);
    }

    @AfterAll
    static void stopQuestDb() {
        questDb.close();
    }

    @BeforeEach
    void setUp() {
        db = new DbAdapter(sink, results, new DbSettings(0, 1_000, 500, 60, 1_000L));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void runsAQueryAndStoresRowsByColumn() {
        db.execute(1L, spec, "SELECT x, x * 1.5 d, x % 2 = 0 even, cast(x AS STRING) s, "
                + "timestamp_sequence(to_timestamp('2024-01-01T00:00:00', 'yyyy-MM-ddTHH:mm:ss'), 1000000) ts "
                + "FROM long_sequence(3)");

        Intent.SqlCompleted done = sink.await(Intent.SqlCompleted.class, c -> c.requestId() == 1L, WAIT);
        assertThat(done.rows()).isEqualTo(3);
        assertThat(sink.of(Intent.ConnectionOpened.class)).hasSize(1);
        assertThat(sink.of(Intent.SqlStarted.class)).hasSize(1);

        ResultView r = results.get(1L);
        assertThat(r.columnCount()).isEqualTo(5);
        assertThat(r.kind(0)).isEqualTo(ColumnKind.LONG);
        assertThat(r.kind(1)).isEqualTo(ColumnKind.DOUBLE);
        assertThat(r.kind(2)).isEqualTo(ColumnKind.BOOLEAN);
        assertThat(r.kind(3)).isEqualTo(ColumnKind.TEXT);
        assertThat(r.kind(4)).isEqualTo(ColumnKind.TIMESTAMP);
        assertThat(r.getLong(2, 0)).isEqualTo(3L);
        assertThat(r.getDouble(1, 1)).isEqualTo(3.0);
        assertThat(r.getBoolean(1, 2)).isTrue();
        assertThat(r.getText(0, 3)).isEqualTo("1");
        assertThat(r.getText(1, 4)).isEqualTo("2024-01-01T00:00:01.000000Z");
    }

    @Test
    void nullsAreKeptPerCell() {
        db.execute(2L, spec, "SELECT CASE WHEN x = 2 THEN NULL ELSE x END n, CASE WHEN x = 1 THEN NULL ELSE 'v' END s FROM long_sequence(2)");
        sink.await(Intent.SqlCompleted.class, c -> c.requestId() == 2L, WAIT);
        ResultView r = results.get(2L);
        assertThat(r.isNull(0, 0)).isFalse();
        assertThat(r.isNull(1, 0)).isTrue();
        assertThat(r.isNull(0, 1)).isTrue();
        assertThat(r.getText(1, 1)).isEqualTo("v");
    }

    @Test
    void rowCapTruncates() {
        db.execute(3L, spec, "SELECT x FROM long_sequence(5000)");
        Intent.SqlCompleted done = sink.await(Intent.SqlCompleted.class, c -> c.requestId() == 3L, WAIT);
        assertThat(done.rows()).isEqualTo(1_000);
        assertThat(done.truncated()).isTrue();
        assertThat(sink.of(Intent.SqlRows.class)).isNotEmpty();
    }

    @Test
    void ddlReportsNoRows() {
        db.execute(4L, spec, "CREATE TABLE IF NOT EXISTS ddl_probe (ts TIMESTAMP, v LONG) TIMESTAMP(ts) PARTITION BY DAY");
        Intent.SqlCompleted done = sink.await(Intent.SqlCompleted.class, c -> c.requestId() == 4L, WAIT);
        assertThat(done.rows()).isZero();
        assertThat(results.get(4L)).isNull();
    }

    @Test
    void errorsAreReportedWithTheServerMessage() {
        db.execute(5L, spec, "SELECT * FROM no_such_table");
        Intent.SqlFailed failed = sink.await(Intent.SqlFailed.class, f -> f.requestId() == 5L, WAIT);
        assertThat(failed.message()).contains("no_such_table");
    }

    @Test
    void cancelOfALongQueryReturnsQuickly() {
        db.execute(6L, spec, "SELECT sum(x) FROM long_sequence(100_000_000_000)");
        sink.await(Intent.SqlStarted.class, s -> s.requestId() == 6L, WAIT);
        sleep(300);
        long start = System.nanoTime();
        db.cancel(6L);
        long callMillis = (System.nanoTime() - start) / 1_000_000L;
        assertThat(callMillis).as("cancel() must not block its caller, the reducer thread; took %d ms", callMillis).isLessThan(100L);
        sink.await(Intent.SqlCancelled.class, c -> c.requestId() == 6L, Duration.ofSeconds(5));
        long tookMillis = (System.nanoTime() - start) / 1_000_000L;
        assertThat(tookMillis).as("cancel took %d ms", tookMillis).isLessThan(3_000L);

        // the lane keeps working afterwards
        db.execute(7L, spec, "SELECT 1");
        sink.await(Intent.SqlCompleted.class, c -> c.requestId() == 7L, WAIT);
    }

    @Test
    void aQueuedRunCancelledBeforeItStartsNeverRuns() {
        db.execute(8L, spec, "SELECT sum(x) FROM long_sequence(100_000_000_000)");
        sink.await(Intent.SqlStarted.class, s -> s.requestId() == 8L, WAIT);
        db.execute(9L, spec, "SELECT 1");
        db.cancel(9L);
        db.cancel(8L);
        sink.await(Intent.SqlCancelled.class, c -> c.requestId() == 9L, Duration.ofSeconds(10));
        assertThat(sink.of(Intent.SqlStarted.class)).noneMatch(s -> s.requestId() == 9L);
    }

    @Test
    void connectFailureIsReported() {
        ConnectionSpec nowhere = new ConnectionSpec("nowhere", "127.0.0.1", 1, "qdb", "admin", "quest", false);
        db.execute(10L, nowhere, "SELECT 1");
        sink.await(Intent.ConnectionFailed.class, f -> f.name().equals("nowhere"), WAIT);
        sink.await(Intent.SqlFailed.class, f -> f.requestId() == 10L, WAIT);
    }

    @Test
    void queryForClaudeReturnsCappedTextRows() throws Exception {
        var rows = db.query(spec, "SELECT x, x * 2 y FROM long_sequence(10)", 4).get();
        assertThat(rows.columns()).containsExactly("x", "y");
        assertThat(rows.rows()).hasSize(4).first().isEqualTo(java.util.List.of("1", "2"));
        assertThat(rows.truncated()).isTrue();

        db.query(spec, "CREATE TABLE IF NOT EXISTS described (ts TIMESTAMP, v DOUBLE) TIMESTAMP(ts)", 1).get();
        var described = db.describe(spec, "described").get();
        assertThat(described.columns()).contains("column", "type");
        assertThat(described.rows()).extracting(r -> r.getFirst()).containsExactly("ts", "v");
    }

    @Test
    void schemaListsTablesWithTheirColumnsAndShowsATree() throws Exception {
        SchemaRegistry schemas = new SchemaRegistry();
        db.close();
        db = new DbAdapter(sink, results, schemas, new DbSettings(0, 1_000, 500, 60, 1_000L));
        db.query(spec, "CREATE TABLE IF NOT EXISTS schema_probe (ts TIMESTAMP, \"Mixed Case\" SYMBOL, v DOUBLE) TIMESTAMP(ts)", 1).get();
        db.loadSchema(spec, true);
        Intent.ShowView view = sink.await(Intent.ShowView.class, WAIT);
        Schema.Table probe = schemas.of("local").tables().stream().filter(t -> t.name().equals("schema_probe")).findFirst().orElseThrow();
        assertThat(probe.columns()).extracting(Schema.Column::name).containsExactly("ts", "Mixed Case", "v");
        assertThat(probe.columns()).extracting(Schema.Column::type).allSatisfy(t -> assertThat(t).isNotEmpty());
        ViewSpec.TreeNode table = ((ViewSpec.Tree) view.spec()).root().children().stream()
                .filter(n -> n.label().equals("schema_probe")).findFirst().orElseThrow();
        assertThat(table.link()).isEqualTo("comando-insert:SELECT * FROM schema_probe LIMIT 100;");
        assertThat(table.children().get(1).link()).isEqualTo("comando-insert:\"Mixed Case\"");
    }

    @Test
    void testerReportsReachability() {
        db.test(spec);
        Intent.ConnectionTested ok = sink.await(Intent.ConnectionTested.class, WAIT);
        assertThat(ok.ok()).isTrue();
    }

    @Test
    void closeReportsClosed() {
        db.open(spec);
        sink.await(Intent.ConnectionOpened.class, WAIT);
        db.close("local");
        sink.await(Intent.ConnectionClosed.class, WAIT);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
