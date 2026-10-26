package dev.marregui.comando.app;

import dev.marregui.comando.claude.ClaudeSettings;
import dev.marregui.comando.claude.KitJson;
import dev.marregui.comando.core.model.KitRoot;

import java.nio.file.Path;

/**
 * Where comando keeps its files, and the Claude Code folder it runs its sessions on. The data root is {@code
 * -Dcomando.home}, else {@code COMANDO_HOME}, else {@code ~/.comando}; tests point it at a temporary directory. The
 * Claude Code folder is the kit root ({@link KitRoot}).
 *
 * @param keychain keep connection passwords in the OS secret store; on for the default data root, off for any other unless
 *                 {@code -Dcomando.keychain=true}, so a test root never writes the user's keychain
 */
public record AppConfig(Path home, String claudeExecutable, KitRoot root, boolean recordIntents, boolean keychain) {

    /** A configuration for tests and tools: Claude Code's folder holds {@code claudeProjects}, without a kit; no keychain. */
    public AppConfig(Path home, String claudeExecutable, Path claudeProjects, boolean recordIntents) {
        this(home, claudeExecutable, KitRoot.plain(claudeProjects.toAbsolutePath().normalize().getParent()), recordIntents, false);
    }

    /** A configuration for tests and tools on {@code root}: no keychain. */
    public AppConfig(Path home, String claudeExecutable, KitRoot root, boolean recordIntents) {
        this(home, claudeExecutable, root, recordIntents, false);
    }

    public static AppConfig fromEnvironment() {
        String prop = System.getProperty("comando.home");
        String env = System.getenv("COMANDO_HOME");
        Path chosen = prop != null && !prop.isBlank() ? Path.of(prop)
                : env != null && !env.isBlank() ? Path.of(env)
                : Path.of(System.getProperty("user.home"), ".comando");
        String claude = System.getenv("COMANDO_CLAUDE");
        Path standard = Path.of(System.getProperty("user.home"), ".comando").toAbsolutePath().normalize();
        // normalised: a data root written ./x or a/../b names one folder
        Path home = chosen.toAbsolutePath().normalize();
        String keychain = System.getProperty("comando.keychain");
        return new AppConfig(home,
                claude != null && !claude.isBlank() ? claude : ClaudeSettings.findExecutable(),
                KitJson.resolve(),
                Boolean.getBoolean("comando.recordIntents"),
                keychain != null ? Boolean.parseBoolean(keychain) : home.equals(standard));
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
