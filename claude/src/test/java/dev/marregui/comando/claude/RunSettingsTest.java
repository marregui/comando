package dev.marregui.comando.claude;

import com.sun.management.ThreadMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/// [RunSettings] reads what a message ran at from the conversation's transcript, `<projects>/<project>/<id>.jsonl`.
/// - A message's assistant line gives its model and its `perTurnEffort`, else its `effort`.
/// - A message whose line is not in the transcript yet, or a conversation with no transcript, gives nothing.
/// - Only the file's end is read: a message near the end of a file larger than the tail is still found.
/// - The tail is scanned in place: a read copies out only the line it parses, whatever text surrounds it.
class RunSettingsTest {

    @TempDir
    Path projects;

    private final ObjectMapper json = new ObjectMapper();

    /// Two lines of one message and a later message: each is found by its id among other records, the per-turn
    /// effort (`high`, after a skill raised it) winning over the session's (`medium`); a line with no per-turn effort
    /// gives the session's.
    @Test
    void aMessagesLineGivesItsModelAndItsPerTurnEffort() throws IOException {
        final Path file = transcript("conv-1", """
                {"type":"user","message":{"role":"user","content":"go"}}
                {"type":"assistant","effort":"medium","perTurnEffort":"medium","message":{"id":"m1","model":"claude-opus-5-5","content":[]}}
                {"type":"attachment","attachment":{"model":"claude-haiku-5-5"}}
                {"type":"assistant","effort":"medium","perTurnEffort":"high","message":{"id":"m2","model":"claude-opus-5-5","content":[]}}
                {"type":"assistant","effort":"low","message":{"id":"m3","model":"claude-sonnet-5-5","content":[]}}
                """);
        final RunSettings settings = new RunSettings(projects, json);

        assertThat(settings.of("conv-1", "m1")).contains(new RunSettings.Ran("claude-opus-5-5", "medium"));
        assertThat(settings.of("conv-1", "m2")).contains(new RunSettings.Ran("claude-opus-5-5", "high"));
        assertThat(settings.of("conv-1", "m3")).as("no per-turn effort: the session's").contains(new RunSettings.Ran("claude-sonnet-5-5", "low"));
        assertThat(file).exists();
    }

    /// A message not yet written gives nothing, and is found once its line is appended; an unknown conversation and an
    /// empty id give nothing.
    @Test
    void aMessageNotYetWrittenGivesNothingUntilItsLineIsThere() throws IOException {
        final Path file = transcript("conv-2", """
                {"type":"assistant","perTurnEffort":"low","message":{"id":"m1","model":"claude-opus-5-5","content":[]}}
                """);
        final RunSettings settings = new RunSettings(projects, json);
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
        final StringBuilder big = new StringBuilder();
        final String filler = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"" + "x".repeat(1_000) + "\"}}\n";
        while (big.length() < RunSettings.TAIL * 2) {
            big.append(filler);
        }
        big.append("{\"type\":\"assistant\",\"perTurnEffort\":\"high\",\"message\":{\"id\":\"last\",\"model\":\"claude-opus-5-5\",\"content\":[]}}\n");
        transcript("conv-3", big.toString());

        assertThat(new RunSettings(projects, json).of("conv-3", "last")).contains(new RunSettings.Ran("claude-opus-5-5", "high"));
    }

    /// A line among others holding non-ASCII text (which made the old read decode the whole tail to UTF-16) is found,
    /// and its own non-ASCII text parses.
    @Test
    void aLineAmongNonAsciiTextIsFoundAndParsed() throws IOException {
        transcript("conv-4", """
                {"type":"user","message":{"role":"user","content":"señal — ünïcode ✓"}}
                {"type":"assistant","perTurnEffort":"max","message":{"id":"m1","model":"claude-opus-5-5","content":[{"type":"text","text":"listo ✓"}]}}
                {"type":"user","message":{"role":"user","content":"después — m1 ✓"}}
                """);

        assertThat(new RunSettings(projects, json).of("conv-4", "m1"))
                .contains(new RunSettings.Ran("claude-opus-5-5", "max"));
    }

    /// The retries while a message's line is not yet written allocate next to nothing: 20 misses over a full tail of
    /// non-ASCII text stay under one tail's size in all (measured 2026-11-02: 19.6 KB). The read this replaced decoded
    /// the tail to a UTF-16 string and copied each line: 16.2 MB for these 20.
    @Test
    void readsThatFindNothingAllocateNextToNothing() throws IOException {
        final StringBuilder big = new StringBuilder();
        final String filler = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\""
                + "ñ".repeat(500) + "\"}}\n";
        while (big.length() < RunSettings.TAIL * 2) {
            big.append(filler);
        }
        transcript("conv-5", big.toString());
        final RunSettings settings = new RunSettings(projects, json);
        settings.of("conv-5", "warm-up");
        final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 20; i++) {
            assertThat(settings.of("conv-5", "not-yet")).isEmpty();
        }
        assertThat(threads.getCurrentThreadAllocatedBytes() - before).isLessThan(RunSettings.TAIL);
    }

    private Path transcript(final String conversation, final String lines) throws IOException {
        final Path dir = Files.createDirectories(projects.resolve("-Users-me-work"));
        return Files.writeString(dir.resolve(conversation + ".jsonl"), lines);
    }
}
