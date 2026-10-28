package dev.marregui.comando.core.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// [KitFolders] recognizes configured kit folders by name or resolved location and merges only missing folders.
///
/// - startup preserves an existing kit-folder selection
/// - symbolic links resolve to their target folders
/// - duplicate folder names exclude the kit entry
class KitFoldersTest {

    private static FolderRoot folder(String name, Path path) {
        return new FolderRoot(name, path.toString(), "", List.of());
    }

    /// Startup prepends kit folders only when none is already shown, retaining user folders and omitting kit names already taken.
    @Test
    void atStartTheKitsFoldersGoFirstWhenTheTabShowsNoneOfThemAndTheUsersStay(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("kit"));
        FolderRoot memory = folder("Memory", Files.createDirectories(root.resolve("BRAIN/MEMORY")));
        FolderRoot skills = folder("Skills", Files.createDirectories(root.resolve("skills")));
        FolderRoot repo = folder("Plans", Files.createDirectories(root.resolve("plans")));
        FolderRoot notes = folder("Notes", Files.createDirectories(tmp.resolve("notes")));

        assertThat(KitFolders.atStart(List.of(repo, notes), List.of(memory, skills))).containsExactly(memory, skills, repo, notes);
        assertThat(KitFolders.atStart(List.of(), List.of(memory, skills))).containsExactly(memory, skills);
        assertThat(KitFolders.atStart(List.of(skills, repo), List.of(memory, skills)))
                .as("one shown already: the user's list stands, the other was taken out").containsExactly(skills, repo);
        assertThat(KitFolders.atStart(List.of(repo), List.of())).as("no kit").containsExactly(repo);
    }

    /// A symbolic link to a kit directory counts as present, so `missing` and `use` add only the other kit folder.
    @Test
    void aFolderShownThroughALinkIsTheKitsFolder(@TempDir Path tmp) throws Exception {
        Path brainMemory = Files.createDirectories(tmp.resolve("claude/BRAIN/MEMORY"));
        Path link = Files.createSymbolicLink(tmp.resolve("claude/MEMORY"), brainMemory);
        FolderRoot mine = folder("My memory", link);
        FolderRoot kit = folder("Memory", brainMemory);
        FolderRoot kb = folder("KB", Files.createDirectories(tmp.resolve("claude/BRAIN/KB")));

        assertThat(KitFolders.atStart(List.of(mine), List.of(kit, kb))).as("the link counts: nothing added").containsExactly(mine);
        assertThat(KitFolders.missing(List.of(mine), List.of(kit, kb))).containsExactly(kb);
        assertThat(KitFolders.use(List.of(mine), List.of(kit, kb))).as("the action adds what is missing, first").containsExactly(kb, mine);
    }

    /// A user folder with the kit folder's name blocks that kit entry, and path comparison ignores case after normalization.
    @Test
    void aNameTakenByAnotherFolderLeavesTheKitsFolderOut(@TempDir Path tmp) throws Exception {
        FolderRoot elsewhere = folder("Memory", Files.createDirectories(tmp.resolve("elsewhere")));
        FolderRoot kit = folder("Memory", Files.createDirectories(tmp.resolve("kit/BRAIN/MEMORY")));
        FolderRoot kb = folder("KB", Files.createDirectories(tmp.resolve("kit/BRAIN/KB")));

        assertThat(KitFolders.atStart(List.of(elsewhere), List.of(kit, kb))).as("names are unique in folders.json").containsExactly(kb, elsewhere);
        assertThat(KitFolders.missing(List.of(folder("x", Path.of("/u/KIT/brain/memory"))), List.of(folder("Memory", Path.of("/u/kit/BRAIN/MEMORY")))))
                .as("case ignored, as the volume compares names").isEmpty();
    }
}
