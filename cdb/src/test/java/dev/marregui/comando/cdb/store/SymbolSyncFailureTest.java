package dev.marregui.comando.cdb.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static dev.marregui.comando.cdb.store.StoreRows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A dictionary's first sync creates its file and forces it; when that force fails, the checkpoint fails, and the
/// file stays behind with a name in the table folder that nothing made durable. The checkpoint that next succeeds, in
/// the same process, names the file's length in `_txn`: it must force the folder before it, or a power cut can keep
/// that `_txn` and lose the dictionary, and the table does not open. (A reopen forces the table folder itself.) The
/// test cuts the power at every operation of that checkpoint, on seeded adversarial images. The class sets
/// [StoreFiles#recorder], a static: safe because cdb's tests run no two classes at once in one JVM, and each test
/// resets it.
class SymbolSyncFailureTest {

    private static final TableDef M = new TableDef("m", List.of(
            new ColumnDef("s", CdbType.SYMBOL),
            new ColumnDef("ts", CdbType.TIMESTAMP)), 1, PartitionBy.DAY, List.of(), 0L);
    private static final long TS = micros("2026-11-01T00:00:00Z");
    // no other writer exists in this test: a wait this long is a leak
    private static final long WAIT_MILLIS = 1_000L;
    // adversarial images per cut point: one keeps the _txn rename and drops the dictionary's entry with odds of 1 in 4,
    // so 16 miss it with odds near 1 in 100; the seeds are fixed, so a failure replays, and these catch it
    private static final int IMAGES = 16;
    private static final long SEED = 207L;

    /// The table's partition is checkpointed while its dictionary is empty; rows that add keys append to it in place,
    /// so the next checkpoint creates no folder. That checkpoint fails on the dictionary's force; the next, in the same
    /// process, succeeds, and every disk a power cut leaves during it opens with every committed row.
    @Test
    void aRetriedCheckpointForcesTheFolderOfTheDictionary(@TempDir final Path dir) throws Exception {
        final Path root = dir.resolve("store");
        Files.createDirectories(root);
        final DiskModel model = new DiskModel(root);
        StoreFiles.recorder = model;
        final List<String> committed;
        final int from;
        try {
            try (final Store store = Store.open(root)) {
                store.create(M, false);
                commit(store, null, TS);
                store.checkpoint(M.name());
                commit(store, "a", TS + 1L);
                commit(store, "b", TS + 2L);
                committed = read(store);
                model.failForce(SymbolSyncFailureTest::isDictionary);
                assertThatThrownBy(() -> store.checkpoint(M.name())).isInstanceOf(CdbException.class);
                model.failForce(_ -> false);
                from = model.operations();
                store.checkpoint(M.name());
            }
        } finally {
            StoreFiles.recorder = null;
        }
        everyCutOpens(model, from, dir, committed);
    }

    // each cut from operation from on, on IMAGES adversarial images, written to folders of their own
    private static void everyCutOpens(final DiskModel model, final int from, final Path dir,
            final List<String> committed) throws Exception {
        final Random seeds = new Random(SEED);
        for (int k = from; k <= model.operations(); k++) {
            final DiskModel cut = model.at(k);
            for (int i = 0; i < IMAGES; i++) {
                final long seed = seeds.nextLong();
                final Path rebooted = dir.resolve("cut-" + k + '-' + i);
                DiskModel.write(rebooted, cut.adversarialImage(seed));
                final List<String> rows;
                try (final Store store = Store.open(rebooted)) {
                    rows = read(store);
                } catch (final CdbException e) {
                    throw new AssertionError("cannot open table after power cut [cut=" + k + ", seed=" + seed + ']', e);
                }
                assertThat(rows).as("rows after power cut [cut=" + k + ", seed=" + seed + ']').isEqualTo(committed);
            }
        }
    }

    private static boolean isDictionary(final Path file) {
        return file.getFileName().toString().endsWith(".sym");
    }

    private static void commit(final Store store, final String value, final long ts) {
        try (final TableWriter writer = store.writer(M.name(), WAIT_MILLIS)) {
            writer.newRow(ts);
            writer.putText(0, value);
            writer.endRow();
            writer.commit();
        }
    }

    private static List<String> read(final Store store) {
        try (final TableReader reader = store.reader(M.name())) {
            return rows(reader);
        }
    }
}
