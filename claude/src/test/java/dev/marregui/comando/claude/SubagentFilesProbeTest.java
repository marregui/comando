package dev.marregui.comando.claude;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/// [SubagentFiles] against generated inputs: every JSON type in every field read, line ends, and the cut at the limit.
/// - a field of any JSON type neither throws nor stops the next valid line rendering
/// - meta fields of any JSON type never make `find` or `finished` throw; a numeric `toolUseId` matches its digits
/// - CRLF lines render as LF lines do
/// - a cut inside a multi-byte character leaves no replacement character; a cut at a line start keeps that line
/// - a file at or under the limit renders whole; a last line longer than the limit leaves only the notice
class SubagentFilesProbeTest {

    private final ObjectMapper json = new ObjectMapper();
    @TempDir
    Path tmp;

    private static final List<String> VALUES = List.of("null", "true", "7", "1.5", "\"s\"", "[]", "[1,\"x\",{}]", "{}", "{\"a\":1}");

    private static String sentinel() {
        return "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"SENTINEL\"}]}}";
    }

    /// Each of nine JSON values, from `null` to a nested object, put in every field the renderer reads, renders without
    /// an exception, and a valid sentinel line after it still renders.
    @Test
    void everyJsonTypeInEveryFieldReadRendersWithoutThrowing() throws IOException {
        final List<String> templates = List.of(
                "%s",
                "{\"type\":%s}",
                "{\"type\":\"user\",\"message\":%s}",
                "{\"type\":\"user\",\"message\":{\"content\":%s}}",
                "{\"type\":\"assistant\",\"message\":{\"content\":%s}}",
                "{\"type\":\"user\",\"message\":{\"content\":[%s]}}",
                "{\"type\":\"assistant\",\"message\":{\"content\":[%s]}}",
                "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":%s}]}}",
                "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":%s}]}}",
                "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":%s}]}}",
                "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":%s,\"name\":\"T\",\"input\":{}}]}}",
                "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"i\",\"name\":%s,\"input\":{}}]}}",
                "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"i\",\"name\":\"T\",\"input\":%s}]}}",
                "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"i\",\"name\":\"T\",\"input\":{\"command\":%s}}]}}",
                "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":%s,\"content\":\"r\"}]}}",
                "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"i\",\"content\":%s}]}}",
                "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"i\",\"content\":\"r\",\"is_error\":%s}]}}");
        final List<String> failures = new ArrayList<>();
        int n = 0;
        for (final String t : templates) {
            for (final String v : VALUES) {
                final Path f = tmp.resolve("probe-" + n++ + ".jsonl");
                final String call = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"i\",\"name\":\"T\",\"input\":{}}]}}";
                Files.writeString(f, call + "\n" + String.format(t, v) + "\n" + sentinel() + "\n");
                try {
                    final String md = SubagentFiles.render(f, SubagentFiles.SCAN_BYTES, json);
                    if (!md.contains("SENTINEL")) {
                        failures.add(t + " <- " + v + ": sentinel lost");
                    }
                } catch (Exception | Error e) {
                    failures.add(t + " <- " + v + ": " + e);
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    /// Meta files whose fields hold each JSON type never make [SubagentFiles#find] or [SubagentFiles#finished] throw,
    /// also for an id no file has.
    @Test
    void everyJsonTypeInMetaFieldsFindsWithoutThrowing() throws IOException {
        final Path projects = tmp.resolve("projects");
        final Path dir = Files.createDirectories(projects.resolve("-w/conv-1/subagents"));
        int n = 0;
        for (final String v : VALUES) {
            Files.writeString(dir.resolve("agent-t" + n + ".meta.json"), "{\"toolUseId\":" + v + ",\"description\":" + v + ",\"agentType\":" + v + "}");
            Files.writeString(dir.resolve("agent-r" + n + ".meta.json"), v);
            n++;
        }
        Files.writeString(dir.resolve("agent-empty.meta.json"), "");
        Files.write(dir.resolve("agent-bin.meta.json"), new byte[]{(byte) 0xC3, (byte) 0x28, 0, (byte) 0xFF});
        assertThatCode(() -> SubagentFiles.find(projects, "conv-1", "toolu_none", json)).doesNotThrowAnyException();
        assertThatCode(() -> SubagentFiles.finished(dir.resolve("agent-empty.meta.json"), json)).doesNotThrowAnyException();
    }

    /// A meta file with the number `7` as `toolUseId` is found for the id `"7"`, and an object `description` reads as
    /// empty; the test pins this behaviour, not a design.
    @Test
    void aNumericToolUseIdMatchesItsDigits() throws IOException {
        final Path projects = tmp.resolve("projects");
        final Path dir = Files.createDirectories(projects.resolve("-w/conv-1/subagents"));
        Files.writeString(dir.resolve("agent-n.meta.json"), "{\"toolUseId\":7,\"description\":{\"x\":1}}");
        final SubagentFiles.Found f = SubagentFiles.find(projects, "conv-1", "7", json).orElseThrow();
        assertThat(f.description()).isEmpty();
    }

    /// A user prompt, a text with a tool call and its result render identically with CRLF and with LF line ends.
    @Test
    void crlfLinesRenderAsLf() throws IOException {
        final Path lf = tmp.resolve("lf.jsonl");
        final Path crlf = tmp.resolve("crlf.jsonl");
        final String a = "{\"type\":\"user\",\"message\":{\"content\":\"the task\"}}";
        final String b = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"t\"},"
                + "{\"type\":\"tool_use\",\"id\":\"i\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}]}}";
        final String c = "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"i\",\"content\":\"out\"}]}}";
        Files.writeString(lf, a + "\n" + b + "\n" + c + "\n");
        Files.writeString(crlf, a + "\r\n" + b + "\r\n" + c + "\r\n");
        assertThat(SubagentFiles.render(crlf, SubagentFiles.SCAN_BYTES, json))
                .isEqualTo(SubagentFiles.render(lf, SubagentFiles.SCAN_BYTES, json));
    }

    /// A cut that falls inside a three-byte character keeps the following lines, `LAST` among them, with no `�` in the
    /// output.
    @Test
    void aMultiByteCharacterSplitAtTheCutLeavesNoReplacementCharacter() throws IOException {
        final long limit = 1000;
        final Path f = tmp.resolve("utf8.jsonl");
        final String euro = "€".repeat(400); // 1200 bytes
        final String first = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"" + euro + "\"}]}}";
        final String last = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"LAST\"}]}}";
        final byte[] all = (first + "\n" + last + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(f, all);
        final long from = all.length - limit;
        final int prefix = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"".length();
        assertThat((from - prefix) % 3).as("cut inside a character").isNotZero();
        final String md = SubagentFiles.render(f, limit, json);
        assertThat(md).contains("LAST").doesNotContain("�");
    }

    /// Files of 4 095 and 4 096 bytes rendered with a 4 096-byte limit have no notice and keep `FIRST`; at 4 097 bytes
    /// the output starts with the notice, drops `FIRST` and keeps `LAST`.
    @Test
    void aFileExactlyAtTheLimitIsReadWhole() throws IOException {
        for (final int delta : new int[]{-1, 0}) {
            final Path f = sized(tmp.resolve("at" + delta + ".jsonl"), 4096 + delta);
            final String md = SubagentFiles.render(f, 4096, json);
            assertThat(md).doesNotContain("is longer than").contains("FIRST");
        }
        final Path over = sized(tmp.resolve("over.jsonl"), 4097);
        assertThat(SubagentFiles.render(over, 4096, json)).startsWith("_The conversation is longer than").doesNotContain("FIRST").contains("LAST");
    }

    /// A cut landing exactly at the start of the second line drops the first and shows the second and third whole,
    /// under the notice.
    @Test
    void aCutAtALineStartKeepsThatLine() throws IOException {
        final Path f = tmp.resolve("boundary.jsonl");
        final String first = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"FIRST\"}]}}";
        final String second = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"SECOND\"}]}}";
        final String third = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"THIRD\"}]}}";
        Files.writeString(f, first + "\n" + second + "\n" + third + "\n");
        final long limit = (second + "\n" + third + "\n").getBytes(StandardCharsets.UTF_8).length;

        final String md = SubagentFiles.render(f, limit, json);

        assertThat(md).startsWith("_The conversation is longer than").doesNotContain("FIRST").contains("THIRD");
        assertThat(md).as("the whole line that starts exactly at the cut").contains("SECOND");
    }

    /// With a 5 000-character last line and a 4 096-byte limit, the output is the task prompt and the notice, with none
    /// of the long line; the test pins this behaviour, not a design.
    @Test
    void aLastLineLongerThanTheLimitShowsOnlyTheNotice() throws IOException {
        final Path f = tmp.resolve("oneline.jsonl");
        final String big = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"" + "z".repeat(5000) + "\"}]}}";
        Files.writeString(f, "{\"type\":\"user\",\"message\":{\"content\":\"task\"}}\n" + big + "\n");
        final String md = SubagentFiles.render(f, 4096, json);
        assertThat(md.strip()).startsWith("> task\n\n_The conversation is longer than").doesNotContain("zzz");
    }

    private static Path sized(final Path f, final int size) throws IOException {
        final String first = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"FIRST\"}]}}\n";
        final String lastPrefix = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"LAST";
        final String lastSuffix = "\"}]}}\n";
        final int pad = size - first.length() - lastPrefix.length() - lastSuffix.length();
        try (OutputStream out = Files.newOutputStream(f)) {
            out.write((first + lastPrefix + "p".repeat(pad) + lastSuffix).getBytes(StandardCharsets.UTF_8));
        }
        assertThat(Files.size(f)).isEqualTo(size);
        return f;
    }
}
