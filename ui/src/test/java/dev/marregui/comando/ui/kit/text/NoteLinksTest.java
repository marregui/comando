package dev.marregui.comando.ui.kit.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/// [NoteLinks#resolve] finds the file a `[[note]]` link names in the repository around a folder.
/// - A memory link resolves by the name in a file's front matter.
/// - A file name, in any case or as a path from the root, wins and is found anywhere in the repository.
/// - Of two matches the one nearest the folder wins.
/// - Dashes in the name find underscores in the file name.
/// - A missing note, a note in a hidden folder or no folder resolves to nothing; outside a repository the folder is the
///   root.
class NoteLinksTest {

    @TempDir
    Path repo;

    /** A brain laid out as the CLI's memory: snake-case files, kebab-case names in their front matter. */
    private Path memory() throws IOException {
        Files.createDirectories(repo.resolve(".git"));
        Path memory = Files.createDirectories(repo.resolve("MEMORY"));
        Files.writeString(memory.resolve("feedback_verification_discipline.md"),
                "---\nname: feedback-verification-discipline\ndescription: x\nmetadata:\n  type: feedback\n---\n\nBody\n");
        Files.writeString(memory.resolve("MEMORY.md"), "# Index\n");
        Files.writeString(Files.createDirectories(repo.resolve("KB/claude")).resolve("CLAUDE_INTERNALS.md"), "# Internals\n");
        return memory;
    }

    /// A kebab-case name resolves to the snake-case file whose front matter `name` is it.
    @Test
    void aMemoryLinkResolvesByTheNameInItsFrontMatter() throws IOException {
        Path memory = memory();
        assertThat(NoteLinks.resolve(memory, "feedback-verification-discipline"))
                .contains(memory.resolve("feedback_verification_discipline.md"));
    }

    /// A bare file name found in another folder, the same in lower case, a name with `.md`, and a path from the
    /// repository root all resolve.
    @Test
    void aFileNameWinsAndIsFoundAnywhereInTheRepository() throws IOException {
        Path memory = memory();
        assertThat(NoteLinks.resolve(memory, "CLAUDE_INTERNALS")).contains(repo.resolve("KB/claude/CLAUDE_INTERNALS.md"));
        assertThat(NoteLinks.resolve(memory, "claude_internals")).as("in any letter case").contains(repo.resolve("KB/claude/CLAUDE_INTERNALS.md"));
        assertThat(NoteLinks.resolve(memory, "MEMORY.md")).contains(memory.resolve("MEMORY.md"));
        assertThat(NoteLinks.resolve(memory, "KB/claude/CLAUDE_INTERNALS")).as("a path from the root").contains(repo.resolve("KB/claude/CLAUDE_INTERNALS.md"));
    }

    /// With `MEMORY.md` in two folders, each folder resolves to its own.
    @Test
    void ofTwoTheNearestWins() throws IOException {
        Path memory = memory();
        Files.writeString(repo.resolve("KB/claude/MEMORY.md"), "# Another\n");
        assertThat(NoteLinks.resolve(memory, "MEMORY")).contains(memory.resolve("MEMORY.md"));
        assertThat(NoteLinks.resolve(repo.resolve("KB/claude"), "MEMORY")).contains(repo.resolve("KB/claude/MEMORY.md"));
    }

    /// A file without front matter is still found when the link's dashes are its name's underscores.
    @Test
    void aNameWithDashesFindsAFileWithUnderscores() throws IOException {
        Path memory = memory();
        Files.writeString(memory.resolve("project_current_lane.md"), "No front matter\n");
        assertThat(NoteLinks.resolve(memory, "project-current-lane")).contains(memory.resolve("project_current_lane.md"));
    }

    /// An unknown name, a name only matched under `.git`, and a null folder resolve to empty.
    @Test
    void nothingForANoteThatIsNotThereOrHidden() throws IOException {
        Path memory = memory();
        Files.writeString(repo.resolve(".git/secret.md"), "---\nname: secret\n---\n");
        assertThat(NoteLinks.resolve(memory, "no-such-memory")).isEmpty();
        assertThat(NoteLinks.resolve(memory, "secret")).as("hidden folders are not searched").isEmpty();
        assertThat(NoteLinks.resolve(null, "x")).isEmpty();
    }

    /// Without a `.git` above it, [NoteLinks#rootOf] is the folder itself and links resolve under it.
    @Test
    void outsideARepositoryTheFolderIsTheRoot() throws IOException {
        Path loose = Files.createDirectories(repo.resolve("loose/inner"));
        Files.writeString(loose.resolve("a.md"), "a");
        assertThat(NoteLinks.rootOf(loose)).isEqualTo(loose);
        assertThat(NoteLinks.resolve(loose, "a")).contains(loose.resolve("a.md"));
    }
}
