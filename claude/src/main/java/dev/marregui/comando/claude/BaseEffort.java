package dev.marregui.comando.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonException;
import dev.marregui.comando.core.json.JsonNode;

/**
 * The effort level Claude Code starts a model's turns at, from its {@code settings.json}: the model's own
 * {@code modelSettings.<model id>.effortLevel} first, else {@code effortLevel}. The stream does not report it, and a
 * turn may raise it (a skill, a hook); this is the base the session runs at.
 */
final class BaseEffort {

    private BaseEffort() {
    }

    /** The base effort for {@code model}, or empty when the settings give none or cannot be read. */
    static String base(final Path settings, final String model) {
        JsonNode root;
        try {
            if (!Files.isRegularFile(settings)) {
                return "";
            }
            root = Mapped.read(settings, m -> Json.readTree(m.stream()));
        } catch (final IOException | JsonException _) {
            return "";
        }
        final String own = model.isEmpty() ? "" : root.path("modelSettings").path(model).path("effortLevel").asString("");
        return own.isEmpty() ? root.path("effortLevel").asString("") : own;
    }
}
