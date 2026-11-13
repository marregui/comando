package dev.marregui.comando.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.model.AtomicFiles;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.model.KitRoot;
import dev.marregui.comando.core.resource.FolderKind;

/**
 * A kit's {@code comando-kit.json} read into a {@link KitRoot.Manifest}: schema version 1 ({@code "kit": 1}; a file
 * without the key is version 1), a name, the brain's folder when it names one (absolute, {@code ~/…} or relative to the
 * kit), the folders of the folder tabs (Kit, Brain, Scratchpad), each inside its {@code root} (the kit, or the brain),
 * the requirements, credentials and jobs (record 133 gives the schema). A file that is no JSON, of a later version,
 * without a name, or with a folder outside its root, is refused whole, with the reason. A folder that breaks the rules
 * every folder of the folder tabs meets ({@link FolderKind}: its icon, index, listing file, a name used once) or names
 * a root other than {@code kit} or {@code brain} is left out and named in {@link KitRoot.Manifest#problems}, so it
 * never shows at one start to vanish at the next; {@link #repair} rewrites the file without such folders once the user
 * confirms.
 */
public final class KitJson {

    /** The one version of the schema comando reads. */
    static final int VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper PRETTY = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private KitJson() {
    }

    /** The root comando runs on, from its own {@code CLAUDE_CONFIG_DIR} and {@code COMANDO_BRAIN} and the user's home. */
    public static KitRoot resolve() {
        return KitRoot.resolve(System.getenv("CLAUDE_CONFIG_DIR"), System.getenv(KitRoot.BRAIN_ENV), Path.of(HomeDir.home()), KitJson::read);
    }

    /**
     * Reads {@code file}; throws when it does not read or breaks the schema. A folder entry that breaks the folders'
     * rules is left out and named in the manifest's problems.
     */
    public static KitRoot.Manifest read(final Path file) throws IOException {
        final JsonNode root = tree(file);
        final String name = text(root, "name");
        if (name.isEmpty()) {
            throw new IOException("\"name\" is missing");
        }
        final String brain = brain(text(root, "brain"));
        final List<FolderRoot> folders = new ArrayList<>();
        final Set<String> inBrain = new HashSet<>();
        final List<String> problems = new ArrayList<>();
        int at = 0;
        for (final JsonNode f : root.path("folders")) {
            final String folder = text(f, "name");
            final FolderRoot entry = new FolderRoot(folder, inside(text(f, "path"), "folder " + folder, true), text(f, "index"),
                    strings(f.path("excludes")), text(f, "icon"), f.path("deletable").asBoolean(false), text(f, "listedIn"), text(f, "group"));
            final Optional<String> wrong = entryProblem(file, brain, f, entry, folders);
            if (wrong.isPresent()) {
                problems.add("folders[" + at + "]" + (folder.isEmpty() ? "" : " (" + folder + ")") + ": " + wrong.get());
            } else {
                folders.add(entry);
                if (text(f, "root").equals(KitRoot.IN_BRAIN)) {
                    inBrain.add(folder);
                }
            }
            at++;
        }
        final List<KitRoot.Requirement> requirements = list(root.path("requirements"), r -> {
            final Map<String, List<String>> install = new LinkedHashMap<>();
            r.path("install").properties().forEach(e -> install.put(e.getKey(), strings(e.getValue())));
            return new KitRoot.Requirement(text(r, "tool"), strings(r.path("check")), install);
        });
        final List<KitRoot.Credential> credentials = list(root.path("credentials"), c -> new KitRoot.Credential(text(c, "key"), text(c, "for"), text(c, "where")));
        final List<KitRoot.Job> jobs = list(root.path("jobs"), j -> {
            final Map<String, String> schedule = new LinkedHashMap<>();
            j.path("schedule").properties().forEach(e -> schedule.put(e.getKey(), e.getValue().asString("")));
            return new KitRoot.Job(text(j, "name"), schedule, strings(j.path("command")));
        });
        return new KitRoot.Manifest(name, brain, folders, requirements, credentials, jobs, problems, inBrain);
    }

    /**
     * The manifest's {@code brain} when it names a folder (absolute, {@code ~/…} or relative to the kit, outside it too:
     * the brain is a folder of its own, D10), empty when it names none; throws when it is no path.
     */
    private static String brain(final String path) throws IOException {
        if (path.isEmpty()) {
            return "";
        }
        try {
            Path.of(HomeDir.expand(path));
        } catch (final InvalidPathException e) {
            throw new IOException("brain: " + e.getMessage(), e);
        }
        return path;
    }

    /**
     * What is wrong with folder entry {@code f}, read as {@code entry}: a {@code root} other than {@code kit} or {@code
     * brain}, else what {@link #folderProblem} finds, its path taken in that root; empty when nothing is.
     */
    private static Optional<String> entryProblem(final Path file, final String brain, final JsonNode f, final FolderRoot entry,
                                                 final List<FolderRoot> kept) {
        final String root = text(f, "root");
        if (!root.isEmpty() && !root.equals(KitRoot.IN_KIT) && !root.equals(KitRoot.IN_BRAIN)) {
            return Optional.of("\"root\" is \"" + root + "\"; it is \"" + KitRoot.IN_KIT + "\" (the default) or \"" + KitRoot.IN_BRAIN + '"');
        }
        final Path kit = file.toAbsolutePath().getParent();
        final Path base = root.equals(KitRoot.IN_BRAIN) ? kit.resolve(HomeDir.expand(brain.isEmpty() ? KitRoot.BRAIN : brain)).normalize() : kit;
        return folderProblem(base, entry, kept);
    }

    /** The file's JSON object, of a version this comando reads; throws with the reason otherwise. */
    private static JsonNode tree(final Path file) throws IOException {
        JsonNode root;
        try {
            root = Mapped.read(file, m -> JSON.readTree(m.stream()));
        } catch (final JacksonException e) {
            throw new IOException(e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IOException("not a JSON object");
        }
        // a file written before the version was required is version 1 (record 133)
        final JsonNode version = root.path("kit");
        if (!version.isMissingNode() && (!version.isIntegralNumber() || version.asInt() != VERSION)) {
            throw new IOException("\"kit\" is " + version + ", the schema's version; this comando reads version " + VERSION
                    + (version.isIntegralNumber() && version.asInt() > VERSION ? ": the kit is newer than comando, upgrade comando" : ""));
        }
        return root;
    }

    /**
     * What is wrong with folder {@code f} by the rules every folder of the folder tabs meets, its path taken inside
     * {@code base}; empty when nothing is. {@code kept} holds the folders before it that passed.
     */
    private static Optional<String> folderProblem(final Path base, final FolderRoot f, final List<FolderRoot> kept) {
        if (f.name().isEmpty()) {
            return Optional.of("the folder has no name");
        }
        if (kept.stream().anyMatch(k -> k.name().equals(f.name()))) {
            return Optional.of("the name is used by another folder");
        }
        final Path dir = base.resolve(f.path()).normalize();
        try {
            FolderKind.INSTANCE.fromAttributes(FolderKind.INSTANCE.toAttributes(new FolderRoot(f.name(), dir.toString(), f.index(), f.excludes(),
                    f.icon(), f.deletable(), f.listedIn(), f.group())));
            return Optional.empty();
        } catch (final IllegalArgumentException e) {
            return Optional.of(e.getMessage());
        }
    }

    /**
     * As {@link #repair(Path, List)} with the problems the file has now: every entry {@link #read} leaves out.
     */
    public static List<String> repair(final Path file) throws IOException {
        return repair(file, read(file).problems());
    }

    /**
     * Rewrites {@code file} without the folder entries {@link #read} leaves out, keeping everything else, once the
     * user confirmed {@code confirmed}, the problems the window showed: when the file's problems are no longer those
     * (the kit's upgrade rewrote it meanwhile) nothing is removed and the call throws, so no entry the user did not see
     * goes. Answers the entries it took out, in {@link KitRoot.Manifest#problems}' words, none when there were none.
     * The old file is kept beside it as {@code comando-kit.json.bak-<time>}, one per repair, never overwritten. Throws
     * too when the file is refused whole: dropping entries cannot repair it, and it is left as it is.
     */
    public static List<String> repair(final Path file, final List<String> confirmed) throws IOException {
        final KitRoot.Manifest read = read(file);
        if (read.problems().isEmpty()) {
            return List.of();
        }
        if (!read.problems().equals(confirmed)) {
            throw new IOException(file.getFileName() + " changed since its problems were shown; nothing is removed: " + read.problems());
        }
        final ObjectNode root = (ObjectNode) tree(file);
        final ArrayNode kept = JSON.createArrayNode();
        final List<FolderRoot> passed = new ArrayList<>();
        // the entries read keeps, judged in the same order by the same rules
        for (final JsonNode f : root.path("folders")) {
            final FolderRoot entry = new FolderRoot(text(f, "name"), text(f, "path"), text(f, "index"), strings(f.path("excludes")), text(f, "icon"),
                    f.path("deletable").asBoolean(false), text(f, "listedIn"), text(f, "group"));
            if (entryProblem(file, read.brain(), f, entry, passed).isEmpty()) {
                passed.add(entry);
                kept.add(f);
            }
        }
        root.set("folders", kept);
        Files.copy(file, backup(file));
        AtomicFiles.writeString(file, PRETTY.writeValueAsString(root) + "\n");
        return read.problems();
    }

    /** A name for this repair's copy of {@code file} that no earlier copy holds: {@code comando-kit.json.bak-<time>}. */
    private static Path backup(final Path file) {
        final String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now());
        Path bak = file.resolveSibling(file.getFileName() + ".bak-" + stamp);
        for (int n = 2; Files.exists(bak); n++) {
            bak = file.resolveSibling(file.getFileName() + ".bak-" + stamp + "-" + n);
        }
        return bak;
    }

    private static String text(final JsonNode node, final String field) {
        final JsonNode v = node.path(field);
        return v.isValueNode() ? v.asString("").strip() : "";
    }

    private static List<String> strings(final JsonNode array) {
        final List<String> out = new ArrayList<>();
        array.forEach(v -> out.add(v.asString("")));
        return out;
    }

    private static <T> List<T> list(final JsonNode array, final Function<JsonNode, T> each) {
        final List<T> out = new ArrayList<>();
        array.forEach(v -> out.add(each.apply(v)));
        return out;
    }

    /**
     * {@code path} when it names a place inside the root: relative, without {@code ..}; with {@code root}, the root
     * itself too ({@code "."}): a folder of the folder tabs may be the kit's whole folder, its excludes leaving out the
     * rest.
     */
    private static String inside(final String path, final String what, final boolean root) throws IOException {
        Path p;
        try {
            p = Path.of(path).normalize();
        } catch (final InvalidPathException e) {
            throw new IOException(what + ": " + e.getMessage(), e);
        }
        if (path.isEmpty() || p.isAbsolute() || path.startsWith("~") || p.startsWith("..") || p.toString().isEmpty() && !root) {
            throw new IOException(what + " is not a folder inside the root: \"" + path + '"');
        }
        return path;
    }
}
