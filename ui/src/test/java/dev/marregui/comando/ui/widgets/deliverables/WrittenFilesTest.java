package dev.marregui.comando.ui.widgets.deliverables;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;

import static org.assertj.core.api.Assertions.assertThat;

/// [WrittenFiles]: the files a session's transcript wrote (record 143).
/// - in the order the session first wrote each, each once, across reads too
/// - Write, Edit, MultiEdit and NotebookEdit only; a failed call wrote nothing, a running one counts once it ends
/// - a path that is not absolute names no file
/// - a row's folder is its repository's name and the folder inside it, else the folder with the home as `~`
/// - a transcript cleared and filled again is read from its start
class WrittenFilesTest {

    private final Transcript transcript = new Transcript();
    private final WrittenFiles written = new WrittenFiles(transcript);

    @TempDir
    Path dir;

    private int ids;

    /// Files come in the order the session first wrote each; a file written again stays where it first came.
    @Test
    void filesComeInFirstWriteOrderEachOnce() {
        call("Write", "file_path", "/w/a.md", Block.ToolStatus.DONE);
        call("Edit", "file_path", "/w/b.md", Block.ToolStatus.DONE);
        call("Edit", "file_path", "/w/a.md", Block.ToolStatus.DONE);
        call("MultiEdit", "file_path", "/w/./c.md", Block.ToolStatus.DONE);
        call("Write", "file_path", "/w/b.md", Block.ToolStatus.DONE);

        assertThat(files()).containsExactly(Path.of("/w/a.md"), Path.of("/w/b.md"), Path.of("/w/c.md"));
    }

    /// A file written in an earlier read and written again later is not listed twice; only the new blocks are read.
    @Test
    void aFileWrittenAgainAfterAReadStaysOnce() {
        call("Write", "file_path", "/w/a.md", Block.ToolStatus.DONE);
        assertThat(files()).containsExactly(Path.of("/w/a.md"));

        call("Edit", "file_path", "/w/b.md", Block.ToolStatus.DONE);
        call("Edit", "file_path", "/w/a.md", Block.ToolStatus.DONE);

        assertThat(files()).containsExactly(Path.of("/w/a.md"), Path.of("/w/b.md"));
    }

    /// The four tools that write a file count, the notebook's by its own field; a tool that only reads or runs does
    /// not.
    @Test
    void onlyTheFourWritingToolsCount() {
        call("Read", "file_path", "/w/read.md", Block.ToolStatus.DONE);
        call("Write", "file_path", "/w/write.md", Block.ToolStatus.DONE);
        call("Grep", "path", "/w/grep", Block.ToolStatus.DONE);
        call("Edit", "file_path", "/w/edit.md", Block.ToolStatus.DONE);
        call("MultiEdit", "file_path", "/w/multi.md", Block.ToolStatus.DONE);
        call("NotebookEdit", "notebook_path", "/w/book.ipynb", Block.ToolStatus.DONE);
        call("NotebookEdit", "file_path", "/w/not-the-field.ipynb", Block.ToolStatus.DONE);

        assertThat(files()).containsExactly(Path.of("/w/write.md"), Path.of("/w/edit.md"), Path.of("/w/multi.md"),
                Path.of("/w/book.ipynb"));
    }

    /// A call that failed wrote nothing; a call still running is listed once its result says it wrote, in its place.
    @Test
    void aFailedCallWroteNothingAndARunningOneCountsOnceDone() {
        final int running = call("Write", "file_path", "/w/first.md", Block.ToolStatus.RUNNING);
        call("Edit", "file_path", "/w/failed.md", Block.ToolStatus.ERROR);
        call("Edit", "file_path", "/w/second.md", Block.ToolStatus.DONE);
        assertThat(files()).containsExactly(Path.of("/w/second.md"));

        transcript.replace(running, toolCall("Write", "file_path", "/w/first.md", Block.ToolStatus.DONE));

        assertThat(files()).containsExactly(Path.of("/w/first.md"), Path.of("/w/second.md"));
    }

    /// A call still running that fails is never listed.
    @Test
    void aRunningCallThatFailsIsNeverListed() {
        final int running = call("Write", "file_path", "/w/a.md", Block.ToolStatus.RUNNING);
        assertThat(files()).isEmpty();

        transcript.replace(running, toolCall("Write", "file_path", "/w/a.md", Block.ToolStatus.ERROR));

        assertThat(files()).isEmpty();
    }

    /// A relative, blank or missing path names no file.
    @Test
    void aPathThatIsNotAbsoluteNamesNoFile() {
        call("Write", "file_path", "notes/a.md", Block.ToolStatus.DONE);
        call("Write", "file_path", "", Block.ToolStatus.DONE);
        call("Edit", "old_string", "/w/b.md", Block.ToolStatus.DONE);
        transcript.append(new Block.ToolCall("toolu_x", "Write", "not json", "", Block.ToolStatus.DONE, ""));

        assertThat(files()).isEmpty();
    }

    /// A row's folder is relative to its repository, named by the repository's top; a file at the top shows the name
    /// alone; outside a repository the folder shows whole, the home folder as `~`.
    @Test
    void aFoldersLabelIsRelativeToItsRepository() throws IOException {
        final Path repo = Files.createDirectories(dir.resolve("comando"));
        Files.createDirectories(repo.resolve(".git"));
        final Path deep = Files.createDirectories(repo.resolve("ui/src"));
        final Path loose = Files.createDirectories(dir.resolve("loose"));
        call("Write", "file_path", deep.resolve("A.java").toString(), Block.ToolStatus.DONE);
        call("Write", "file_path", repo.resolve("README.md").toString(), Block.ToolStatus.DONE);
        call("Write", "file_path", loose.resolve("notes.md").toString(), Block.ToolStatus.DONE);

        assertThat(written.read()).extracting(WrittenFiles.Written::folder)
                .containsExactly("comando/ui/src", "comando", HomeDir.shorten(loose.toString()));
    }

    /// A transcript cleared and filled again lists only what the new blocks wrote, even when it holds as many blocks.
    @Test
    void aTranscriptClearedAndFilledAgainIsReadFromItsStart() {
        call("Write", "file_path", "/w/old.md", Block.ToolStatus.DONE);
        call("Write", "file_path", "/w/old2.md", Block.ToolStatus.DONE);
        assertThat(files()).hasSize(2);

        transcript.clear();
        call("Write", "file_path", "/w/new.md", Block.ToolStatus.DONE);
        call("Write", "file_path", "/w/new2.md", Block.ToolStatus.DONE);
        call("Write", "file_path", "/w/new3.md", Block.ToolStatus.DONE);

        assertThat(files()).containsExactly(Path.of("/w/new.md"), Path.of("/w/new2.md"), Path.of("/w/new3.md"));
    }

    /// A transcript cleared and not filled again lists nothing.
    @Test
    void aClearedTranscriptListsNothing() {
        call("Write", "file_path", "/w/old.md", Block.ToolStatus.DONE);
        assertThat(files()).hasSize(1);

        transcript.clear();

        assertThat(files()).isEmpty();
    }

    private int call(final String tool, final String field, final String path, final Block.ToolStatus status) {
        return transcript.append(toolCall(tool, field, path, status));
    }

    private Block.ToolCall toolCall(
            final String tool,
            final String field,
            final String path,
            final Block.ToolStatus status
    ) {
        return new Block.ToolCall("toolu_" + ids++, tool, "{\"" + field + "\":\"" + path + "\"}", "", status, "");
    }

    private List<Path> files() {
        return written.read().stream().map(WrittenFiles.Written::file).toList();
    }
}
