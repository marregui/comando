package dev.marregui.comando.tools;

import dev.marregui.comando.core.console.ConsoleRegistry;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ChangeAsk;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.RecordingSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// [ToolsAdapter] watches folders and git repositories and reads, sends, rolls back and removes changes in them.
/// - The Changes view's repositories are read at once, every kind of change listed.
/// - A watched folder in a repository leaves out what git ignores, and brings its repository's status for the colours.
/// - A folder that is a repository's top is read beside the repository that ignores it.
/// - A folder git ignores as a whole shows all it holds; a folder outside any repository is read whole.
/// - A change is read with its before and after texts.
/// - Rolling back takes every kind of change to `HEAD` and keeps what it replaced in the Trash; without a Trash nothing
///   is lost.
/// - Removing sends files to the Trash and takes a new file out of the index.
class ToolsAdapterReposTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private final RecordingSink sink = new RecordingSink();

    private ToolsAdapter adapter() {
        return new ToolsAdapter(sink, new ConsoleRegistry(), () -> new ToolSettings(Optional.empty(), System.getProperty("java.home")));
    }

    private static FolderRoot folder(String name, Path path) {
        return new FolderRoot(name, path.toString(), "", List.of());
    }

    /// Watching a folder inside a test repository with changes sends one ReposLoaded for the repository's top, listing
    /// the added, deleted, renamed, untracked-in-a-subfolder and modified files in path order.
    @Test
    void theChangesViewsRepositoriesAreReadAtOnce(@TempDir Path dir) throws Exception {
        Path root = GitTest.repo(dir.toRealPath());
        try (ToolsAdapter tools = adapter()) {
            tools.watchRepos(List.of(folder("src", root.resolve("src"))), true);
            Intent.ReposLoaded loaded = sink.await(Intent.ReposLoaded.class, r -> !r.repos().isEmpty(), WAIT);
            RepoStatus s = loaded.repos().getFirst();
            assertThat(s.root()).isEqualTo(root.toString());
            assertThat(s.changes()).extracting(RepoStatus.FileChange::path)
                    .containsExactly("added.txt", "gone.txt", "new name.txt", "notes/deep/n.md", "src/main/A.java");
        }
    }

    /// The tree of a watched repository folder holds its tracked and untracked entries but not the ignored `dist`, and
    /// the repository's status follows for the Files tab's colours.
    @Test
    void aWatchedFolderInARepositoryLeavesOutWhatGitIgnores(@TempDir Path dir) throws Exception {
        Path root = GitTest.repo(dir.toRealPath());
        FolderRoot f = folder("repo", root);
        try (ToolsAdapter tools = adapter()) {
            tools.watchFolders(List.of(f));
            Intent.FolderLoaded loaded = sink.await(Intent.FolderLoaded.class, WAIT);
            assertThat(loaded.tree().children()).extracting(ViewSpec.TreeNode::label)
                    .contains("src", "notes", "added.txt").doesNotContain("dist");
            // the open folder's repository comes with it, for the tab's colours
            Intent.ReposLoaded repos = sink.await(Intent.ReposLoaded.class, r -> !r.repos().isEmpty(), WAIT);
            assertThat(repos.repos()).extracting(RepoStatus::root).containsExactly(root.toString());
        }
    }

    /// A nested repository (the brain inside the kit, ignored by the kit) is read as a repository of its own, each
    /// listing only its own changes.
    @Test
    void aFolderThatIsARepositorysTopIsReadBesideTheRepositoryItIgnoresInside(@TempDir Path dir) throws Exception {
        Path kit = dir.toRealPath();
        GitTest.git(kit, "init", "-q");
        Files.writeString(kit.resolve(".gitignore"), "BRAIN/\n");
        Files.createDirectories(kit.resolve("hooks"));
        Files.writeString(kit.resolve("hooks/guard.py"), "a = 1\n");
        GitTest.git(kit, "add", "-A");
        GitTest.git(kit, "commit", "-q", "-m", "kit");
        Path brain = Files.createDirectories(kit.resolve("BRAIN"));
        GitTest.git(brain, "init", "-q");
        Files.createDirectories(brain.resolve("MEMORY"));
        Files.writeString(brain.resolve("MEMORY/m.md"), "m\n");
        GitTest.git(brain, "add", "-A");
        GitTest.git(brain, "commit", "-q", "-m", "brain");
        Files.writeString(kit.resolve("hooks/guard.py"), "a = 2\n");
        Files.writeString(brain.resolve("MEMORY/m.md"), "n\n");
        try (ToolsAdapter tools = adapter()) {
            tools.watchRepos(List.of(new FolderRoot("Kit", kit.toString(), "", List.of("BRAIN", ".git")),
                    folder("Memory", brain.resolve("MEMORY"))), true);
            Intent.ReposLoaded loaded = sink.await(Intent.ReposLoaded.class, r -> r.repos().size() == 2, WAIT);
            assertThat(loaded.repos()).extracting(RepoStatus::root).containsExactly(kit.toString(), brain.toString());
            assertThat(loaded.repos().get(0).changes()).extracting(RepoStatus.FileChange::path).containsExactly("hooks/guard.py");
            assertThat(loaded.repos().get(1).changes()).extracting(RepoStatus.FileChange::path).containsExactly("MEMORY/m.md");
        }
    }

    /// Reading a modified file's change answers the follow ask with the change and its `HEAD` and working-tree texts.
    @Test
    void aChangeIsReadAndSent(@TempDir Path dir) throws Exception {
        Path root = GitTest.repo(dir.toRealPath());
        try (ToolsAdapter tools = adapter()) {
            RepoStatus.FileChange c = new RepoStatus.FileChange("src/main/A.java", "", RepoStatus.Kind.MODIFIED);
            tools.readChange(root.toString(), c, ChangeAsk.FOLLOW);
            Intent.ChangeRead read = sink.await(Intent.ChangeRead.class, WAIT);
            assertThat(read.ask()).isEqualTo(ChangeAsk.FOLLOW);
            assertThat(read.texts().change()).isEqualTo(c);
            assertThat(read.texts().before()).contains("x = 1");
            assertThat(read.texts().after()).contains("x = 2");
        }
    }

    /// Watching the ignored `dist` folder itself shows its files: ignore rules apply only from a repository folder
    /// above.
    @Test
    void aFolderGitIgnoresAsAWholeShowsAllItHolds(@TempDir Path dir) throws Exception {
        Path root = GitTest.repo(dir.toRealPath());
        try (ToolsAdapter tools = adapter()) {
            tools.watchFolders(List.of(folder("dist", root.resolve("dist"))));
            Intent.FolderLoaded loaded = sink.await(Intent.FolderLoaded.class, WAIT);
            assertThat(loaded.tree().children()).extracting(ViewSpec.TreeNode::label).containsExactly("bundle.js");
        }
    }

    /// A folder in no repository shows every entry, `dist` included, with no git filtering.
    @Test
    void aFolderOutsideAnyRepositoryIsReadAsBefore(@TempDir Path dir) throws Exception {
        Path plain = Files.createDirectories(dir.toRealPath().resolve("plain"));
        Files.createDirectories(plain.resolve("dist"));
        Files.writeString(plain.resolve("dist/x.txt"), "x");
        try (ToolsAdapter tools = adapter()) {
            tools.watchFolders(List.of(folder("plain", plain)));
            Intent.FolderLoaded loaded = sink.await(Intent.FolderLoaded.class, WAIT);
            assertThat(loaded.tree().children()).extracting(ViewSpec.TreeNode::label).containsExactly("dist");
        }
    }

    /** A Trash that moves each file into {@code bin}, keeping what went there by its old path. */
    private static ToolsAdapter.Trash binIn(Path bin, List<Path> moved) {
        return file -> {
            Files.createDirectories(bin);
            Files.move(file, bin.resolve(moved.size() + "-" + file.getFileName()));
            moved.add(file);
            return true;
        };
    }

    private ToolsAdapter adapter(ToolsAdapter.Trash trash) {
        return new ToolsAdapter(sink, new ConsoleRegistry(), () -> new ToolSettings(Optional.empty(), System.getProperty("java.home")), trash);
    }

    /// Rolling back modified, deleted, renamed, added and new files restores `HEAD`, leaves nothing to commit, says how
    /// many files went back, and moves the replaced versions to the Trash.
    @Test
    void rollingBackTakesEveryKindOfChangeToHeadAndKeepsWhatItReplacedInTheTrash(@TempDir Path dir) throws Exception {
        Path root = GitTest.repo(Files.createDirectories(dir.resolve("repo")).toRealPath());
        List<Path> moved = new CopyOnWriteArrayList<>();
        List<RepoStatus.FileChange> all;
        try (Git git = new Git()) {
            all = git.status(root).status().changes();
        }
        try (ToolsAdapter tools = adapter(binIn(dir.resolve("bin"), moved))) {
            tools.rollBack(root.toString(), all);
            Intent.Notify n = sink.await(Intent.Notify.class, WAIT);
            assertThat(n.text()).startsWith("Rolled back 5 files to HEAD; the versions you had are in the Trash");
        }
        try (Git git = new Git()) {
            assertThat(git.status(root).status().changes()).as("nothing left to commit").isEmpty();
        }
        assertThat(Files.readString(root.resolve("src/main/A.java"))).contains("x = 1");
        assertThat(Files.readString(root.resolve("gone.txt"))).isEqualTo("bye\n");
        assertThat(Files.readString(root.resolve("old.txt"))).isEqualTo("same\n");
        assertThat(moved).extracting(p -> root.relativize(p).toString())
                .containsExactlyInAnyOrder("added.txt", "new name.txt", "notes/deep/n.md", "src/main/A.java");
    }

    /// On a desktop without a Trash the roll back is refused per file with a notice, and the modified file keeps its
    /// edit.
    @Test
    void withoutATrashAChangedFileStaysAsItIs(@TempDir Path dir) throws Exception {
        Path root = GitTest.repo(dir.toRealPath());
        try (ToolsAdapter tools = adapter(ToolsAdapter.Trash.NONE)) {
            tools.rollBack(root.toString(), List.of(new RepoStatus.FileChange("src/main/A.java", "", RepoStatus.Kind.MODIFIED)));
            Intent.Notify n = sink.await(Intent.Notify.class, WAIT);
            assertThat(n.text()).isEqualTo("Nothing rolled back; A.java: this desktop has no Trash; left as it is");
        }
        assertThat(Files.readString(root.resolve("src/main/A.java"))).contains("x = 2");
    }

    /// Removing two files moves both to the Trash, says so, and takes a newly added file out of the index.
    @Test
    void removingSendsFilesToTheTrashAndANewOneOutOfTheIndex(@TempDir Path dir) throws Exception {
        Path root = GitTest.repo(Files.createDirectories(dir.resolve("repo")).toRealPath());
        List<Path> moved = new CopyOnWriteArrayList<>();
        try (ToolsAdapter tools = adapter(binIn(dir.resolve("bin"), moved))) {
            tools.removeFiles(root.toString(), List.of("added.txt", "src/main/A.java"));
            Intent.Notify n = sink.await(Intent.Notify.class, WAIT);
            assertThat(n.text()).isEqualTo("Moved 2 files to the Trash");
        }
        try (Git git = new Git()) {
            assertThat(git.status(root).status().changes())
                    .as("the added file is gone from the index too; the tracked one is a removal to commit")
                    .contains(new RepoStatus.FileChange("src/main/A.java", "", RepoStatus.Kind.DELETED))
                    .extracting(RepoStatus.FileChange::path).doesNotContain("added.txt");
        }
    }
}
