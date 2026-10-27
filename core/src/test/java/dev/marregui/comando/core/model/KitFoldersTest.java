package dev.marregui.comando.core.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The kit's folders that {@code comando-kit.json} declares, merged into the Files tab's at start and by "Use the kit's folders". */
class KitFoldersTest {

    private static FolderRoot folder(String name, Path path) {
        return new FolderRoot(name, path.toString(), "", List.of());
    }

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
