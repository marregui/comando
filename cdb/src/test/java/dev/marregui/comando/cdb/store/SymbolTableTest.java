package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// [SymbolTable] gives each distinct value a key in first-seen order and keeps the synced ones in its file.
class SymbolTableTest {

    /// A value keeps its key; null is -1; keys survive a reopen once synced, and keys not synced do not.
    @Test
    void keysSurviveAReopenOnceSynced(@TempDir final Path dir) {
        final Path file = dir.resolve("s.sym");
        try (final SymbolTable symbols = SymbolTable.open(file)) {
            for (int i = 0; i < 100; i++) {
                assertThat(symbols.intern("v" + i)).isEqualTo(i);
            }
            assertThat(symbols.intern("v7")).isEqualTo(7);
            assertThat(symbols.intern(null)).isEqualTo(-1);
            assertThat(symbols.value(-1)).isNull();
            symbols.sync();
            symbols.intern("unsynced");
        }
        try (final SymbolTable reopened = SymbolTable.open(file)) {
            assertThat(reopened.size()).isEqualTo(100);
            assertThat(reopened.value(42)).isEqualTo("v42");
            assertThat(reopened.intern("v99")).isEqualTo(99);
            assertThat(reopened.intern("new")).isEqualTo(100);
        }
    }

    /// A torn last entry, left by a crash during sync, is cut on open, and the next sync appends after the cut.
    @Test
    void aTornLastEntryIsCut(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("s.sym");
        try (final SymbolTable symbols = SymbolTable.open(file)) {
            symbols.intern("a");
            symbols.intern("bb");
            symbols.sync();
        }
        final long synced = Files.size(file);
        Files.write(file, new byte[] {9, 0, 0, 0, 'x'}, StandardOpenOption.APPEND);
        try (final SymbolTable reopened = SymbolTable.open(file)) {
            assertThat(reopened.size()).isEqualTo(2);
            assertThat(Files.size(file)).isEqualTo(synced);
            reopened.intern("c");
            reopened.sync();
        }
        try (final SymbolTable last = SymbolTable.open(file)) {
            assertThat(last.value(2)).isEqualTo("c");
        }
    }
}
