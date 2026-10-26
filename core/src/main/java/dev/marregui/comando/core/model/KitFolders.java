package dev.marregui.comando.core.model;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The kit's folders in the Files tab. A folder of {@code folders.json} is a kit's folder when both name one folder on
 * disk, links followed and case ignored as macOS's volume compares names: {@code ~/.claude/MEMORY}, a link to {@code
 * ~/.claude/BRAIN/MEMORY}, is the kit's Memory.
 */
public final class KitFolders {

    private KitFolders() {
    }

    /**
     * The folders at start: when {@code have} shows none of the kit's, the kit's come first, but those whose name a
     * folder of {@code have} already takes; else {@code have} as it is, so a folder the user took out stays out.
     */
    public static List<FolderRoot> atStart(List<FolderRoot> have, List<FolderRoot> kit) {
        boolean shown = kit.stream().anyMatch(k -> have.stream().anyMatch(h -> samePlace(h, k)));
        return shown ? List.copyOf(have) : use(have, kit);
    }

    /** "Use the kit's folders": those {@code have} lacks first, in the kit's order, then {@code have}. */
    public static List<FolderRoot> use(List<FolderRoot> have, List<FolderRoot> kit) {
        List<FolderRoot> out = new ArrayList<>(missing(have, kit));
        out.addAll(have);
        return List.copyOf(out);
    }

    /** The kit's folders {@code have} lacks: none of its folders shows that folder or has that name. */
    public static List<FolderRoot> missing(List<FolderRoot> have, List<FolderRoot> kit) {
        List<FolderRoot> missing = new ArrayList<>();
        for (FolderRoot k : kit) {
            if (have.stream().noneMatch(h -> h.name().equals(k.name()) || samePlace(h, k))) {
                missing.add(k);
            }
        }
        return List.copyOf(missing);
    }

    /** Whether two folders name one folder on disk. */
    static boolean samePlace(FolderRoot a, FolderRoot b) {
        if (a.path().isBlank() || b.path().isBlank()) {
            return false;
        }
        return real(a.path()).toString().equalsIgnoreCase(real(b.path()).toString());
    }

    /** {@code path} with its links resolved when it exists, absolute and normalised either way. */
    private static Path real(String path) {
        Path p = Path.of(HomeDir.expand(path)).toAbsolutePath().normalize();
        try {
            return p.toRealPath();
        } catch (IOException | RuntimeException e) {
            return p;
        }
    }
}
