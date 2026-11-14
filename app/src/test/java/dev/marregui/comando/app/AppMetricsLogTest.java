package dev.marregui.comando.app;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.claude.MetricsLog;
import dev.marregui.comando.claude.QuestDbEndpoint;
import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;

/// How the app picks its metrics log (record 149): the machine's QuestDB is the only store, so an instance that
/// leaves it out (`-Dcomando.questdb=false`, as every test run does) keeps no metrics at all, and neither choice
/// writes a metrics folder under the data root. The shadow server is the one the app's constructor passes in; no log
/// built here reads the machine folder's `shadow` file. Each test runs with the user's home moved to a scratch folder
/// whose machine folder names a shadow, so a log that read the file would show it and the user's own is never read.
class AppMetricsLogTest {

    /// A QuestDB that never runs: the log may not reach it, and nothing here waits for it.
    private static final QuestDbEndpoint NO_SERVER = new QuestDbEndpoint() {
        @Override
        public Optional<URI> ensureUp() {
            return Optional.empty();
        }

        @Override
        public Optional<URI> live() {
            return Optional.empty();
        }
    };

    /// The shadow the scratch home's machine folder names.
    private static final String FILE_SHADOW = "http://127.0.0.1:9";

    private final RecordingSink notices = new RecordingSink();

    /// The user's home as the JVM had it before the test.
    private String realHome;

    /// Moves the user's home to `userHome`, its machine folder's `shadow` file naming [#FILE_SHADOW].
    @BeforeEach
    void moveTheUsersHome(@TempDir final Path userHome) throws Exception {
        Files.writeString(Files.createDirectories(userHome.resolve(".comando-questdb")).resolve("shadow"), FILE_SHADOW);
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", userHome.toString());
    }

    /// Puts the user's home back.
    @AfterEach
    void restoreTheUsersHome() {
        System.setProperty("user.home", realHome);
    }

    /// With the machine's QuestDB left out by the property, the app takes the log that keeps nothing.
    @Test
    void withTheMachinesQuestDbLeftOutTheLogKeepsNothing(@TempDir final Path home) {
        assertThat(App.machineQuestDb()).isFalse();
        assertThat(App.metricsLog(App.machineQuestDb(), home, NO_SERVER, notices, null)).isSameAs(MetricsLog.NONE);
        assertThat(Files.exists(home.resolve("metrics"))).isFalse();
    }

    /// With the machine's QuestDB, the app builds a log of its own, and still no metrics folder. With no shadow given
    /// it has none, though the machine folder's `shadow` file names one: the file is not read on this path.
    @Test
    void withTheMachinesQuestDbTheLogIsItsOwn(@TempDir final Path home) {
        try (final MetricsLog log = App.metricsLog(true, home, NO_SERVER, notices, null)) {
            assertThat(log).isNotSameAs(MetricsLog.NONE);
            assertThat(log.shadow()).as("no shadow read from the machine folder").isNull();
        }
        assertThat(Files.exists(home.resolve("metrics"))).isFalse();
    }

    /// The shadow the app passes in is the one each batch also goes to, not the machine folder's.
    @Test
    void theShadowGivenIsTheLogsShadow(@TempDir final Path home) {
        try (final MetricsLog log = App.metricsLog(true, home, NO_SERVER, notices, "http://127.0.0.1:7")) {
            assertThat(log.shadow()).isEqualTo(URI.create("http://127.0.0.1:7"));
        }
    }
}
