package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

/**
 * The tables under one root folder: {@code <root>/<table>/} holds {@code _meta} (the {@link TableDef}), {@code _txn}
 * (the committed transaction: each partition's folder name and row count, replaced atomically by rename) and one folder
 * per partition version, {@code <partition>.<txn>/}, with one file per column. Thread-safe.
 */
public final class Store implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Store.class.getName());
    // how long close waits for each table's open writer; a writer still open after it fails on its next commit
    private static final long CLOSE_WAIT_MILLIS = 10_000L;
    // the root's lock file; a store holds it from open to close, so two never write one root
    private static final String LOCK = "_lock";

    /** Run by a commit right before it replaces {@code _txn}: a crash test stops the process there. */
    @TestOnly
    Runnable beforeTxnReplace = () -> {
    };

    private final Path root;
    private final Map<String, TableState> tables = new ConcurrentHashMap<>();
    private final Object createLock = new Object();
    // written by the closing thread, read by every caller: once set, no table opens a reader or writer
    private volatile boolean closed;
    // the channel holding the root's exclusive lock: one store per root, across processes; null until open has it
    private FileChannel lock;

    private Store(final Path root) {
        this.root = root;
    }

    /** Opens the store at {@code root}, creating the folder when it is missing. */
    public static Store open(final Path root) {
        final Store store = new Store(root);
        try {
            Files.createDirectories(root);
            store.lock = StoreFiles.lockExclusively(root.resolve(LOCK));
            if (store.lock == null) {
                throw new CdbException("refuse to open store another store holds [root=" + root + ']', -1);
            }
            try (final DirectoryStream<Path> dirs = Files.newDirectoryStream(root, Files::isDirectory)) {
                for (final Path dir : dirs) {
                    if (Files.exists(dir.resolve(TableFiles.META))) {
                        final TableState state = load(dir);
                        store.tables.put(state.key, state);
                    }
                }
            }
            return store;
        } catch (final IOException | RuntimeException e) {
            store.close();
            if (e instanceof final CdbException cdb) {
                throw cdb;
            }
            throw new CdbException("cannot open store [root=" + root + ']', e);
        }
    }

    /** The tables' names, sorted. */
    public List<String> tables() {
        final List<String> names = new ArrayList<>(tables.size());
        for (final TableState state : tables.values()) {
            names.add(state.current().def().name());
        }
        names.sort(null);
        return names;
    }

    /** The table's committed definition; null when there is no such table (names match without regard to case). */
    public TableDef def(final String table) {
        final TableState state = tables.get(TableState.key(table));
        if (state == null) {
            return null;
        }
        final TableState.Snapshot snapshot = state.current();
        return snapshot == null ? null : snapshot.def();
    }

    /** Creates {@code def}; false when a table of that name exists, which is an error unless {@code ifNotExists}. */
    public boolean create(final TableDef def, final boolean ifNotExists) {
        checkOpen();
        check(def);
        synchronized (createLock) {
            if (tables.containsKey(TableState.key(def.name()))) {
                if (ifNotExists) {
                    return false;
                }
                throw new CdbException("refuse to create existing table [table=" + def.name() + ']', -1);
            }
            final Path dir = root.resolve(def.name());
            try {
                Files.createDirectories(dir);
                StoreFiles.replaceText(dir.resolve(TableFiles.TXN), TableFiles.encodeTxn(0L, List.of()));
                // _meta last: open takes a folder with _meta for a table
                StoreFiles.replaceText(dir.resolve(TableFiles.META), TableFiles.encodeMeta(def));
            } catch (final IOException e) {
                throw new CdbException("cannot create table [table=" + def.name() + ", dir=" + dir + ']', e);
            }
            final TableState state = new TableState(dir, def);
            state.init(new TableState.Snapshot(0L, def, List.of()));
            tables.put(state.key, state);
            LOG.log(System.Logger.Level.DEBUG, "created table [table=" + def.name() + ", dir=" + dir + ']');
            return true;
        }
    }

    /** A snapshot of the table's committed state. */
    public TableReader reader(final String table) {
        checkOpen();
        final TableState state = state(table);
        return new DefaultReader(state, state.acquire());
    }

    /** The table's writer, waiting up to {@code waitMillis} for another to be closed. */
    public TableWriter writer(final String table, final long waitMillis) {
        checkOpen();
        final TableState state = state(table);
        state.lease(waitMillis);
        try {
            return new DefaultWriter(this, state);
        } catch (final RuntimeException e) {
            state.unlease();
            throw e;
        }
    }

    /** A line-protocol appender over this store (tables and columns created on first write). */
    public CdbAppender appender() {
        checkOpen();
        return new IlpAppender(this);
    }

    /** Waits for open writers to close, then releases every file; idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (final TableState state : tables.values()) {
            if (!state.close(CLOSE_WAIT_MILLIS)) {
                LOG.log(System.Logger.Level.WARNING, "closed store with writer still open [table=" + state.key
                        + ", waitMillis=" + CLOSE_WAIT_MILLIS + ']');
            }
        }
        final FileChannel held = lock;
        lock = null;
        if (held != null) {
            try {
                held.close();
            } catch (final IOException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot release store lock [root=" + root + ']', e);
            }
        }
    }

    private TableState state(final String table) {
        final TableState state = tables.get(TableState.key(table));
        if (state == null) {
            throw new CdbException("refuse unknown table [table=" + table + ']', -1);
        }
        return state;
    }

    private void checkOpen() {
        if (closed) {
            throw new CdbException("refuse use of closed store [root=" + root + ']', -1);
        }
    }

    private static void check(final TableDef def) {
        TableFiles.checkName(def.name(), "table");
        if (def.columns().isEmpty()) {
            throw new CdbException("refuse table without columns [table=" + def.name() + ']', -1);
        }
        final Set<String> names = new HashSet<>();
        for (final ColumnDef column : def.columns()) {
            TableFiles.checkName(column.name(), "column");
            if (!names.add(TableState.key(column.name()))) {
                throw new CdbException("refuse duplicate column [table=" + def.name() + ", column=" + column.name()
                        + ']', -1);
            }
        }
        final int ts = def.timestampIndex();
        if (ts >= def.columns().size() || ts >= 0 && def.columns().get(ts).type() != CdbType.TIMESTAMP) {
            throw new CdbException("refuse designated timestamp that is not a TIMESTAMP column [table=" + def.name()
                    + ", index=" + ts + ']', -1);
        }
        if (ts < 0 && def.partitionBy() != PartitionBy.NONE) {
            throw new CdbException("refuse partitioning without designated timestamp [table=" + def.name()
                    + ", partitionBy=" + def.partitionBy() + ']', -1);
        }
        boolean timestampKey = false;
        for (final String key : def.dedupKeys()) {
            final int c = def.columnIndex(key);
            if (c < 0) {
                throw new CdbException("refuse unknown dedup key [table=" + def.name() + ", column=" + key + ']', -1);
            }
            timestampKey |= c == ts;
        }
        if (!def.dedupKeys().isEmpty() && !timestampKey) {
            throw new CdbException("refuse dedup keys without the designated timestamp [table=" + def.name() + ']', -1);
        }
        if (def.ttlMicros() < 0L) {
            throw new CdbException("refuse negative TTL [table=" + def.name() + ", ttlMicros=" + def.ttlMicros() + ']',
                    -1);
        }
    }

    /**
     * Reads a table folder: {@code _meta}, {@code _txn} (none: transaction 0), the versions it names mapped; deletes
     * the folders and temporary files a crashed commit left that {@code _txn} does not name.
     */
    private static TableState load(final Path dir) throws IOException {
        final Path meta = dir.resolve(TableFiles.META);
        final TableDef def = TableFiles.decodeMeta(StoreFiles.readText(meta), meta.toString());
        final Path txnFile = dir.resolve(TableFiles.TXN);
        final TableFiles.Txn txn = Files.exists(txnFile)
                ? TableFiles.decodeTxn(StoreFiles.readText(txnFile), txnFile.toString())
                : new TableFiles.Txn(0L, List.of());
        final Set<String> named = new HashSet<>();
        for (final TableFiles.Entry entry : txn.partitions()) {
            named.add(entry.folder());
        }
        try (final DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (final Path entry : entries) {
                final String name = entry.getFileName().toString();
                if (Files.isDirectory(entry) && !named.contains(name)) {
                    LOG.log(System.Logger.Level.INFO, "deleted partition of an unfinished commit [dir=" + entry + ']');
                    StoreFiles.deleteFolder(entry);
                } else if (name.endsWith(".tmp")) {
                    Files.delete(entry);
                }
            }
        }
        final TableState state = new TableState(dir, def);
        final List<Version> versions = new ArrayList<>(txn.partitions().size());
        try {
            for (final TableFiles.Entry entry : txn.partitions()) {
                final Path partition = dir.resolve(entry.folder());
                TableFiles.checkName(entry.folder(), "partition");
                versions.add(Version.open(partition, def, entry.rows(), state.symbols()));
            }
        } catch (final RuntimeException e) {
            for (final Version v : versions) {
                v.unmap();
            }
            throw e;
        }
        state.init(new TableState.Snapshot(txn.number(), def, versions));
        return state;
    }
}
