package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// [StoreFiles#replaceText] writes its `.tmp` without deleting it first when none is there, and still replaces a
/// leftover one a crash kept.
///
/// The tests set [StoreFiles#recorder], a static: safe because cdb's tests run no two classes at once in one JVM, and
/// each test resets it.
class StoreFilesReplaceTest {

    /// The recorder a test installs is gone before the next.
    @AfterEach
    void removeRecorder() {
        StoreFiles.recorder = null;
    }

    /// A replace of an existing file with no `.tmp` beside it tries no delete (whose miss costs an error inside the JDK):
    /// the tmp is created, moved over the file, and the file holds the new text.
    @Test
    void aReplaceWithNoTmpDeletesNothing(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("_txn");
        Files.writeString(file, "old");
        final List<String> ops = record();
        assertThat(StoreFiles.replaceText(file, "new text")).isTrue();
        assertThat(ops).containsExactly("created _txn.tmp", "wrote _txn.tmp", "forcing _txn.tmp",
                "moved _txn.tmp _txn", "folderForcing");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("new text");
        assertThat(dir.resolve("_txn.tmp")).doesNotExist();
    }

    /// A `.tmp` a crash left between its write and its move is deleted and written anew: the file gets the new text,
    /// none of the leftover.
    @Test
    void aLeftoverTmpIsReplaced(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("_meta");
        Files.writeString(file, "old");
        Files.writeString(dir.resolve("_meta.tmp"), "a leftover longer than the new text");
        final List<String> ops = record();
        assertThat(StoreFiles.replaceText(file, "new")).isTrue();
        assertThat(ops).containsExactly("deleting _meta.tmp", "deleted _meta.tmp", "created _meta.tmp", "wrote _meta.tmp",
                "forcing _meta.tmp", "moved _meta.tmp _meta", "folderForcing");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("new");
    }

    // installs a recorder that lists each operation by the file names it names
    private static List<String> record() {
        final List<String> ops = new ArrayList<>();
        StoreFiles.recorder = new StoreFiles.Recorder() {
            @Override
            public void created(final Path path) {
                ops.add("created " + path.getFileName());
            }

            @Override
            public void wrote(final Path file, final long position, final ByteBuffer bytes) {
                ops.add("wrote " + file.getFileName());
            }

            @Override
            public void truncated(final Path file, final long size) {
                ops.add("truncated " + file.getFileName());
            }

            @Override
            public void forcing(final Path file) {
                ops.add("forcing " + file.getFileName());
            }

            @Override
            public void folderForcing(final Path folder) {
                ops.add("folderForcing");
            }

            @Override
            public void moved(final Path from, final Path to) {
                ops.add("moved " + from.getFileName() + ' ' + to.getFileName());
            }

            @Override
            public void deleted(final Path path) {
                ops.add("deleted " + path.getFileName());
            }

            @Override
            public void deleting(final Path path) {
                ops.add("deleting " + path.getFileName());
            }
        };
        return ops;
    }
}
