package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A symbol file that took a failed write keeps every committed key on a reopen: a later sync does not append behind
/// the failed write's bytes, and a file that lost committed bytes is refused rather than read with NULLs. The class
/// sets [StoreFiles#recorder], a static: safe because cdb's tests run no two classes at once in one JVM, and each test
/// resets it.
class SymbolTornWriteTest {

    /// The second force of the file fails after its bytes were written: the sync throws, and the next sync does not
    /// repeat the failed one's keys behind them; a reopen at the committed length holds every key.
    @Test
    void aFailedForceDoesNotShiftTheKeysThatFollow(@TempDir final Path dir) {
        final Path file = dir.resolve("s.sym");
        StoreFiles.recorder = new FailingForce(file, new AtomicInteger());
        final long committed;
        try (final SymbolTable symbols = SymbolTable.open(file)) {
            symbols.intern("a");
            symbols.sync();
            symbols.intern("b");
            assertThatThrownBy(symbols::sync).isInstanceOf(CdbException.class);
            symbols.intern("c");
            symbols.sync();
            committed = symbols.syncedBytes();
        } finally {
            StoreFiles.recorder = null;
        }
        try (final SymbolTable reopened = SymbolTable.open(file, committed)) {
            assertThat(reopened.size()).isEqualTo(3);
            assertThat(reopened.value(0)).isEqualTo("a");
            assertThat(reopened.value(1)).isEqualTo("b");
            assertThat(reopened.value(2)).isEqualTo("c");
        }
    }

    /// A half-written entry left behind by a failed sync is cut by the next one: the keys after it are not lost to a
    /// reopen at the committed length.
    @Test
    void aTornEntryBehindTheCommittedBytesIsCutByTheNextSync(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("s.sym");
        final long committed;
        try (final SymbolTable symbols = SymbolTable.open(file)) {
            symbols.intern("a");
            symbols.sync();
            Files.write(file, new byte[] {9, 0, 0, 0, 'x'}, StandardOpenOption.APPEND);
            symbols.intern("c");
            symbols.sync();
            committed = symbols.syncedBytes();
        }
        assertThat(Files.size(file)).isEqualTo(committed);
        try (final SymbolTable reopened = SymbolTable.open(file, committed)) {
            assertThat(reopened.size()).isEqualTo(2);
            assertThat(reopened.value(0)).isEqualTo("a");
            assertThat(reopened.value(1)).isEqualTo("c");
        }
    }

    /// A file shorter than the length the last checkpoint recorded lost committed keys: the open fails, it does not
    /// answer NULL for them.
    @Test
    void aFileShorterThanTheCommittedBytesIsRefused(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("s.sym");
        final long committed;
        try (final SymbolTable symbols = SymbolTable.open(file)) {
            symbols.intern("a");
            symbols.intern("b");
            symbols.sync();
            committed = symbols.syncedBytes();
        }
        StoreFiles.truncate(file, committed - 2L);
        assertThatThrownBy(() -> SymbolTable.open(file, committed)).isInstanceOf(CdbException.class);
        Files.delete(file);
        assertThatThrownBy(() -> SymbolTable.open(file, committed)).isInstanceOf(CdbException.class);
    }

    /// Fails the second force of one file, as a device that refuses it would; every other operation passes.
    private static final class FailingForce implements StoreFiles.Recorder {

        private final Path file;
        private final AtomicInteger forces;

        private FailingForce(final Path file, final AtomicInteger forces) {
            this.file = file;
            this.forces = forces;
        }

        @Override
        public void created(final Path path) {
        }

        @Override
        public void wrote(final Path path, final long position, final ByteBuffer bytes) {
        }

        @Override
        public void truncated(final Path path, final long size) {
        }

        @Override
        public void forcing(final Path path) throws IOException {
            if (path.equals(file) && forces.getAndIncrement() == 1) {
                throw new IOException("refuse force of the symbol file [file=" + path + ']');
            }
        }

        @Override
        public void folderForcing(final Path folder) {
        }

        @Override
        public void moved(final Path from, final Path to) {
        }

        @Override
        public void deleted(final Path path) {
        }
    }
}
