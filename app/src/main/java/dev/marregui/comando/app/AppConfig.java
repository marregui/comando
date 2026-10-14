package dev.marregui.comando.app;

import dev.marregui.comando.claude.ClaudeSettings;

import java.nio.file.Path;

/**
 * Where comando keeps its files. The data root is {@code -Dcomando.home}, else {@code COMANDO_HOME}, else
 * {@code ~/.comando}; tests point it at a temporary directory.
 *
 * @param keychain keep connection passwords in the OS secret store; on for the default data root, off for
 *                 any other unless {@code -Dcomando.keychain=true}, so a test root never writes the user's keychain
 */
public record AppConfig(Path home, String claudeExecutable, Path claudeProjects, boolean recordIntents, boolean keychain) {

    /** A configuration for tests and tools: no keychain. */
    public AppConfig(Path home, String claudeExecutable, Path claudeProjects, boolean recordIntents) {
        this(home, claudeExecutable, claudeProjects, recordIntents, false);
    }

    public static AppConfig fromEnvironment() {
        String prop = System.getProperty("comando.home");
        String env = System.getenv("COMANDO_HOME");
        Path home = prop != null && !prop.isBlank() ? Path.of(prop)
                : env != null && !env.isBlank() ? Path.of(env)
                : Path.of(System.getProperty("user.home"), ".comando");
        String claude = System.getenv("COMANDO_CLAUDE");
        Path standard = Path.of(System.getProperty("user.home"), ".comando").toAbsolutePath();
        String keychain = System.getProperty("comando.keychain");
        return new AppConfig(home.toAbsolutePath(),
                claude != null && !claude.isBlank() ? claude : ClaudeSettings.findExecutable(),
                ClaudeSettings.defaultProjectsDir(),
                Boolean.getBoolean("comando.recordIntents"),
                keychain != null ? Boolean.parseBoolean(keychain) : home.toAbsolutePath().equals(standard));
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
}
