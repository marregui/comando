package dev.marregui.comando.db;

import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.Intent;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The database port: a lane per connection name, a timer for health checks and cancel escalation, and
 * virtual threads for connection tests. Every method returns at once.
 */
public final class DbAdapter implements DatabasePort, QueryPort, AutoCloseable {

    private final IntentSink sink;
    private final ResultRegistry results;
    private final SchemaRegistry schemas;
    private final DbSettings settings;
    private final int maxCatalogueTables;
    private final ConcurrentHashMap<String, Lane> lanes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Lane> laneOfRequest = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer;
    private final ExecutorService testers = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService cancellers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean closed;

    public DbAdapter(IntentSink sink, ResultRegistry results, DbSettings settings) {
        this(sink, results, new SchemaRegistry(), settings);
    }

    public DbAdapter(IntentSink sink, ResultRegistry results, SchemaRegistry schemas, DbSettings settings) {
        this(sink, results, schemas, settings, MAX_CATALOGUE_TABLES);
    }

    DbAdapter(IntentSink sink, ResultRegistry results, SchemaRegistry schemas, DbSettings settings, int maxCatalogueTables) {
        this.sink = sink;
        this.results = results;
        this.schemas = schemas;
        this.settings = settings;
        this.maxCatalogueTables = maxCatalogueTables;
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().name("db-timer").daemon(true).unstarted(r));
        long period = settings.healthPeriodSeconds();
        timer.scheduleWithFixedDelay(this::checkHealth, period, period, TimeUnit.SECONDS);
    }

    private Lane lane(String name) {
        return lanes.computeIfAbsent(name, n -> new Lane(n, sink, results, settings, timer, cancellers));
    }

    @Override
    public void open(ConnectionSpec spec) {
        lane(spec.name()).open(spec);
    }

    @Override
    public void close(String name) {
        Lane lane = lanes.get(name);
        if (lane == null) {
            sink.dispatch(new Intent.ConnectionClosed(name));
        } else {
            lane.disconnect();
        }
    }

    @Override
    public void test(ConnectionSpec spec) {
        testers.execute(() -> {
            Properties props = new Properties();
            props.setProperty("user", spec.user());
            props.setProperty("password", spec.password());
            props.setProperty("loginTimeout", "10");
            try (Connection c = DriverManager.getConnection(spec.jdbcUrl(), props)) {
                boolean ok = c.isValid(10);
                sink.dispatch(new Intent.ConnectionTested(spec.name(), ok, ok ? "reachable" : "not valid"));
            } catch (SQLException e) {
                sink.dispatch(new Intent.ConnectionTested(spec.name(), false, Lane.message(e)));
            }
        });
    }

    @Override
    public void execute(long requestId, ConnectionSpec spec, String sql) {
        Lane lane = lane(spec.name());
        laneOfRequest.put(requestId, lane);
        lane.execute(requestId, spec, sql);
    }

    @Override
    public void cancel(long requestId) {
        Lane lane = laneOfRequest.get(requestId);
        if (lane != null) {
            lane.cancel(requestId);
        }
    }

    @Override
    public void release(long requestId) {
        laneOfRequest.remove(requestId);
        results.release(requestId);
    }

    @Override
    public CompletableFuture<Rows> query(ConnectionSpec spec, String sql, int maxRows) {
        CompletableFuture<Rows> out = new CompletableFuture<>();
        lane(spec.name()).query(spec, sql, maxRows, out);
        return out;
    }

    @Override
    public CompletableFuture<Rows> describe(ConnectionSpec spec, String table) {
        CompletableFuture<Rows> out = new CompletableFuture<>();
        lane(spec.name()).describe(spec, table, out);
        return out;
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
     * tables; a larger QuestDB and any other server answer the information schema, in one round trip.
     */
    @Override
    public void loadSchema(ConnectionSpec spec, boolean show) {
        questDbSchema(spec)
                .exceptionallyCompose(e -> query(spec, COLUMNS_SQL, MAX_SCHEMA_ROWS).thenApply(DbAdapter::fromColumns))
                .whenComplete((schema, e) -> {
                    if (e != null) {
                        sink.dispatch(new Intent.Notify(Notice.Level.ERROR, "Cannot read the schema of " + spec.name() + ": "
                                + rootMessage(e), false));
                        return;
                    }
                    schemas.put(spec.name(), schema);
                    if (show) {
                        sink.dispatch(new Intent.ShowView("", Effect.LoadSchema.viewTitle(spec.name()), new ViewSpec.Tree(tree(spec.name(), schema)),
                                Placement.SIDE));
                    }
                });
    }

    /** Rows of (table, column, type), in table order, grouped into tables. */
    static Schema fromColumns(QueryPort.Rows rows) {
        Map<String, List<Schema.Column>> tables = new LinkedHashMap<>();
        for (List<String> r : rows.rows()) {
            tables.computeIfAbsent(r.get(0), k -> new ArrayList<>()).add(new Schema.Column(r.get(1), r.get(2)));
        }
        if (tables.isEmpty()) {
            throw new IllegalStateException("the information schema lists no columns");
        }
        List<Schema.Table> out = new ArrayList<>();
        tables.forEach((name, columns) -> out.add(new Schema.Table(name, columns)));
        return new Schema(out);
    }

    /** QuestDB's own catalogue: {@code tables()}, then the columns of each; fails above the table limit. */
    private CompletableFuture<Schema> questDbSchema(ConnectionSpec spec) {
        return query(spec, "SELECT table_name FROM tables() ORDER BY table_name", MAX_SCHEMA_ROWS).thenCompose(t -> {
            List<String> names = t.rows().stream().map(List::getFirst).toList();
            if (names.size() > maxCatalogueTables) {
                return CompletableFuture.failedFuture(new IllegalStateException(names.size() + " tables: the information schema answers"));
            }
            List<CompletableFuture<Schema.Table>> each = new ArrayList<>();
            for (String name : names) {
                each.add(describe(spec, name).thenApply(d -> new Schema.Table(name,
                        d.rows().stream().map(r -> new Schema.Column(r.get(0), r.size() > 1 ? r.get(1) : "")).toList())));
            }
            return CompletableFuture.allOf(each.toArray(CompletableFuture[]::new))
                    .thenApply(v -> new Schema(each.stream().map(CompletableFuture::join).toList()));
        });
    }

    static ViewSpec.TreeNode tree(String connection, Schema schema) {
        List<ViewSpec.TreeNode> tables = new ArrayList<>();
        for (Schema.Table t : schema.tables()) {
            List<ViewSpec.TreeNode> columns = new ArrayList<>();
            for (Schema.Column c : t.columns()) {
                columns.add(new ViewSpec.TreeNode(c.name(), c.type(), "comando-insert:" + quoteIfNeeded(c.name()), List.of()));
            }
            tables.add(new ViewSpec.TreeNode(t.name(), count(t.columns().size(), "column"),
                    "comando-insert:SELECT * FROM " + quoteIfNeeded(t.name()) + " LIMIT 100;", columns));
        }
        return new ViewSpec.TreeNode(connection, count(schema.tables().size(), "table"), tables);
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static String quoteIfNeeded(String name) {
        return name.matches("[a-z_][a-z0-9_]*") ? name : Lane.quote(name);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t != t.getCause()) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private void checkHealth() {
        for (Lane lane : lanes.values()) {
            lane.checkHealth();
        }
    }

    /** Closes every lane and stops the timer. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Lane lane : lanes.values()) {
            lane.close();
        }
        lanes.clear();
        timer.shutdownNow();
        testers.shutdownNow();
        cancellers.shutdown();
    }
}
