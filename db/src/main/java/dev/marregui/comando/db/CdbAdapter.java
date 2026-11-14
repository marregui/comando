package dev.marregui.comando.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbResult;
import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.port.DatabasePort;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.QueryPort;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.result.ResultText;
import dev.marregui.comando.core.schema.Schema;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;

/**
 * The database port for connections of kind cdb, comando's own database, run in this process: a connection whose host
 * is {@code cdb}, or {@code cdb:<folder>}, opens the database in that folder ({@link #root}). Each connection name has
 * a strand that runs its work in order, as {@link DbAdapter}'s lanes do; two connections on one folder share one open
 * database. Every method returns at once; outcomes come back as intents, and Claude's queries as futures.
 *
 * <p>cdb has no cancel of a running statement: a cancelled run that has not started never starts, and one already
 * running ends as cancelled when it returns, its rows not kept.</p>
 */
public final class CdbAdapter implements DatabasePort, QueryPort, AutoCloseable {

    /** Where a connection's database lives when its host names no folder. */
    public static final Path DEFAULT_ROOT = Path.of(System.getProperty("user.home"), ".comando", "cdb");
    /** The host of a cdb connection, alone or before {@code :<folder>}. */
    public static final String HOST = "cdb";
    /** Rows a describe keeps: {@link Lane}'s describe bound. */
    static final int MAX_DESCRIBE_ROWS = 10_000;
    private static final System.Logger LOG = System.getLogger(CdbAdapter.class.getName());

    /** Requests cancelled before they ended; a run takes its id out when it ends. */
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();
    /** Each connection's count of closes and forgets, so a schema load can tell it was overtaken. */
    private final ConcurrentHashMap<String, AtomicLong> changes = new ConcurrentHashMap<>();
    /** The open databases by folder, with their users; guarded by itself. */
    private final Map<Path, Shared> databases = new HashMap<>();
    private final Path defaultRoot;
    /** Requests queued or running: a cancel is kept only for one of them. */
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();
    private final ResultRegistry results;
    private final SchemaRegistry schemas;
    private final DbSettings settings;
    private final IntentSink sink;
    private final ConcurrentHashMap<String, Strand> strands = new ConcurrentHashMap<>();
    /** Written by {@link #close}; read by every caller before it queues work. */
    private volatile boolean closed;

    /** {@code defaultRoot} is the folder of a connection whose host names none ({@link #DEFAULT_ROOT} in comando). */
    public CdbAdapter(
            final IntentSink sink,
            final ResultRegistry results,
            final SchemaRegistry schemas,
            final DbSettings settings,
            final Path defaultRoot
    ) {
        this.sink = sink;
        this.results = results;
        this.schemas = schemas;
        this.settings = settings;
        this.defaultRoot = defaultRoot.toAbsolutePath().normalize();
    }

    /** Whether {@code spec} is a cdb connection: its host is {@code cdb} or {@code cdb:<folder>}. */
    public static boolean handles(final ConnectionSpec spec) {
        final String host = spec.host().strip();
        return host.equals(HOST) || host.startsWith(HOST + ':');
    }

    @Override
    public void open(final ConnectionSpec spec) {
        onStrand(spec.name(), strand -> strand.open(spec));
    }

    @Override
    public void close(final String name) {
        changed(name);
        final Strand strand = strands.remove(name);
        if (strand == null || !strand.retire(true)) {
            sink.dispatch(new Intent.ConnectionClosed(name)); // no strand, or one that retired: nothing is open
        }
    }

    @Override
    public void forgetSchema(final String name) {
        synchronized (changes) {
            changed(name);
            schemas.forget(name);
        }
    }

    /** Reachable when the folder is a database already open, a folder, or not there yet under a folder that is. */
    @Override
    public void test(final ConnectionSpec spec) {
        if (!onStrand(spec.name(), strand -> strand.test(spec))) {
            sink.dispatch(new Intent.ConnectionTested(spec.name(), false, "comando is closing"));
        }
    }

    @Override
    public void execute(final long requestId, final ConnectionSpec spec, final String sql) {
        pending.add(requestId);
        if (!onStrand(spec.name(), strand -> strand.execute(requestId, spec, sql))) {
            pending.remove(requestId);
            sink.dispatch(new Intent.SqlCancelled(requestId));
        }
    }

    @Override
    public void cancel(final long requestId) {
        if (pending.contains(requestId)) {
            cancelled.add(requestId);
        }
    }

    @Override
    public void release(final long requestId) {
        cancelled.remove(requestId);
        results.release(requestId);
    }

    @Override
    public CompletableFuture<Rows> query(final ConnectionSpec spec, final String sql, final int maxRows) {
        final CompletableFuture<Rows> out = new CompletableFuture<>();
        if (!onStrand(spec.name(), strand -> strand.call(spec, out, () -> true, db -> rows(db.run(sql, maxRows))))) {
            out.completeExceptionally(closedError());
        }
        return out;
    }

    /** Columns of {@code table} as {@code SHOW COLUMNS} lists them: {@code column}, {@code type} and QuestDB's rest. */
    @Override
    public CompletableFuture<Rows> describe(final ConnectionSpec spec, final String table) {
        final CompletableFuture<Rows> out = new CompletableFuture<>();
        if (!onStrand(spec.name(), strand -> strand.call(spec, out, () -> true,
                db -> rows(db.run(showColumns(table), MAX_DESCRIBE_ROWS))))) {
            out.completeExceptionally(closedError());
        }
        return out;
    }

    /**
     * Every table and its columns, read on the connection's strand, into the schema registry. A close or forget of the
     * connection before the load's turn overtakes it: it does not open the database again, and keeps nothing; one
     * while it loads keeps nothing either.
     */
    @Override
    public void loadSchema(final ConnectionSpec spec, final boolean show) {
        final String name = spec.name();
        final long at = changes(name);
        final BooleanSupplier wanted = () -> changes(name) == at;
        final CompletableFuture<Schema> out = new CompletableFuture<>();
        if (!onStrand(name, strand -> strand.call(spec, out, wanted, CdbAdapter::schemaOf))) {
            return;
        }
        out.whenComplete((schema, e) -> {
            if (e != null) {
                sink.dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR, "Cannot read the schema of " + name + ": "
                        + message(e), e));
                return;
            }
            // checked and kept as one step against a close or forget: an overtaken load keeps nothing
            synchronized (changes) {
                if (schema == null || !wanted.getAsBoolean()) {
                    return;
                }
                schemas.put(name, schema);
            }
            if (show) {
                sink.dispatch(new Intent.ShowView("", Effect.LoadSchema.viewTitle(name),
                        new ViewSpec.Tree(DbAdapter.tree(name, schema)), Placement.SIDE));
            }
        });
    }

    /**
     * Stops taking work, lets each strand finish what it has queued for {@link ExecutorStop#WAIT}, then closes every
     * database still open. Idempotent.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // one snapshot: a strand made meanwhile is refused work, as onStrand reads the flag first
        final Map<String, ExecutorService> executors = new LinkedHashMap<>();
        for (final Strand strand : List.copyOf(strands.values())) {
            strand.retire(false);
            executors.put("cdb-" + strand.name, strand.executor);
        }
        strands.clear();
        ExecutorStop.drain("cdb", ExecutorStop.WAIT, executors);
        final List<Cdb> left = new ArrayList<>();
        synchronized (databases) {
            for (final Shared s : databases.values()) {
                left.add(s.db);
            }
            databases.clear();
        }
        for (final Cdb db : left) {
            closeQuietly(db);
        }
    }

    /**
     * The folder of {@code spec}'s database: the host's text after {@code cdb:}, {@code ~} for the user's home, a
     * relative folder under {@code defaultRoot}; {@code defaultRoot} itself when the host names none.
     */
    static Path root(final ConnectionSpec spec, final Path defaultRoot) {
        if (!handles(spec)) {
            throw new IllegalArgumentException("connection is not a cdb connection [connection=" + spec.name()
                    + ", host=" + spec.host() + ']');
        }
        final String host = spec.host().strip();
        final String folder = host.length() > HOST.length() ? host.substring(HOST.length() + 1).strip() : "";
        if (folder.isEmpty()) {
            return defaultRoot;
        }
        final Path path;
        if (folder.equals("~")) {
            path = Path.of(System.getProperty("user.home"));
        } else if (folder.startsWith("~/")) {
            path = Path.of(System.getProperty("user.home"), folder.substring(2));
        } else {
            path = defaultRoot.resolve(folder);
        }
        return path.toAbsolutePath().normalize();
    }

    /** {@code SHOW COLUMNS} of {@code table}, its name quoted. */
    static String showColumns(final String table) {
        return "SHOW COLUMNS FROM " + Lane.quote(table);
    }

    /** A result as Claude reads it: its cells as text, or the count of rows a statement changed. */
    static Rows rows(final CdbResult result) {
        if (result.updateCount() >= 0) {
            return new Rows(List.of(), List.of(), result.updateCount(), false);
        }
        final CdbResultView view = new CdbResultView(-1L, result);
        return new Rows(ResultText.columns(view), ResultText.rows(view, view.rowCount()), -1L, result.truncated());
    }

    /** Strands alive now; a closed connection's strand leaves at once. */
    int strandCount() {
        return strands.size();
    }

    private static IllegalStateException closedError() {
        return new IllegalStateException("comando is closing; the database is no longer reached");
    }

    private static void closeQuietly(final Cdb db) {
        try {
            db.close();
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot close a cdb database", e);
        }
    }

    /** The first message along the causes; the class name when none has one. */
    private static String message(final Throwable e) {
        Throwable t = e;
        while (t.getMessage() == null && t.getCause() != null && t != t.getCause()) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static long millisSince(final long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** The tables of {@code db}, each with its columns as {@code SHOW COLUMNS} names and types them. */
    private static Schema schemaOf(final Cdb db) {
        final List<Schema.Table> tables = new ArrayList<>();
        for (final String table : db.tables()) {
            final CdbResult columns = db.run(showColumns(table), MAX_DESCRIBE_ROWS);
            final List<Schema.Column> out = new ArrayList<>(columns.rowCount());
            for (int r = 0; r < columns.rowCount(); r++) {
                final String type = columns.columnCount() > 1 ? columns.getText(r, 1) : null;
                out.add(new Schema.Column(columns.getText(r, 0), type == null ? "" : type));
            }
            tables.add(new Schema.Table(table, out));
        }
        return new Schema(tables);
    }

    /** Whether the nearest folder above {@code folder} that exists is one comando may write in. */
    private static boolean writableAncestor(final Path folder) {
        for (Path p = folder.getParent(); p != null; p = p.getParent()) {
            if (Files.exists(p)) {
                return Files.isDirectory(p) && Files.isWritable(p);
            }
        }
        return false;
    }

    /**
     * Queues work on the strand of {@code name}; a strand that has retired refuses it and a new strand takes it. Once
     * the adapter is closed no strand is made and nothing is queued: answers false.
     */
    private boolean onStrand(final String name, final StrandWork work) {
        while (!closed) {
            final Strand strand = strands.computeIfAbsent(name, Strand::new);
            if (work.queue(strand)) {
                return true;
            }
            strands.remove(name, strand);
        }
        return false;
    }

    private long changes(final String name) {
        return changes.computeIfAbsent(name, n -> new AtomicLong()).get();
    }

    /** Under the lock of {@link #changes}, so a load's last check and its keeping are one step against it. */
    private void changed(final String name) {
        synchronized (changes) {
            changes.computeIfAbsent(name, n -> new AtomicLong()).incrementAndGet();
        }
    }

    /** The database in {@code root}, opened for one more user. */
    private Cdb acquire(final Path root) {
        synchronized (databases) {
            final Shared s = databases.get(root);
            if (s != null) {
                s.users++;
                return s.db;
            }
            final Cdb db = Cdb.open(root);
            databases.put(root, new Shared(db));
            return db;
        }
    }

    /** One user fewer of the database in {@code root}; the last one closes it. */
    private void releaseRoot(final Path root) {
        final Cdb last;
        synchronized (databases) {
            final Shared s = databases.get(root);
            if (s == null || --s.users > 0) {
                return;
            }
            databases.remove(root);
            last = s.db;
        }
        closeQuietly(last);
    }

    /** Work that queues itself on a strand; false when the strand has retired. */
    @FunctionalInterface
    private interface StrandWork {
        boolean queue(Strand strand);
    }

    /** Work against an open database, on its connection's strand. */
    @FunctionalInterface
    private interface DbCall<T> {
        T apply(Cdb db);
    }

    /** An open database and how many connections hold it. */
    private static final class Shared {

        private final Cdb db;
        private int users = 1;

        private Shared(final Cdb db) {
            this.db = db;
        }
    }

    /** One connection's strand and its database while it is open; the database fields are the strand's only. */
    private final class Strand {

        private final ExecutorService executor;
        private final String name;
        private Cdb db;
        private Path root;

        private Strand(final String name) {
            this.name = name;
            this.executor = Threads.strand("cdb-" + name);
        }

        boolean open(final ConnectionSpec spec) {
            return submit(() -> {
                try {
                    ensureOpen(spec);
                } catch (final RuntimeException e) {
                    sink.dispatch(new Intent.ConnectionFailed(name, message(e)));
                }
            });
        }

        boolean test(final ConnectionSpec spec) {
            return submit(() -> {
                try {
                    final Path folder = root(spec, defaultRoot);
                    final boolean ok = (db != null && folder.equals(root)) || Files.isDirectory(folder)
                            || (!Files.exists(folder) && writableAncestor(folder));
                    sink.dispatch(new Intent.ConnectionTested(name, ok, ok ? "reachable"
                            : "not a folder comando can use: " + folder));
                } catch (final RuntimeException e) {
                    sink.dispatch(new Intent.ConnectionTested(name, false, message(e)));
                }
            });
        }

        boolean execute(final long requestId, final ConnectionSpec spec, final String sql) {
            return submit(() -> run(requestId, spec, sql));
        }

        /**
         * Completes {@code out} with {@code call} on the open database; with null, the database not opened, once
         * {@code wanted} says the work was overtaken while it waited.
         */
        <T> boolean call(
                final ConnectionSpec spec,
                final CompletableFuture<T> out,
                final BooleanSupplier wanted,
                final DbCall<T> call
        ) {
            return submit(() -> {
                if (out.isDone()) {
                    return; // the caller gave up while this waited behind other runs: it must not run later
                }
                if (!wanted.getAsBoolean()) {
                    out.complete(null);
                    return;
                }
                try {
                    out.complete(call.apply(ensureOpen(spec)));
                } catch (final RuntimeException e) {
                    out.completeExceptionally(new IllegalStateException(message(e), e));
                }
            });
        }

        /**
         * Queues the close of the database, told as {@code ConnectionClosed} when {@code tell}, and stops taking
         * work; false when the strand had already retired.
         */
        boolean retire(final boolean tell) {
            final boolean queued = submit(() -> {
                closeDb();
                if (tell) {
                    sink.dispatch(new Intent.ConnectionClosed(name));
                }
            });
            executor.shutdown();
            return queued;
        }

        private boolean submit(final Runnable task) {
            try {
                executor.execute(task);
                return true;
            } catch (final RejectedExecutionException _) {
                return false;
            }
        }

        private Cdb ensureOpen(final ConnectionSpec spec) {
            final Path folder = root(spec, defaultRoot);
            if (db != null && folder.equals(root)) {
                return db;
            }
            closeDb();
            LOG.log(System.Logger.Level.INFO, "opening cdb [connection=" + name + ", root=" + folder + ']');
            db = acquire(folder);
            root = folder;
            sink.dispatch(new Intent.ConnectionOpened(name));
            LOG.log(System.Logger.Level.INFO, "opened cdb [connection=" + name + ", root=" + folder + ']');
            return db;
        }

        private void closeDb() {
            if (db == null) {
                return;
            }
            final Path was = root;
            db = null;
            root = null;
            releaseRoot(was);
            LOG.log(System.Logger.Level.INFO, "closed cdb [connection=" + name + ", root=" + was + ']');
        }

        private void run(final long requestId, final ConnectionSpec spec, final String sql) {
            final long start = System.nanoTime();
            try {
                if (cancelled.remove(requestId)) {
                    sink.dispatch(new Intent.SqlCancelled(requestId));
                    return;
                }
                final Cdb opened;
                try {
                    opened = ensureOpen(spec);
                } catch (final RuntimeException e) {
                    sink.dispatch(new Intent.ConnectionFailed(name, message(e)));
                    sink.dispatch(new Intent.SqlFailed(requestId, message(e), millisSince(start)));
                    return;
                }
                sink.dispatch(new Intent.SqlStarted(requestId));
                final CdbResult result;
                try {
                    result = opened.run(sql, settings.maxRows());
                } catch (final RuntimeException e) {
                    if (cancelled.remove(requestId)) {
                        sink.dispatch(new Intent.SqlCancelled(requestId));
                    } else {
                        sink.dispatch(new Intent.SqlFailed(requestId, message(e), millisSince(start)));
                    }
                    return;
                }
                final long execMillis = millisSince(start);
                if (cancelled.remove(requestId)) {
                    sink.dispatch(new Intent.SqlCancelled(requestId));
                    return;
                }
                if (result.updateCount() >= 0) {
                    final long total = millisSince(start);
                    sink.dispatch(new Intent.SqlCompleted(requestId, 0, result.updateCount(), false, execMillis,
                            total - execMillis, total));
                    return;
                }
                final long fetchStart = System.nanoTime();
                results.put(new CdbResultView(requestId, result));
                final long total = millisSince(start);
                sink.dispatch(new Intent.SqlCompleted(requestId, result.rowCount(), -1L, result.truncated(), execMillis,
                        millisSince(fetchStart), total));
            } finally {
                pending.remove(requestId);
                cancelled.remove(requestId);
            }
        }
    }
}
