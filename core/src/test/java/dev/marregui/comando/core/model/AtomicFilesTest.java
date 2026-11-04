package dev.marregui.comando.core.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [AtomicFiles] writes a file whole or not at all, through a uniquely named temporary file in the target's folder.
/// - A write creates the target, and a second write replaces its content.
/// - No temporary file is left after a write that succeeds.
/// - A write that fails leaves no temporary file, leaves the target as it was, and reaches the caller.
/// - A cleanup that fails after a failed write is kept as suppressed, never in place of the write's failure.
/// - Where the file system cannot move atomically, a plain replacing move writes the file.
/// - A file another program wrote is moved over the target.
/// - A replaced file keeps its permissions; the temporary file's owner-only mode never reaches it.
class AtomicFilesTest {

    @TempDir
    Path dir;

    private List<String> names() throws IOException {
        try (final Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /// [AtomicFiles#writeString] creates a missing target with the UTF-8 bytes of the text, and a second call replaces
    /// the content rather than appending; non-ASCII text round-trips.
    @Test
    void theContentIsWrittenAndReplaced() throws IOException {
        final Path target = dir.resolve("settings.json");
        AtomicFiles.writeString(target, "first ✓");
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("first ✓");
        AtomicFiles.write(target, "2".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("2");
    }

    /// After two successful writes the folder holds only the target: the temporary file was moved, not copied.
    @Test
    void noTemporaryFileIsLeftAfterAWrite() throws IOException {
        final Path target = dir.resolve("opened-files");
        AtomicFiles.writeString(target, "a\n");
        AtomicFiles.writeString(target, "a\nb\n");
        assertThat(names()).containsExactly("opened-files");
    }

    /// A target that is a non-empty folder cannot be replaced: the exception reaches the caller, the folder and what it
    /// holds stay, and no temporary file is left beside it.
    @Test
    void aFailingWriteLeavesNoTemporaryFileAndTheTargetUnchanged() throws IOException {
        final Path target = Files.createDirectory(dir.resolve("taken"));
        Files.writeString(target.resolve("inside.txt"), "kept");
        assertThatThrownBy(() -> AtomicFiles.writeString(target, "new")).isInstanceOf(IOException.class);
        assertThat(Files.isDirectory(target)).isTrue();
        assertThat(Files.readString(target.resolve("inside.txt"))).isEqualTo("kept");
        assertThat(names()).containsExactly("taken");
    }

    /// A write whose move fails and whose cleanup fails too reaches the caller with the move's failure, the cleanup's
    /// kept as suppressed: the cleanup never hides why the write failed.
    @Test
    void aFailedCleanupDoesNotHideWhyTheWriteFailed() {
        final Path target = dir.resolve("panels.json");
        final AtomicFiles.Mover refusing = (from, to, options) -> {
            // the temporary file becomes a non-empty folder, which deleteIfExists cannot remove
            Files.delete(from);
            Files.createDirectory(from);
            Files.writeString(from.resolve("inside.txt"), "x");
            throw new IOException("move refused");
        };
        assertThatThrownBy(() -> AtomicFiles.write(target, new byte[] {1}, refusing))
                .hasMessage("move refused")
                .satisfies(e -> assertThat(e.getSuppressed()).singleElement().isInstanceOf(DirectoryNotEmptyException.class));
    }

    /// A mover that refuses `ATOMIC_MOVE` with [AtomicMoveNotSupportedException] is called again with
    /// `REPLACE_EXISTING` alone, the file is written, and no temporary file is left.
    @Test
    void withoutAtomicMovesAPlainReplacingMoveWritesTheFile() throws IOException {
        final Path target = dir.resolve("tutorial.md");
        final List<List<CopyOption>> moves = new ArrayList<>();
        AtomicFiles.write(target, "# t".getBytes(StandardCharsets.UTF_8), (from, to, options) -> {
            moves.add(List.of(options));
            if (List.of(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "no atomic rename here");
            }
            Files.move(from, to, options);
        });
        assertThat(moves).containsExactly(List.of(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
                List.of(StandardCopyOption.REPLACE_EXISTING));
        assertThat(Files.readString(target)).isEqualTo("# t");
        assertThat(names()).containsExactly("tutorial.md");
    }

    /// [AtomicFiles#move] puts a file written elsewhere in the folder over an existing target, which takes its content.
    @Test
    void aFileAnotherProgramWroteIsMovedOverTheTarget() throws IOException {
        final Path target = Files.writeString(dir.resolve("d.png"), "old");
        final Path drawn = Files.writeString(dir.resolve(".d.tmp.png"), "new");
        AtomicFiles.move(drawn, target);
        assertThat(Files.readString(target)).isEqualTo("new");
        assertThat(names()).containsExactly("d.png");
    }

    /// A listing the user keeps readable by all (`rw-r--r--`) is still `rw-r--r--` after a rewrite, although the
    /// temporary file it came from was made `rw-------`.
    @Test
    void aReplacedFileKeepsItsPermissions() throws IOException {
        final Path target = Files.writeString(dir.resolve("INDEX.md"), "old");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));
        AtomicFiles.writeString(target, "new");
        assertThat(Files.readString(target)).isEqualTo("new");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target))).isEqualTo("rw-r--r--");
    }
}
