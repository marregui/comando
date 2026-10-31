package dev.marregui.comando.core.reduce;

import java.util.List;
import java.util.Objects;

import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.state.AppState;

/** The result of applying one intent: the next state and the effects to hand out, in order. */
public record Transition(AppState state, List<Effect> effects) {

    public Transition {
        Objects.requireNonNull(state, "state");
        effects = List.copyOf(effects);
    }

    public static Transition of(final AppState state) {
        return new Transition(state, List.of());
    }
}
