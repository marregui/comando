package dev.marregui.comando.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AppConfigTest {

    private static final String HOME = System.getProperty("user.home");

    @Test
    void aLeadingTildeInTheDataRootIsTheHomeFolder() {
        assertThat(AppConfig.dataRoot("~/data/comando", null)).isEqualTo(Path.of(HOME, "data", "comando"));
        assertThat(AppConfig.dataRoot(null, "~/x")).isEqualTo(Path.of(HOME, "x"));
        assertThat(AppConfig.dataRoot(" ~ ", null)).isEqualTo(Path.of(HOME));
    }

    @Test
    void thePropertyComesBeforeTheVariableAndTheDefaultIsDotComando() {
        assertThat(AppConfig.dataRoot("/tmp/a", "/tmp/b")).isEqualTo(Path.of("/tmp/a"));
        assertThat(AppConfig.dataRoot("  ", "/tmp/b")).isEqualTo(Path.of("/tmp/b"));
        assertThat(AppConfig.dataRoot(null, null)).isEqualTo(Path.of(HOME, ".comando"));
        assertThat(AppConfig.dataRoot("rel/~x", null)).as("a tilde inside a path is a name").isEqualTo(Path.of("rel/~x"));
    }
}
