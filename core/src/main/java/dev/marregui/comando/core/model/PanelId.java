package dev.marregui.comando.core.model;

import java.util.Objects;
import java.util.UUID;

/** Stable identity of an editor panel; persisted with the panel so bindings survive a restart. */
public record PanelId(String value) {

    public PanelId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("panel id is empty");
        }
    }

    public static PanelId random() {
        return new PanelId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
