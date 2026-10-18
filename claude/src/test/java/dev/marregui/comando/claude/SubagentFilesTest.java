package dev.marregui.comando.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubagentFilesTest {

    private final ObjectMapper json = new ObjectMapper();
    @TempDir
    Path tmp;
    private Path projects;
    private Path subagents;

    @BeforeEach
    void setUp() throws IOException {
        projects = tmp.resolve("projects");
        Files.createDirectories(projects.resolve("-other"));
        Path project = Files.createDirectories(projects.resolve("-w"));
        Files.writeString(project.resolve("conv-1.jsonl"), "");
        subagents = Files.createDirectories(project.resolve("conv-1/subagents"));
        for (String f : List.of("agent-a1.jsonl", "agent-a1.meta.json", "agent-a2.meta.json", "agent-a3.meta.json")) {
            try (InputStream in = getClass().getResourceAsStream("/subagents/" + f)) {
                Files.write(subagents.resolve(f), in.readAllBytes());
            }
        }
    }

    @Test
    void findsTheSubagentByItsAgentCallAmongTheMetaFiles() {
        SubagentFiles.Found found = SubagentFiles.find(projects, "conv-1", "toolu_A2", json).orElseThrow();
        assertThat(found.transcript()).isEqualTo(subagents.resolve("agent-a2.jsonl"));
        assertThat(found.meta()).isEqualTo(subagents.resolve("agent-a2.meta.json"));
        assertThat(found.description()).isEqualTo("Find the gauges");

        assertThat(SubagentFiles.find(projects, "conv-1", "toolu_A1", json).orElseThrow().transcript())
                .isEqualTo(subagents.resolve("agent-a1.jsonl"));
    }

    @Test
    void anUnknownCallOrConversationFindsNothing() {
        assertThat(SubagentFiles.find(projects, "conv-1", "toolu_nope", json)).isEmpty();
        assertThat(SubagentFiles.find(projects, "conv-2", "toolu_A1", json)).isEmpty();
        assertThat(SubagentFiles.find(tmp.resolve("missing"), "conv-1", "toolu_A1", json)).isEmpty();
    }

    @Test
    void aFileTooLargeToBeAMetaFileIsSkipped() throws IOException {
        Files.writeString(subagents.resolve("agent-big.meta.json"),
                "{\"toolUseId\":\"toolu_BIG\",\"pad\":\"" + "x".repeat((int) SubagentFiles.META_BYTES) + "\"}");
        assertThat(SubagentFiles.find(projects, "conv-1", "toolu_BIG", json)).isEmpty();
    }

    @Test
    void theMetaFileSaysWhenTheSubagentFinished() {
        assertThat(SubagentFiles.finished(subagents.resolve("agent-a3.meta.json"), json)).isTrue();
        assertThat(SubagentFiles.finished(subagents.resolve("agent-a1.meta.json"), json)).isFalse();
    }

    @Test
    void rendersThePromptTheTextsAndOneLinePerTool() throws IOException {
        String md = SubagentFiles.render(subagents.resolve("agent-a1.jsonl"), SubagentFiles.LIMIT_BYTES, json);

        assertThat(md).startsWith("> Count the widgets in the demo folder.\n> Report the total.");
        assertThat(md).contains("I will list the folder first.", "There are 3 widgets.");
        assertThat(md).contains("- `Bash` ls demo → widget-1 widget-2 widget-3\n");
        assertThat(md).contains("- `Read` demo/widget-1 → a widget\n");
        assertThat(md.lines().filter(l -> l.startsWith("- `"))).hasSize(2);
        assertThat(md).doesNotContain("private musing", "ignored", "Only the last");
    }

    @Test
    void aLongToolResultIsClipped() throws IOException {
        Path f = subagents.resolve("agent-long.jsonl");
        Files.writeString(f, assistantToolUse("tu9", "Grep", "{\"pattern\":\"needle\"}") + "\n"
                + toolResult("tu9", "y".repeat(500)) + "\n");

        String md = SubagentFiles.render(f, SubagentFiles.LIMIT_BYTES, json);

        assertThat(md).contains("- `Grep` needle → " + "y".repeat(SubagentFiles.RESULT_CHARS) + " …");
        assertThat(md).doesNotContain("y".repeat(SubagentFiles.RESULT_CHARS + 1));
    }

    @Test
    void aFileOverTheLimitShowsOnlyItsTailAndSaysSo() throws IOException {
        Path f = subagents.resolve("agent-huge.jsonl");
        try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
            w.write(userText("THE FIRST PROMPT") + "\n");
            String filler = assistantText("filler " + "z".repeat(1000));
            for (long written = 0; written < 3L * 1024 * 1024; written += filler.length() + 1) {
                w.write(filler + "\n");
            }
            w.write(assistantText("THE LAST WORD") + "\n");
        }
        assertThat(Files.size(f)).isGreaterThan(3L * 1024 * 1024);

        String md = SubagentFiles.render(f, SubagentFiles.LIMIT_BYTES, json);

        assertThat(md).startsWith("_Only the last 2.0 MB of 3.0 MB are shown._");
        assertThat(md).doesNotContain("THE FIRST PROMPT");
        assertThat(md).contains("THE LAST WORD");
        assertThat(md.length()).isLessThan((int) SubagentFiles.LIMIT_BYTES + 1000);
    }

    @Test
    void aFileNotWrittenYetRendersAsEmpty() throws IOException {
        assertThat(SubagentFiles.render(subagents.resolve("agent-a2.jsonl"), SubagentFiles.LIMIT_BYTES, json))
                .isEqualTo("_The sub-agent has written nothing yet._\n");
    }

    @Test
    void aLineStillBeingWrittenIsSkipped() throws IOException {
        Path f = subagents.resolve("agent-partial.jsonl");
        Files.writeString(f, assistantText("whole") + "\n{\"type\":\"assistant\",\"mess");

        assertThat(SubagentFiles.render(f, SubagentFiles.LIMIT_BYTES, json)).isEqualTo("whole\n");
    }

    private static String userText(String text) {
        return "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"" + text + "\"}}";
    }

    private static String assistantText(String text) {
        return "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}}";
    }

    private static String assistantToolUse(String id, String name, String input) {
        return "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"" + id
                + "\",\"name\":\"" + name + "\",\"input\":" + input + "}]}}";
    }

    private static String toolResult(String id, String text) {
        return "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"" + id
                + "\",\"content\":\"" + text + "\"}]}}";
    }
}
