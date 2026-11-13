package dev.marregui.comando.core.files;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [OwnerOnly] makes files and folders only their owner may read (rule 15), whatever the umask or the program that
/// made them.
///
/// - a folder is created, with its missing parents, and narrowed to `rwx------`; one that exists wider is narrowed
/// - a file is narrowed to `rw-------`; a symbolic link is not followed
/// - a script is narrowed to `rwx------`, runnable by its owner alone; a symbolic link is not followed
/// - a new file and a temporary file are `rw-------` from their creation
class OwnerOnlyTest {

    /// A folder that does not exist is created `rwx------` under a missing parent; one a wider umask made `rwxrwxrwx`
    /// is narrowed.
    @Test
    void aFolderIsCreatedOrNarrowedToItsOwner(@TempDir final Path tmp) throws Exception {
        final Path made = OwnerOnly.folder(tmp.resolve("data/metrics"));
        assertThat(Files.getPosixFilePermissions(made)).isEqualTo(PosixFilePermissions.fromString("rwx------"));

        final Path wide = Files.createDirectory(tmp.resolve("audit"));
        Files.setPosixFilePermissions(wide, PosixFilePermissions.fromString("rwxrwxrwx"));
        OwnerOnly.folder(wide);
        assertThat(Files.getPosixFilePermissions(wide)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    /// A file a wider umask made `rw-rw-rw-` is narrowed to `rw-------`; a symbolic link to it is left alone, and so is
    /// the file it names.
    @Test
    void aFileIsNarrowedAndALinkIsNotFollowed(@TempDir final Path tmp) throws Exception {
        final Path file = Files.writeString(tmp.resolve("audit.jsonl"), "{}\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"));
        final Path link = Files.createSymbolicLink(tmp.resolve("link"), file);

        OwnerOnly.narrow(link);
        assertThat(Files.getPosixFilePermissions(file)).as("through the link: untouched")
                .isEqualTo(PosixFilePermissions.fromString("rw-rw-rw-"));

        OwnerOnly.narrow(file);
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    /// A script a wider umask made `rwxrwxr-x` is narrowed to `rwx------`, and one made `rw-rw-rw-` made runnable by
    /// its owner alone; a symbolic link to it is left alone, and so is the file it names.
    @Test
    void aScriptIsNarrowedToItsOwnerAndRunnable(@TempDir final Path tmp) throws Exception {
        final Path wide = Files.writeString(tmp.resolve("start.sh"), "#!/bin/bash\n");
        Files.setPosixFilePermissions(wide, PosixFilePermissions.fromString("rwxrwxr-x"));
        OwnerOnly.executable(wide);
        assertThat(Files.getPosixFilePermissions(wide)).isEqualTo(PosixFilePermissions.fromString("rwx------"));

        final Path plain = Files.writeString(tmp.resolve("stop.sh"), "#!/bin/bash\n");
        Files.setPosixFilePermissions(plain, PosixFilePermissions.fromString("rw-rw-rw-"));
        final Path link = Files.createSymbolicLink(tmp.resolve("link"), plain);
        OwnerOnly.executable(link);
        assertThat(Files.getPosixFilePermissions(plain)).as("through the link: untouched")
                .isEqualTo(PosixFilePermissions.fromString("rw-rw-rw-"));

        OwnerOnly.executable(plain);
        assertThat(Files.getPosixFilePermissions(plain)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    /// A new file and a temporary file are owner-only from their creation; a file that exists is not created again.
    @Test
    void aNewFileAndATemporaryFileAreTheOwnersFromTheirCreation(@TempDir final Path tmp) throws Exception {
        final Path file = OwnerOnly.file(tmp.resolve("a.mcp.json"));
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThatThrownBy(() -> OwnerOnly.file(file)).isInstanceOf(FileAlreadyExistsException.class);

        final Path temp = OwnerOnly.tempFile("comando-test-", ".stderr");
        try {
            assertThat(temp.getFileName().toString()).startsWith("comando-test-").endsWith(".stderr");
            assertThat(Files.getPosixFilePermissions(temp)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
