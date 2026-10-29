package dev.marregui.comando.claude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// [SessionCatalogue] lists stored conversations with their latest resumable details.
///
/// - orders transcript files by modification time
/// - extracts the latest title, branch, and prompt
/// - treats a missing projects directory as empty
class SessionCatalogueTest {

    /// Transcript entries are newest first and retain the latest branch, custom title, and human prompt.
    /// Two dated JSONL files and an unrelated text file distinguish ordering, extraction, and filtering.
    @Test
    void listsConversationsNewestFirstWithTheirDirectoryTitleAndLastPrompt(@TempDir Path projects) throws Exception {
        Path project = Files.createDirectories(projects.resolve("-Users-x-edge"));
        Path older = project.resolve("11111111-aaaa.jsonl");
        Files.writeString(older, """
                {"type":"user","cwd":"/Users/x/edge","gitBranch":"main","message":{"role":"user","content":"first"},"origin":{"kind":"human"}}
                {"type":"ai-title","aiTitle":"Old work"}
                """);
        Path newer = project.resolve("22222222-bbbb.jsonl");
        Files.writeString(newer, """
                {"type":"user","cwd":"/Users/x/edge","gitBranch":"main","message":{"role":"user","content":"hello"},"origin":{"kind":"human"}}
                {"type":"assistant","message":{"content":[{"type":"text","text":"hi"}]}}
                {"type":"user","cwd":"/Users/x/edge","gitBranch":"fix/1","message":{"role":"user","content":"now the fix"},"origin":{"kind":"human"}}
                {"type":"custom-title","customTitle":"ENG-1 fix"}
                """);
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_000L));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000L));
        Files.writeString(project.resolve("notes.txt"), "not a transcript");

        List<SessionCatalogue.Entry> entries = new SessionCatalogue(projects).scan(10);

        assertThat(entries).extracting(SessionCatalogue.Entry::sessionId).containsExactly("22222222-bbbb", "11111111-aaaa");
        SessionCatalogue.Entry e = entries.getFirst();
        assertThat(e.cwd()).isEqualTo("/Users/x/edge");
        assertThat(e.branch()).isEqualTo("fix/1");
        assertThat(e.title()).isEqualTo("ENG-1 fix");
        assertThat(e.lastPrompt()).isEqualTo("now the fix");
        assertThat(entries.get(1).title()).isEqualTo("Old work");
    }

    /// A missing projects directory gives no entries, not a failure.
    @Test
    void missingDirectoryIsEmpty(@TempDir Path dir) {
        assertThat(new SessionCatalogue(dir.resolve("nope")).scan(10)).isEmpty();
    }
}
