package dev.marregui.comando.cdb.exec;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.Store;
import dev.marregui.comando.cdb.store.TableDef;
import dev.marregui.comando.cdb.store.TableReader;
import dev.marregui.comando.cdb.store.TableWriter;

/**
 * Runs {@code BACKUP TO 'folder'}: every table's committed state copied, through the store's public API, into a new
 * store at {@code folder}, which must not exist. The copy is built in a sibling folder {@code <folder>.tmp-<random>}
 * and renamed to {@code folder} once its store is closed, so {@code folder} holds a whole backup or nothing; a failure
 * removes the sibling. Each table is copied at one committed transaction, one reader's snapshot, the tables one after
 * another (cdb has no transaction across tables); writes go on meanwhile. A table a drop removes before its snapshot is
 * taken is skipped, one created after the backup listed the tables is not copied. The copy of a table is created
 * without its TTL, which its last commit sets, so no batch of the copy expires a row the source still holds. The cost
 * is a rewrite of every table, O(store). The copy's commits and close make its files durable, and the rename is forced
 * into its parent folder before the answer. The folder is absolute and outside the store's root: a relative one would
 * depend on the server's working folder, and one inside the root would be read as a table at the next open.
 */
public final class BackupRunner {

    private static final System.Logger LOG = System.getLogger(BackupRunner.class.getName());

    /**
     * The rows of a copy's commit, ended at a page boundary. Not measured: above the store's direct-commit threshold
     * (100,000 rows), so a batch goes straight into the copy's column files rather than through its log, and small
     * enough that a writer's pending rows stay bounded for a table of any size.
     */
    static final long BATCH_ROWS = 262_144L;

    private BackupRunner() {
    }

    /**
     * Backs every table of {@code store} up into {@code backup}'s folder, an absolute path outside the store's root;
     * answers the tables copied. A folder that exists, or that appears before the copy is renamed to it, is refused.
     */
    public static long run(final Plan.Backup backup, final Store store) {
        final Path folder = folderOf(backup.folder());
        final Path root = store.root().toAbsolutePath().normalize();
        if (folder.startsWith(root)) {
            throw new CdbException("cannot back up into the store's own folder [folder=" + folder + ", root=" + root
                    + ']', -1);
        }
        if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            throw exists(folder);
        }
        final Path temp = folder.resolveSibling(folder.getFileName() + ".tmp-"
                + Long.toHexString(ThreadLocalRandom.current().nextLong()));
        try {
            Files.createDirectories(folder.getParent());
            Files.createDirectory(temp);
        } catch (final IOException e) {
            throw new CdbException("cannot back up into folder [folder=" + folder + ", temp=" + temp + ']', e);
        }
        LOG.log(System.Logger.Level.INFO, "backing up store [folder=" + folder + ", temp=" + temp + ']');
        long tables = 0;
        long rows = 0;
        try {
            try (Store copy = Store.open(temp)) {
                final List<String> names = store.tables();
                for (final String table : names) {
                    final long copied = copy(store, copy, table);
                    if (copied >= 0) {
                        tables++;
                        rows += copied;
                    }
                }
            }
            Files.move(temp, folder, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException e) {
            // another backup's rename, or a folder made by hand, took the name first
            final CdbException failure = Files.exists(folder, LinkOption.NOFOLLOW_LINKS)
                    ? exists(folder)
                    : new CdbException("cannot back up into folder [folder=" + folder + ", temp=" + temp + ']', e);
            remove(temp, failure);
            throw failure;
        } catch (final RuntimeException | Error e) {
            remove(temp, e);
            throw e;
        }
        try {
            Store.forceFolder(folder.getParent());
        } catch (final IOException e) {
            // the rename may not survive a power cut: the backup is removed rather than answered
            final CdbException failure = new CdbException("cannot force backup's folder entry [folder=" + folder + ']',
                    e);
            remove(folder, failure);
            throw failure;
        }
        LOG.log(System.Logger.Level.INFO, "backed up store [folder=" + folder + ", tables=" + tables + ", rows=" + rows
                + ']');
        return tables;
    }

    /**
     * Copies {@code table} of {@code store} into {@code copy} at one snapshot; answers its rows, -1 when a drop removed
     * the table before the snapshot was taken.
     */
    private static long copy(final Store store, final Store copy, final String table) {
        final TableReader reader;
        try {
            reader = store.freshReader(table);
        } catch (final CdbException e) {
            if (store.def(table) == null) {
                LOG.log(System.Logger.Level.INFO, "skipped table dropped during backup [table=" + table + ']');
                return -1;
            }
            throw e;
        }
        final TableDef def;
        final TableWriter writer;
        try {
            def = reader.def();
            copy.create(def.withTtlMicros(0L), false);
            writer = copy.writer(def.name(), UpdateRunner.WAIT_MILLIS);
        } catch (final RuntimeException | Error e) {
            try {
                reader.close();
            } catch (final RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        try (writer) {
            final long rows = UpdateRunner.rewrite("BACKUP", def.name(), null, reader, writer, 0L,
                    (fields, timestamp) -> new Batches(writer, fields, timestamp));
            if (def.ttlMicros() > 0L) {
                writer.setTtl(def.ttlMicros());
                writer.commit();
            }
            return rows;
        }
    }

    private static Path folderOf(final String folder) {
        final Path path;
        try {
            path = Path.of(folder);
        } catch (final InvalidPathException e) {
            throw new CdbException("cannot back up into a folder that is not a path [folder=" + folder + ']', e);
        }
        if (!path.isAbsolute()) {
            throw new CdbException("cannot back up into a relative folder [folder=" + folder + ']', -1);
        }
        return path.normalize();
    }

    private static CdbException exists(final Path folder) {
        return new CdbException("cannot back up into a folder that exists [folder=" + folder + ']', -1);
    }

    /** Deletes the copy's folder {@code temp} and everything in it, a failure to do so added to {@code failure}. */
    private static void remove(final Path temp, final Throwable failure) {
        try {
            Files.walkFileTree(temp, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes)
                        throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(final Path dir, final IOException e) throws IOException {
                    if (e != null) {
                        throw e;
                    }
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException e) {
            failure.addSuppressed(new CdbException("cannot remove backup's temporary folder [temp=" + temp + ']', e));
        }
    }

    /** Appends every row of each page to the copy, committing once {@link #BATCH_ROWS} rows are pending. */
    private static final class Batches implements UpdateRunner.PageEdit {

        private final TableWriter writer;
        private final List<Field> fields;
        private final int timestamp;
        private long pending;

        private Batches(final TableWriter writer, final List<Field> fields, final int timestamp) {
            this.writer = writer;
            this.fields = fields;
            this.timestamp = timestamp;
        }

        @Override
        public void apply(final Page page, final boolean[] hit) {
            for (int r = 0; r < page.size; r++) {
                UpdateRunner.append(writer, fields, timestamp, page, r, null);
            }
            pending += page.size;
            if (pending >= BATCH_ROWS) {
                writer.commit();
                pending = 0;
            }
        }
    }
}
