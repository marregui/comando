package dev.marregui.comando.core.transcript;

import dev.marregui.comando.core.view.Clip;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text a panel's topic is read from: the end of its session's conversation, or the start of its own text. Only
 * the main conversation counts (sub-agents' blocks are their parent's detail), without thinking or notes; the latest
 * prompt is always kept, since it names what the session was asked to do. Empty when there is nothing to read.
 */
public final class TopicSource {

    /**
     * At most this much of a conversation or a text is read, about 500 tokens: a card's summary is to come back within
     * 3 s. Measured 2026-10-22 with Claude Code 2.1.308 (Haiku, the whole command): about 4 200 characters in took 2.6 to
     * 4.5 s, 2 000 a median of 2.0 s; 1 200 was no faster.
     */
    public static final int MAX_CHARS = 2_000;
    static final int MAX_PROMPT = 800;
    static final int MAX_TEXT = 600;
    static final int MAX_TOOL_INPUT = 160;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TopicSource() {
    }

    /**
     * The end of {@code session}'s conversation, newest last, at most {@code maxChars}, headed by where the session works
     * ({@code workDir}, and {@code branch} when known) so the summary can name the repository, and by its panel's title,
     * {@code panel}, unless blank (a headless session); empty when it has none.
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
        String in = panel.isBlank() ? "" : ", in a panel titled \"" + panel + "\"";
        return "A coding session named \"" + session + "\"" + where + in + ". The end of its conversation, oldest first:\n"
                + String.join("", lines);
    }

    /** The most recent paragraph of a session panel's text read whole up to this; a longer one keeps its start. */
    static final int MAX_LAST = 600;
    /** The session's last response read up to this, its start kept: a report puts its result first. */
    static final int MAX_RESPONSE = 700;
    /** How far back a conversation is searched for its last response. */
    static final int RESPONSE_BLOCKS = 500;
    private static final Pattern BLANK_LINE = Pattern.compile("\\n[ \\t]*\\n");

    /**
     * A session panel's own text, where the user writes what the session is asked, at most {@code maxChars}: its last
     * paragraph, the command being worked on now, which the summary is about; the session's last {@code response}, read
     * with that command; then the text before it, its end kept, for context. Headed by where the session works
     * ({@code workDir}, and {@code branch} when known) so the summary can name the repository. Empty when the text is
     * blank.
     */
    public static String ofPanel(String panel, String session, String workDir, String branch, String text, String response, int maxChars) {
        String t = text.strip();
        if (t.isEmpty()) {
            return "";
        }
        Matcher m = BLANK_LINE.matcher(t);
        int cut = 0;
        while (m.find()) {
            cut = m.end();
        }
        String last = Clip.keepStart(t.substring(cut).strip(), Math.min(MAX_LAST, maxChars));
        String before = t.substring(0, cut).strip();
        String where = workDir.isBlank() ? "" : ", working in " + workDir + (branch.isBlank() ? "" : " (git branch " + branch + ")");
        StringBuilder sb = new StringBuilder("The panel \"").append(panel).append("\", where the user writes the prompts of a coding session named \"")
                .append(session).append('"').append(where).append(".\nIts most recent paragraph, the command being worked on now, which the summary is about:\n")
                .append(last);
        int room = maxChars - last.length();
        String answer = response.strip();
        if (!answer.isEmpty() && room > 0) {
            String kept = Clip.keepStart(answer, Math.min(MAX_RESPONSE, room));
            sb.append("\n\nThe session's last response, to read with that command:\n").append(kept);
            room -= kept.length();
        }
        if (!before.isEmpty() && room > 0) {
            sb.append("\n\nThe text before it, for context, oldest first:\n").append(Clip.keepEnd(before, room));
        }
        return sb.toString();
    }

    /** The text of the main conversation's latest response from the session; empty when it has none. */
    public static String lastResponse(Transcript t) {
        for (int i = t.size() - 1, last = Math.max(0, t.size() - RESPONSE_BLOCKS); i >= last; i--) {
            if (t.get(i) instanceof Block.AssistantText a && a.parent().isEmpty() && !a.text().isBlank()) {
                return a.text();
            }
        }
        return "";
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
