package dev.marregui.comando.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.Errands;
import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.port.DatabasePort;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.QueryPort;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.schema.Schema;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;

/**
 * The database port: a lane per connection name while the connection is open or has work queued, a timer for
 * health checks and cancel escalation, and virtual threads for connection tests and cancels. Every method returns at
 * once; outcomes come back as intents, and Claude's queries as futures.
 */
public final class DbAdapter implements DatabasePort, QueryPort, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(DbAdapter.class.getName());

    private final IntentSink sink;
    private final ResultRegistry results;
    private final SchemaRegistry schemas;
    private final DbSettings settings;
    private final int maxCatalogueTables;
    private final ConcurrentHashMap<String, Lane> lanes = new ConcurrentHashMap<>();
    /** Each connection's count of closes and forgets, so a schema load can tell it was overtaken. */
    private final ConcurrentHashMap<String, AtomicLong> changes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Lane> laneOfRequest = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer;
    /** Connection tests: one blocking connect per gesture, named, interrupted and waited for at close. */
    private final Errands testers = new Errands("db");
    private final ExecutorService cancellers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean closed;

    public DbAdapter(final IntentSink sink, final ResultRegistry results, final DbSettings settings) {
        this(sink, results, new SchemaRegistry(), settings);
    }

    public DbAdapter(final IntentSink sink, final ResultRegistry results, final SchemaRegistry schemas, final DbSettings settings) {
        this(sink, results, schemas, settings, MAX_CATALOGUE_TABLES);
    }

    DbAdapter(final IntentSink sink, final ResultRegistry results, final SchemaRegistry schemas, final DbSettings settings, final int maxCatalogueTables) {
        this.sink = sink;
        this.results = results;
        this.schemas = schemas;
        this.settings = settings;
        this.maxCatalogueTables = maxCatalogueTables;
        this.timer = Threads.timedStrand("db-timer");
        final long period = settings.healthPeriodSeconds();
        timer.scheduleWithFixedDelay(this::checkHealth, period, period, TimeUnit.SECONDS);
    }

    /**
     * Queues work on the lane of {@code name}; a lane that has stopped refuses it and a new lane takes it. Once the
     * adapter is closed no lane is made and nothing is queued: answers false.
     */
    private boolean onLane(final String name, final Predicate<Lane> work) {
        if (closed) {
            return false;
        }
        Lane lane = lanes.computeIfAbsent(name, this::newLane);
        while (!work.test(lane)) {
            lanes.remove(name, lane);
            if (closed) {
                return false;
            }
            lane = lanes.computeIfAbsent(name, this::newLane);
        }
        if (closed) {
            lane.close(); // made while close walked the lanes: it would hold a connection nobody closes
        }
        return true;
    }

    private Lane newLane(final String name) {
        return new Lane(name, sink, results, settings, timer, cancellers, stopped -> lanes.remove(name, stopped));
    }

    /** Lanes alive now; a closed connection's lane leaves once its last task ends. */
    int laneCount() {
        return lanes.size();
    }

    @Override
    public void open(final ConnectionSpec spec) {
        onLane(spec.name(), lane -> lane.open(spec));
    }

    @Override
    public void close(final String name) {
        changed(name);
        final Lane lane = lanes.get(name);
        if (lane == null || !lane.disconnect()) {
            sink.dispatch(new Intent.ConnectionClosed(name)); // no lane, or one that stopped: nothing is open
        }
    }

    @Override
    public void forgetSchema(final String name) {
        synchronized (changes) {
            changed(name);
            schemas.forget(name);
        }
    }

    @Override
    public void test(final ConnectionSpec spec) {
        if (closed) {
            return;
        }
        testers.start("db-test-" + spec.name(), () -> {
            try (Connection c = DriverManager.getConnection(spec.jdbcUrl(), Lane.driverProperties(spec))) {
                final boolean ok = c.isValid(10);
                sink.dispatch(new Intent.ConnectionTested(spec.name(), ok, ok ? "reachable" : "not valid"));
            } catch (final SQLException e) {
                sink.dispatch(new Intent.ConnectionTested(spec.name(), false, Lane.message(e)));
            }
        });
    }

    @Override
    public void execute(final long requestId, final ConnectionSpec spec, final String sql) {
        onLane(spec.name(), lane -> {
            laneOfRequest.put(requestId, lane); // before the run is queued, so a cancel always finds its lane
            return lane.execute(requestId, spec, sql);
        });
    }

    @Override
    public void cancel(final long requestId) {
        final Lane lane = laneOfRequest.get(requestId);
        if (lane != null) {
            lane.cancel(requestId);
        }
    }

    @Override
    public void release(final long requestId) {
        laneOfRequest.remove(requestId);
        results.release(requestId);
    }

    @Override
    public CompletableFuture<Rows> query(final ConnectionSpec spec, final String sql, final int maxRows) {
        return query(spec, sql, maxRows, () -> true);
    }

    private CompletableFuture<Rows> query(final ConnectionSpec spec, final String sql, final int maxRows, final BooleanSupplier wanted) {
        final CompletableFuture<Rows> out = new CompletableFuture<>();
        if (!onLane(spec.name(), lane -> lane.query(spec, sql, maxRows, out, wanted))) {
            out.completeExceptionally(closedError());
        }
        return out;
    }

    @Override
    public CompletableFuture<Rows> describe(final ConnectionSpec spec, final String table) {
        return describe(spec, table, () -> true);
    }

    private CompletableFuture<Rows> describe(final ConnectionSpec spec, final String table, final BooleanSupplier wanted) {
        final CompletableFuture<Rows> out = new CompletableFuture<>();
        if (!onLane(spec.name(), lane -> lane.describe(spec, table, out, wanted))) {
            out.completeExceptionally(closedError());
        }
        return out;
    }

    private static IllegalStateException closedError() {
        return new IllegalStateException("comando is closing; the database is no longer reached");
    }

    /** The connection's count of closes and forgets: a schema load started before one of them is overtaken by it. */
    private long changes(final String name) {
        return changes.computeIfAbsent(name, n -> new AtomicLong()).get();
    }

    /** Under the lock of {@link #changes}, so a load's last check and its publishing are one step against it. */
    private void changed(final String name) {
        synchronized (changes) {
            changes.computeIfAbsent(name, n -> new AtomicLong()).incrementAndGet();
        }
    }

    // ---- schema

    static final int MAX_SCHEMA_ROWS = 50_000;
    /**
     * QuestDB's own catalogue costs one statement per table on the connection's single lane, where the user's next
     * statement waits; above this many tables the schema comes from one information-schema query instead.
     */
    static final int MAX_CATALOGUE_TABLES = 50;
    static final String COLUMNS_SQL = "SELECT table_name, column_name, data_type FROM information_schema.columns "
            + "WHERE table_schema NOT IN ('pg_catalog', 'information_schema') ORDER BY table_name, ordinal_position";

    /**
     * QuestDB's own catalogue first, so the explorer and completion show its types ({@code SYMBOL}, {@code TIMESTAMP})
     * rather than the PostgreSQL names its information schema maps them to, for up to {@link #MAX_CATALOGUE_TABLES}
     * tables; a larger QuestDB and any other server answer the information schema, in one round trip. A Disconnect,
     * removal or change of the connection while it loads overtakes the load: its remaining queries do not run (they
     * would open the connection again, on the settings it had), and its tables are not kept.
     */
    @Override
    public void loadSchema(final ConnectionSpec spec, final boolean show) {
        final long at = changes(spec.name());
        final BooleanSupplier wanted = () -> changes(spec.name()) == at;
        questDbSchema(spec, wanted)
                .exceptionallyCompose(e -> overtaken(e) ? CompletableFuture.failedFuture(e)
                        : query(spec, COLUMNS_SQL, MAX_SCHEMA_ROWS, wanted).thenApply(DbAdapter::fromColumns))
                .whenComplete((schema, e) -> {
                    if (e != null && overtaken(e)) {
                        return;
                    }
                    if (e != null) {
                        sink.dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR, "Cannot read the schema of " + spec.name() + ": "
                                + rootMessage(e), e));
                        return;
                    }
                    // checked and kept as one step against a close or forget: an overtaken load keeps nothing
                    synchronized (changes) {
                        if (!wanted.getAsBoolean()) {
                            return;
                        }
                        schemas.put(spec.name(), schema);
                    }
                    if (show) {
                        sink.dispatch(new Intent.ShowView("", Effect.LoadSchema.viewTitle(spec.name()), new ViewSpec.Tree(tree(spec.name(), schema)),
                                Placement.SIDE));
                    }
                });
    }

    /** Rows of (table, column, type), in table order, grouped into tables. */
    static Schema fromColumns(final QueryPort.Rows rows) {
        final Map<String, List<Schema.Column>> tables = new LinkedHashMap<>();
        for (final List<String> r : rows.rows()) {
            tables.computeIfAbsent(r.get(0), k -> new ArrayList<>()).add(new Schema.Column(r.get(1), r.get(2)));
        }
        if (tables.isEmpty()) {
            throw new IllegalStateException("the information schema lists no columns");
        }
        final List<Schema.Table> out = new ArrayList<>();
        tables.forEach((name, columns) -> out.add(new Schema.Table(name, columns)));
        return new Schema(out);
    }

    /** QuestDB's own catalogue: {@code tables()}, then the columns of each; fails above the table limit. */
    private CompletableFuture<Schema> questDbSchema(final ConnectionSpec spec, final BooleanSupplier wanted) {
        return query(spec, "SELECT table_name FROM tables() ORDER BY table_name", MAX_SCHEMA_ROWS, wanted).thenCompose(t -> {
            final List<String> names = t.rows().stream().map(List::getFirst).toList();
            if (names.size() > maxCatalogueTables) {
                return CompletableFuture.failedFuture(new IllegalStateException(names.size() + " tables: the information schema answers"));
            }
            final List<CompletableFuture<Schema.Table>> each = new ArrayList<>();
            for (final String name : names) {
                each.add(describe(spec, name, wanted).thenApply(d -> new Schema.Table(name,
                        d.rows().stream().map(r -> new Schema.Column(r.get(0), r.size() > 1 ? r.get(1) : "")).toList())));
            }
            return CompletableFuture.allOf(each.toArray(CompletableFuture[]::new))
                    .thenApply(v -> new Schema(each.stream().map(CompletableFuture::join).toList()));
        });
    }

    static ViewSpec.TreeNode tree(final String connection, final Schema schema) {
        final List<ViewSpec.TreeNode> tables = new ArrayList<>();
        for (final Schema.Table t : schema.tables()) {
            final List<ViewSpec.TreeNode> columns = new ArrayList<>();
            for (final Schema.Column c : t.columns()) {
                columns.add(new ViewSpec.TreeNode(c.name(), c.type(), "comando-insert:" + quoteIfNeeded(c.name()), List.of()));
            }
            tables.add(new ViewSpec.TreeNode(t.name(), count(t.columns().size(), "column"),
                    "comando-insert:SELECT * FROM " + quoteIfNeeded(t.name()) + " LIMIT 100;", columns));
        }
        return new ViewSpec.TreeNode(connection, count(schema.tables().size(), "table"), tables);
    }

    private static String count(final int n, final String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static String quoteIfNeeded(final String name) {
        return name.matches("[a-z_][a-z0-9_]*") ? name : Lane.quote(name);
    }

    private static boolean overtaken(final Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof Lane.Overtaken) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(final Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t != t.getCause()) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private void checkHealth() {
        for (final Lane lane : lanes.values()) {
            lane.checkHealth();
        }
    }

    /**
     * Closes every lane and stops the timer. Idempotent. Every lane begins its close before any is waited for, so the
     * statements are cancelled together and the whole close waits one lane's bound, not one per lane.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // one snapshot for both passes: a lane made meanwhile sees closed and closes itself (onLane)
        final List<Lane> closing = List.copyOf(lanes.values());
        for (final Lane lane : closing) {
            lane.beginClose();
        }
        final long deadline = System.nanoTime() + Lane.CLOSE_WAIT_NANOS;
        for (final Lane lane : closing) {
            lane.finishClose(deadline);
        }
        lanes.clear();
        ExecutorStop.now("db", Map.of("db-timer", timer));
        testers.close();
        // a cancel the lanes' close handed over is let finish, so the server stops the statement it was sent for
        ExecutorStop.drain("db", ExecutorStop.WAIT, Map.of("db-cancel", cancellers));
    }
}
