package dev.marregui.comando.claude;

import dev.marregui.comando.core.model.HomeDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * How comando runs Claude Code.
 *
 * @param executable    the {@code claude} binary
 * @param runDir        where per-session MCP config files live while a process runs
 * @param projectsDir   Claude Code's stored conversations: {@code projects} in its folder ({@link HomeDir#claudeDir})
 * @param uiInstructions text passed with {@code --append-system-prompt}; empty for none
 * @param launch        what each process gets from the kit root: its folder and the kit's settings
 */
public record ClaudeSettings(String executable, Path runDir, Path projectsDir, String uiInstructions, RootLaunch launch) {

    /** Every process on the environment comando was started with ({@link RootLaunch#INHERIT}); for tests. */
    public ClaudeSettings(String executable, Path runDir, Path projectsDir, String uiInstructions) {
        this(executable, runDir, projectsDir, uiInstructions, RootLaunch.INHERIT);
    }

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
}
