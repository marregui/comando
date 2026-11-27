package dev.marregui.comando.core.port;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import dev.marregui.comando.core.model.Engine;
import dev.marregui.comando.core.model.SessionSpec;

/**
 * Routes each session's calls to the adapter of its engine ({@link Engine#of(String)} on the session's model), so the
 * reducer and the effect router stay engine-blind (ADR 0197). A call by name alone goes where the session's last
 * {@link #start} or {@link #loadHistory} sent it; a name never seen goes to Claude.
 */
public final class EnginePort implements ClaudePort {

    private final ClaudePort claude;
    private final ClaudePort codex;
    private final Map<String, Engine> engines = new ConcurrentHashMap<>();

    public EnginePort(final ClaudePort claude, final ClaudePort codex) {
        this.claude = Objects.requireNonNull(claude, "claude");
        this.codex = Objects.requireNonNull(codex, "codex");
    }

    @Override
    public void start(final SessionSpec spec) {
        port(remember(spec)).start(spec);
    }

    @Override
    public void stop(final String name) {
        byName(name).stop(name);
    }

    @Override
    public void send(final String name, final String text, final String origin, final String preface) {
        byName(name).send(name, text, origin, preface);
    }

    @Override
    public void send(
            final String name,
            final String text,
            final String origin,
            final String preface,
            final boolean delivered
    ) {
        byName(name).send(name, text, origin, preface, delivered);
    }

    @Override
    public void interrupt(final String name) {
        byName(name).interrupt(name);
    }

    @Override
    public void clearTranscript(final String name) {
        byName(name).clearTranscript(name);
    }

    @Override
    public void loadHistory(final SessionSpec spec) {
        port(remember(spec)).loadHistory(spec);
    }

    @Override
    public void forget(final String name) {
        byName(name).forget(name);
        engines.remove(name);
    }

    @Override
    public void showSubagent(final String name, final String claudeSessionId, final String toolUseId) {
        byName(name).showSubagent(name, claudeSessionId, toolUseId);
    }

    private ClaudePort byName(final String name) {
        return port(engines.getOrDefault(name, Engine.CLAUDE));
    }

    private ClaudePort port(final Engine engine) {
        return engine == Engine.CODEX ? codex : claude;
    }

    private Engine remember(final SessionSpec spec) {
        final Engine engine = Engine.of(spec.model());
        engines.put(spec.name(), engine);
        return engine;
    }
}
