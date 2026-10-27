package dev.marregui.comando.ui.kit.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NoteLinksTest {

    @TempDir
    Path repo;

    /** A brain laid out as Claude Code's memory: snake-case files, kebab-case names in their front matter. */
    private Path memory() throws IOException {
        Files.createDirectories(repo.resolve(".git"));
        Path memory = Files.createDirectories(repo.resolve("MEMORY"));
        Files.writeString(memory.resolve("feedback_verification_discipline.md"),
                "---\nname: feedback-verification-discipline\ndescription: x\nmetadata:\n  type: feedback\n---\n\nBody\n");
        Files.writeString(memory.resolve("MEMORY.md"), "# Index\n");
        Files.writeString(Files.createDirectories(repo.resolve("KB/claude")).resolve("CLAUDE_INTERNALS.md"), "# Internals\n");
        return memory;
    }

    @Test
    void aMemoryLinkResolvesByTheNameInItsFrontMatter() throws IOException {
        Path memory = memory();
        assertThat(NoteLinks.resolve(memory, "feedback-verification-discipline"))
                .contains(memory.resolve("feedback_verification_discipline.md"));
    }

    @Test
    void aFileNameWinsAndIsFoundAnywhereInTheRepository() throws IOException {
        Path memory = memory();
        assertThat(NoteLinks.resolve(memory, "CLAUDE_INTERNALS")).contains(repo.resolve("KB/claude/CLAUDE_INTERNALS.md"));
        assertThat(NoteLinks.resolve(memory, "claude_internals")).as("in any letter case").contains(repo.resolve("KB/claude/CLAUDE_INTERNALS.md"));
        assertThat(NoteLinks.resolve(memory, "MEMORY.md")).contains(memory.resolve("MEMORY.md"));
        assertThat(NoteLinks.resolve(memory, "KB/claude/CLAUDE_INTERNALS")).as("a path from the root").contains(repo.resolve("KB/claude/CLAUDE_INTERNALS.md"));
    }

    @Test
    void ofTwoTheNearestWins() throws IOException {
        Path memory = memory();
        Files.writeString(repo.resolve("KB/claude/MEMORY.md"), "# Another\n");
        assertThat(NoteLinks.resolve(memory, "MEMORY")).contains(memory.resolve("MEMORY.md"));
        assertThat(NoteLinks.resolve(repo.resolve("KB/claude"), "MEMORY")).contains(repo.resolve("KB/claude/MEMORY.md"));
    }

    @Test
    void aNameWithDashesFindsAFileWithUnderscores() throws IOException {
        Path memory = memory();
        Files.writeString(memory.resolve("project_current_lane.md"), "No front matter\n");
        assertThat(NoteLinks.resolve(memory, "project-current-lane")).contains(memory.resolve("project_current_lane.md"));
    }

    @Test
    void nothingForANoteThatIsNotThereOrHidden() throws IOException {
        Path memory = memory();
        Files.writeString(repo.resolve(".git/secret.md"), "---\nname: secret\n---\n");
        assertThat(NoteLinks.resolve(memory, "no-such-memory")).isEmpty();
        assertThat(NoteLinks.resolve(memory, "secret")).as("hidden folders are not searched").isEmpty();
        assertThat(NoteLinks.resolve(null, "x")).isEmpty();
    }

    @Test
    void outsideARepositoryTheFolderIsTheRoot() throws IOException {
        Path loose = Files.createDirectories(repo.resolve("loose/inner"));
        Files.writeString(loose.resolve("a.md"), "a");
        assertThat(NoteLinks.rootOf(loose)).isEqualTo(loose);
        assertThat(NoteLinks.resolve(loose, "a")).contains(loose.resolve("a.md"));
    }
}
