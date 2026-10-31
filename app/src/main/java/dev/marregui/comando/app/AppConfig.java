package dev.marregui.comando.app;

import java.nio.file.Path;

import dev.marregui.comando.claude.ClaudeSettings;
import dev.marregui.comando.claude.KitJson;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.model.KitRoot;

/**
 * Where comando keeps its files, and the Claude Code folder it runs its sessions on. The data root is {@code
 * -Dcomando.home}, else {@code COMANDO_HOME}, else {@code ~/.comando}; tests point it at a temporary directory. The
 * Claude Code folder is the kit root ({@link KitRoot}).
 *
 * @param keychain keep connection passwords in the OS secret store; on for the default data root, off for any other
 *                 unless {@code -Dcomando.keychain=true}, so a test root never writes the user's keychain
 */
public record AppConfig(Path home, String claudeExecutable, KitRoot root, boolean recordIntents, boolean keychain) {

    /** For tests and tools: Claude Code's folder holds {@code claudeProjects}, without a kit; no keychain. */
    public AppConfig(final Path home, final String claudeExecutable, final Path claudeProjects, final boolean recordIntents) {
        this(home, claudeExecutable, KitRoot.plain(claudeProjects.toAbsolutePath().normalize().getParent()), recordIntents, false);
    }

    /** A configuration for tests and tools on {@code root}: no keychain. */
    public AppConfig(final Path home, final String claudeExecutable, final KitRoot root, final boolean recordIntents) {
        this(home, claudeExecutable, root, recordIntents, false);
    }

    /**
     * The data root as asked: {@code -Dcomando.home}, else {@code COMANDO_HOME}, else {@code ~/.comando}; a leading
     * {@code ~} is the home folder, as in {@code CLAUDE_CONFIG_DIR}, so a quoted {@code ~/x} is not a folder named
     * {@code ~} under the working directory.
     */
    static Path dataRoot(final String prop, final String env) {
        final String chosen = prop != null && !prop.isBlank() ? prop : env != null && !env.isBlank() ? env : null;
        return chosen != null ? Path.of(HomeDir.expand(chosen.strip())) : Path.of(System.getProperty("user.home"), ".comando");
    }

    public static AppConfig fromEnvironment() {
        final Path chosen = dataRoot(System.getProperty("comando.home"), System.getenv("COMANDO_HOME"));
        final String claude = System.getenv("COMANDO_CLAUDE");
        final Path standard = Path.of(System.getProperty("user.home"), ".comando").toAbsolutePath().normalize();
        // normalised: a data root written ./x or a/../b names one folder
        final Path home = chosen.toAbsolutePath().normalize();
        final String keychain = System.getProperty("comando.keychain");
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

    /** Where the sessions' metrics are kept, a file a day (record 110). */
    public Path metricsDir() {
        return home.resolve("metrics");
    }

    public Path questDbRoot() {
        return home.resolve("questdb");
    }

    /** Where comando's Edge is installed and keeps its data and logs. */
    public Path edgeRoot() {
        return home.resolve("edge");
    }
}
