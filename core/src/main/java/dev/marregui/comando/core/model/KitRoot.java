package dev.marregui.comando.core.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The Claude Code folder comando starts its sessions on, and the kit in it, when it has one. A kit is the setup of a
 * Claude Code folder, declared by the {@code comando-kit.json} at its top: its name, its brain (the folder holding memory,
 * knowledge base and scratchpad), the folders the Files tab shows, and what it needs outside the folder. comando reads
 * nothing else of a kit.
 *
 * <p>The root is {@code CLAUDE_CONFIG_DIR} when comando itself was started with it; else {@code ~/.claude} when it holds
 * a {@code comando-kit.json}; else {@code ~/.claude/comando-kit} when that holds one; else {@code ~/.claude}, with no kit. A {@code
 * comando-kit.json} that does not read is logged and counts as none.</p>
 *
 * @param dir     the root, absolute and normalised
 * @param kit     the kit's {@code comando-kit.json}, when the root has one that reads
 * @param userDir the user's own Claude Code folder, {@code ~/.claude}: a root elsewhere is passed to every {@code claude}
 *                comando starts, and the session that upgrades the kit runs on the user's own
 */
public record KitRoot(Path dir, Optional<Manifest> kit, Path userDir) {

    private static final System.Logger LOG = System.getLogger(KitRoot.class.getName());
    /** The file at the top of a root that makes it a kit. */
    public static final String FILE = "comando-kit.json";
    /** The folder under {@code ~/.claude} a kit of someone else's is cloned into. */
    public static final String CLONE = "comando-kit";
    /** The session that upgrades the kit: in the root, on the user's own Claude Code folder, so it can replace the kit. */
    public static final String UPGRADE_SESSION = "kit";
    /** Where the auto memory lives inside the brain. */
    public static final String MEMORY = "MEMORY";

    /** The root comando runs on, once {@link #use} named it; until then, one without a kit. */
    private static volatile KitRoot current;

    public KitRoot {
        dir = dir.toAbsolutePath().normalize();
        userDir = userDir.toAbsolutePath().normalize();
        Objects.requireNonNull(kit, "kit");
    }

    /** Reads a {@code comando-kit.json}; a file that does not read, or breaks the schema, throws. */
    @FunctionalInterface
    public interface Reader {
        Manifest read(Path file) throws IOException;
    }

    /**
     * A {@code comando-kit.json}: version 1 of its schema.
     *
     * @param name         the kit's name, shown with its version
     * @param brain        the brain's folder, relative to the root
     * @param folders      the Files tab's folders, their paths relative to the root
     * @param requirements the tools the kit needs outside the root
     * @param credentials  the keys its tools read, never their values
     * @param jobs         the scheduled jobs it installs
     */
    public record Manifest(String name, String brain, List<FolderRoot> folders, List<Requirement> requirements,
                           List<Credential> credentials, List<Job> jobs) {
        public Manifest {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(brain, "brain");
            folders = List.copyOf(folders);
            requirements = List.copyOf(requirements);
            credentials = List.copyOf(credentials);
            jobs = List.copyOf(jobs);
        }
    }

    /**
     * A tool the kit needs.
     *
     * @param check   the command whose success says the tool is there
     * @param install the command that installs it, by platform ({@code macos}, {@code linux}, {@code windows})
     */
    public record Requirement(String tool, List<String> check, Map<String, List<String>> install) {
        public Requirement {
            check = List.copyOf(check);
            install = Map.copyOf(install);
        }
    }

    /**
     * A credential the kit's tools read from the root's {@code settings.local.json}.
     *
     * @param usedBy what reads it
     * @param where  where the user gets it
     */
    public record Credential(String key, String usedBy, String where) {
    }

    /**
     * A scheduled job.
     *
     * @param schedule {@code daily} or {@code weekly}, and when
     * @param command  the command, relative to the root
     */
    public record Job(String name, Map<String, String> schedule, List<String> command) {
        public Job {
            schedule = Map.copyOf(schedule);
            command = List.copyOf(command);
        }
    }

    /** A root without a kit, on the user's own {@code ~/.claude} as {@link HomeDir#home} names it. */
    public static KitRoot plain(Path dir) {
        return new KitRoot(dir, Optional.empty(), Path.of(HomeDir.home(), ".claude"));
    }

    /**
     * The root for comando as started: {@code configDir} is the {@code CLAUDE_CONFIG_DIR} comando was started with (null
     * or blank when none), {@code home} the user's home folder.
     */
    public static KitRoot resolve(String configDir, Path home, Reader reader) {
        Path user = home.resolve(".claude");
        if (configDir != null && !configDir.isBlank()) {
            Path dir = Path.of(HomeDir.expand(configDir.strip()));
            return new KitRoot(dir, read(dir, reader), user);
        }
        for (Path dir : List.of(user, user.resolve(CLONE))) {
            Optional<Manifest> kit = read(dir, reader);
            if (kit.isPresent()) {
                return new KitRoot(dir, kit, user);
            }
        }
        return new KitRoot(user, Optional.empty(), user);
    }

    /** The kit of {@code dir}, when its {@code comando-kit.json} exists and reads; one that does not is logged. */
    private static Optional<Manifest> read(Path dir, Reader reader) {
        Path file = dir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(reader.read(file));
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read the kit, comando runs without one [file=" + file + ']', e);
            return Optional.empty();
        }
    }

    /** The root comando runs on, as the application named it; before that, Claude Code's folder, without a kit. */
    public static KitRoot current() {
        KitRoot r = current;
        if (r != null) {
            return r;
        }
        String env = System.getenv("CLAUDE_CONFIG_DIR");
        return plain(env != null && !env.isBlank() ? Path.of(HomeDir.expand(env.strip())) : Path.of(HomeDir.home(), ".claude"));
    }

    /** Names the root comando runs on, for what reads Claude Code's folder without being handed it; null forgets it. */
    public static void use(KitRoot root) {
        current = root;
    }

    /** Whether the root is the user's own {@code ~/.claude}, so a {@code claude} comando starts needs no other folder. */
    public boolean own() {
        return dir.equals(userDir);
    }

    /** Claude Code's stored conversations under the root. */
    public Path projects() {
        return dir.resolve("projects");
    }

    /** The kit's name, empty without a kit. */
    public String name() {
        return kit.map(Manifest::name).orElse("");
    }

    /** The brain's folder, with a kit. */
    public Optional<Path> brain() {
        return kit.map(k -> dir.resolve(k.brain()).normalize());
    }

    /** Where the sessions' auto memory lives, with a kit: the brain's {@code MEMORY}. */
    public Optional<Path> memory() {
        return brain().map(b -> b.resolve(MEMORY));
    }

    /** The kit's folders for the Files tab, their paths made absolute under the root; none without a kit. */
    public List<FolderRoot> folders() {
        return kit.map(k -> k.folders().stream().map(f -> new FolderRoot(f.name(), dir.resolve(f.path()).normalize().toString(), f.index(),
                f.excludes(), f.icon(), f.deletable(), f.listedIn(), f.group())).toList()).orElse(List.of());
    }

    /** Whether {@code workDir} names the root: the session there called {@link #UPGRADE_SESSION} upgrades the kit. */
    public boolean isRoot(String workDir) {
        try {
            return Path.of(HomeDir.expand(workDir)).toAbsolutePath().normalize().equals(dir);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
