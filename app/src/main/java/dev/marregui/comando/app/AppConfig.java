package dev.marregui.comando.app;

import dev.marregui.comando.claude.ClaudeSettings;

import java.nio.file.Path;

/**
 * Where comando keeps its files. The data root is {@code -Dcomando.home}, else {@code COMANDO_HOME}, else
 * {@code ~/.comando}; tests point it at a temporary directory.
 *
 * @param keychain keep connection passwords in the OS secret store; on for the default data root, off for
 *                 any other unless {@code -Dcomando.keychain=true}, so a test root never writes the user's keychain
 * @param offerKit offer comando's Claude Code kit at start; on for the default data root, off for any other unless
 *                 {@code -Dcomando.offerKit=true}, so a test root never asks Claude Code about the user's plugins
 */
public record AppConfig(Path home, String claudeExecutable, Path claudeProjects, boolean recordIntents, boolean keychain,
                        boolean offerKit) {

    /** A configuration for tests and tools: no keychain, no offer of the kit. */
    public AppConfig(Path home, String claudeExecutable, Path claudeProjects, boolean recordIntents) {
        this(home, claudeExecutable, claudeProjects, recordIntents, false, false);
    }

    public static AppConfig fromEnvironment() {
        String prop = System.getProperty("comando.home");
        String env = System.getenv("COMANDO_HOME");
        Path chosen = prop != null && !prop.isBlank() ? Path.of(prop)
                : env != null && !env.isBlank() ? Path.of(env)
                : Path.of(System.getProperty("user.home"), ".comando");
        String claude = System.getenv("COMANDO_CLAUDE");
        Path standard = Path.of(System.getProperty("user.home"), ".comando").toAbsolutePath().normalize();
        // normalised: a data root written ./x or a/../b names one folder, and the kit's copy compares paths
        Path home = chosen.toAbsolutePath().normalize();
        String keychain = System.getProperty("comando.keychain");
        String offerKit = System.getProperty("comando.offerKit");
        boolean standardHome = home.equals(standard);
        return new AppConfig(home,
                claude != null && !claude.isBlank() ? claude : ClaudeSettings.findExecutable(),
                ClaudeSettings.defaultProjectsDir(),
                Boolean.getBoolean("comando.recordIntents"),
                keychain != null ? Boolean.parseBoolean(keychain) : standardHome,
                offerKit != null ? Boolean.parseBoolean(offerKit) : standardHome);
    }

    public Path runDir() {
        return home.resolve("run");
    }

    public Path logDir() {
        return home.resolve("logs");
    }

    public Path auditDir() {
        return home.resolve("audit");
    }

    public Path questDbRoot() {
        return home.resolve("questdb");
    }

    /** comando's copy of its Claude Code kit. */
    public Path kitDir() {
        return home.resolve("kit");
    }
}
