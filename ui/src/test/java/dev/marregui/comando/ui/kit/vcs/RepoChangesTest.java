package dev.marregui.comando.ui.kit.vcs;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.model.RepoStatus;

import static org.assertj.core.api.Assertions.assertThat;

/// [RepoChanges] holds the repositories' changed files for the file tree to mark.
/// - A changed file is found by its normalised path, and every folder holding one inside the repository.
/// - Listeners hear only of a read that changed something, until stopped.
class RepoChangesTest {

    private static RepoStatus repo(final RepoStatus.FileChange... changes) {
        return new RepoStatus("/r", "main", List.of(changes), false, "");
    }

    /// [RepoChanges#of] gives a file's kind for its path with `..` or nothing for an unchanged file;
    /// [RepoChanges#holdsChanges] is true for each ancestor folder inside the repository and false for a clean folder
    /// or one above the repository.
    @Test
    void filesAndTheFoldersHoldingThemAreFoundByPath() {
        final RepoChanges c = new RepoChanges();
        c.set(List.of(repo(new RepoStatus.FileChange("src/main/A.java", "", RepoStatus.Kind.MODIFIED),
                new RepoStatus.FileChange("notes/n.md", "", RepoStatus.Kind.UNTRACKED))));
        assertThat(c.of(Path.of("/r/src/main/A.java"))).contains(RepoStatus.Kind.MODIFIED);
        assertThat(c.of(Path.of("/r/src/main/../main/A.java"))).as("normalized").contains(RepoStatus.Kind.MODIFIED);
        assertThat(c.of(Path.of("/r/src/main/B.java"))).isEmpty();
        assertThat(c.holdsChanges(Path.of("/r/src"))).isTrue();
        assertThat(c.holdsChanges(Path.of("/r/src/main"))).isTrue();
        assertThat(c.holdsChanges(Path.of("/r/notes"))).isTrue();
        assertThat(c.holdsChanges(Path.of("/r/docs"))).isFalse();
        assertThat(c.holdsChanges(Path.of("/"))).as("above the repository: not its business").isFalse();
    }

    /// Setting an equal status twice is heard once; after stop an empty set is not heard and leaves no repositories.
    @Test
    void listenersHearOnlyOfAReadThatChangedSomething() {
        final RepoChanges c = new RepoChanges();
        final AtomicInteger heard = new AtomicInteger();
        final Runnable stop = c.listen(heard::incrementAndGet);
        final RepoStatus r = repo(new RepoStatus.FileChange("a", "", RepoStatus.Kind.ADDED));
        c.set(List.of(r));
        c.set(List.of(repo(new RepoStatus.FileChange("a", "", RepoStatus.Kind.ADDED))));
        assertThat(heard).hasValue(1);
        stop.run();
        c.set(List.of());
        assertThat(heard).hasValue(1);
        assertThat(c.repos()).isEmpty();
    }
}
