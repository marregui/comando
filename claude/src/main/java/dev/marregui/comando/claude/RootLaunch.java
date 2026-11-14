package dev.marregui.comando.claude;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonException;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.json.ObjectNode;
import dev.marregui.comando.core.model.KitRoot;

/**
 * What every {@code claude} comando starts gets from the kit root: {@code CLAUDE_CONFIG_DIR=<root>} when the root is
 * not the user's own {@code ~/.claude}, and, with a kit, {@code --settings} naming the brain's memory as
 * {@code autoMemoryDirectory}. The session that upgrades a kit at or inside the user's own folder is the one exception:
 * it runs on the user's own folder, so it can replace the kit. A root elsewhere is another instance's kit: its upgrade
 * runs on that root, so nothing of the instance reads or writes {@code ~/.claude}.
 * <p>
 * A session an agent made ({@code SessionSpec.createdBy}) also gets {@code COMANDO_CREATED_BY=<creator>}, so a kit's
 * hooks can tell it from a person's session and leave out what only a person needs; every other process has the
 * variable taken out, so none inherits it from the shell that started comando.
 * <p>
 * A session launched while comando runs the checkpoint at the context threshold gets
 * {@code CONTEXT_CHECKPOINT_BY=comando} (record 129), so a hook that would checkpoint on its own at that size leaves it
 * to comando; every other process has the variable taken out.
 * <p>
 * Every process gets {@code COMANDO_HOME=<data root>}, the instance's own, beside {@code CLAUDE_CONFIG_DIR}: an
 * instance is isolated, so a kit's hook that reads comando's files (its sessions, the folder tabs' folders) reads this
 * instance's, whatever data root comando was started with ({@code -Dcomando.home}, {@code COMANDO_HOME} or
 * {@code ~/.comando}). With a kit, every process gets {@code COMANDO_BRAIN=<brain>} too, the brain the instance
 * resolved ({@link KitRoot#brain}: Miguel's decision D10 of the first-release review), and {@code autoMemoryDirectory}
 * is its {@code MEMORY}; without a kit there is no brain, whatever {@code COMANDO_BRAIN} comando was started with, and
 * the variable is taken out.
 */
public final class RootLaunch {

    static final String CONFIG_DIR = "CLAUDE_CONFIG_DIR";
    static final String SETTINGS = "--settings";
    static final String MEMORY_SETTING = "autoMemoryDirectory";
    static final String CREATED_BY = "COMANDO_CREATED_BY";
    static final String CHECKPOINT_BY = "CONTEXT_CHECKPOINT_BY";
    static final String DATA_ROOT = "COMANDO_HOME";
    static final String BRAIN = KitRoot.BRAIN_ENV;
    static final String COMANDO = "comando";

    /** The environment comando was started with, and no settings: tests, and tools started without a root. */
    public static final RootLaunch INHERIT = new RootLaunch(null, Optional.empty(), false, Optional.empty(), "", false, null);

    /** The root sessions run on, when it is not the user's own folder; null for none. */
    private final Path configDir;
    private final Optional<KitRoot> root;
    /** Whether {@code CLAUDE_CONFIG_DIR} is taken out, so {@code claude} uses the user's own folder. */
    private final boolean usersOwn;
    private final Optional<Path> memory;
    /** The agent that made the session, or empty for a person's session and every other process. */
    private final String createdBy;
    /** comando runs the session's checkpoint at the context threshold. */
    private final boolean checkpointByComando;
    /** The instance's data root, passed as {@code COMANDO_HOME}; null to leave the variable as inherited. */
    private final Path dataRoot;

    private RootLaunch(final Path configDir, final Optional<KitRoot> root, final boolean usersOwn, final Optional<Path> memory, final String createdBy,
                       final boolean checkpointByComando, final Path dataRoot) {
        this.configDir = configDir;
        this.root = root;
        this.usersOwn = usersOwn;
        this.memory = memory;
        this.createdBy = createdBy;
        this.checkpointByComando = checkpointByComando;
        this.dataRoot = dataRoot;
    }

    /** Every {@code claude} on {@code root}, of the instance whose data root is {@code dataRoot}. */
    public static RootLaunch on(final KitRoot root, final Path dataRoot) {
        return new RootLaunch(root.own() ? null : root.dir(), Optional.of(root), false, root.memory(), "", false,
                dataRoot.toAbsolutePath().normalize());
    }

    /**
     * The session {@code name} in {@code workDir}: the root's, but for the session that upgrades a kit at or inside the
     * user's own folder.
     */
    public RootLaunch forSession(final String name, final String workDir) {
        if (root.isPresent() && name.equals(KitRoot.UPGRADE_SESSION) && root.get().isRoot(workDir)
                && root.get().dir().startsWith(root.get().userDir())) {
            return new RootLaunch(null, root, true, Optional.empty(), createdBy, checkpointByComando, dataRoot);
        }
        return this;
    }

    /** This launch for a session {@code createdBy} made, an agent's name; empty for a person's session. */
    public RootLaunch madeBy(final String createdBy) {
        final String by = createdBy == null ? "" : createdBy.strip();
        return by.equals(this.createdBy) ? this : new RootLaunch(configDir, root, usersOwn, memory, by, checkpointByComando, dataRoot);
    }

    /** This launch for a session whose checkpoint comando runs at the context threshold, or not. */
    public RootLaunch checkpointBy(final boolean comando) {
        return comando == checkpointByComando ? this : new RootLaunch(configDir, root, usersOwn, memory, createdBy, comando, dataRoot);
    }

    /**
     * {@code argv} with the kit's settings merged into a {@code --settings} it already has, else added after the
     * executable.
     */
    public List<String> argv(final List<String> argv) {
        if (memory.isEmpty()) {
            return argv;
        }
        final List<String> out = new ArrayList<>(argv);
        final int at = out.indexOf(SETTINGS);
        if (at >= 0 && at + 1 < out.size()) {
            out.set(at + 1, merged(out.get(at + 1)));
        } else {
            out.add(Math.min(1, out.size()), SETTINGS);
            out.add(Math.min(2, out.size()), merged(""));
        }
        return out;
    }

    /** The settings given, as JSON, with the kit's memory; a value that is no JSON object (a file) is replaced. */
    private String merged(final String given) {
        ObjectNode settings = Json.object();
        if (given.strip().startsWith("{")) {
            try {
                final JsonNode read = Json.readTree(given);
                if (read instanceof ObjectNode o) {
                    settings = o;
                }
            } catch (final JsonException e) {
                // not JSON: the kit's settings alone
            }
        }
        settings.put(MEMORY_SETTING, memory.orElseThrow().toString());
        return settings.toString();
    }

    /**
     * Sets the process's environment: the root's folder, or the user's own for the upgrade; the instance's data root;
     * the session's maker; who runs the checkpoint at the context threshold.
     */
    public void environment(final Map<String, String> env) {
        if (dataRoot != null) {
            env.put(DATA_ROOT, dataRoot.toString());
        }
        final Optional<Path> brain = root.flatMap(KitRoot::brain);
        if (brain.isPresent()) {
            env.put(BRAIN, brain.get().toString());
        } else if (root.isPresent()) {
            env.remove(BRAIN);
        }
        if (usersOwn) {
            env.remove(CONFIG_DIR);
        } else if (configDir != null) {
            env.put(CONFIG_DIR, configDir.toString());
        }
        if (createdBy.isEmpty()) {
            env.remove(CREATED_BY);
        } else {
            env.put(CREATED_BY, createdBy);
        }
        if (checkpointByComando) {
            env.put(CHECKPOINT_BY, COMANDO);
        } else {
            env.remove(CHECKPOINT_BY);
        }
    }

    /**
     * The environment's changes, for the log and tests: {@code CLAUDE_CONFIG_DIR=<root>}, its removal, or none; then
     * {@code COMANDO_HOME=<data root>}, {@code COMANDO_BRAIN=<brain>}, {@code COMANDO_CREATED_BY=<creator>} for an
     * agent's session, and {@code CONTEXT_CHECKPOINT_BY=comando} for a session whose checkpoint comando runs.
     */
    @Override
    public String toString() {
        final String config = usersOwn ? "-" + CONFIG_DIR : configDir != null ? CONFIG_DIR + '=' + configDir : "inherit";
        final String home = dataRoot == null ? config : config + ' ' + DATA_ROOT + '=' + dataRoot;
        final String dir = root.flatMap(KitRoot::brain).map(b -> home + ' ' + BRAIN + '=' + b).orElse(home);
        final String by = createdBy.isEmpty() ? dir : dir + ' ' + CREATED_BY + '=' + createdBy;
        return checkpointByComando ? by + ' ' + CHECKPOINT_BY + '=' + COMANDO : by;
    }
}
