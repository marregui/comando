package dev.marregui.comando.core.transcript;

import dev.marregui.comando.core.view.Clip;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

/**
 * The text a panel's topic is read from: the end of its session's conversation, or the start of its own text. Only
 * the main conversation counts (sub-agents' blocks are their parent's detail), without thinking or notes; the latest
 * prompt is always kept, since it names what the session was asked to do. Empty when there is nothing to read.
 */
public final class TopicSource {

    /** At most this much of a conversation or a text is read; about 1 500 tokens. */
    public static final int MAX_CHARS = 6_000;
    static final int MAX_PROMPT = 800;
    static final int MAX_TEXT = 600;
    static final int MAX_TOOL_INPUT = 160;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TopicSource() {
    }

    /**
     * The end of {@code session}'s conversation, newest last, at most {@code maxChars}, headed by where the session works
     * ({@code workDir}, and {@code branch} when known) so the summary can name the repository; empty when it has none.
     */
    public static String ofSession(String session, String panel, String workDir, String branch, Transcript t, int maxChars) {
        int size = t.size();
        Deque<String> lines = new ArrayDeque<>();
        int used = 0;
        boolean prompt = false;
        for (int i = size - 1; i >= 0; i--) {
            Block b = t.get(i);
            if (b == null || !b.parent().isEmpty()) {
                continue;
            }
            boolean isPrompt = b instanceof Block.UserPrompt;
            if (used >= maxChars && !isPrompt) {
                continue; // past the budget, only the latest prompt is still taken
            }
            String line = line(b);
            if (line.isEmpty()) {
                continue;
            }
            if (used + line.length() > maxChars && (prompt || !isPrompt)) {
                if (prompt) {
                    break;
                }
                used = maxChars;
                continue;
            }
            lines.addFirst(line);
            used += line.length();
            prompt |= isPrompt;
        }
        if (lines.isEmpty()) {
            return "";
        }
        String where = workDir.isBlank() ? "" : ", working in " + workDir + (branch.isBlank() ? "" : " (git branch " + branch + ")");
        return "A coding session named \"" + session + "\"" + where + ", in a panel titled \"" + panel
                + "\". The end of its conversation, oldest first:\n" + String.join("", lines);
    }

    /** The start of a panel's own text, at most {@code maxChars}, with its file when it has one; empty when the text is blank. */
    public static String ofText(String panel, String file, String text, int maxChars) {
        if (text.isBlank()) {
            return "";
        }
        return "A document" + (file.isBlank() ? "" : " " + file) + " in a panel titled \"" + panel + "\". Its text begins:\n"
                + Clip.keepStart(text.strip(), maxChars);
    }

    private static String line(Block b) {
        return switch (b) {
            case Block.UserPrompt p -> p.text().isBlank() ? "" : "User: " + oneLine(p.text(), MAX_PROMPT) + '\n';
            case Block.AssistantText a -> a.text().isBlank() ? "" : "Assistant: " + oneLine(a.text(), MAX_TEXT) + '\n';
            case Block.ToolCall c -> "Tool " + c.name() + ": " + oneLine(c.inputJson(), MAX_TOOL_INPUT) + '\n';
            case Block.Thinking ignored -> "";
            case Block.Note ignored -> "";
            case Block.TurnEnd ignored -> "";
        };
    }

    /** At most {@code max} characters of {@code text} on one line; only the start is read, however long the text. */
    private static String oneLine(String text, int max) {
        int end = Math.min(text.length(), 2 * max);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        String start = text.substring(0, end);
        return Clip.keepStart(WHITESPACE.matcher(start.strip()).replaceAll(" "), max);
    }
}
