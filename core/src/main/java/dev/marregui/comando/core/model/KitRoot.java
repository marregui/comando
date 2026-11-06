package dev.marregui.comando.core.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The Claude Code folder comando starts its sessions on, and the kit in it, when it has one. A kit is the setup of a
 * Claude Code folder, declared by the {@code comando-kit.json} at its top: its name, its brain (the folder holding
 * memory, knowledge base and scratchpad), the folders the Files tab shows, and what it needs outside the folder.
 * What else comando reads of a kit is listed in the Kit and brain document, §2.1.
 *
 * <p>The root is {@code CLAUDE_CONFIG_DIR} when comando itself was started with it; else {@code ~/.claude} when it
 * holds a {@code comando-kit.json}; else {@code ~/comando-kit}, where a new install puts its kit, when that holds one;
 * else {@code ~/.claude/comando-kit}, for kits made before; else {@code ~/.claude}, with no kit. The first folder that
 * holds a {@code comando-kit.json} is the kit's: when that file does not read, comando runs with no kit, never on the
 * next folder's, and says why and how to get back to the kit in {@link #refused}, which the window tells the user
 * (record 133; Miguel's decisions D7 and D10 of the first-release review).</p>
 *
 * <p>The brain is a folder of its own, anywhere, and only with a kit: {@code COMANDO_BRAIN} when comando was started
 * with it; else the manifest's {@code brain}, absolute, {@code ~/…} or relative to the kit; else the kit's {@code BRAIN}
 * (Miguel's decision D10 of the first-release review, record 80). A Files-tab folder of the manifest lies in the kit or
 * in the brain, as its {@code root} says, so a brain moved by {@code COMANDO_BRAIN} takes its folders with it.</p>
 *
 * @param dir      the root, absolute and normalised
 * @param kit      the kit's {@code comando-kit.json}, when the root has one that reads
 * @param userDir  the user's own Claude Code folder, {@code ~/.claude}: a root elsewhere is passed to every
 *                 {@code claude} comando starts, and the session that upgrades the kit runs on the user's own
 * @param refused  why the root's {@code comando-kit.json} was not read, naming the file; empty when it read or there is
 *                 none
 * @param brainEnv the {@code COMANDO_BRAIN} comando was started with, empty when none; it names the brain only with a
 *                 kit
 */
public record KitRoot(Path dir, Optional<Manifest> kit, Path userDir, String refused, String brainEnv) {

    private static final System.Logger LOG = System.getLogger(KitRoot.class.getName());
    /** The file at the top of a root that makes it a kit. */
    public static final String FILE = "comando-kit.json";
    /**
     * The kit's folder a new install makes in the user's home ({@code ~/comando-kit}); under {@code ~/.claude}, where
     * kits made before were cloned, the same name.
     */
    public static final String CLONE = "comando-kit";
    /** The brain's folder a new install makes in the user's home, beside the kit, named by the manifest. */
    public static final String HOME_BRAIN = "comando-brain";
    /** The brain's folder in the kit when neither {@link #BRAIN_ENV} nor the manifest names one. */
    public static final String BRAIN = "BRAIN";
    /** The variable that names the brain, over the manifest; every session comando starts gets it. */
    public static final String BRAIN_ENV = "COMANDO_BRAIN";
    /** A Files-tab folder's {@code root}: its path is relative to the kit, the default. */
    public static final String IN_KIT = "kit";
    /** A Files-tab folder's {@code root}: its path is relative to the brain. */
    public static final String IN_BRAIN = "brain";
    /**
     * The session that upgrades the kit: in the root, on the user's own Claude Code folder, so it can replace the kit.
     */
    public static final String UPGRADE_SESSION = "kit";
    /** Where the auto memory lives inside the brain. */
    public static final String MEMORY = "MEMORY";

    /** The root comando runs on, once {@link #use} named it; until then, one without a kit. */
    private static volatile KitRoot current;

    public KitRoot {
        dir = dir.toAbsolutePath().normalize();
        userDir = userDir.toAbsolutePath().normalize();
        Objects.requireNonNull(kit, "kit");
        refused = refused == null ? "" : refused;
        brainEnv = brainEnv == null ? "" : brainEnv.strip();
    }

    /** A root whose {@code comando-kit.json}, if any, read or is absent, or was refused; no {@code COMANDO_BRAIN}. */
    public KitRoot(final Path dir, final Optional<Manifest> kit, final Path userDir, final String refused) {
        this(dir, kit, userDir, refused, "");
    }

    /** A root whose {@code comando-kit.json}, if any, read or is absent. */
    public KitRoot(final Path dir, final Optional<Manifest> kit, final Path userDir) {
        this(dir, kit, userDir, "");
    }

    /**
     * What is wrong with the root's {@code comando-kit.json}, each naming the field and the fault: the whole file when
     * it was refused, else the entries left out of it; none when nothing is.
     */
    public List<String> problems() {
        if (!refused.isEmpty()) {
            return List.of(refused);
        }
        return kit.map(Manifest::problems).orElse(List.of());
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
     * @param brain        the brain's folder as the manifest names it: absolute, {@code ~/…} or relative to the root;
     *                     empty for the root's {@link #BRAIN}
     * @param folders      the Files tab's folders, their paths relative to the root, or to the brain for those
     *                     {@code inBrain} names
     * @param requirements the tools the kit needs outside the root
     * @param credentials  the keys its tools read, never their values
     * @param jobs         the scheduled jobs it installs
     * @param problems     the entries left out because they break their rules, each naming its field and the fault
     * @param inBrain      the names of the folders whose {@code root} is {@link #IN_BRAIN}
     */
    public record Manifest(String name, String brain, List<FolderRoot> folders, List<Requirement> requirements,
                           List<Credential> credentials, List<Job> jobs, List<String> problems, Set<String> inBrain) {
        public Manifest {
            Objects.requireNonNull(name, "name");
            brain = brain == null ? "" : brain.strip();
            folders = List.copyOf(folders);
            requirements = List.copyOf(requirements);
            credentials = List.copyOf(credentials);
            jobs = List.copyOf(jobs);
            problems = problems == null ? List.of() : List.copyOf(problems);
            inBrain = inBrain == null ? Set.of() : Set.copyOf(inBrain);
        }

        /** A manifest whose folders all lie in the kit. */
        public Manifest(final String name, final String brain, final List<FolderRoot> folders, final List<Requirement> requirements,
                        final List<Credential> credentials, final List<Job> jobs, final List<String> problems) {
            this(name, brain, folders, requirements, credentials, jobs, problems, Set.of());
        }

        /** A manifest that read whole, its folders all in the kit. */
        public Manifest(final String name, final String brain, final List<FolderRoot> folders, final List<Requirement> requirements,
                        final List<Credential> credentials, final List<Job> jobs) {
            this(name, brain, folders, requirements, credentials, jobs, List.of());
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
    public static KitRoot plain(final Path dir) {
        return new KitRoot(dir, Optional.empty(), Path.of(HomeDir.home(), ".claude"));
    }

    /** As {@link #resolve(String, String, Path, Reader)} without {@code COMANDO_BRAIN}. */
    public static KitRoot resolve(final String configDir, final Path home, final Reader reader) {
        return resolve(configDir, "", home, reader);
    }

    /**
     * The root for comando as started: {@code configDir} is the {@code CLAUDE_CONFIG_DIR} and {@code brainEnv} the
     * {@code COMANDO_BRAIN} comando was started with (null or blank when none), {@code home} the user's home folder.
     * The kit is the first of {@code CLAUDE_CONFIG_DIR}; {@code ~/.claude}, {@code ~/comando-kit}, {@code
     * ~/.claude/comando-kit} that holds a {@code comando-kit.json}; else {@code ~/.claude} without a kit. When the file
     * of the first that holds one does not read, the root is {@code ~/.claude} without a kit, refused with why: a folder
     * further down the list is never taken in its place, as its settings, brain and folders are another kit's.
     */
    public static KitRoot resolve(final String configDir, final String brainEnv, final Path home, final Reader reader) {
        final Path user = home.resolve(".claude");
        if (configDir != null && !configDir.isBlank()) {
            final Path dir = Path.of(HomeDir.expand(configDir.strip()));
            final Read r = read(dir, reader);
            return new KitRoot(dir, r.kit(), user, r.refused(), brainEnv);
        }
        for (final Path dir : List.of(user, home.resolve(CLONE), user.resolve(CLONE))) {
            if (holds(dir)) {
                final Read r = read(dir, reader);
                return r.kit().isPresent() ? new KitRoot(dir, r.kit(), user, "", brainEnv) : new KitRoot(user, Optional.empty(), user, r.refused(), brainEnv);
            }
        }
        return new KitRoot(user, Optional.empty(), user, "", brainEnv);
    }

    /** Whether {@code dir} holds a {@code comando-kit.json}, whatever it is: a file, a link, even a folder. */
    private static boolean holds(final Path dir) {
        return !Files.notExists(dir.resolve(FILE), LinkOption.NOFOLLOW_LINKS);
    }

    /** A {@code comando-kit.json} read: its manifest, or why it was refused; neither when there is none. */
    private record Read(Optional<Manifest> kit, String refused) {
    }

    /**
     * The kit of {@code dir}, when it holds a {@code comando-kit.json} and that reads; one that does not is logged, and
     * refused with why and the way back: correct the file, then restart comando.
     */
    private static Read read(final Path dir, final Reader reader) {
        final Path file = dir.resolve(FILE);
        if (!holds(dir)) {
            return new Read(Optional.empty(), "");
        }
        try {
            return new Read(Optional.of(reader.read(file)), "");
        } catch (final IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read the kit, comando runs without one [file=" + file + ']', e);
            return new Read(Optional.empty(), refusal(HomeDir.shorten(file.toString()), Objects.toString(e.getMessage(), e.getClass().getSimpleName())));
        }
    }

    /** What the window tells of the kit's file {@code where}, refused for {@code why}: what is wrong and the way back. */
    private static String refusal(final String where, final String why) {
        return where + " is not used, so comando runs without the kit: " + why + ". Correct the file, then restart comando to run on the kit";
    }

    /** The root comando runs on, as the application named it; before that, Claude Code's folder, without a kit. */
    public static KitRoot current() {
        final KitRoot r = current;
        if (r != null) {
            return r;
        }
        final String env = System.getenv("CLAUDE_CONFIG_DIR");
        return plain(env != null && !env.isBlank() ? Path.of(HomeDir.expand(env.strip())) : Path.of(HomeDir.home(), ".claude"));
    }

    /** Names the root comando runs on, for what reads Claude Code's folder without being handed it; null forgets it. */
    public static void use(final KitRoot root) {
        current = root;
    }

    /**
     * Whether the root is the user's own {@code ~/.claude}, so a {@code claude} comando starts needs no other folder.
     */
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

    /**
     * The brain's folder, absolute and normalised, with a kit: {@code COMANDO_BRAIN} when comando was started with it;
     * else the manifest's {@code brain} (absolute, {@code ~/…} or relative to the kit), or the kit's {@link #BRAIN}
     * when it names none. Empty without a kit, {@code COMANDO_BRAIN} or not: the brain is a kit's ("kit required", Miguel's
     * decision of the first-release review), so a session on a root without one gets neither {@code COMANDO_BRAIN} nor the brain's
     * memory.
     */
    public Optional<Path> brain() {
        return kit.map(k -> brainEnv.isEmpty() ? dir.resolve(HomeDir.expand(k.brain().isEmpty() ? BRAIN : k.brain())).normalize()
                : Path.of(HomeDir.expand(brainEnv)).toAbsolutePath().normalize());
    }

    /** Where the sessions' auto memory lives, with a kit: the brain's {@code MEMORY}; empty without a kit. */
    public Optional<Path> memory() {
        return brain().map(b -> b.resolve(MEMORY));
    }

    /**
     * The kit's folders for the Files tab, their paths made absolute under the root, or under the {@link #brain} for
     * those whose {@code root} is {@link #IN_BRAIN}; none without a kit.
     */
    public List<FolderRoot> folders() {
        return kit.map(k -> k.folders().stream().map(f -> {
            final Path base = k.inBrain().contains(f.name()) ? brain().orElse(dir.resolve(BRAIN)) : dir;
            return new FolderRoot(f.name(), base.resolve(f.path()).normalize().toString(), f.index(), f.excludes(), f.icon(), f.deletable(),
                    f.listedIn(), f.group());
        }).toList()).orElse(List.of());
    }

    /** Whether {@code workDir} names the root: the session there called {@link #UPGRADE_SESSION} upgrades the kit. */
    public boolean isRoot(final String workDir) {
        try {
            return Path.of(HomeDir.expand(workDir)).toAbsolutePath().normalize().equals(dir);
        } catch (final RuntimeException e) {
            return false;
        }
    }
}
