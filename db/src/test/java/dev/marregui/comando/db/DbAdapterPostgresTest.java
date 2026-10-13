package dev.marregui.comando.db;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.result.ColumnKind;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.result.ResultView;
import dev.marregui.comando.core.schema.Schema;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.testkit.RecordingSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The adapter against a real Postgres: types, cancel, schema and describe through the driver's metadata. */
@Testcontainers(disabledWithoutDocker = true)
class DbAdapterPostgresTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine");

    private static ConnectionSpec spec;
    private final RecordingSink sink = new RecordingSink();
    private final ResultRegistry results = new ResultRegistry();
    private final SchemaRegistry schemas = new SchemaRegistry();
    private DbAdapter db;

    @BeforeAll
    static void spec() {
        spec = new ConnectionSpec("pg", PG.getHost(), PG.getMappedPort(5432), PG.getDatabaseName(), PG.getUsername(), PG.getPassword(),
                false, "disable");
    }

    @BeforeEach
    void setUp() {
        db = new DbAdapter(sink, results, schemas, new DbSettings(0, 1_000, 500, 60, 1_000L));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void typesMapToColumnKinds() {
        db.execute(1L, spec, "SELECT 1::int i, 2.5::float8 d, true b, 'x'::text t, TIMESTAMP '2024-01-01 00:00:01' ts, NULL::int n");
        sink.await(Intent.SqlCompleted.class, c -> c.requestId() == 1L, WAIT);
        ResultView r = results.get(1L);
        assertThat(List.of(r.kind(0), r.kind(1), r.kind(2), r.kind(3), r.kind(4)))
                .containsExactly(ColumnKind.LONG, ColumnKind.DOUBLE, ColumnKind.BOOLEAN, ColumnKind.TEXT, ColumnKind.TIMESTAMP);
        assertThat(r.getMicros(0, 4)).isEqualTo(1_704_067_201_000_000L);
        assertThat(r.isNull(0, 5)).isTrue();
    }

    @Test
    void cancelStopsASleepingQueryAtOnce() {
        db.execute(2L, spec, "SELECT pg_sleep(30)");
        sink.await(Intent.SqlStarted.class, c -> c.requestId() == 2L, WAIT);
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long t0 = System.nanoTime();
        db.cancel(2L);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(100));
        sink.await(Intent.SqlCancelled.class, c -> c.requestId() == 2L, Duration.ofSeconds(5));
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void aStatementWithdrawnWhileQueuedNeverRuns() throws Exception {
        db.query(spec, "CREATE TABLE IF NOT EXISTS withdrawn (id int)", 1).get();
        db.execute(3L, spec, "SELECT pg_sleep(1)");
        sink.await(Intent.SqlStarted.class, c -> c.requestId() == 3L, WAIT);
        var queued = db.query(spec, "INSERT INTO withdrawn VALUES (1)", 1);
        assertThat(queued.cancel(false)).isTrue(); // the tool's timeout gives up while it waits behind the user's run
        sink.await(Intent.SqlCompleted.class, c -> c.requestId() == 3L, WAIT);
        var count = db.query(spec, "SELECT count(*) FROM withdrawn", 1).get();
        assertThat(count.rows().getFirst().getFirst()).isEqualTo("0");
    }

    @Test
    void schemaComesFromTheInformationSchema() throws Exception {
        db.query(spec, "CREATE TABLE IF NOT EXISTS trades (ts timestamptz, symbol text, price double precision)", 1).get();
        db.loadSchema(spec, false);
        long deadline = System.currentTimeMillis() + 10_000;
        while (schemas.of("pg").tables().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        Schema.Table trades = schemas.of("pg").tables().stream().filter(t -> t.name().equals("trades")).findFirst().orElseThrow();
        assertThat(trades.columns()).extracting(Schema.Column::name).containsExactly("ts", "symbol", "price");
        assertThat(trades.columns().get(2).type()).isEqualTo("double precision");
    }

    @Test
    void describeFallsBackToDriverMetadata() throws Exception {
        db.query(spec, "CREATE TABLE IF NOT EXISTS described (id bigint, name text)", 1).get();
        var rows = db.describe(spec, "described").get();
        assertThat(rows.rows()).extracting(r -> r.getFirst()).containsExactly("id", "name");
    }
}
