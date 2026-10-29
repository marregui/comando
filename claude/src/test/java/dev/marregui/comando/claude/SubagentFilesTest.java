package dev.marregui.comando.claude;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// [SubagentFiles] finds a sub-agent's stored conversation by its Agent call and renders it as Markdown.
/// - `find` matches the call id among the meta files, and finds nothing for an unknown call, conversation or root
/// - a meta file larger than `META_BYTES` is skipped; the meta file says when the sub-agent finished
/// - the render quotes the task, keeps the texts, gives one line per tool with its result clipped at `RESULT_CHARS`
/// - a file over the limit keeps its task, then only its tail, and says so; an empty file and a partial line render
///   safely
/// - a long run keeps its last `LAST_TOOLS` tool calls and counts all of them
/// - reading as the file grows renders as one whole read, parsing only new lines and restarting on a shorter file
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

    /// `find` returns the transcript, meta file and description "Find the gauges" of the sub-agent whose meta names the
    /// call id, and the other sub-agent by its own call id.
    @Test
    void findsTheSubagentByItsAgentCallAmongTheMetaFiles() {
        SubagentFiles.Found found = SubagentFiles.find(projects, "conv-1", "toolu_A2", json).orElseThrow();
        assertThat(found.transcript()).isEqualTo(subagents.resolve("agent-a2.jsonl"));
        assertThat(found.meta()).isEqualTo(subagents.resolve("agent-a2.meta.json"));
        assertThat(found.description()).isEqualTo("Find the gauges");

        assertThat(SubagentFiles.find(projects, "conv-1", "toolu_A1", json).orElseThrow().transcript())
                .isEqualTo(subagents.resolve("agent-a1.jsonl"));
    }

    /// An unknown call id, another conversation id and a missing projects root each find nothing.
    @Test
    void anUnknownCallOrConversationFindsNothing() {
        assertThat(SubagentFiles.find(projects, "conv-1", "toolu_nope", json)).isEmpty();
        assertThat(SubagentFiles.find(projects, "conv-2", "toolu_A1", json)).isEmpty();
        assertThat(SubagentFiles.find(tmp.resolve("missing"), "conv-1", "toolu_A1", json)).isEmpty();
    }

    /// A meta file padded past [SubagentFiles#META_BYTES] is not read, so its call id finds nothing.
    @Test
    void aFileTooLargeToBeAMetaFileIsSkipped() throws IOException {
        Files.writeString(subagents.resolve("agent-big.meta.json"),
                "{\"toolUseId\":\"toolu_BIG\",\"pad\":\"" + "x".repeat((int) SubagentFiles.META_BYTES) + "\"}");
        assertThat(SubagentFiles.find(projects, "conv-1", "toolu_BIG", json)).isEmpty();
    }

    /// [SubagentFiles#finished] is true for a meta file that says so and false for one that does not.
    @Test
    void theMetaFileSaysWhenTheSubagentFinished() {
        assertThat(SubagentFiles.finished(subagents.resolve("agent-a3.meta.json"), json)).isTrue();
        assertThat(SubagentFiles.finished(subagents.resolve("agent-a1.meta.json"), json)).isFalse();
    }

    /// The render starts with the task quoted line by line, keeps the assistant texts, and writes exactly two tool
    /// lines of the form ``- `Bash` ls demo → result``.
    @Test
    void rendersThePromptTheTextsAndOneLinePerTool() throws IOException {
        String md = SubagentFiles.render(subagents.resolve("agent-a1.jsonl"), SubagentFiles.SCAN_BYTES, json);

        assertThat(md).startsWith("> Count the widgets in the demo folder.\n> Report the total.");
        assertThat(md).contains("I will list the folder first.", "There are 3 widgets.");
        assertThat(md).contains("- `Bash` ls demo → widget-1 widget-2 widget-3\n");
        assertThat(md).contains("- `Read` demo/widget-1 → a widget\n");
        assertThat(md.lines().filter(l -> l.startsWith("- `"))).hasSize(2);
        assertThat(md).doesNotContain("private musing", "ignored", "is longer than");
    }

    /// A tool result longer than [SubagentFiles#RESULT_CHARS] keeps that many characters followed by ` …`.
    @Test
    void aLongToolResultIsClipped() throws IOException {
        Path f = subagents.resolve("agent-long.jsonl");
        Files.writeString(f, assistantToolUse("tu9", "Grep", "{\"pattern\":\"needle\"}") + "\n"
                + toolResult("tu9", "y".repeat(500)) + "\n");

        String md = SubagentFiles.render(f, SubagentFiles.SCAN_BYTES, json);

        assertThat(md).contains("- `Grep` needle → " + "y".repeat(SubagentFiles.RESULT_CHARS) + " …");
        assertThat(md).doesNotContain("y".repeat(SubagentFiles.RESULT_CHARS + 1));
    }

    /// A file over 3 MB rendered with a 2 MB limit starts with its first prompt and the notice "longer than 2.0 MB:
    /// only its last 2.0 MB are read", keeps its last word, and stays under the limit plus 1 000 characters.
    @Test
    void aFileOverTheLimitShowsItsTaskThenOnlyItsTailAndSaysSo() throws IOException {
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

        long limit = 2L * 1024 * 1024;
        String md = SubagentFiles.render(f, limit, json);

        assertThat(md).as("the task stays first, so a refreshed view keeps its start")
                .startsWith("> THE FIRST PROMPT\n\n_The conversation is longer than 2.0 MB: only its last 2.0 MB are read._");
        assertThat(md).contains("THE LAST WORD");
        assertThat(md.length()).isLessThan((int) limit + 1000);
    }

    /// An empty transcript renders as "_The sub-agent has written nothing yet._".
    @Test
    void aFileNotWrittenYetRendersAsEmpty() throws IOException {
        assertThat(SubagentFiles.render(subagents.resolve("agent-a2.jsonl"), SubagentFiles.SCAN_BYTES, json))
                .isEqualTo("_The sub-agent has written nothing yet._\n");
    }

    /// A final line without its newline, still being written, is left out of the render.
    @Test
    void aLineStillBeingWrittenIsSkipped() throws IOException {
        Path f = subagents.resolve("agent-partial.jsonl");
        Files.writeString(f, assistantText("whole") + "\n{\"type\":\"assistant\",\"mess");

        assertThat(SubagentFiles.render(f, SubagentFiles.SCAN_BYTES, json)).isEqualTo("whole\n");
    }

    /// Of 250 tool calls, the render keeps the task, a notice that earlier steps are hidden, calls 151 to 250, and ends
    /// with "_100 of 250 tool calls shown._".
    @Test
    void aLongSubagentKeepsItsTaskAndItsLastHundredToolCallsAndSaysHowManyOfAll() throws IOException {
        Path f = subagents.resolve("agent-many.jsonl");
        StringBuilder sb = new StringBuilder(userText("THE TASK")).append('\n');
        for (int i = 1; i <= 250; i++) {
            sb.append(assistantToolUse("t" + i, "Bash", "{\"command\":\"step [" + i + "]\"}")).append('\n');
            sb.append(toolResult("t" + i, "out [" + i + "]")).append('\n');
        }
        Files.writeString(f, sb.toString());

        String md = SubagentFiles.render(f, SubagentFiles.SCAN_BYTES, json);

        assertThat(md).startsWith("> THE TASK\n\n_Earlier steps are not shown: the view keeps the last 100 tool calls._");
        assertThat(md.lines().filter(l -> l.startsWith("- `"))).hasSize(SubagentFiles.LAST_TOOLS);
        assertThat(md).contains("- `Bash` step [151] → out [151]", "- `Bash` step [250] → out [250]").doesNotContain("[150]");
        assertThat(md.strip()).endsWith("_100 of 250 tool calls shown._");
    }

    /// A file read in pieces cut anywhere, mid-line too, renders exactly as the whole file read once and counts its 130
    /// tool calls.
    @Test
    void aConversationReadAsItGrowsRendersAsTheWholeFileReadOnce() throws IOException {
        StringBuilder sb = new StringBuilder(userText("THE TASK")).append('\n');
        for (int i = 1; i <= 130; i++) {
            sb.append(assistantText("thinking about " + i)).append('\n');
            sb.append(assistantToolUse("t" + i, "Read", "{\"file_path\":\"f" + i + "\"}")).append('\n');
            sb.append(toolResult("t" + i, "content " + i)).append('\n');
        }
        byte[] all = sb.toString().getBytes(StandardCharsets.UTF_8);
        Path whole = subagents.resolve("agent-whole.jsonl");
        Files.write(whole, all);
        Path grown = subagents.resolve("agent-grown.jsonl");
        Files.write(grown, new byte[0]);
        SubagentFiles.Conversation c = new SubagentFiles.Conversation(grown, json, SubagentFiles.SCAN_BYTES);
        Random random = new Random(7);

        for (int at = 0; at < all.length; ) {
            int n = Math.min(all.length - at, 1 + random.nextInt(700));
            Files.write(grown, Arrays.copyOfRange(all, at, at + n), StandardOpenOption.APPEND);
            at += n;
            c.read();
        }

        assertThat(c.render("")).isEqualTo(SubagentFiles.render(whole, SubagentFiles.SCAN_BYTES, json));
        assertThat(c.tools()).isEqualTo(130);
    }

    /// Bytes already read and then changed in place are not read again: a later read adds only the new lines.
    @Test
    void aReadAfterGrowthParsesOnlyTheNewLines() throws IOException {
        Path f = subagents.resolve("agent-inc.jsonl");
        Files.writeString(f, assistantText("EARLY") + "\n");
        SubagentFiles.Conversation c = new SubagentFiles.Conversation(f, json, SubagentFiles.SCAN_BYTES);
        c.read();
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.seek(0);
            raf.write("xxxxxxxx".getBytes(StandardCharsets.UTF_8)); // the early line, now not JSON
            raf.seek(raf.length());
            raf.write((assistantText("LATE") + "\n").getBytes(StandardCharsets.UTF_8));
        }

        c.read();

        assertThat(c.render("")).isEqualTo("EARLY\n\nLATE\n");
    }

    /// A file replaced by a shorter one is read again from its start, showing only the new content.
    @Test
    void aFileReplacedByAShorterOneIsReadAgainFromItsStart() throws IOException {
        Path f = subagents.resolve("agent-replaced.jsonl");
        Files.writeString(f, assistantText("OLD ONE") + "\n" + assistantText("OLD TWO") + "\n");
        SubagentFiles.Conversation c = new SubagentFiles.Conversation(f, json, SubagentFiles.SCAN_BYTES);
        c.read();

        Files.writeString(f, assistantText("NEW") + "\n");
        c.read();

        assertThat(c.render("")).isEqualTo("NEW\n");
    }

    /// A footer passed to the render is appended after the last text, separated by a blank line.
    @Test
    void aFooterEndsTheView() throws IOException {
        SubagentFiles.Conversation c = new SubagentFiles.Conversation(subagents.resolve("agent-a1.jsonl"), json, SubagentFiles.SCAN_BYTES);
        c.read();
        assertThat(c.render("_footer_")).startsWith("> Count the widgets").endsWith("There are 3 widgets.\n\n_footer_\n");
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
