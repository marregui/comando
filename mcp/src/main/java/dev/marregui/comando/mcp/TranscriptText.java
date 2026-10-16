package dev.marregui.comando.mcp;

import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;

import java.util.Locale;

/**
 * A session's transcript as text for another session, from a block index on: the prompts, Claude's text, the tool
 * calls with their results, notes and turn ends. A block still streaming or running is shown as such, and the cursor
 * returned stops at the first of them, so the next read shows it again once it has finished.
 */
final class TranscriptText {

    static final int MAX_TEXT = 4_000;
    static final int MAX_TOOL_INPUT = 500;
    static final int MAX_TOOL_RESULT = 2_000;

    /** The text, and the index to pass as {@code after} next time. */
    record Read(String text, int next) {
    }

    private TranscriptText() {
    }

    /**
     * Blocks {@code after} onward, at most {@code maxChars} of them in text; {@code status} heads the text. An
     * {@code after} beyond the end (a transcript that restarted) reads from the start.
     */
    static Read read(String session, String status, Transcript t, int after, int maxChars) {
        int size = t.size();
        StringBuilder body = new StringBuilder();
        String restarted = "";
        if (after > size) {
            restarted = "; the transcript restarted since that cursor, read from its start";
            after = 0;
        }
        int next = size;
        int unfinished = -1;
        for (int i = after; i < size; i++) {
            Block b = t.get(i);
            if (b == null) {
                next = i; // cleared under the reader
                break;
            }
            String entry = entry(i, b);
            if (body.length() + entry.length() > maxChars && body.length() > 0) {
                next = i;
                break;
            }
            body.append(entry);
            if (unfinished < 0 && !finished(b)) {
                unfinished = i;
            }
        }
        if (unfinished >= 0) {
            next = Math.min(next, unfinished);
        }
        String head = "session " + session + " · " + status + " · " + size + " blocks, shown from " + after
                + " · next read: after " + next + restarted + (next < size && unfinished < 0 ? "; more blocks follow" : "") + '\n';
        return new Read(body.isEmpty() ? head + "(no blocks)\n" : head + body, next);
    }

    static boolean finished(Block b) {
        return switch (b) {
            case Block.AssistantText a -> !a.streaming();
            case Block.Thinking th -> !th.streaming();
            case Block.ToolCall c -> c.status() != Block.ToolStatus.RUNNING;
            default -> true;
        };
    }

    private static String entry(int i, Block b) {
        String sub = b.parent().isEmpty() ? "" : "(subagent) ";
        String line = switch (b) {
            case Block.UserPrompt p -> "prompt" + (p.origin().isEmpty() ? "" : " from panel " + p.origin()) + ":\n" + indent(clip(p.text(), MAX_TEXT));
            case Block.AssistantText a -> "Claude" + (a.streaming() ? " (still writing)" : "") + ":\n" + indent(clip(a.text(), MAX_TEXT));
            case Block.Thinking th -> "thinking" + (th.tokens() > 0 ? " (" + th.tokens() + " tokens)" : "") + (th.streaming() ? " (still thinking)" : "");
            case Block.ToolCall c -> "tool " + c.name() + " " + clip(c.inputJson(), MAX_TOOL_INPUT) + " → " + c.status().name().toLowerCase(Locale.ROOT)
                    + (c.resultText().isEmpty() ? "" : ":\n" + indent(clip(c.resultText(), MAX_TOOL_RESULT)));
            case Block.Note n -> "note " + n.kind().name().toLowerCase(Locale.ROOT) + ": " + clip(n.text(), MAX_TOOL_RESULT);
            case Block.TurnEnd e -> String.format(Locale.ROOT, "turn %s: %d turns, %.1f s%s", e.error() ? "failed" : "ended",
                    e.numTurns(), e.durationMillis() / 1000.0, e.text().isEmpty() || !e.error() ? "" : ": " + clip(e.text(), MAX_TOOL_RESULT));
        };
        return "[" + i + "] " + sub + line + '\n';
    }

    private static String indent(String text) {
        return "  " + text.strip().replace("\n", "\n  ");
    }

    private static String clip(String s, int max) {
        return McpTools.clip(s, max);
    }
}
