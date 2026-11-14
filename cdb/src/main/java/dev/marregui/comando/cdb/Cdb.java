package dev.marregui.comando.cdb;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import dev.marregui.comando.cdb.exec.Executor;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.plan.Planner;
import dev.marregui.comando.cdb.sql.Parser;
import dev.marregui.comando.cdb.store.Store;

/**
 * comando's database: columnar, partitioned by time, the SQL its own and the kit's queries use, QuestDB's syntax and
 * results where they meet. The one public entry point of the module; only comando's {@code db.CdbAdapter} uses it.
 * Thread-safe: any number of queries at once, one writer per table.
 */
public final class Cdb implements AutoCloseable {

    private final Store store;

    private Cdb(final Store store) {
        this.store = store;
    }

    /** The database under {@code root}, created when the folder is missing. */
    public static Cdb open(final Path root) {
        return new Cdb(Store.open(root));
    }

    /** Runs {@code sql}, every row of a query. */
    public CdbResult run(final String sql) {
        return run(sql, 0);
    }

    /** Runs {@code sql}; a query keeps at most {@code maxRows} rows (no bound when not positive). */
    public CdbResult run(final String sql, final int maxRows) {
        final long now = nowMicros();
        final Plan plan = Planner.plan(Parser.parse(sql), store::def, now);
        if (Executor.changesStore(plan)) {
            return CdbResult.updated(Executor.execute(plan, store, now));
        }
        return CdbResult.drain(Executor.open(plan, store, now), maxRows);
    }

    /** The plan of {@code sql}, as text, for tests and the explain command. */
    public String explain(final String sql) {
        return Planner.plan(Parser.parse(sql), store::def, nowMicros()).toString();
    }

    /** A line-protocol appender; the caller closes it. */
    public CdbAppender appender() {
        return store.appender();
    }

    /** The tables' names, sorted. */
    public List<String> tables() {
        return store.tables();
    }

    /** Closes the store; idempotent. */
    @Override
    public void close() {
        store.close();
    }

    private static long nowMicros() {
        final Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000;
    }
}
