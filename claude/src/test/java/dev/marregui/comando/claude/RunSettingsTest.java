package dev.marregui.comando.claude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// [RunSettings] reads what a message ran at from the conversation's transcript, `<projects>/<project>/<id>.jsonl`.
/// - A message's assistant line gives its model and its `perTurnEffort`, else its `effort`.
/// - A message whose line is not in the transcript yet, or a conversation with no transcript, gives nothing.
/// - Only the file's end is read: a message near the end of a file larger than the tail is still found.
class RunSettingsTest {

    @TempDir
    Path projects;

    private final ObjectMapper json = new ObjectMapper();

    /// Two lines of one message and a later message: each is found by its id among other records, the per-turn
    /// effort (`high`, after a skill raised it) winning over the session's (`medium`); a line with no per-turn effort
    /// gives the session's.
    @Test
    void aMessagesLineGivesItsModelAndItsPerTurnEffort() throws IOException {
        Path file = transcript("conv-1", """
                {"type":"user","message":{"role":"user","content":"go"}}
                {"type":"assistant","effort":"medium","perTurnEffort":"medium","message":{"id":"m1","model":"claude-opus-5-5","content":[]}}
                {"type":"attachment","attachment":{"model":"claude-haiku-5-5"}}
                {"type":"assistant","effort":"medium","perTurnEffort":"high","message":{"id":"m2","model":"claude-opus-5-5","content":[]}}
                {"type":"assistant","effort":"low","message":{"id":"m3","model":"claude-sonnet-5-5","content":[]}}
                """);
        RunSettings settings = new RunSettings(projects, json);

        assertThat(settings.of("conv-1", "m1")).contains(new RunSettings.Ran("claude-opus-5-5", "medium"));
        assertThat(settings.of("conv-1", "m2")).contains(new RunSettings.Ran("claude-opus-5-5", "high"));
        assertThat(settings.of("conv-1", "m3")).as("no per-turn effort: the session's").contains(new RunSettings.Ran("claude-sonnet-5-5", "low"));
        assertThat(file).exists();
    }

    /// A message not yet written gives nothing, and is found once its line is appended; an unknown conversation and an
    /// empty id give nothing.
    @Test
    void aMessageNotYetWrittenGivesNothingUntilItsLineIsThere() throws IOException {
        Path file = transcript("conv-2", """
                {"type":"assistant","perTurnEffort":"low","message":{"id":"m1","model":"claude-opus-5-5","content":[]}}
                """);
        RunSettings settings = new RunSettings(projects, json);
        assertThat(settings.of("conv-2", "m2")).isEmpty();

        Files.writeString(file, Files.readString(file)
                + "{\"type\":\"assistant\",\"perTurnEffort\":\"xhigh\",\"message\":{\"id\":\"m2\",\"model\":\"claude-opus-5-5\",\"content\":[]}}\n");
        assertThat(settings.of("conv-2", "m2")).contains(new RunSettings.Ran("claude-opus-5-5", "xhigh"));
        assertThat(settings.of("conv-none", "m1")).isEqualTo(Optional.empty());
        assertThat(settings.of("conv-2", "")).isEmpty();
    }

    /// In a file twice the tail's size, the last message is found from the tail alone, its line whole.
    @Test
    void theLatestMessageIsFoundInTheTailOfALargeFile() throws IOException {
        StringBuilder big = new StringBuilder();
        String filler = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"" + "x".repeat(1_000) + "\"}}\n";
        while (big.length() < RunSettings.TAIL * 2) {
            big.append(filler);
        }
        big.append("{\"type\":\"assistant\",\"perTurnEffort\":\"high\",\"message\":{\"id\":\"last\",\"model\":\"claude-opus-5-5\",\"content\":[]}}\n");
        transcript("conv-3", big.toString());

        assertThat(new RunSettings(projects, json).of("conv-3", "last")).contains(new RunSettings.Ran("claude-opus-5-5", "high"));
    }

    private Path transcript(String conversation, String lines) throws IOException {
        Path dir = Files.createDirectories(projects.resolve("-Users-me-work"));
        return Files.writeString(dir.resolve(conversation + ".jsonl"), lines);
    }
}
