package dev.marregui.comando.db;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.port.DatabasePort;
import dev.marregui.comando.core.port.QueryPort;

/**
 * The database port of the application: a connection of kind cdb ({@link CdbAdapter#handles}) goes to the
 * {@link CdbAdapter}, every other to the {@link DbAdapter}. A connection name keeps the adapter that last opened,
 * ran or loaded it, so its close and forget, and a request's cancel and release, reach the adapter that holds them; a
 * name whose host changes from one kind to the other is closed on the old adapter first. Every method returns at once,
 * as the adapters' do.
 */
public final class DbRouter implements DatabasePort, QueryPort, AutoCloseable {

    private final CdbAdapter cdb;
    private final Target cdbTarget;
    private final DbAdapter jdbc;
    private final Target jdbcTarget;
    /** The adapter that holds each connection, by name. */
    private final ConcurrentHashMap<String, Target> owners = new ConcurrentHashMap<>();
    /** The adapter that runs each request not yet released, by request id. */
    private final ConcurrentHashMap<Long, Target> requests = new ConcurrentHashMap<>();

    public DbRouter(final DbAdapter jdbc, final CdbAdapter cdb) {
        this.jdbc = jdbc;
        this.cdb = cdb;
        this.jdbcTarget = new Target(jdbc, jdbc);
        this.cdbTarget = new Target(cdb, cdb);
    }

    @Override
    public void open(final ConnectionSpec spec) {
        route(spec).db.open(spec);
    }

    @Override
    public void close(final String name) {
        final Target target = owners.remove(name);
        (target == null ? jdbcTarget : target).db.close(name);
    }

    /** Both adapters forget: the name may have been pointed from one kind to the other. */
    @Override
    public void forgetSchema(final String name) {
        jdbc.forgetSchema(name);
        cdb.forgetSchema(name);
    }

    @Override
    public void test(final ConnectionSpec spec) {
        pick(spec).db.test(spec);
    }

    @Override
    public void execute(final long requestId, final ConnectionSpec spec, final String sql) {
        final Target target = route(spec);
        requests.put(requestId, target); // before the run is queued, so a cancel always finds its adapter
        target.db.execute(requestId, spec, sql);
    }

    @Override
    public void cancel(final long requestId) {
        final Target target = requests.get(requestId);
        (target == null ? jdbcTarget : target).db.cancel(requestId);
    }

    @Override
    public void release(final long requestId) {
        final Target target = requests.remove(requestId);
        (target == null ? jdbcTarget : target).db.release(requestId);
    }

    @Override
    public void loadSchema(final ConnectionSpec spec, final boolean show) {
        route(spec).db.loadSchema(spec, show);
    }

    @Override
    public CompletableFuture<Rows> query(final ConnectionSpec spec, final String sql, final int maxRows) {
        return route(spec).query.query(spec, sql, maxRows);
    }

    @Override
    public CompletableFuture<Rows> describe(final ConnectionSpec spec, final String table) {
        return route(spec).query.describe(spec, table);
    }

    /** Closes both adapters, each attempted whatever the other does. */
    @Override
    public void close() {
        try {
            cdb.close();
        } finally {
            jdbc.close();
        }
    }

    private Target pick(final ConnectionSpec spec) {
        return CdbAdapter.handles(spec) ? cdbTarget : jdbcTarget;
    }

    /** The adapter of {@code spec}, kept as the holder of its name; the old holder of another kind closes the name. */
    private Target route(final ConnectionSpec spec) {
        final Target target = pick(spec);
        final Target old = owners.put(spec.name(), target);
        if (old != null && old != target) {
            old.db.close(spec.name());
        }
        return target;
    }

    /** One adapter, as the two ports it answers. */
    private record Target(DatabasePort db, QueryPort query) {
    }
}
