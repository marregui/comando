package dev.marregui.comando.app;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [AppConfig] selects and expands the application data-root path.
///
/// - expands a leading home-directory marker
/// - puts the system property before the environment
/// - defaults to the hidden application directory
/// - keeps passwords in the keychain on the standard data root only, unless the property says otherwise
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

    /// The keychain is on for the standard data root only; `-Dcomando.keychain` decides when set, and an empty value
    /// is no.
    @Test
    void theKeychainIsForTheStandardDataRootUnlessThePropertySaysOtherwise() {
        final Path standard = Path.of(HOME, ".comando");
        final Path other = Path.of("build", "test-home").toAbsolutePath();
        assertThat(AppConfig.keychain(null, standard, standard)).isTrue();
        assertThat(AppConfig.keychain(null, other, standard)).as("a test root").isFalse();
        assertThat(AppConfig.keychain("true", other, standard)).isTrue();
        assertThat(AppConfig.keychain("false", standard, standard)).isFalse();
        assertThat(AppConfig.keychain("", standard, standard)).as("empty is no").isFalse();
    }

    /// The environment's configuration takes the data root from `-Dcomando.home`, normalised, and keeps a root under
    /// `build/` out of the keychain; an empty `-Dcomando.keychain` keeps it out too.
    @Test
    void theEnvironmentsDataRootIsNormalisedAndATestRootHasNoKeychain() {
        final String home = System.getProperty("comando.home");
        final String keychain = System.getProperty("comando.keychain");
        try {
            System.setProperty("comando.home", "build/x/../test-home");
            System.clearProperty("comando.keychain");
            final AppConfig config = AppConfig.fromEnvironment();
            assertThat(config.home()).isEqualTo(Path.of("build", "test-home").toAbsolutePath());
            assertThat(config.keychain()).isFalse();
            assertThat(config.logDir()).isEqualTo(config.home().resolve("logs"));

            System.setProperty("comando.keychain", "");
            assertThat(AppConfig.fromEnvironment().keychain()).isFalse();
        } finally {
            restore("comando.home", home);
            restore("comando.keychain", keychain);
        }
    }

    private static void restore(final String key, final String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
