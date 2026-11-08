package dev.marregui.comando.ui.dialogs.resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.model.HomeDir;

/**
 * What a session's working folder adds to the kit's settings: Claude Code reads the folder's own
 * {@code .claude/settings.json} and {@code .claude/settings.local.json} on top of the kit's, and a permission mode set
 * there wins over the kit's, the local file's over the shared one's (Miguel's decision of 2026-11-01). The session
 * dialog says so under its context line, so the kit's mode is not taken for the session's whole truth. Reads files:
 * not on the EDT.
 */
final class ProjectSettings {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    /** The project's files, the later read over the earlier. */
    private static final List<String> FILES = List.of("settings.json", "settings.local.json");

    private ProjectSettings() {
    }

    /**
     * The line for a session working in {@code workDir}: which project files apply on top of the kit's, and the
     * permission mode they set, which overrides the kit's; empty when the folder has none or is not given.
     */
    static String line(final String workDir) {
        return line(workDir, HomeDir.claudeDir());
    }

    /**
     * As {@link #line(String)}, with the kit at {@code kitDir}: when the folder's {@code .claude} is the kit itself, its
     * settings files are the kit's, so the line says that nothing lies on top of them.
     */
    static String line(final String workDir, final Path kitDir) {
        if (workDir == null || workDir.isBlank()) {
            return "";
        }
        final Path claude = Path.of(HomeDir.expand(workDir.strip())).resolve(".claude");
        final List<String> found = new ArrayList<>();
        String mode = "";
        String modeFrom = "";
        for (final String name : FILES) {
            final Path f = claude.resolve(name);
            if (!Files.isRegularFile(f)) {
                continue;
            }
            found.add(name);
            final String m = modeOf(f);
            if (!m.isEmpty()) {
                mode = m;
                modeFrom = name;
            }
        }
        if (found.isEmpty()) {
            return "";
        }
        final String where = HomeDir.shorten(claude.toString()) + "/" + String.join(" and ", found);
        if (claude.toAbsolutePath().normalize().equals(kitDir.toAbsolutePath().normalize())) {
            return "The working folder's " + where + " " + (found.size() == 1 ? "is" : "are") + " the kit's own: no project settings on top";
        }
        return "Plus the project's " + where + " on top of the kit's" + (mode.isEmpty() ? ""
                : "; its permissions: " + mode + " (" + modeFrom + ") overrides the kit's");
    }

    /** {@code permissions.defaultMode} in {@code file}; empty when it sets none or does not read. */
    private static String modeOf(final Path file) {
        try {
            final JsonNode root = Mapped.read(file, m -> JSON.readTree(m.stream()));
            return root == null ? "" : root.path("permissions").path("defaultMode").asString("").strip();
        } catch (final IOException | JacksonException _) {
            return "";
        }
    }
}
