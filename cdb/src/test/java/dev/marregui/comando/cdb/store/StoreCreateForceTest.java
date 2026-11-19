package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A table's create whose folder force fails after `_txn` or `_meta` was moved into place is in doubt: a power cut
/// may keep `_meta` without `_txn`, or lose both. The create is refused, its folder removed, and no open finds the
/// table.
///
/// The case sets [StoreFiles#recorder], a static: safe because cdb's tests run no two classes at once in one JVM, and
/// the test resets it.
class StoreCreateForceTest {

    private static final TableDef M = new TableDef("m", List.of(
            new ColumnDef("value", CdbType.DOUBLE),
            new ColumnDef("ts", CdbType.TIMESTAMP)), 1, PartitionBy.DAY, List.of(), 0L);

    /// Every force of the new table's folder fails: the create throws a `CdbException`, no table folder is left under
    /// the root, the store lists no table, and neither does a store opened on the root after it.
    @Test
    void aCreateWhoseFolderForceFailsIsRefusedAndLeavesNoTable(@TempDir final Path root) throws IOException {
        final AtomicBoolean failing = new AtomicBoolean(true);
        StoreFiles.recorder = new Silent() {
            @Override
            public void folderForcing(final Path folder) throws IOException {
                if (failing.get() && folder.getFileName().toString().equals(M.name())) {
                    throw new IOException("injected folder force failure [folder=" + folder + ']');
                }
            }
        };
        try {
            try (final Store store = Store.open(root)) {
                assertThatThrownBy(() -> store.create(M, false)).isInstanceOf(CdbException.class);
                assertThat(store.tables()).isEmpty();
                assertThat(folders(root)).isEmpty();
            }
            failing.set(false);
            StoreFiles.recorder = null;
            try (final Store reopened = Store.open(root)) {
                assertThat(reopened.tables()).isEmpty();
            }
        } finally {
            StoreFiles.recorder = null;
        }
    }

    private static List<Path> folders(final Path root) throws IOException {
        try (final Stream<Path> paths = Files.list(root)) {
            return paths.filter(Files::isDirectory).toList();
        }
    }

    /// A recorder that sees nothing; a test overrides the operation it fails.
    private static class Silent implements StoreFiles.Recorder {

        @Override
        public void created(final Path path) {
        }

        @Override
        public void wrote(final Path file, final long position, final ByteBuffer bytes) {
        }

        @Override
        public void truncated(final Path file, final long size) {
        }

        @Override
        public void forcing(final Path file) {
        }

        @Override
        public void folderForcing(final Path folder) throws IOException {
        }

        @Override
        public void moved(final Path from, final Path to) {
        }

        @Override
        public void deleted(final Path path) {
        }
    }
}
