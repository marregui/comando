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

import static org.assertj.core.api.Assertions.assertThat;

class ToolsAdapterReposTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private final RecordingSink sink = new RecordingSink();

    private ToolsAdapter adapter() {
        return new ToolsAdapter(sink, new ConsoleRegistry(), () -> new ToolSettings(Optional.empty(), System.getProperty("java.home")));
    }

    private static FolderRoot folder(String name, Path path) {
        return new FolderRoot(name, path.toString(), "", List.of());
    }

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

    @Test
    void aFolderGitIgnoresAsAWholeShowsAllItHolds(@TempDir Path dir) throws Exception {
        Path root = GitTest.repo(dir.toRealPath());
        try (ToolsAdapter tools = adapter()) {
            tools.watchFolders(List.of(folder("dist", root.resolve("dist"))));
            Intent.FolderLoaded loaded = sink.await(Intent.FolderLoaded.class, WAIT);
            assertThat(loaded.tree().children()).extracting(ViewSpec.TreeNode::label).containsExactly("bundle.js");
        }
    }

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
}
