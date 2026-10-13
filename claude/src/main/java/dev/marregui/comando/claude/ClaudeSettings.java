package dev.marregui.comando.claude;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * How comando runs Claude Code.
 *
 * @param executable    the {@code claude} binary
 * @param runDir        where per-session MCP config files live while a process runs
 * @param projectsDir   Claude Code's transcripts, {@code ~/.claude/projects}
 * @param uiInstructions text passed with {@code --append-system-prompt}; empty for none
 */
public record ClaudeSettings(String executable, Path runDir, Path projectsDir, String uiInstructions) {

    /** Finds {@code claude}: a GUI app launched from Finder has a short PATH, so known install paths come first. */
    public static String findExecutable() {
        String home = System.getProperty("user.home");
        for (String candidate : List.of(home + "/.local/bin/claude", home + "/.claude/local/claude", "/opt/homebrew/bin/claude",
                "/usr/local/bin/claude")) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return "claude";
    }

    public static Path defaultProjectsDir() {
        return Path.of(System.getProperty("user.home"), ".claude", "projects");
    }
}
