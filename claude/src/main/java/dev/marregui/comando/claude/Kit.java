package dev.marregui.comando.claude;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.HomeDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * comando's Claude Code kit: the plugin this build carries (the repository's {@code kit/} folder), kept in comando's
 * folder and installed into Claude Code with the user's consent, through Claude Code's own plugin commands.
 *
 * <p>{@link #refresh} keeps comando's copy equal to the build's, as the tutorial is kept: the plugin loads from that
 * copy, so an installed kit follows comando's version. {@link #install} registers the copy as the marketplace
 * {@value #MARKETPLACE}, installs {@value #PLUGIN} for the user (or enables it), and writes the skeleton files that
 * are missing, never over an existing one. It also sets Claude Code's {@value #MEMORY_SETTING} to {@code
 * ~/.claude/MEMORY} unless the user set it, so every project shares one memory: the one key comando writes into
 * Claude Code's {@code settings.json} itself, listed for the user's consent like every other change. A setup of the user's own ({@code /reflect} or {@code /deep-review} as a
 * skill or a command) is never duplicated: the kit is then not installed. comando relies on nothing else of the
 * user's Claude Code setup.</p>
 *
 * <p>Every method blocks (file writes, {@code claude plugin} runs of up to a minute each): call them off the EDT and
 * off the reducer thread. They are synchronized, so a refresh at start never overlaps an install.</p>
 */
public final class Kit {

    public static final String MARKETPLACE = "comando";
    public static final String PLUGIN = "comando@comando";
    /** Where the build carries the kit, and the index of its files ({@code kit/} has hidden folders a jar cannot list). */
    static final String RESOURCES = "/dev/marregui/comando/claude/kit/";
    static final String INDEX = "files.txt";
    private static final String MANIFEST = ".claude-plugin/plugin.json";
    private static final String SKELETON = "skeleton/";
    /** Claude Code's setting for where auto memory lives; the comando flow keeps one memory for every project. */
    public static final String MEMORY_SETTING = "autoMemoryDirectory";
    /** Skeleton files under this folder go to the memory folder, wherever it is. */
    private static final String MEMORY = "MEMORY/";
    /** {@code settings.json} as Claude Code writes it: two spaces, {@code "key": value}. */
    private static final DefaultPrettyPrinter SETTINGS_PRINTER = settingsPrinter();
    /** The skills the kit brings; a user's own of the same name is a setup of their own. */
    private static final List<String> SKILLS = List.of("reflect", "deep-review");
    /** How long one {@code claude} run may take. */
    static final Duration CLI_BOUND = Duration.ofSeconds(60);
    /** How long a finished run's output may stay open, held by a process it left behind. */
    static final Duration DRAIN_BOUND = Duration.ofSeconds(2);
    private static final int MESSAGE_CHARS = 300;
    private static final Set<PosixFilePermission> EXECUTABLE = EnumSet.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);

    /** The kit's files as the build carries them. */
    public interface Bundle {
        /** Every file, relative to the kit's root, with {@code /} separators. */
        List<String> files() throws IOException;

        byte[] read(String file) throws IOException;

        /** The kit this build carries, from its resources. */
        static Bundle resources() {
            return new Bundle() {
                @Override
                public List<String> files() throws IOException {
                    return new String(read(INDEX), StandardCharsets.UTF_8).lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
                }

                @Override
                public byte[] read(String file) throws IOException {
                    try (InputStream in = Kit.class.getResourceAsStream(RESOURCES + file)) {
                        if (in == null) {
                            throw new IOException("the kit's " + file + " is not in this build");
                        }
                        return in.readAllBytes();
                    }
                }
            };
        }
    }

    /** Runs {@code claude} with arguments; the real one starts a process, tests answer from a script. */
    public interface Cli {
        /** The exit status and the output, standard error included. */
        record Output(int exit, String text) {
        }

        Output run(List<String> args) throws IOException;

        /** {@code executable} as a process, each run bounded by {@link #CLI_BOUND}. */
        static Cli process(String executable) {
            return process(executable, CLI_BOUND);
        }

        /**
         * {@code executable} as a process with no input (a prompt reads end of file rather than waiting); a run that
         * outlives {@code bound} is killed with what it started, and fails.
         */
        static Cli process(String executable, Duration bound) {
            return args -> {
                List<String> command = new ArrayList<>(args.size() + 1);
                command.add(executable);
                command.addAll(args);
                String what = "claude " + String.join(" ", args);
                Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
                p.getOutputStream().close();
                // read on a thread of its own, so the bound holds whatever the process does with its output
                FutureTask<byte[]> output = new FutureTask<>(() -> p.getInputStream().readAllBytes());
                Thread.ofVirtual().name("claude-output").start(output);
                try {
                    if (!p.waitFor(bound)) {
                        p.descendants().forEach(ProcessHandle::destroyForcibly);
                        p.destroyForcibly();
                        throw new IOException(what + " did not finish within " + seconds(bound));
                    }
                    return new Output(p.exitValue(), new String(output.get(DRAIN_BOUND.toMillis(), TimeUnit.MILLISECONDS),
                            StandardCharsets.UTF_8));
                } catch (TimeoutException e) {
                    p.getInputStream().close();
                    throw new IOException(what + " ended, but a process it started kept its output open", e);
                } catch (ExecutionException e) {
                    throw new IOException(what + ": its output could not be read: " + e.getCause().getMessage(), e.getCause());
                } catch (InterruptedException e) {
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                    Thread.currentThread().interrupt();
                    throw new IOException(what + " was interrupted", e);
                }
            };
        }

        private static String seconds(Duration d) {
            return d.toMillis() % 1000 == 0 ? d.toSeconds() + " s" : d.toMillis() + " ms";
        }
    }

    /**
     * What the kit is on this machine.
     *
     * @param version          the version this build carries
     * @param installedVersion the version Claude Code loads for the user, empty when the plugin is not installed
     * @param enabled          whether the installed plugin is enabled; false when not installed
     * @param loadsFrom        the folder Claude Code loads the installed plugin from, empty when not installed or not
     *                         reported; comando's copy unless the user installed it from elsewhere
     * @param fromCopy         whether Claude Code loads the installed plugin from comando's copy
     * @param existing         a setup of the user's own ({@code ~/.claude/skills/reflect}), shortened to {@code ~}:
     *                         while the plugin is not installed or disabled, the kit is neither installed nor enabled
     *                         beside it
     * @param changes          what an install would do, one line each, for the user to read before saying yes; empty
     *                         when there is nothing to do or a setup of the user's own stands in the way
     */
    public record Status(String version, String installedVersion, boolean enabled, String loadsFrom, boolean fromCopy,
                         List<String> existing, List<String> changes) {
        public boolean installed() {
            return !installedVersion.isEmpty();
        }

        /** A setup of the user's own stands where the kit would go: it is not installed, or disabled. */
        public boolean alreadySetUp() {
            return !existing.isEmpty() && (!installed() || !enabled);
        }

        /** Whether new Claude sessions load comando's copy of the kit. */
        public boolean loadsCopy() {
            return installed() && enabled && fromCopy;
        }
    }

    /** The plugin as Claude Code lists it for the user. */
    private record Installed(String version, boolean enabled, String loadsFrom) {
    }

    private final ObjectMapper json = new ObjectMapper();
    private final Bundle bundle;
    private final Cli cli;
    private final Path dir;
    private final Path claudeDir;
    /** The memory folder found by the last {@link #status} or {@link #install}; the Files tab's Memory row. */
    private volatile Path memory;

    /**
     * @param dir       comando's copy of the kit ({@code ~/.comando/kit})
     * @param claudeDir Claude Code's folder ({@code ~/.claude}), where the skeleton goes
     */
    public Kit(Bundle bundle, Cli cli, Path dir, Path claudeDir) {
        this.bundle = bundle;
        this.cli = cli;
        // normalised: refresh compares the files it wrote with the files it walks
        this.dir = dir.toAbsolutePath().normalize();
        this.claudeDir = claudeDir.toAbsolutePath().normalize();
        this.memory = this.claudeDir.resolve("MEMORY");
    }

    /** comando's copy of the kit. */
    public Path dir() {
        return dir;
    }

    /** The Gradle wrapper the kit carries, which queues runs in one checkout: comando's default for Gradle runs. */
    public Path gradleSerial() {
        return dir.resolve("bin").resolve("gradle-serial");
    }

    /** The version this build carries, from its plugin manifest. */
    public String version() throws IOException {
        return json.readTree(bundle.read(MANIFEST)).path("version").asText("");
    }

    /**
     * Makes comando's copy equal to the build's: writes each file that differs, marks the scripts executable, and
     * deletes files the build no longer carries.
     *
     * @return true when anything changed
     */
    public synchronized boolean refresh() throws IOException {
        List<String> files = bundle.files();
        Set<Path> wanted = new HashSet<>();
        boolean changed = false;
        for (String f : files) {
            Path target = resolveInside(dir, f);
            wanted.add(target);
            byte[] bytes = bundle.read(f);
            if (!Files.isRegularFile(target) || !Arrays.equals(Files.readAllBytes(target), bytes)) {
                write(target, bytes);
                changed = true;
            }
            if (executable(f) && !Files.isExecutable(target)) {
                setExecutable(target);
                changed = true;
            }
        }
        if (Files.isDirectory(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                List<Path> stale = walk.filter(Files::isRegularFile).map(Path::normalize).filter(p -> !wanted.contains(p)).toList();
                for (Path p : stale) {
                    Files.delete(p);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** What the kit is on this machine; asks Claude Code which plugins and marketplaces it has. */
    public synchronized Status status() throws IOException {
        Optional<Installed> installed = installed();
        List<String> existing = existingSetup();
        List<String> changes = new ArrayList<>();
        Installed i = installed.orElse(new Installed("", false, ""));
        boolean fromCopy = installed.isPresent() && samePath(i.loadsFrom(), dir);
        if (!existing.isEmpty() && (installed.isEmpty() || !i.enabled())) {
            return new Status(version(), i.version(), i.enabled(), i.loadsFrom(), fromCopy, existing, List.of());
        }
        Optional<String> setting = memorySetting();
        memory = memoryDir(setting);
        if (setting.isEmpty()) {
            changes.add("Set " + MEMORY_SETTING + " to " + shorten(memory) + " in " + shorten(settingsFile())
                    + ": every project then shares one memory, whose MEMORY.md index each session loads (up to 200 lines or 25 KB);"
                    + " memories kept per project so far stay where they are");
        }
        if (installed.isEmpty()) {
            Optional<String> registered = marketplacePath();
            if (registered.isPresent() && !samePath(registered.get(), dir)) {
                changes.add("Point Claude Code's plugin marketplace '" + MARKETPLACE + "' at " + shorten(dir) + " (it now reads "
                        + HomeDir.shorten(registered.get()) + "; Claude Code uninstalls what was installed from it, in any project)");
            } else if (registered.isEmpty()) {
                changes.add("Register " + shorten(dir) + " with Claude Code as the plugin marketplace '" + MARKETPLACE + "'");
            }
            changes.add("Install the plugin " + PLUGIN + " " + version() + " for your user (Claude Code adds it to its settings)");
        } else if (!installed.get().enabled()) {
            changes.add("Enable the plugin " + PLUGIN + " for your user");
        }
        for (String f : skeleton()) {
            Path target = skeletonTarget(f);
            if (!Files.exists(target)) {
                changes.add("Create " + shorten(target));
            }
        }
        return new Status(version(), i.version(), i.enabled(), i.loadsFrom(), fromCopy, existing, List.copyOf(changes));
    }

    /** One install step that changed something, and how to take it back. */
    private record Step(String done, Undo undo) {
    }

    @FunctionalInterface
    private interface Undo {
        /** Takes the step back; what was undone. */
        String run() throws IOException;
    }

    /**
     * Installs the kit. First comando's copy is made equal to the build's, as at every start: the copy is comando's,
     * and this is not undone. Then, whole or not at all: sets the memory folder unless the user did, registers the
     * copy and installs the plugin unless Claude Code has it (or enables it when disabled), and writes the skeleton
     * files that are missing. A step that fails undoes the steps before it, last first.
     *
     * @return what was done, one line each
     * @throws IOException naming the step that failed and what Claude Code said, and what was undone (or could not be);
     *                     or the setup of the user's own that keeps the kit out
     */
    public synchronized List<String> install() throws IOException {
        refresh();
        Optional<Installed> installed = installed();
        List<String> existing = existingSetup();
        if (!existing.isEmpty() && (installed.isEmpty() || !installed.get().enabled())) {
            throw new IOException("a setup of your own was found (" + String.join(", ", existing)
                    + "); the kit would duplicate it, so nothing was " + (installed.isEmpty() ? "installed" : "enabled"));
        }
        List<Step> steps = new ArrayList<>();
        try {
            Optional<String> setting = memorySetting();
            memory = memoryDir(setting);
            if (setting.isEmpty()) {
                setMemory(shorten(memory)).ifPresent(steps::add);
            }
            if (installed.isEmpty()) {
                Optional<String> registered = marketplacePath();
                if (registered.isPresent() && !samePath(registered.get(), dir)) {
                    String old = registered.get();
                    run(List.of("plugin", "marketplace", "remove", MARKETPLACE, "--json"), "plugin marketplace remove");
                    steps.add(new Step("Removed the marketplace '" + MARKETPLACE + "' at " + HomeDir.shorten(old), () -> {
                        if (!old.startsWith("/")) {
                            throw new IOException("its source " + old + " is not a folder comando can register again");
                        }
                        run(List.of("plugin", "marketplace", "add", old, "--json"), "plugin marketplace add");
                        return "registered the marketplace '" + MARKETPLACE + "' at " + HomeDir.shorten(old)
                                + " again (plugins Claude Code uninstalled with it are not installed again)";
                    }));
                    registered = Optional.empty();
                }
                if (registered.isEmpty()) {
                    run(List.of("plugin", "marketplace", "add", dir.toString(), "--json"), "plugin marketplace add");
                    steps.add(new Step("Registered the marketplace '" + MARKETPLACE + "' at " + shorten(dir), () -> {
                        run(List.of("plugin", "marketplace", "remove", MARKETPLACE, "--json"), "plugin marketplace remove");
                        return "removed the marketplace '" + MARKETPLACE + "'";
                    }));
                }
                run(List.of("plugin", "install", PLUGIN, "-y", "--json"), "plugin install");
                steps.add(new Step("Installed " + PLUGIN + " " + version(), () -> {
                    run(List.of("plugin", "uninstall", PLUGIN, "-y", "--json"), "plugin uninstall");
                    return "uninstalled " + PLUGIN;
                }));
            } else if (!installed.get().enabled()) {
                run(List.of("plugin", "enable", PLUGIN, "--scope", "user", "--json"), "plugin enable");
                steps.add(new Step("Enabled " + PLUGIN, () -> {
                    run(List.of("plugin", "disable", PLUGIN, "--scope", "user", "--json"), "plugin disable");
                    return "disabled " + PLUGIN;
                }));
            }
            String today = LocalDate.now().toString();
            for (String f : skeleton()) {
                Path target = skeletonTarget(f);
                if (Files.exists(target)) {
                    continue;
                }
                String text = new String(bundle.read(SKELETON + f), StandardCharsets.UTF_8).replace("<date>", today);
                try {
                    Files.createDirectories(target.getParent());
                } catch (IOException e) {
                    throw new IOException("cannot create " + shorten(target) + ": " + e.getMessage(), e);
                }
                try {
                    Files.writeString(target, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                } catch (FileAlreadyExistsException e) {
                    continue; // written meanwhile by the user or another session: theirs stands
                } catch (IOException e) {
                    throw new IOException("cannot create " + shorten(target) + ": " + e.getMessage(), e);
                }
                steps.add(new Step("Created " + shorten(target), () -> {
                    if (!Files.readString(target, StandardCharsets.UTF_8).equals(text)) {
                        throw new IOException("written to since; kept");
                    }
                    Files.delete(target);
                    return "deleted " + shorten(target);
                }));
            }
        } catch (IOException e) {
            throw new IOException(e.getMessage() + undo(steps), e);
        }
        return steps.stream().map(Step::done).toList();
    }

    /** Takes the steps back, last first; what was undone and what could not be, as a clause for the failure. */
    private static String undo(List<Step> steps) {
        if (steps.isEmpty()) {
            return "";
        }
        List<String> undone = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (int i = steps.size() - 1; i >= 0; i--) {
            Step step = steps.get(i);
            try {
                undone.add(step.undo().run());
            } catch (IOException | RuntimeException e) {
                kept.add(step.done() + " (" + e.getMessage() + ")");
            }
        }
        return (undone.isEmpty() ? "" : "; undone: " + String.join(", ", undone))
                + (kept.isEmpty() ? "" : "; NOT undone: " + String.join(", ", kept));
    }

    private void run(List<String> args, String what) throws IOException {
        ok(cli.run(args), what);
    }

    /**
     * The folders the Files tab gains with the kit: the memory, the knowledge base and the scratchpad, each with its
     * index. The memory folder is the one the last {@link #status} found; reads nothing.
     */
    public List<FolderRoot> folders() {
        return List.of(new FolderRoot("Memory", memory.toString(), "MEMORY.md", List.of(), "memory"),
                new FolderRoot("Knowledge base", claudeDir.resolve("KB").toString(), "ALPHA.md", List.of(), "knowledge"),
                new FolderRoot("Scratchpad", claudeDir.resolve("SCRATCHPAD").toString(), "README.md", List.of(), "scratchpad"));
    }

    /** The skeleton's files, relative to Claude Code's folder. */
    private List<String> skeleton() throws IOException {
        return bundle.files().stream().filter(f -> f.startsWith(SKELETON)).map(f -> f.substring(SKELETON.length())).toList();
    }

    /** Where a skeleton file goes: under the memory folder for {@code MEMORY/…}, else under Claude Code's folder. */
    private Path skeletonTarget(String file) throws IOException {
        return file.startsWith(MEMORY) ? resolveInside(memory, file.substring(MEMORY.length())) : resolveInside(claudeDir, file);
    }

    private Path settingsFile() {
        return claudeDir.resolve("settings.json");
    }

    /**
     * The user's settings as read: where the file lives (a link followed), its bytes (null when missing), whether it
     * opens with a byte-order mark, and its object.
     */
    private record Settings(Path file, byte[] bytes, boolean bom, ObjectNode object) {
    }

    /** The user's settings; refuses what Claude Code would refuse, so a file it cannot read is never rewritten. */
    private Settings settings() throws IOException {
        Path link = settingsFile();
        if (Files.isSymbolicLink(link) && !Files.exists(link)) {
            throw new IOException(shorten(link) + " links to " + Files.readSymbolicLink(link) + ", which does not exist;"
                    + " nothing was written to it");
        }
        Path file = Files.isSymbolicLink(link) ? link.toRealPath() : link;
        byte[] bytes = Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        String text = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
        boolean bom = text.startsWith("\uFEFF");
        if (bom) {
            text = text.substring(1);
        }
        if (text.isBlank()) {
            return new Settings(file, bytes, bom, json.createObjectNode());
        }
        JsonNode node;
        try {
            node = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
        } catch (IOException e) {
            throw new IOException(shorten(link) + " is not JSON (" + firstLine(String.valueOf(e.getMessage())) + "); nothing was written to it", e);
        }
        if (!(node instanceof ObjectNode object)) {
            throw new IOException(shorten(link) + " is not a JSON object; nothing was written to it");
        }
        return new Settings(file, bytes, bom, object);
    }

    /** The user's {@value #MEMORY_SETTING}, when set; refused when Claude Code would not take it. */
    private Optional<String> memorySetting() throws IOException {
        JsonNode v = settings().object().get(MEMORY_SETTING);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            return Optional.empty();
        }
        String value = v.asText().strip();
        if (!value.startsWith("/") && !value.startsWith("~/")) {
            throw new IOException(MEMORY_SETTING + " \"" + value + "\" in " + shorten(settingsFile())
                    + " is neither an absolute path nor ~/…, as Claude Code requires; correct it, then try again");
        }
        return Optional.of(value);
    }

    /** The memory folder: the user's setting, else {@code MEMORY} in Claude Code's folder. */
    private Path memoryDir(Optional<String> setting) {
        return setting.map(s -> Path.of(HomeDir.expand(s)).toAbsolutePath().normalize()).orElse(claudeDir.resolve("MEMORY"));
    }

    /**
     * Sets {@value #MEMORY_SETTING} in the user's settings, read again just before, keeping every other key, Claude
     * Code's layout, a byte-order mark, the file's permissions and a link to where it lives.
     *
     * @return how to undo it, or empty when it was set meanwhile (the user's value stands)
     */
    private Optional<Step> setMemory(String value) throws IOException {
        Settings settings = settings();
        JsonNode v = settings.object().get(MEMORY_SETTING);
        if (v != null && v.isTextual() && !v.asText().isBlank()) {
            return Optional.empty();
        }
        settings.object().put(MEMORY_SETTING, value);
        Path file = settings.file();
        Set<PosixFilePermission> permissions = settings.bytes() == null ? null : permissions(file);
        byte[] written = writeSettings(settings);
        byte[] before = settings.bytes();
        return Optional.of(new Step("Set " + MEMORY_SETTING + " to " + value, () -> {
            // put back what was there, unless someone wrote the file since: then take out only the key
            if (Arrays.equals(Files.readAllBytes(file), written)) {
                if (before == null) {
                    Files.delete(file);
                } else {
                    write(file, before);
                    if (permissions != null) {
                        Files.setPosixFilePermissions(file, permissions);
                    }
                }
            } else {
                Settings now = settings();
                JsonNode current = now.object().get(MEMORY_SETTING);
                if (current == null || !value.equals(current.asText())) {
                    throw new IOException("its value was changed since; kept");
                }
                now.object().remove(MEMORY_SETTING);
                writeSettings(now);
            }
            return "took " + MEMORY_SETTING + " out of " + shorten(settingsFile());
        }));
    }

    /** Writes the settings back as Claude Code lays them out, keeping a byte-order mark and the file's permissions. */
    private byte[] writeSettings(Settings settings) throws IOException {
        byte[] bytes = ((settings.bom() ? "\uFEFF" : "") + json.writer(SETTINGS_PRINTER).writeValueAsString(settings.object()) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        Set<PosixFilePermission> permissions = settings.bytes() == null ? null : permissions(settings.file());
        write(settings.file(), bytes);
        if (permissions != null) {
            Files.setPosixFilePermissions(settings.file(), permissions);
        }
        return bytes;
    }

    /** A file's POSIX permissions, or null on a file system without them. */
    private static Set<PosixFilePermission> permissions(Path file) throws IOException {
        try {
            return Files.getPosixFilePermissions(file);
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }

    private static DefaultPrettyPrinter settingsPrinter() {
        DefaultIndenter twoSpaces = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter(Separators.createDefaultInstance()
                .withObjectFieldValueSpacing(Separators.Spacing.AFTER).withObjectEmptySeparator("").withArrayEmptySeparator(""));
        printer.indentObjectsWith(twoSpaces);
        printer.indentArraysWith(twoSpaces);
        return printer;
    }

    /** The user's own {@code /reflect} or {@code /deep-review}, as a skill or a command. */
    private List<String> existingSetup() {
        List<String> found = new ArrayList<>();
        for (String name : SKILLS) {
            for (Path p : List.of(claudeDir.resolve("skills").resolve(name), claudeDir.resolve("commands").resolve(name + ".md"))) {
                if (Files.exists(p)) {
                    found.add(shorten(p));
                }
            }
        }
        return List.copyOf(found);
    }

    private Optional<Installed> installed() throws IOException {
        for (JsonNode p : parse(ok(cli.run(List.of("plugin", "list", "--json")), "plugin list"), "plugin list")) {
            if (PLUGIN.equals(p.path("id").asText()) && "user".equals(p.path("scope").asText("user"))) {
                // the version recorded at install stays; the one that loads is the folder's
                String version = p.path("folderVersion").asText(p.path("version").asText("?"));
                return Optional.of(new Installed(version.isEmpty() ? "?" : version, p.path("enabled").asBoolean(true),
                        p.path("readFromFolder").asText("")));
            }
        }
        return Optional.empty();
    }

    /** Where the marketplace named {@value #MARKETPLACE} reads from, when one is registered: its folder, else its source. */
    private Optional<String> marketplacePath() throws IOException {
        for (JsonNode m : parse(ok(cli.run(List.of("plugin", "marketplace", "list", "--json")), "plugin marketplace list"),
                "plugin marketplace list")) {
            if (MARKETPLACE.equals(m.path("name").asText())) {
                String path = m.path("path").asText("");
                return Optional.of(path.isEmpty() ? m.path("source").asText("an unknown source") : path);
            }
        }
        return Optional.empty();
    }

    /** Whether {@code path} (as Claude Code reports it) is {@code folder}, links resolved. */
    static boolean samePath(String path, Path folder) {
        Path p;
        try {
            p = Path.of(path);
        } catch (InvalidPathException e) {
            return false;
        }
        if (!p.isAbsolute()) {
            return false; // not a folder: a GitHub or URL source
        }
        p = p.normalize();
        try {
            return p.toRealPath().equals(folder.toRealPath());
        } catch (IOException e) {
            return p.equals(folder);
        }
    }

    /**
     * The output of a run that succeeded. A run that exited non-zero, or whose JSON result says its outcome was not
     * "ok", throws with Claude Code's message when its result has one, else with its first line.
     */
    private String ok(Cli.Output out, String what) throws IOException {
        String text = out.text().strip();
        JsonNode result = resultLine(text);
        boolean refused = result != null && result.has("outcome") && !"ok".equals(result.path("outcome").asText());
        if (out.exit() != 0 || refused) {
            String message = result == null ? "" : result.path("message").asText("").strip();
            throw new IOException(message.isEmpty()
                    ? "claude " + what + " failed (exit " + out.exit() + "): " + firstLine(text)
                    : "claude " + what + ": " + clip(message));
        }
        return text;
    }

    /** The last line that is a JSON object: the result line {@code --json} prints, before or after other lines. */
    private JsonNode resultLine(String text) {
        List<String> lines = text.lines().map(String::strip).filter(l -> l.startsWith("{") && l.endsWith("}")).toList();
        for (int i = lines.size() - 1; i >= 0; i--) {
            try {
                JsonNode node = json.readTree(lines.get(i));
                if (node != null && node.isObject()) {
                    return node;
                }
            } catch (IOException e) {
                // not JSON after all: an earlier line may be
            }
        }
        return null;
    }

    private Iterable<JsonNode> parse(String text, String what) throws IOException {
        JsonNode node;
        try {
            node = json.readTree(text);
        } catch (IOException e) {
            throw new IOException("claude " + what + " did not answer in JSON: " + firstLine(text), e);
        }
        if (node == null || !node.isArray()) {
            throw new IOException("claude " + what + " did not answer with a list: " + firstLine(text));
        }
        return node;
    }

    private static String firstLine(String text) {
        return clip(text.lines().filter(l -> !l.isBlank()).findFirst().orElse("no output"));
    }

    private static String clip(String line) {
        return line.length() > MESSAGE_CHARS ? line.substring(0, MESSAGE_CHARS) + "…" : line;
    }

    /** {@code root/relative}, refusing a relative path that leaves {@code root} (a damaged index). */
    private static Path resolveInside(Path root, String relative) throws IOException {
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root) || p.equals(root)) {
            throw new IOException("the kit's file " + relative + " is outside " + root);
        }
        return p;
    }

    private static boolean executable(String file) {
        return file.startsWith("bin/") || file.endsWith(".py");
    }

    private static void setExecutable(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, EXECUTABLE);
        } catch (UnsupportedOperationException e) {
            if (!file.toFile().setExecutable(true, false)) {
                throw new IOException("cannot make " + file + " executable", e);
            }
        }
    }

    /** Writes {@code file} through a temporary file moved into place, so a reader never sees half of it. */
    private static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), ".kit", ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String shorten(Path p) {
        return HomeDir.shorten(p.toString());
    }
}
