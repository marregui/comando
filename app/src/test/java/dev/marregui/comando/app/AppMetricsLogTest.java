package dev.marregui.comando.app;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.claude.MetricsLog;
import dev.marregui.comando.claude.QuestDbEndpoint;
import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;

/// How the app picks its metrics log (record 149): the machine's QuestDB is the only store, so an instance that
/// leaves it out (`-Dcomando.questdb=false`, as every test run does) keeps no metrics at all, and neither choice
/// writes a metrics folder under the data root.
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

    private final RecordingSink notices = new RecordingSink();

    /// With the machine's QuestDB left out by the property, the app takes the log that keeps nothing.
    @Test
    void withTheMachinesQuestDbLeftOutTheLogKeepsNothing(@TempDir final Path home) {
        assertThat(App.machineQuestDb()).isFalse();
        assertThat(App.metricsLog(App.machineQuestDb(), home, NO_SERVER, notices)).isSameAs(MetricsLog.NONE);
        assertThat(Files.exists(home.resolve("metrics"))).isFalse();
    }

    /// With the machine's QuestDB, the app builds a log of its own, and still no metrics folder.
    @Test
    void withTheMachinesQuestDbTheLogIsItsOwn(@TempDir final Path home) {
        try (final MetricsLog log = App.metricsLog(true, home, NO_SERVER, notices)) {
            assertThat(log).isNotSameAs(MetricsLog.NONE);
        }
        assertThat(Files.exists(home.resolve("metrics"))).isFalse();
    }
}
