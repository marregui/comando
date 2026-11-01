package dev.marregui.comando.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// [AuditLog] keeps its folder and its files the user's alone (rule 15), whoever made the folder first.
class AuditLogTest {

    /// The audit folder, missing because it was deleted while comando ran, is made again `rwx------`, and the day's
    /// file is born `rw-------`, whatever the umask; a second line is appended to the same file.
    @Test
    void aFolderDeletedWhileRunningIsMadeAgainOwnerOnlyAndTheFileIsBornSo(@TempDir final Path home) throws Exception {
        final Path dir = home.resolve("audit");
        final AuditLog log = new AuditLog(dir);

        log.record("work", "call", "panel_read", "{}", "ok");
        log.record("work", "call", "panel_read", "{}", "ok");

        assertThat(Files.getPosixFilePermissions(dir)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        final List<Path> days;
        try (final Stream<Path> files = Files.list(dir)) {
            days = files.toList();
        }
        assertThat(days).hasSize(1);
        assertThat(Files.getPosixFilePermissions(days.getFirst())).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.readAllLines(days.getFirst())).hasSize(2);
    }
}
