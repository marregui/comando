package dev.marregui.comando.core.transcript;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One entry of a session transcript. {@code parent} is the id of the Agent or Skill tool call that started
 * the subagent the block belongs to, empty for the main conversation.
 */
public sealed interface Block {

    String parent();

    /** A prompt sent to the session; {@code origin} names the panel, empty for history. */
    record UserPrompt(String text, String origin, String parent) implements Block {
        public UserPrompt {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(parent, "parent");
        }
    }

    /** Assistant text; {@code streaming} while deltas still arrive. */
    record AssistantText(String messageId, int index, String text, boolean streaming, String parent) implements Block {
        public AssistantText {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(parent, "parent");
        }

        public AssistantText appended(final String delta) {
            return new AssistantText(messageId, index, text + delta, true, parent);
        }

        public AssistantText finished(final String finalText) {
            return new AssistantText(messageId, index, finalText, false, parent);
        }
    }

    /** Thinking; the text is empty when the model's thinking is not displayed. */
    record Thinking(String messageId, int index, String text, long tokens, boolean streaming, String parent) implements Block {
        public Thinking {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(parent, "parent");
        }
    }

    enum ToolStatus { RUNNING, DONE, ERROR }

    /** A tool call and, once it arrives, its result. */
    record ToolCall(String toolUseId, String name, String inputJson, String resultText, ToolStatus status, String parent) implements Block {
        public ToolCall {
            Objects.requireNonNull(toolUseId, "toolUseId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(inputJson, "inputJson");
            Objects.requireNonNull(resultText, "resultText");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(parent, "parent");
        }

        public ToolCall withInput(final String json) {
            return new ToolCall(toolUseId, name, json, resultText, status, parent);
        }

        public ToolCall withResult(final String text, final boolean error) {
            return new ToolCall(toolUseId, name, inputJson, text, error ? ToolStatus.ERROR : ToolStatus.DONE, parent);
        }

        public boolean isSubagent() {
            return name.equals("Agent") || name.equals("Task");
        }

        /** An Agent call's result that launched its sub-agent into the background, with the sub-agent's id. */
        private static final Pattern LAUNCHED = Pattern.compile("(?s)\\s*Async agent launched\\b.*?agentId: ([A-Za-z0-9_-]+)");

        /**
         * The id of the sub-agent an Agent call's {@code result} launched into the background ("Async agent launched
         * successfully. … agentId: a58…", Claude Code 2.1.302, undocumented); empty for any other result. Only the
         * launch's own text counts, never a report that quotes one.
         */
        public static String launchedAgent(final String result) {
            final Matcher m = LAUNCHED.matcher(result);
            return m.lookingAt() ? m.group(1) : "";
        }
    }

    enum NoteKind { INIT, COMPACT, HOOK, RETRY, DENIED, INTERRUPT, STATUS, ERROR, COMMAND, INFO }

    /** A system event worth showing: init, compaction, hook output, a retry, a denial. */
    record Note(NoteKind kind, String text, String parent) implements Block {
        public Note {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(parent, "parent");
        }
    }

    /**
     * The {@code result} that ended a turn; or, read from a stored conversation, the end of a turn without its numbers.
     */
    record TurnEnd(double costUsd, long durationMillis, int numTurns, boolean error, String text) implements Block {
        /** The duration of a turn read from a stored conversation, which records none. */
        public static final long UNTIMED = -1L;

        public TurnEnd {
            Objects.requireNonNull(text, "text");
        }

        /** A turn of a stored conversation that ended: its last message stopped at the end of the turn. */
        public static TurnEnd stored() {
            return new TurnEnd(-1.0, UNTIMED, 0, false, "");
        }

        /** False for a turn read from a stored conversation, with no time, cost or count. */
        public boolean timed() {
            return durationMillis != UNTIMED;
        }

        @Override
        public String parent() {
            return "";
        }
    }
}
