package dev.marregui.comando.core.port;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.model.Engine;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.testkit.RecordingPorts;

import static org.assertj.core.api.Assertions.assertThat;

/// [EnginePort] sends each session's calls to the adapter of the engine its model names (ADR 0197).
class EnginePortTest {

    /// Codex families and `gpt-` ids are Codex, anything else Claude; a family maps to its model id.
    @Test
    void theModelNamesTheEngine() {
        assertThat(Engine.of("sol")).isEqualTo(Engine.CODEX);
        assertThat(Engine.of("gpt-6.2-astra")).isEqualTo(Engine.CODEX);
        assertThat(Engine.of("opus")).isEqualTo(Engine.CLAUDE);
        assertThat(Engine.of("")).isEqualTo(Engine.CLAUDE);
        assertThat(Engine.of(null)).isEqualTo(Engine.CLAUDE);
        assertThat(Engine.codexModelId("")).isEqualTo("gpt-6.2-sol");
        assertThat(Engine.codexModelId("luna")).isEqualTo("gpt-6-luna");
        assertThat(Engine.codexModelId("gpt-7")).isEqualTo("gpt-7");
        assertThat(Engine.sessionModels()).containsSequence(SessionSpec.MODELS).endsWith("sol", "astra", "luna");
    }

    /// A started session's calls go to its engine's adapter; a name never started goes to Claude.
    @Test
    void eachSessionsCallsGoToTheAdapterOfItsEngine() {
        final RecordingPorts claude = new RecordingPorts();
        final RecordingPorts codex = new RecordingPorts();
        final EnginePort port = new EnginePort(claude, codex);
        port.start(spec("c", "opus"));
        port.start(spec("x", "sol"));
        port.send("c", "hi", "user", "");
        port.send("x", "hey", "user", "");
        port.stop("x");
        port.stop("never-started");
        assertThat(claude.calls()).containsExactly("claude.start c", "claude.send c hi", "claude.stop never-started");
        assertThat(codex.calls()).containsExactly("claude.start x", "claude.send x hey", "claude.stop x");
    }

    /// After `forget`, a session's name is unknown again and goes to Claude.
    @Test
    void aForgottenSessionFallsBackToClaude() {
        final RecordingPorts claude = new RecordingPorts();
        final RecordingPorts codex = new RecordingPorts();
        final EnginePort port = new EnginePort(claude, codex);
        port.loadHistory(spec("x", "sol"));
        port.forget("x");
        port.interrupt("x");
        assertThat(codex.calls()).hasSize(2);
        assertThat(claude.calls()).hasSize(1);
    }

    private static SessionSpec spec(final String name, final String model) {
        final SessionSpec fresh = SessionSpec.fresh(name, "/tmp");
        return fresh.withModel(model);
    }
}
