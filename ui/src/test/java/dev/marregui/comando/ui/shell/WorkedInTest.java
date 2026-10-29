package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.ui.kit.desktop.FileSearch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/// [WorkedIn] gives the folders a session worked in, where a path it writes relative to one is found (record 111).
/// - The repositories of the files the session's calls named, the latest first; a folder outside one as it is.
class WorkedInTest {

    @TempDir
    Path tmp;

    /// From Read and Edit calls with absolute paths, [WorkedIn#roots] gives the repository top of the latest first and
    /// the loose folder after it, ignores a relative Grep path and a Bash call, honours the limit, and lets
    /// [dev.marregui.comando.ui.kit.desktop.FileSearch#bySuffix] find a file under them.
    @Test
    void theRepositoriesOfTheFilesASessionsCallsNamedTheLatestFirst() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("comando"));
        Files.createDirectories(repo.resolve(".git"));
        Path doc = Files.createDirectories(repo.resolve("docs")).resolve("workbench.md");
        Files.writeString(doc, "# Workbench\n");
        Path loose = Files.createDirectories(tmp.resolve("loose"));
        Transcript t = new Transcript();
        t.append(new Block.ToolCall("1", "Read", "{\"file_path\":\"" + loose.resolve("a.txt") + "\"}", "", Block.ToolStatus.DONE, ""));
        t.append(new Block.ToolCall("2", "Edit", "{\"file_path\":\"" + repo.resolve("ui/src/X.java") + "\"}", "", Block.ToolStatus.DONE, ""));
        t.append(new Block.ToolCall("3", "Grep", "{\"pattern\":\"x\",\"path\":\"relative/not/taken\"}", "", Block.ToolStatus.DONE, ""));
        t.append(new Block.ToolCall("4", "Bash", "{\"command\":\"ls\"}", "", Block.ToolStatus.DONE, ""));

        assertThat(WorkedIn.roots(t, WorkedIn.MAX_ROOTS)).as("the repository's top, the latest first; a folder outside one as it is")
                .containsExactly(repo, loose);
        assertThat(WorkedIn.roots(t, 1)).containsExactly(repo);
        assertThat(FileSearch.bySuffix("docs/workbench.md", WorkedIn.roots(t, WorkedIn.MAX_ROOTS))).contains(doc);
    }
}
