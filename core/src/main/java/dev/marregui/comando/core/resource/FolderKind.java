package dev.marregui.comando.core.resource;

import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Files tab's folders as a resource kind: a name, a folder, an index file, what to leave out, the row's icon,
 * whether its entries may go to the Trash and the file that lists them.
 * Its rules are the ones every entry of {@code folders.json} meets as the store reads it.
 */
public final class FolderKind implements ResourceKind<FolderRoot> {

    public static final FolderKind INSTANCE = new FolderKind();

    private static final List<Attribute> ATTRIBUTES = List.of(
            Attribute.of("name", "Name", Attribute.Kind.TEXT, true, "unique; the folder's name in the Files tab"),
            Attribute.of("path", "Folder", Attribute.Kind.DIRECTORY, true, ""),
            Attribute.of("index", "Index file", Attribute.Kind.TEXT, false,
                    "a file in the folder, such as 00_START_HERE.md; opened with the folder"),
            Attribute.of("excludes", "Leave out", Attribute.Kind.TEXT, false,
                    "folder and file names, comma-separated; build output is always left out"),
            Attribute.choice("icon", "Icon", FolderRoot.ICONS, "the icon of the folder's row in the Files tab"),
            Attribute.of("deletable", "Trash", Attribute.Kind.BOOLEAN, false,
                    "whether the Files tab moves the folder's files and subfolders to the Trash"),
            Attribute.of("listedIn", "Listed in", Attribute.Kind.TEXT, false,
                    "a file name, such as INDEX.md: an entry moved to the Trash leaves the nearest one above it"),
            Attribute.of("group", "Group", Attribute.Kind.TEXT, false,
                    "a header above this folder and the next ones in the same group, such as Brain; nothing nests"));

    private FolderKind() {
    }

    @Override
    public String noun() {
        return "folder";
    }

    @Override
    public List<Attribute> attributes() {
        return ATTRIBUTES;
    }

    @Override
    public Map<String, String> toAttributes(FolderRoot f) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", f.name());
        m.put("path", f.path());
        m.put("index", f.index());
        m.put("excludes", String.join(", ", f.excludes()));
        m.put("icon", f.icon());
        m.put("deletable", Boolean.toString(f.deletable()));
        m.put("listedIn", f.listedIn());
        m.put("group", f.group());
        return m;
    }

    @Override
    public FolderRoot fromAttributes(Map<String, String> a) {
        String name = a.getOrDefault("name", "").strip();
        String path = a.getOrDefault("path", "").strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("the folder needs a name");
        }
        if (path.isEmpty()) {
            throw new IllegalArgumentException("choose the folder to show");
        }
        path = HomeDir.expand(path);
        if (!Path.of(path).isAbsolute()) {
            throw new IllegalArgumentException("the folder must be a full path, such as ~/notes: " + path);
        }
        String index = a.getOrDefault("index", "").strip();
        if (Path.of(index).isAbsolute() || Path.of(index).normalize().startsWith("..")) {
            throw new IllegalArgumentException("the index file must be inside the folder: " + index);
        }
        String icon = a.getOrDefault("icon", "").strip();
        if (!icon.isEmpty() && !FolderRoot.ICONS.contains(icon)) {
            throw new IllegalArgumentException("the icon must be one of " + String.join(", ", FolderRoot.ICONS) + ": " + icon);
        }
        String listedIn = a.getOrDefault("listedIn", "").strip();
        if (!listedIn.isEmpty() && (listedIn.contains("/") || listedIn.contains("\\") || listedIn.equals(".") || listedIn.equals(".."))) {
            throw new IllegalArgumentException("the listing file must be a file name, such as INDEX.md: " + listedIn);
        }
        return new FolderRoot(
                name,
                path,
                index,
                Arrays.asList(a.getOrDefault("excludes", "").split(",")),
                icon,
                Boolean.parseBoolean(a.getOrDefault("deletable", "false").strip()),
                listedIn,
                a.getOrDefault("group", "").strip());
    }

    @Override
    public String nameOf(FolderRoot spec) {
        return spec.name();
    }

    @Override
    public FolderRoot withName(FolderRoot spec, String name) {
        return spec.withName(name);
    }

    @Override
    public FolderRoot template(String name) {
        return new FolderRoot(name, HomeDir.home(), "", List.of());
    }

    @Override
    public String noticeSubject(String name) {
        return Notice.folder(name);
    }

    @Override
    public String removalQuestion(AppState s, String name) {
        return "Remove folder " + name + " from the Files tab? Its files stay on disk.";
    }
}
