package dev.marregui.comando.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbCancel;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.port.QueryPort;
import dev.marregui.comando.core.result.ColumnKind;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [CdbAdapter] without the engine: which connections are cdb ones, where their database lives, how cdb types read,
/// and how open, test, run, close and the adapter's own close answer when the database cannot be opened.
/// - A host of `cdb` or `cdb:<folder>` is a cdb connection; the folder resolves under the default root, `~` under the
///   user's home.
/// - SYMBOL and VARCHAR read as text; the other types as their own kind.
/// - A folder that cannot hold a database fails the open, the run and Claude's query, and the test says so.
/// - A connection closed, or the adapter closed, leaves no strand; work after the adapter's close is refused.
/// - Each run gets a [CdbCancel] of its own, and the adapter's cancel of a running request cancels it.
class CdbAdapterTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @TempDir
    Path home;

    private final RecordingSink sink = new RecordingSink();
    private CdbAdapter cdb;

    @BeforeEach
    void setUp() {
        cdb = new CdbAdapter(sink, new ResultRegistry(), new SchemaRegistry(), DbSettings.DEFAULTS,
                home.resolve("cdb"));
    }

    @AfterEach
    void tearDown() {
        cdb.close();
    }

    /// `cdb` alone, padded, or with a folder after `:` is a cdb host; a JDBC host, or one that only starts with the
    /// letters, is not.
    @Test
    void aHostOfCdbAloneOrWithAFolderIsACdbConnection() {
        assertThat(CdbAdapter.handles(spec("cdb"))).isTrue();
        assertThat(CdbAdapter.handles(spec(" cdb "))).isTrue();
        assertThat(CdbAdapter.handles(spec("cdb:/var/data"))).isTrue();
        assertThat(CdbAdapter.handles(spec("127.0.0.1"))).isFalse();
        assertThat(CdbAdapter.handles(spec("cdbhost"))).isFalse();
    }

    /// No folder, or an empty one, is the default root; a relative folder resolves under it, an absolute one is kept,
    /// `~` and `~/x` are the user's home and a folder in it; a JDBC connection has no root.
    @Test
    void theRootIsTheHostsFolderOrTheDefaultRoot() {
        final Path root = home.resolve("cdb");
        final Path userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();

        assertThat(CdbAdapter.root(spec("cdb"), root)).isEqualTo(root);
        assertThat(CdbAdapter.root(spec("cdb:"), root)).isEqualTo(root);
        assertThat(CdbAdapter.root(spec("cdb:metrics"), root)).isEqualTo(root.resolve("metrics"));
        final Path elsewhere = home.resolve("elsewhere");
        assertThat(CdbAdapter.root(spec("cdb:" + elsewhere), root)).isEqualTo(elsewhere);
        assertThat(CdbAdapter.root(spec("cdb:~"), root)).isEqualTo(userHome);
        assertThat(CdbAdapter.root(spec("cdb:~/db"), root)).isEqualTo(userHome.resolve("db"));
        assertThatThrownBy(() -> CdbAdapter.root(spec("127.0.0.1"), root))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /// SYMBOL and VARCHAR are text; BOOLEAN, LONG, DOUBLE and TIMESTAMP keep their own kind.
    @Test
    void eachCdbTypeReadsAsItsKind() {
        assertThat(CdbResultView.kindOf(CdbType.BOOLEAN)).isEqualTo(ColumnKind.BOOLEAN);
        assertThat(CdbResultView.kindOf(CdbType.LONG)).isEqualTo(ColumnKind.LONG);
        assertThat(CdbResultView.kindOf(CdbType.DOUBLE)).isEqualTo(ColumnKind.DOUBLE);
        assertThat(CdbResultView.kindOf(CdbType.TIMESTAMP)).isEqualTo(ColumnKind.TIMESTAMP);
        assertThat(CdbResultView.kindOf(CdbType.SYMBOL)).isEqualTo(ColumnKind.TEXT);
        assertThat(CdbResultView.kindOf(CdbType.VARCHAR)).isEqualTo(ColumnKind.TEXT);
    }

    /// A folder that is a regular file opens no database: Connect fails, a run fails after the connection does, and
    /// Claude's query fails its future; nothing says the connection opened or the run started.
    @Test
    void aFolderThatIsAFileFailsTheOpenTheRunAndTheQuery() throws Exception {
        final Path file = Files.writeString(home.resolve("plain"), "not a database");
        final ConnectionSpec spec = spec("cdb:" + file);

        cdb.open(spec);
        sink.await(Intent.ConnectionFailed.class, f -> f.name().equals("local"), WAIT);
        cdb.execute(1L, spec, "SELECT 1");
        sink.await(Intent.SqlFailed.class, f -> f.requestId() == 1L, WAIT);
        final CompletableFuture<QueryPort.Rows> rows = cdb.query(spec, "SELECT 1", 10);

        assertThatThrownBy(() -> rows.get(WAIT.toSeconds(), TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(sink.of(Intent.ConnectionOpened.class)).isEmpty();
        assertThat(sink.of(Intent.SqlStarted.class)).isEmpty();
    }

    /// A folder not there yet under one comando can write in is reachable, and so is a folder; a regular file is not.
    @Test
    void aTestSaysWhetherTheFolderCanHoldADatabase() throws Exception {
        final Path file = Files.writeString(home.resolve("plain"), "not a database");

        cdb.test(spec("fresh", "cdb:" + home.resolve("new/db")));
        cdb.test(spec("folder", "cdb:" + home));
        cdb.test(spec("file", "cdb:" + file));

        assertThat(sink.await(Intent.ConnectionTested.class, t -> t.name().equals("fresh"), WAIT).ok()).isTrue();
        assertThat(sink.await(Intent.ConnectionTested.class, t -> t.name().equals("folder"), WAIT).ok()).isTrue();
        assertThat(sink.await(Intent.ConnectionTested.class, t -> t.name().equals("file"), WAIT).ok()).isFalse();
    }

    /// Closing a connection that never opened still says closed, and its strand leaves with it.
    @Test
    void closingAConnectionLeavesNoStrand() {
        cdb.test(spec("cdb"));
        sink.await(Intent.ConnectionTested.class, WAIT);

        cdb.close("local");

        sink.await(Intent.ConnectionClosed.class, c -> c.name().equals("local"), WAIT);
        assertThat(cdb.strandCount()).isZero();
    }

    /// After the adapter's close a run ends cancelled, Claude's query fails, a test says unreachable, no strand is
    /// made, no connection is told closed, and a second close does nothing.
    @Test
    void workAfterTheAdaptersCloseIsRefused() {
        cdb.test(spec("cdb"));
        sink.await(Intent.ConnectionTested.class, WAIT);
        cdb.close();
        sink.clear();

        cdb.execute(7L, spec("cdb"), "SELECT 1");
        cdb.test(spec("cdb"));
        final CompletableFuture<QueryPort.Rows> rows = cdb.query(spec("cdb"), "SELECT 1", 10);
        cdb.close();

        assertThat(sink.of(Intent.SqlCancelled.class)).extracting(Intent.SqlCancelled::requestId).containsExactly(7L);
        assertThat(sink.of(Intent.ConnectionTested.class)).singleElement()
                .satisfies(t -> assertThat(t.ok()).isFalse());
        assertThat(rows).isCompletedExceptionally();
        assertThat(sink.of(Intent.ConnectionClosed.class)).isEmpty();
        assertThat(cdb.strandCount()).isZero();
    }

    /// The adapter's cancel of a request whose query is running cancels the [CdbCancel] the query was given; the
    /// query's failure then ends the request as cancelled, not failed.
    @Test
    void aCancelOfARunningRequestCancelsItsQuery() throws Exception {
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicReference<CdbCancel> seen = new AtomicReference<>();
        final CdbAdapter.Runner runner = (db, sql, maxRows, cancel) -> {
            seen.set(cancel);
            running.countDown();
            await(go);
            throw new CdbException("query cancelled [cancelled=" + cancel.cancelled() + ']', -1);
        };
        try (final CdbAdapter own = new CdbAdapter(sink, new ResultRegistry(), new SchemaRegistry(),
                DbSettings.DEFAULTS, home.resolve("own"), runner)) {
            own.execute(1L, spec("cdb"), "SELECT 1");
            assertThat(running.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            assertThat(seen.get().cancelled()).isFalse();

            own.cancel(1L);

            assertThat(seen.get().cancelled()).isTrue();
            go.countDown();
            sink.await(Intent.SqlCancelled.class, c -> c.requestId() == 1L, WAIT);
            assertThat(sink.of(Intent.SqlFailed.class)).isEmpty();
        }
    }

    /// Each run is given a [CdbCancel] of its own that is not cancelled; a cancel of a request that has ended
    /// reaches nothing.
    @Test
    void eachRunGetsItsOwnCancelAndAnEndedRunKeepsNone() {
        final List<CdbCancel> seen = new CopyOnWriteArrayList<>();
        final CdbAdapter.Runner runner = (db, sql, maxRows, cancel) -> {
            seen.add(cancel);
            throw new CdbException("not run [sql=" + sql + ']', -1);
        };
        try (final CdbAdapter own = new CdbAdapter(sink, new ResultRegistry(), new SchemaRegistry(),
                DbSettings.DEFAULTS, home.resolve("own"), runner)) {
            own.execute(1L, spec("cdb"), "SELECT 1");
            sink.await(Intent.SqlFailed.class, f -> f.requestId() == 1L, WAIT);
            own.execute(2L, spec("cdb"), "SELECT 2");
            sink.await(Intent.SqlFailed.class, f -> f.requestId() == 2L, WAIT);

            own.cancel(1L);

            assertThat(new ArrayList<>(seen)).hasSize(2).doesNotHaveDuplicates()
                    .allSatisfy(c -> assertThat(c.cancelled()).isFalse());
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "the test did not release the run [waitSeconds=" + WAIT.toSeconds() + ']');
            }
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while the run waited");
        }
    }

    private static ConnectionSpec spec(final String host) {
        return spec("local", host);
    }

    private static ConnectionSpec spec(final String name, final String host) {
        return new ConnectionSpec(name, host, ConnectionSpec.DEFAULT_PORT, "", "", "", false);
    }
}
