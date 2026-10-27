package dev.marregui.comando.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.model.KitRoot;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A kit's {@code comando-kit.json} read into a {@link KitRoot.Manifest}: schema version 1, a name, the brain's folder and the
 * Files tab's folders inside the root, the requirements, credentials and jobs. A file that is no JSON, of another
 * version, without a name or brain, or with a folder outside the root, is refused whole.
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
    public static KitRoot.Manifest read(Path file) throws IOException {
        JsonNode root = JSON.readTree(file.toFile());
        if (root == null || !root.isObject()) {
            throw new IOException("not a JSON object");
        }
        if (root.path("kit").asInt(-1) != VERSION) {
            throw new IOException("\"kit\" is " + root.path("kit") + ", comando reads version " + VERSION);
        }
        String name = text(root, "name");
        if (name.isEmpty()) {
            throw new IOException("\"name\" is missing");
        }
        String brain = inside(text(root, "brain"), "brain");
        List<FolderRoot> folders = new ArrayList<>();
        for (JsonNode f : root.path("folders")) {
            String folder = text(f, "name");
            if (folder.isEmpty()) {
                throw new IOException("a folder has no name");
            }
            folders.add(new FolderRoot(folder, inside(text(f, "path"), "folder " + folder), text(f, "index"), strings(f.path("excludes")),
                    text(f, "icon"), f.path("deletable").asBoolean(false), text(f, "listedIn"), f.path("repository").asBoolean(false)));
        }
        List<KitRoot.Requirement> requirements = list(root.path("requirements"), r -> {
            Map<String, List<String>> install = new LinkedHashMap<>();
            r.path("install").properties().forEach(e -> install.put(e.getKey(), strings(e.getValue())));
            return new KitRoot.Requirement(text(r, "tool"), strings(r.path("check")), install);
        });
        List<KitRoot.Credential> credentials = list(root.path("credentials"), c -> new KitRoot.Credential(text(c, "key"), text(c, "for"), text(c, "where")));
        List<KitRoot.Job> jobs = list(root.path("jobs"), j -> {
            Map<String, String> schedule = new LinkedHashMap<>();
            j.path("schedule").properties().forEach(e -> schedule.put(e.getKey(), e.getValue().asText("")));
            return new KitRoot.Job(text(j, "name"), schedule, strings(j.path("command")));
        });
        return new KitRoot.Manifest(name, brain, folders, requirements, credentials, jobs);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isValueNode() ? v.asText("").strip() : "";
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(v -> out.add(v.asText("")));
        return out;
    }

    private static <T> List<T> list(JsonNode array, Function<JsonNode, T> each) {
        List<T> out = new ArrayList<>();
        array.forEach(v -> out.add(each.apply(v)));
        return out;
    }

    /** {@code path} when it names a place inside the root: relative, without {@code ..}. */
    private static String inside(String path, String what) throws IOException {
        Path p;
        try {
            p = Path.of(path).normalize();
        } catch (InvalidPathException e) {
            throw new IOException(what + ": " + e.getMessage(), e);
        }
        if (path.isEmpty() || p.isAbsolute() || path.startsWith("~") || p.startsWith("..") || p.toString().isEmpty()) {
            throw new IOException(what + " is not a folder inside the root: \"" + path + '"');
        }
        return path;
    }
}
