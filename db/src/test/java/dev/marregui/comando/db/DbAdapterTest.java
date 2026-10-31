package dev.marregui.comando.db;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.port.QueryPort;
import dev.marregui.comando.core.result.ColumnKind;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.result.ResultView;
import dev.marregui.comando.core.schema.Schema;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [DbAdapter] runs SQL against an embedded QuestDB over the Postgres wire protocol, one lane per connection.
/// - A query's rows are stored by column with their kinds and nulls, capped at the row limit.
/// - DDL reports no rows; errors carry the server's message; a block of several statements says only the first result
///   shows.
/// - Cancel returns at once and ends a long query quickly; a queued run cancelled, or queued at quit, never runs.
/// - Connect, test and close report their outcome, and test and open agree on the TLS mode.
/// - The schema lists tables with their columns as a tree, falling back to the information schema past the table limit.
/// - A schema load overtaken by a close neither reopens the connection nor keeps its tables.
/// - The assistant's queries answer capped text rows.
/// - Closing a connection, a failed open or the adapter itself leaves no lane behind.
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

    /// When the connection is closed and its schema forgotten while a schema load waits on the lane, the lane ends
    /// without opening the connection again, no view, notice or table is left, and only the user's Connect opened it.
    @Test
    void aSchemaLoadOvertakenByACloseNeitherReopensTheConnectionNorKeepsItsTables() throws Exception {
        final SchemaRegistry schemas = new SchemaRegistry();
        db.close();
        db = new DbAdapter(sink, results, schemas, new DbSettings(0, 1_000, 500, 60, 1_000L));
        db.query(spec, "CREATE TABLE IF NOT EXISTS overtaken_probe (ts TIMESTAMP, v DOUBLE) TIMESTAMP(ts)", 1).get();
        db.close("local");
        sink.await(Intent.ConnectionClosed.class, WAIT);
        sink.clear();

        db.open(spec);
        db.loadSchema(spec, true);
        final Thread lane = laneThread("local").orElseThrow();
        // the user points the connection elsewhere at once: the reducer sends CloseDb, then ForgetSchema
        db.close("local");
        db.forgetSchema("local");
        sink.await(Intent.ConnectionClosed.class, WAIT);

        assertThat(lane.join(WAIT)).as("what the load left on the lane does not open the connection again: the lane ends").isTrue();
        assertThat(sink.of(Intent.ConnectionOpened.class)).as("the user's Connect only").hasSize(1);
        assertThat(sink.of(Intent.ShowView.class)).as("the overtaken load shows nothing").isEmpty();
        assertThat(sink.of(Intent.Notify.class)).as("nor reports an error the user caused").isEmpty();
        assertThat(schemas.of("local").tables()).as("nor keeps the tables").isEmpty();
    }

    /// A block of two statements completes with the first one's row and a non-failing warning starting
    /// `2 statements ran`.
    @Test
    void aBlockOfSeveralStatementsSaysThatOnlyTheFirstResultIsShown() {
        db.execute(1L, spec, "SELECT 1 a; SELECT 2 b");

        final Intent.SqlCompleted done = sink.await(Intent.SqlCompleted.class, c -> c.requestId() == 1L, WAIT);
        assertThat(done.rows()).isEqualTo(1);
        final Intent.SqlWarning warning = sink.await(Intent.SqlWarning.class, WAIT);
        assertThat(warning.requestId()).isEqualTo(1L);
        assertThat(warning.failed()).isFalse();
        assertThat(warning.text()).startsWith("2 statements ran");
    }

    /// A single statement completes with no notice and no warning.
    @Test
    void oneStatementRaisesNoNotice() {
        db.execute(1L, spec, "SELECT 1 a;");

        sink.await(Intent.SqlCompleted.class, c -> c.requestId() == 1L, WAIT);
        assertThat(sink.of(Intent.Notify.class)).isEmpty();
        assertThat(sink.of(Intent.SqlWarning.class)).isEmpty();
    }

    /// A three-row query opens the connection once, starts once, and stores long, double, boolean, text and timestamp
    /// columns with their kinds and values, a timestamp formatted to microseconds.
    @Test
    void runsAQueryAndStoresRowsByColumn() {
        db.execute(1L, spec, "SELECT x, x * 1.5 d, x % 2 = 0 even, cast(x AS STRING) s, "
                + "timestamp_sequence(to_timestamp('2024-01-01T00:00:00', 'yyyy-MM-ddTHH:mm:ss'), 1000000) ts "
                + "FROM long_sequence(3)");

        final Intent.SqlCompleted done = sink.await(Intent.SqlCompleted.class, c -> c.requestId() == 1L, WAIT);
        assertThat(done.rows()).isEqualTo(3);
        assertThat(sink.of(Intent.ConnectionOpened.class)).hasSize(1);
        assertThat(sink.of(Intent.SqlStarted.class)).hasSize(1);

        final ResultView r = results.get(1L);
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

    /// A null is recorded per cell, so a null and a value in the same column or row read back separately.
    @Test
    void nullsAreKeptPerCell() {
        db.execute(2L, spec, "SELECT CASE WHEN x = 2 THEN NULL ELSE x END n, CASE WHEN x = 1 THEN NULL ELSE 'v' END s FROM long_sequence(2)");
        sink.await(Intent.SqlCompleted.class, c -> c.requestId() == 2L, WAIT);
        final ResultView r = results.get(2L);
        assertThat(r.isNull(0, 0)).isFalse();
        assertThat(r.isNull(1, 0)).isTrue();
        assertThat(r.isNull(0, 1)).isTrue();
        assertThat(r.getText(1, 1)).isEqualTo("v");
    }

    /// A query for Claude hands SQL NULL over as a `null` cell, apart from the text `null`, so its text form can say which.
    @Test
    void aQueryForClaudeKeepsSqlNullApartFromTheTextNull() throws Exception {
        final QueryPort.Rows rows = db.query(spec, "SELECT CASE WHEN x = 1 THEN NULL ELSE 'null' END s FROM long_sequence(2)", 10)
                .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(rows.rows()).containsExactly(Arrays.asList((String) null), List.of("null"));
    }

    /// A query past the row cap stops at 1 000 rows, marks the result truncated, and has sent rows on the way.
    @Test
    void rowCapTruncates() {
        db.execute(3L, spec, "SELECT x FROM long_sequence(5000)");
        final Intent.SqlCompleted done = sink.await(Intent.SqlCompleted.class, c -> c.requestId() == 3L, WAIT);
        assertThat(done.rows()).isEqualTo(1_000);
        assertThat(done.truncated()).isTrue();
        assertThat(sink.of(Intent.SqlRows.class)).isNotEmpty();
    }

    /// A DDL statement completes with zero rows and stores no result.
    @Test
    void ddlReportsNoRows() {
        db.execute(4L, spec, "CREATE TABLE IF NOT EXISTS ddl_probe (ts TIMESTAMP, v LONG) TIMESTAMP(ts) PARTITION BY DAY");
        final Intent.SqlCompleted done = sink.await(Intent.SqlCompleted.class, c -> c.requestId() == 4L, WAIT);
        assertThat(done.rows()).isZero();
        assertThat(results.get(4L)).isNull();
    }

    /// A query on a missing table fails with the server's message naming the table.
    @Test
    void errorsAreReportedWithTheServerMessage() {
        db.execute(5L, spec, "SELECT * FROM no_such_table");
        final Intent.SqlFailed failed = sink.await(Intent.SqlFailed.class, f -> f.requestId() == 5L, WAIT);
        assertThat(failed.message()).contains("no_such_table");
    }

    /// Cancel returns to its caller, the reducer thread, in under 500 ms, the long query is cancelled within 3 s, and
    /// the connection then runs the next statement.
    @Test
    void cancelOfALongQueryReturnsQuickly() {
        db.execute(6L, spec, "SELECT sum(x) FROM long_sequence(100_000_000_000)");
        sink.await(Intent.SqlStarted.class, s -> s.requestId() == 6L, WAIT);
        sleep(300);
        final long start = System.nanoTime();
        db.cancel(6L);
        final long callMillis = (System.nanoTime() - start) / 1_000_000L;
        // a cancel made on the caller's thread waits for QuestDB's answer, about 1 000 ms; handed off it returns at
        // once, though a loaded CI runner, its cores busy with the query, took 125 ms: 500 ms tells them apart
        assertThat(callMillis).as("cancel() must not block its caller, the reducer thread; took %d ms", callMillis).isLessThan(500L);
        sink.await(Intent.SqlCancelled.class, c -> c.requestId() == 6L, Duration.ofSeconds(5));
        final long tookMillis = (System.nanoTime() - start) / 1_000_000L;
        assertThat(tookMillis).as("cancel took %d ms", tookMillis).isLessThan(3_000L);

        // the lane keeps working afterwards
        db.execute(7L, spec, "SELECT 1");
        sink.await(Intent.SqlCompleted.class, c -> c.requestId() == 7L, WAIT);
    }

    /// A run queued behind a running one and cancelled before it starts reports cancelled and never starts.
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

    /// With the lane held by a connect to a server that never answers, a write queued behind it is answered with a
    /// failure on close and never reaches the real server.
    @Test
    void aStatementQueuedWhenComandoQuitsDoesNotRun() throws Exception {
        // the lane is held by a connect to a server that accepts and never answers; behind it, a write to the real one
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final ConnectionSpec stuck = new ConnectionSpec("probe", "127.0.0.1", silent.getLocalPort(), "qdb", "admin", "quest", false);
            final ConnectionSpec real = new ConnectionSpec("probe", "127.0.0.1", questDb.pgPort(), "qdb", "admin", "quest", false);
            db.execute(10L, stuck, "SELECT 1");
            final CompletableFuture<QueryPort.Rows> queued = db.query(real, "CREATE TABLE closing_probe (ts TIMESTAMP, v DOUBLE) TIMESTAMP(ts)", 1);
            db.close();
            assertThatThrownBy(() -> queued.get(WAIT.toSeconds(), TimeUnit.SECONDS)).as("its caller is answered")
                    .hasMessageContaining("did not run");
        }

        final DbAdapter after = new DbAdapter(sink, results, new DbSettings(0, 1_000, 500, 60, 1_000L));
        try {
            final QueryPort.Rows tables = after.query(spec, "SELECT count() FROM tables() WHERE table_name = 'closing_probe'", 1).get();
            assertThat(tables.rows().getFirst().getFirst()).as("the queued write never ran").isEqualTo("0");
        } finally {
            after.close();
        }
    }

    /// A run on an unreachable connection reports the connection failed and the run failed.
    @Test
    void connectFailureIsReported() {
        final ConnectionSpec nowhere = new ConnectionSpec("nowhere", "127.0.0.1", 1, "qdb", "admin", "quest", false);
        db.execute(10L, nowhere, "SELECT 1");
        sink.await(Intent.ConnectionFailed.class, f -> f.name().equals("nowhere"), WAIT);
        sink.await(Intent.SqlFailed.class, f -> f.requestId() == 10L, WAIT);
    }

    /// The assistant's query answers its columns and at most the cap of text rows, marked truncated, and a table
    /// description lists each column with its type.
    @Test
    void queryForClaudeReturnsCappedTextRows() throws Exception {
        final var rows = db.query(spec, "SELECT x, x * 2 y FROM long_sequence(10)", 4).get();
        assertThat(rows.columns()).containsExactly("x", "y");
        assertThat(rows.rows()).hasSize(4).first().isEqualTo(List.of("1", "2"));
        assertThat(rows.truncated()).isTrue();

        db.query(spec, "CREATE TABLE IF NOT EXISTS described (ts TIMESTAMP, v DOUBLE) TIMESTAMP(ts)", 1).get();
        final var described = db.describe(spec, "described").get();
        assertThat(described.columns()).contains("column", "type");
        assertThat(described.rows()).extracting(r -> r.getFirst()).containsExactly("ts", "v");
    }

    /// Loading the schema lists a table's columns with names (a quoted mixed-case one included) and types, and shows a
    /// tree whose table and column nodes insert a `SELECT … LIMIT 100` and the quoted column name.
    @Test
    void schemaListsTablesWithTheirColumnsAndShowsATree() throws Exception {
        final SchemaRegistry schemas = new SchemaRegistry();
        db.close();
        db = new DbAdapter(sink, results, schemas, new DbSettings(0, 1_000, 500, 60, 1_000L));
        db.query(spec, "CREATE TABLE IF NOT EXISTS schema_probe (ts TIMESTAMP, \"Mixed Case\" SYMBOL, v DOUBLE) TIMESTAMP(ts)", 1).get();
        db.loadSchema(spec, true);
        final Intent.ShowView view = sink.await(Intent.ShowView.class, WAIT);
        final Schema.Table probe = schemas.of("local").tables().stream().filter(t -> t.name().equals("schema_probe")).findFirst().orElseThrow();
        assertThat(probe.columns()).extracting(Schema.Column::name).containsExactly("ts", "Mixed Case", "v");
        // QuestDB's own type names, not the PostgreSQL names its information schema maps them to
        assertThat(probe.columns()).extracting(Schema.Column::type).containsExactly("TIMESTAMP", "SYMBOL", "DOUBLE");
        final ViewSpec.TreeNode table = ((ViewSpec.Tree) view.spec()).root().children().stream()
                .filter(n -> n.label().equals("schema_probe")).findFirst().orElseThrow();
        assertThat(table.link()).isEqualTo("comando-insert:SELECT * FROM schema_probe LIMIT 100;");
        assertThat(table.children().get(1).link()).isEqualTo("comando-insert:\"Mixed Case\"");
    }

    /// Past the table limit the schema comes from the information schema, which names the columns but reports no
    /// `SYMBOL` type.
    @Test
    void aQuestDbWithMoreTablesThanTheLimitReadsTheInformationSchemaInstead() throws Exception {
        final SchemaRegistry schemas = new SchemaRegistry();
        db.close();
        db = new DbAdapter(sink, results, schemas, new DbSettings(0, 1_000, 500, 60, 1_000L), 0);
        db.query(spec, "CREATE TABLE IF NOT EXISTS wide_probe (ts TIMESTAMP, s SYMBOL) TIMESTAMP(ts)", 1).get();
        db.loadSchema(spec, true);
        sink.await(Intent.ShowView.class, WAIT);

        final Schema.Table probe = schemas.of("local").tables().stream().filter(t -> t.name().equals("wide_probe")).findFirst().orElseThrow();
        assertThat(probe.columns()).extracting(Schema.Column::name).containsExactly("ts", "s");
        // the information schema's PostgreSQL names: one query, not one per table
        assertThat(probe.columns()).extracting(Schema.Column::type).doesNotContain("SYMBOL");
    }

    /// Testing a reachable connection reports it ok.
    @Test
    void testerReportsReachability() {
        db.test(spec);
        final Intent.ConnectionTested ok = sink.await(Intent.ConnectionTested.class, WAIT);
        assertThat(ok.ok()).isTrue();
    }

    /// Closing an open connection reports it closed.
    @Test
    void closeReportsClosed() {
        db.open(spec);
        sink.await(Intent.ConnectionOpened.class, WAIT);
        db.close("local");
        sink.await(Intent.ConnectionClosed.class, WAIT);
    }

    /// The embedded server has no TLS: a connection that requires it fails both when opened and when tested.
    @Test
    void testAndOpenAgreeOnTheConnectionsTlsMode() {
        final ConnectionSpec tls = new ConnectionSpec("tls", "127.0.0.1", questDb.pgPort(), "qdb", "admin", "quest", false, "require");
        db.open(tls);
        final Intent.ConnectionFailed failed = sink.await(Intent.ConnectionFailed.class, f -> f.name().equals("tls"), WAIT);

        db.test(tls);
        final Intent.ConnectionTested tested = sink.await(Intent.ConnectionTested.class, WAIT);
        assertThat(tested.ok()).as("the test must fail where open fails: " + failed.error()).isFalse();
    }

    /// Closing an open connection ends its lane thread and leaves no lane, and opening the same name again gives a
    /// working connection that answers a query.
    @Test
    void closingAConnectionStopsItsLaneAndTheNameOpensAgain() throws Exception {
        final ConnectionSpec released = new ConnectionSpec("released", "127.0.0.1", questDb.pgPort(), "qdb", "admin", "quest", false);
        db.open(released);
        sink.await(Intent.ConnectionOpened.class, o -> o.name().equals("released"), WAIT);
        final Thread lane = laneThread("released").orElseThrow();

        db.close("released");
        sink.await(Intent.ConnectionClosed.class, c -> c.name().equals("released"), WAIT);
        assertThat(lane.join(WAIT)).as("the closed connection's lane thread ends").isTrue();
        assertThat(db.laneCount()).isZero();

        sink.clear();
        db.open(released);
        sink.await(Intent.ConnectionOpened.class, o -> o.name().equals("released"), WAIT);
        assertThat(db.query(released, "SELECT 1 x", 1).get().rows()).containsExactly(List.of("1"));
    }

    /// After the adapter is closed, open, execute and test make no lane and send no intent, and query and describe
    /// answer completed exceptionally.
    @Test
    void aClosedAdapterMakesNoLaneAndRunsNothing() {
        db.close();
        sink.clear();
        db.open(spec);
        db.execute(7L, spec, "SELECT 1");
        db.test(spec);
        assertThat(db.laneCount()).as("no lane, so no connection, after close").isZero();
        assertThat(db.query(spec, "SELECT 1", 1)).isCompletedExceptionally();
        assertThat(db.describe(spec, "t")).isCompletedExceptionally();
        assertThat(sink.intents()).isEmpty();
    }

    /// A connection to a port nothing listens on reports its failure, its lane thread ends and no lane is kept, and a
    /// later close still reports it closed.
    @Test
    void aConnectionThatFailsToOpenKeepsNoLaneAndStillReportsItsClose() throws Exception {
        final ConnectionSpec nowhere = new ConnectionSpec("unreachable", "127.0.0.1", 1, "qdb", "admin", "quest", false);
        db.open(nowhere);
        sink.await(Intent.ConnectionFailed.class, f -> f.name().equals("unreachable"), WAIT);
        final Optional<Thread> lane = laneThread("unreachable");
        if (lane.isPresent()) {
            assertThat(lane.get().join(WAIT)).as("the failed connection's lane thread ends").isTrue();
        }
        assertThat(db.laneCount()).isZero();

        db.close("unreachable");
        sink.await(Intent.ConnectionClosed.class, c -> c.name().equals("unreachable"), WAIT);
    }

    private static Optional<Thread> laneThread(final String connection) {
        return Threads.alive("db-" + connection).stream().findFirst();
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
