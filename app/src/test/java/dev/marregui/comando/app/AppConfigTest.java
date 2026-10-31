package dev.marregui.comando.app;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [AppConfig] selects and expands the application data-root path.
///
/// - expands a leading home-directory marker
/// - puts the system property before the environment
/// - defaults to the hidden application directory
class AppConfigTest {

    private static final String HOME = System.getProperty("user.home");

    /// A leading `~` expands to the user's home directory for property and environment inputs.
    /// Whitespace around a lone marker is stripped before expansion.
    @Test
    void aLeadingTildeInTheDataRootIsTheHomeFolder() {
        assertThat(AppConfig.dataRoot("~/data/comando", null)).isEqualTo(Path.of(HOME, "data", "comando"));
        assertThat(AppConfig.dataRoot(null, "~/x")).isEqualTo(Path.of(HOME, "x"));
        assertThat(AppConfig.dataRoot(" ~ ", null)).isEqualTo(Path.of(HOME));
    }

    /// A nonblank property wins over the environment, while blank inputs fall through to the default home path.
    /// A marker embedded inside a relative name remains literal rather than being expanded.
    @Test
    void thePropertyComesBeforeTheVariableAndTheDefaultIsDotComando() {
        assertThat(AppConfig.dataRoot("/tmp/a", "/tmp/b")).isEqualTo(Path.of("/tmp/a"));
        assertThat(AppConfig.dataRoot("  ", "/tmp/b")).isEqualTo(Path.of("/tmp/b"));
        assertThat(AppConfig.dataRoot(null, null)).isEqualTo(Path.of(HOME, ".comando"));
        assertThat(AppConfig.dataRoot("rel/~x", null)).as("a tilde inside a path is a name").isEqualTo(Path.of("rel/~x"));
    }
}
