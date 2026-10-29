package dev.marregui.comando.claude;

import dev.marregui.comando.core.model.KitRoot;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What every {@code claude} comando starts gets from the kit root: {@code CLAUDE_CONFIG_DIR=<root>} when the root is
 * not the user's own {@code ~/.claude}, and, with a kit, {@code --settings} naming the brain's memory as
 * {@code autoMemoryDirectory}. The session that upgrades a kit at or inside the user's own folder is the one exception:
 * it runs on the user's own folder, so it can replace the kit. A root elsewhere is another instance's kit: its upgrade
 * runs on that root, so nothing of the instance reads or writes {@code ~/.claude}.
 */
public final class RootLaunch {

    static final String CONFIG_DIR = "CLAUDE_CONFIG_DIR";
    static final String SETTINGS = "--settings";
    static final String MEMORY_SETTING = "autoMemoryDirectory";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The environment comando was started with, and no settings: tests, and tools started without a root. */
    public static final RootLaunch INHERIT = new RootLaunch(null, Optional.empty(), false, Optional.empty());

    /** The root sessions run on, when it is not the user's own folder; null for none. */
    private final Path configDir;
    private final Optional<KitRoot> root;
    /** Whether {@code CLAUDE_CONFIG_DIR} is taken out, so {@code claude} uses the user's own folder. */
    private final boolean usersOwn;
    private final Optional<Path> memory;

    private RootLaunch(Path configDir, Optional<KitRoot> root, boolean usersOwn, Optional<Path> memory) {
        this.configDir = configDir;
        this.root = root;
        this.usersOwn = usersOwn;
        this.memory = memory;
    }

    /** Every {@code claude} on {@code root}. */
    public static RootLaunch on(KitRoot root) {
        return new RootLaunch(root.own() ? null : root.dir(), Optional.of(root), false, root.memory());
    }

    /**
     * The session {@code name} in {@code workDir}: the root's, but for the session that upgrades a kit at or inside the
     * user's own folder.
     */
    public RootLaunch forSession(String name, String workDir) {
        if (root.isPresent() && name.equals(KitRoot.UPGRADE_SESSION) && root.get().isRoot(workDir)
                && root.get().dir().startsWith(root.get().userDir())) {
            return new RootLaunch(null, root, true, Optional.empty());
        }
        return this;
    }

    /**
     * {@code argv} with the kit's settings merged into a {@code --settings} it already has, else added after the
     * executable.
     */
    public List<String> argv(List<String> argv) {
        if (memory.isEmpty()) {
            return argv;
        }
        List<String> out = new ArrayList<>(argv);
        int at = out.indexOf(SETTINGS);
        if (at >= 0 && at + 1 < out.size()) {
            out.set(at + 1, merged(out.get(at + 1)));
        } else {
            out.add(Math.min(1, out.size()), SETTINGS);
            out.add(Math.min(2, out.size()), merged(""));
        }
        return out;
    }

    /** The settings given, as JSON, with the kit's memory; a value that is no JSON object (a file) is replaced. */
    private String merged(String given) {
        ObjectNode settings = JSON.createObjectNode();
        if (given.strip().startsWith("{")) {
            try {
                JsonNode read = JSON.readTree(given);
                if (read instanceof ObjectNode o) {
                    settings = o;
                }
            } catch (JacksonException e) {
                // not JSON: the kit's settings alone
            }
        }
        settings.put(MEMORY_SETTING, memory.orElseThrow().toString());
        return settings.toString();
    }

    /** Sets the process's environment: the root's folder, or the user's own for the upgrade. */
    public void environment(Map<String, String> env) {
        if (usersOwn) {
            env.remove(CONFIG_DIR);
        } else if (configDir != null) {
            env.put(CONFIG_DIR, configDir.toString());
        }
    }

    /** The environment's changes, for the log and tests: {@code CLAUDE_CONFIG_DIR=<root>}, its removal, or none. */
    @Override
    public String toString() {
        return usersOwn ? "-" + CONFIG_DIR : configDir != null ? CONFIG_DIR + '=' + configDir : "inherit";
    }
}
