package dev.marregui.comando.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// The data root's logs, run, audit and metrics folders are the user's alone (rule 15), whatever the umask or the
/// program that made them; the files in them are narrowed by `core`'s `OwnerOnly`, tested there.
///
/// - the start creates the four folders owner-only, and narrows one that exists wider
/// - the logging setup makes its folder the user's
class DataRootFoldersTest {

    /// The start creates the logs, run, audit and metrics folders `rwx------` and narrows one a wider umask made
    /// `rwxrwxrwx`, as the audit folder of a live data root was found.
    @Test
    void theDataRootsLogRunAuditAndMetricsFoldersAreTheUsersAlone(@TempDir final Path home) throws Exception {
        final AppConfig config = new AppConfig(home, "claude", home.resolve("projects"), false);
        Files.createDirectories(config.auditDir());
        Files.setPosixFilePermissions(config.auditDir(), PosixFilePermissions.fromString("rwxrwxrwx"));

        App.ownerOnlyFolders(config);

        for (final Path dir : List.of(config.logDir(), config.runDir(), config.auditDir(), config.metricsDir())) {
            assertThat(Files.getPosixFilePermissions(dir)).as(dir.toString()).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        }
    }

    /// The logging setup creates its folder `rwx------`.
    @Test
    void theLogFolderIsTheUsers(@TempDir final Path home) throws Exception {
        final Path logs = home.resolve("logs");
        Logging.configure(logs);
        assertThat(Files.getPosixFilePermissions(logs)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }
}
