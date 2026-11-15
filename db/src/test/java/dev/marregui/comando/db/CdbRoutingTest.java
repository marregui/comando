package dev.marregui.comando.db;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbCancel;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;

/// [DbRouter] over the real adapters: a connection whose host is `cdb` or `cdb:<folder>` is held by the [CdbAdapter],
/// every other by the [DbAdapter], and the calls that name no spec find the adapter that holds the name or the
/// request.
/// - A run and a test of a cdb connection reach the cdb adapter and leave the JDBC adapter without a lane.
/// - A JDBC connection is run by the JDBC adapter, which fails its open here, and leaves the cdb adapter without a strand.
/// - A close reaches the adapter that holds the name; a name whose host changes kind is closed on the old adapter.
/// - A cancel of a running cdb request cancels the query's [CdbCancel].
class CdbRoutingTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @TempDir
    Path home;

    private final RecordingSink sink = new RecordingSink();
    private CdbAdapter cdb;
    private DbAdapter jdbc;
    private DbRouter router;

    @BeforeEach
    void setUp() {
        final ResultRegistry results = new ResultRegistry();
        final SchemaRegistry schemas = new SchemaRegistry();
        jdbc = new DbAdapter(sink, results, schemas, DbSettings.DEFAULTS);
        cdb = new CdbAdapter(sink, results, schemas, DbSettings.DEFAULTS, home.resolve("cdb"));
        router = new DbRouter(jdbc, cdb);
    }

    @AfterEach
    void tearDown() {
        router.close();
    }

    /// A test of a cdb connection answers from the cdb adapter's strand; no JDBC lane is made.
    @Test
    void aCdbConnectionIsHeldByTheCdbAdapter() {
        router.test(spec("local", "cdb"));
        router.execute(1L, spec("local", "cdb:metrics"), "SELECT 1");

        sink.await(Intent.ConnectionTested.class, t -> t.name().equals("local") && t.ok(), WAIT);
        sink.await(Intent.SqlStarted.class, s -> s.requestId() == 1L, WAIT);
        assertThat(cdb.strandCount()).isEqualTo(1);
        assertThat(jdbc.laneCount()).isZero();
    }

    /// A run of a JDBC connection is the JDBC adapter's: it fails against the closed port, and the cdb adapter makes no
    /// strand and starts nothing.
    @Test
    void aJdbcConnectionIsHeldByTheJdbcAdapter() {
        router.execute(1L, spec("remote", "127.0.0.1", 1), "SELECT 1");

        sink.await(Intent.SqlFailed.class, f -> f.requestId() == 1L, WAIT);
        assertThat(sink.of(Intent.SqlStarted.class)).isEmpty();
        assertThat(cdb.strandCount()).isZero();
    }

    /// A close names only the connection: it reaches the adapter that opened it, and the other tells nothing.
    @Test
    void aCloseReachesTheAdapterThatHoldsTheName() {
        router.open(spec("local", "cdb"));
        sink.await(Intent.ConnectionOpened.class, o -> o.name().equals("local"), WAIT);
        assertThat(cdb.strandCount()).isEqualTo(1);

        router.close("local");

        sink.await(Intent.ConnectionClosed.class, c -> c.name().equals("local"), WAIT);
        assertThat(cdb.strandCount()).isZero();
        assertThat(sink.of(Intent.ConnectionClosed.class)).hasSize(1);
    }

    /// A name that ran on JDBC and is then pointed at a cdb folder is closed on the JDBC adapter, and the cdb
    /// adapter holds it from then on.
    @Test
    void aNameChangedFromJdbcToCdbIsClosedOnTheOldAdapter() {
        router.execute(1L, spec("local", "127.0.0.1", 1), "SELECT 1");
        sink.await(Intent.SqlFailed.class, f -> f.requestId() == 1L, WAIT);

        router.execute(2L, spec("local", "cdb"), "SELECT 1");

        sink.await(Intent.ConnectionClosed.class, c -> c.name().equals("local"), WAIT);
        sink.await(Intent.SqlStarted.class, s -> s.requestId() == 2L, WAIT);
        assertThat(cdb.strandCount()).isEqualTo(1);
        router.close("local");
        sink.await(Intent.ConnectionClosed.class, c -> c.name().equals("local"), WAIT);
        assertThat(cdb.strandCount()).isZero();
    }

    /// The router's cancel of a request it ran on cdb cancels the query's [CdbCancel], and the request ends as
    /// cancelled.
    @Test
    void aCancelThroughTheRouterReachesTheRunningCdbQuery() throws Exception {
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicReference<CdbCancel> seen = new AtomicReference<>();
        final CdbAdapter.Runner runner = (db, sql, maxRows, cancel) -> {
            seen.set(cancel);
            running.countDown();
            try {
                go.await(WAIT.toSeconds(), TimeUnit.SECONDS);
            } catch (final InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            throw new CdbException("query cancelled [cancelled=" + cancel.cancelled() + ']', -1);
        };
        try (final CdbAdapter own = new CdbAdapter(sink, new ResultRegistry(), new SchemaRegistry(),
                DbSettings.DEFAULTS, home.resolve("own"), runner);
                final DbRouter routed = new DbRouter(new DbAdapter(sink, new ResultRegistry(), DbSettings.DEFAULTS),
                        own)) {
            routed.execute(5L, spec("local", "cdb"), "SELECT 1");
            assertThat(running.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

            routed.cancel(5L);

            assertThat(seen.get().cancelled()).isTrue();
            go.countDown();
            sink.await(Intent.SqlCancelled.class, c -> c.requestId() == 5L, WAIT);
            routed.release(5L);
        }
    }

    private static ConnectionSpec spec(final String name, final String host) {
        return spec(name, host, ConnectionSpec.DEFAULT_PORT);
    }

    private static ConnectionSpec spec(final String name, final String host, final int port) {
        return new ConnectionSpec(name, host, port, "", "", "", false);
    }
}
