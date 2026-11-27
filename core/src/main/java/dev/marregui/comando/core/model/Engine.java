package dev.marregui.comando.core.model;

import java.util.List;
import java.util.stream.Stream;

/**
 * The program that runs a session: Claude Code, or OpenAI's Codex over {@code codex app-server} (ADR 0197). A session's
 * model names its engine, so a session record needs no field of its own: what changes between the two is the model.
 */
public enum Engine {
    CLAUDE,
    CODEX;

    /** The Codex model families comando offers, beside {@link SessionSpec#MODELS}' Claude ones. */
    public static final List<String> CODEX_MODELS = List.of("sol", "astra", "luna");
    /** The family a Codex session without a model runs, at {@link #CODEX_EFFORT}. */
    public static final String CODEX_DEFAULT_MODEL = "sol";
    /** The effort a Codex session without one runs at. */
    public static final String CODEX_EFFORT = "medium";

    /** The models a new session can pick: {@link SessionSpec#MODELS}, then {@link #CODEX_MODELS}. */
    public static List<String> sessionModels() {
        return Stream.concat(SessionSpec.MODELS.stream(), CODEX_MODELS.stream()).toList();
    }

    /** The engine of {@code model}: a Codex family or an OpenAI model id ({@code gpt-…}) is Codex; else Claude. */
    public static Engine of(final String model) {
        final String m = model == null ? "" : model.strip();
        return CODEX_MODELS.contains(m) || m.startsWith("gpt-") ? CODEX : CLAUDE;
    }

    /** The Codex model id a family names; an id passes through, an empty model is {@link #CODEX_DEFAULT_MODEL}'s. */
    public static String codexModelId(final String model) {
        final String m = model == null || model.isBlank() ? CODEX_DEFAULT_MODEL : model.strip();
        return switch (m) {
            case "sol" -> "gpt-6.2-sol";
            case "astra" -> "gpt-6.2-astra";
            case "luna" -> "gpt-6-luna";
            default -> m;
        };
    }
}
