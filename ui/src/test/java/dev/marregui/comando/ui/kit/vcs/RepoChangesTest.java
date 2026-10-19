package dev.marregui.comando.ui.kit.vcs;

import dev.marregui.comando.core.model.RepoStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RepoChangesTest {

    private static RepoStatus repo(RepoStatus.FileChange... changes) {
        return new RepoStatus("/r", "main", List.of(changes), false, "");
    }

    @Test
    void filesAndTheFoldersHoldingThemAreFoundByPath() {
        RepoChanges c = new RepoChanges();
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

    @Test
    void listenersHearOnlyOfAReadThatChangedSomething() {
        RepoChanges c = new RepoChanges();
        AtomicInteger heard = new AtomicInteger();
        Runnable stop = c.listen(heard::incrementAndGet);
        RepoStatus r = repo(new RepoStatus.FileChange("a", "", RepoStatus.Kind.ADDED));
        c.set(List.of(r));
        c.set(List.of(repo(new RepoStatus.FileChange("a", "", RepoStatus.Kind.ADDED))));
        assertThat(heard).hasValue(1);
        stop.run();
        c.set(List.of());
        assertThat(heard).hasValue(1);
        assertThat(c.repos()).isEmpty();
    }
}
