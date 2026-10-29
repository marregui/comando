package dev.marregui.comando.testkit;

import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A deterministic session for rendering measurements: eight turns with Markdown text (headings, lists, code, tables,
 * source references), 41 tool calls (shell, reads, searches, edits, one subagent) and their results, about 10 k tokens
 * in all. {@link #steps} replays it as the stream would arrive: text in small deltas, tool calls first running and then
 * finished.
 */
public final class SyntheticSession {

    /** One change to the transcript; {@code index} is -1 for an append. */
    public record Step(int index, Block block) {
    }

    private SyntheticSession() {
    }

    public static List<Step> steps(int deltaChars) {
        Random r = new Random(42);
        List<Step> out = new ArrayList<>();
        int[] size = {0};
        String[] tools = {"Bash", "Read", "Grep", "Edit", "Bash"};
        int call = 0;
        for (int turn = 0; turn < 8; turn++) {
            append(out, size, new Block.UserPrompt(prompt(turn), "main", ""));
            append(out, size, new Block.Thinking("m" + turn, 0, "Considering the request: " + words(r, 40), 120, false, ""));
            stream(out, size, "m" + turn, 1, markdown(r, turn), deltaChars, "");
            for (String tool : tools) {
                String id = "toolu_" + call++;
                Block.ToolCall running = new Block.ToolCall(id, tool, input(tool, r, call), "", Block.ToolStatus.RUNNING, "");
                int at = append(out, size, running);
                out.add(new Step(at, running.withResult(result(tool, r), call % 13 == 0)));
            }
            if (turn == 4) {
                String id = "toolu_agent";
                Block.ToolCall agent = new Block.ToolCall(id, "Agent", "{\"description\":\"survey the module\",\"prompt\":\"List the classes.\"}",
                        "", Block.ToolStatus.RUNNING, "");
                int at = append(out, size, agent);
                append(out, size, new Block.UserPrompt("List the classes.", "subagent", id));
                stream(out, size, "sub", 0, "The module has **12 classes**; the entry point is `core/Reducer.java:42`.", deltaChars, id);
                out.add(new Step(at, agent.withResult("The module has 12 classes.", false)));
            }
            stream(out, size, "m" + turn, 9, "Done. " + words(r, 30) + "\n\nSee `ui/TranscriptWidget.java:120` for the follow logic.", deltaChars, "");
            append(out, size, new Block.TurnEnd(0.05 * (turn + 1), 12_000 + turn * 1000L, 6, false, "success"));
        }
        return out;
    }

    /** Applies every step to a transcript, the final state of the session. */
    public static Transcript transcript() {
        Transcript t = new Transcript();
        for (Step s : steps(10_000)) {
            if (s.index() < 0) {
                t.append(s.block());
            } else {
                t.replace(s.index(), s.block());
            }
        }
        return t;
    }

    private static int append(List<Step> out, int[] size, Block b) {
        out.add(new Step(-1, b));
        return size[0]++;
    }

    private static void stream(List<Step> out, int[] size, String id, int index, String text, int delta, String parent) {
        Block.AssistantText b = new Block.AssistantText(id, index, text.substring(0, Math.min(delta, text.length())), true, parent);
        int at = append(out, size, b);
        for (int end = delta * 2; end < text.length(); end += delta) {
            b = b.appended(text.substring(end - delta, end));
            out.add(new Step(at, b));
        }
        out.add(new Step(at, b.finished(text)));
    }

    private static String prompt(int turn) {
        return switch (turn % 4) {
            case 0 -> "Why does the cancel path take eleven seconds? Look at db/Lane.java and the driver settings.";
            case 1 -> "Fix it, then run the tests.";
            case 2 -> "Plot the latency of the last hour from the metrics table.";
            default -> "Summarise what changed and what is still open.";
        };
    }

    private static String markdown(Random r, int turn) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Finding ").append(turn + 1).append("\n\n");
        sb.append(words(r, 60)).append(" The call blocks in `Statement.cancel()` at `db/src/main/java/dev/marregui/comando/db/Lane.java:")
                .append(100 + turn).append("`.\n\n");
        sb.append("- **cause**: ").append(words(r, 14)).append("\n- **effect**: ").append(words(r, 12))
                .append("\n  - nested: ").append(words(r, 8)).append("\n- **fix**: ").append(words(r, 10)).append("\n\n");
        sb.append("```java\n");
        for (int i = 0; i < 12; i++) {
            sb.append("    long v").append(i).append(" = lane.cancel(requestId, Duration.ofMillis(").append(r.nextInt(5000)).append(")); // ")
                    .append(words(r, 4)).append('\n');
        }
        sb.append("```\n\n");
        if (turn % 2 == 0) {
            sb.append("| step | before | after |\n|---|---|---|\n");
            for (int i = 0; i < 6; i++) {
                sb.append("| ").append(words(r, 2)).append(" | ").append(r.nextInt(12_000)).append(" ms | ").append(r.nextInt(100)).append(" ms |\n");
            }
            sb.append('\n');
        }
        sb.append(words(r, 120)).append("\n\n> ").append(words(r, 20)).append('\n');
        return sb.toString();
    }

    private static String input(String tool, Random r, int call) {
        return switch (tool) {
            case "Bash" -> "{\"command\":\"./gradlew :db:test --tests 'Lane*' -q 2>&1 | tail -" + (20 + call) + "\"}";
            case "Read" -> "{\"file_path\":\"/w/comando/db/src/main/java/dev/marregui/comando/db/Lane.java\",\"offset\":" + call * 10 + "}";
            case "Grep" -> "{\"pattern\":\"cancelSignalTimeout\",\"path\":\"/w/comando\"}";
            default -> "{\"file_path\":\"/w/comando/db/Lane.java\",\"old_string\":\"" + lines(r, 6, "old") + "\",\"new_string\":\""
                    + lines(r, 7, "new") + "\"}";
        };
    }

    private static String lines(Random r, int n, String tag) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append("    ").append(tag).append(' ').append(words(r, 5)).append(i == n - 1 ? "" : "\\n");
        }
        return sb.toString();
    }

    private static String result(String tool, Random r) {
        int n = switch (tool) {
            case "Read" -> 60;
            case "Bash" -> 25;
            case "Grep" -> 8;
            default -> 1;
        };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i + 1).append('\t').append(words(r, 3 + r.nextInt(8))).append('\n');
        }
        return sb.toString();
    }

    private static final String[] WORDS = ("the lane cancel statement driver query timeout thread reducer intent effect panel session "
            + "result rows fetch chunk column abort timer state transcript block render layout frame width paint scroll "
            + "connection approval policy audit tool view plot table diff marker gutter").split(" ");

    private static String words(Random r, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "" : " ").append(WORDS[r.nextInt(WORDS.length)]);
        }
        return sb.append('.').toString();
    }
}
