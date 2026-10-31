package dev.marregui.comando.claude;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.model.KitRoot;

/**
 * A kit's {@code comando-kit.json} read into a {@link KitRoot.Manifest}: schema version 1, a name, the brain's folder
 * and the Files tab's folders inside the root, the requirements, credentials and jobs. A file that is no JSON, of
 * another version, without a name or brain, or with a folder outside the root, is refused whole.
 */
public final class KitJson {

    /** The one version of the schema comando reads. */
    static final int VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    private KitJson() {
    }

    /** The root comando runs on, from its own {@code CLAUDE_CONFIG_DIR} and the user's home. */
    public static KitRoot resolve() {
        return KitRoot.resolve(System.getenv("CLAUDE_CONFIG_DIR"), Path.of(HomeDir.home()), KitJson::read);
    }

    /** Reads {@code file}; throws when it does not read or breaks the schema. */
    public static KitRoot.Manifest read(final Path file) throws IOException {
        JsonNode root;
        try {
            root = JSON.readTree(file.toFile());
        } catch (final JacksonException e) {
            throw new IOException(e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IOException("not a JSON object");
        }
        if (root.path("kit").asInt(-1) != VERSION) {
            throw new IOException("\"kit\" is " + root.path("kit") + ", comando reads version " + VERSION);
        }
        final String name = text(root, "name");
        if (name.isEmpty()) {
            throw new IOException("\"name\" is missing");
        }
        final String brain = inside(text(root, "brain"), "brain", false);
        final List<FolderRoot> folders = new ArrayList<>();
        for (final JsonNode f : root.path("folders")) {
            final String folder = text(f, "name");
            if (folder.isEmpty()) {
                throw new IOException("a folder has no name");
            }
            folders.add(new FolderRoot(folder, inside(text(f, "path"), "folder " + folder, true), text(f, "index"), strings(f.path("excludes")),
                    text(f, "icon"), f.path("deletable").asBoolean(false), text(f, "listedIn"), text(f, "group")));
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
        return new KitRoot.Manifest(name, brain, folders, requirements, credentials, jobs);
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
     * itself too ({@code "."}): a folder of the Files tab may be the kit's whole folder, its excludes leaving out the
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
