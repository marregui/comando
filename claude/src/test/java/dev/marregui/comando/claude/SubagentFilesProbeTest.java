package dev.marregui.comando.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** {@code SubagentFiles} against generated inputs: every JSON type in every field read, line ends, the cut at the limit. */
class SubagentFilesProbeTest {

    private final ObjectMapper json = new ObjectMapper();
    @TempDir
    Path tmp;

    private static final List<String> VALUES = List.of("null", "true", "7", "1.5", "\"s\"", "[]", "[1,\"x\",{}]", "{}", "{\"a\":1}");

    private static String sentinel() {
        return "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"SENTINEL\"}]}}";
    }

    /** Every JSON type in every field the renderer reads; the next valid line still renders. */
    @Test
    void everyJsonTypeInEveryFieldReadRendersWithoutThrowing() throws IOException {
        List<String> templates = List.of(
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
        List<String> failures = new ArrayList<>();
        int n = 0;
        for (String t : templates) {
            for (String v : VALUES) {
                Path f = tmp.resolve("probe-" + n++ + ".jsonl");
                String call = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"i\",\"name\":\"T\",\"input\":{}}]}}";
                Files.writeString(f, call + "\n" + String.format(t, v) + "\n" + sentinel() + "\n");
                try {
                    String md = SubagentFiles.render(f, SubagentFiles.LIMIT_BYTES, json);
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

    /** Meta fields of every JSON type: find never throws. */
    @Test
    void everyJsonTypeInMetaFieldsFindsWithoutThrowing() throws IOException {
        Path projects = tmp.resolve("projects");
        Path dir = Files.createDirectories(projects.resolve("-w/conv-1/subagents"));
        int n = 0;
        for (String v : VALUES) {
            Files.writeString(dir.resolve("agent-t" + n + ".meta.json"), "{\"toolUseId\":" + v + ",\"description\":" + v + ",\"agentType\":" + v + "}");
            Files.writeString(dir.resolve("agent-r" + n + ".meta.json"), v);
            n++;
        }
        Files.writeString(dir.resolve("agent-empty.meta.json"), "");
        Files.write(dir.resolve("agent-bin.meta.json"), new byte[]{(byte) 0xC3, (byte) 0x28, 0, (byte) 0xFF});
        assertThatCode(() -> SubagentFiles.find(projects, "conv-1", "toolu_none", json)).doesNotThrowAnyException();
        assertThatCode(() -> SubagentFiles.finished(dir.resolve("agent-empty.meta.json"), json)).doesNotThrowAnyException();
    }

    /** A number toolUseId matches a string id of the same digits: type confusion, recorded. */
    @Test
    void aNumericToolUseIdMatchesItsDigits() throws IOException {
        Path projects = tmp.resolve("projects");
        Path dir = Files.createDirectories(projects.resolve("-w/conv-1/subagents"));
        Files.writeString(dir.resolve("agent-n.meta.json"), "{\"toolUseId\":7,\"description\":{\"x\":1}}");
        SubagentFiles.Found f = SubagentFiles.find(projects, "conv-1", "7", json).orElseThrow();
        assertThat(f.description()).isEmpty();
    }

    @Test
    void crlfLinesRenderAsLf() throws IOException {
        Path lf = tmp.resolve("lf.jsonl");
        Path crlf = tmp.resolve("crlf.jsonl");
        String a = "{\"type\":\"user\",\"message\":{\"content\":\"the task\"}}";
        String b = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"t\"},"
                + "{\"type\":\"tool_use\",\"id\":\"i\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}]}}";
        String c = "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"i\",\"content\":\"out\"}]}}";
        Files.writeString(lf, a + "\n" + b + "\n" + c + "\n");
        Files.writeString(crlf, a + "\r\n" + b + "\r\n" + c + "\r\n");
        assertThat(SubagentFiles.render(crlf, SubagentFiles.LIMIT_BYTES, json))
                .isEqualTo(SubagentFiles.render(lf, SubagentFiles.LIMIT_BYTES, json));
    }

    /** The tail cut inside a three-byte character: no replacement character, the following lines kept. */
    @Test
    void aMultiByteCharacterSplitAtTheCutLeavesNoReplacementCharacter() throws IOException {
        long limit = 1000;
        Path f = tmp.resolve("utf8.jsonl");
        String euro = "€".repeat(400); // 1200 bytes
        String first = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"" + euro + "\"}]}}";
        String last = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"LAST\"}]}}";
        byte[] all = (first + "\n" + last + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(f, all);
        long from = all.length - limit;
        int prefix = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"".length();
        assertThat((from - prefix) % 3).as("cut inside a character").isNotZero();
        String md = SubagentFiles.render(f, limit, json);
        assertThat(md).contains("LAST").doesNotContain("�");
    }

    @Test
    void aFileExactlyAtTheLimitIsReadWhole() throws IOException {
        for (int delta : new int[]{-1, 0}) {
            Path f = sized(tmp.resolve("at" + delta + ".jsonl"), 4096 + delta);
            String md = SubagentFiles.render(f, 4096, json);
            assertThat(md).doesNotContain("is longer than").contains("FIRST");
        }
        Path over = sized(tmp.resolve("over.jsonl"), 4097);
        assertThat(SubagentFiles.render(over, 4096, json)).startsWith("_The conversation is longer than").doesNotContain("FIRST").contains("LAST");
    }

    /** The cut lands exactly at the start of a line: that whole line is the first whole line, and is shown. */
    @Test
    void aCutAtALineStartKeepsThatLine() throws IOException {
        Path f = tmp.resolve("boundary.jsonl");
        String first = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"FIRST\"}]}}";
        String second = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"SECOND\"}]}}";
        String third = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"THIRD\"}]}}";
        Files.writeString(f, first + "\n" + second + "\n" + third + "\n");
        long limit = (second + "\n" + third + "\n").getBytes(StandardCharsets.UTF_8).length;

        String md = SubagentFiles.render(f, limit, json);

        assertThat(md).startsWith("_The conversation is longer than").doesNotContain("FIRST").contains("THIRD");
        assertThat(md).as("the whole line that starts exactly at the cut").contains("SECOND");
    }

    /** One line longer than the limit at the end: the notice says the end is shown, and nothing is. Recorded. */
    @Test
    void aLastLineLongerThanTheLimitShowsOnlyTheNotice() throws IOException {
        Path f = tmp.resolve("oneline.jsonl");
        String big = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"" + "z".repeat(5000) + "\"}]}}";
        Files.writeString(f, "{\"type\":\"user\",\"message\":{\"content\":\"task\"}}\n" + big + "\n");
        String md = SubagentFiles.render(f, 4096, json);
        assertThat(md.strip()).startsWith("> task\n\n_The conversation is longer than").doesNotContain("zzz");
    }

    private static Path sized(Path f, int size) throws IOException {
        String first = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"FIRST\"}]}}\n";
        String lastPrefix = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"LAST";
        String lastSuffix = "\"}]}}\n";
        int pad = size - first.length() - lastPrefix.length() - lastSuffix.length();
        try (OutputStream out = Files.newOutputStream(f)) {
            out.write((first + lastPrefix + "p".repeat(pad) + lastSuffix).getBytes(StandardCharsets.UTF_8));
        }
        assertThat(Files.size(f)).isEqualTo(size);
        return f;
    }
}
