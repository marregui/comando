package dev.marregui.comando.core.transcript;

import java.util.Objects;

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

        public AssistantText appended(String delta) {
            return new AssistantText(messageId, index, text + delta, true, parent);
        }

        public AssistantText finished(String finalText) {
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

        public ToolCall withInput(String json) {
            return new ToolCall(toolUseId, name, json, resultText, status, parent);
        }

        public ToolCall withResult(String text, boolean error) {
            return new ToolCall(toolUseId, name, inputJson, text, error ? ToolStatus.ERROR : ToolStatus.DONE, parent);
        }

        public boolean isSubagent() {
            return name.equals("Agent") || name.equals("Task");
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

    /** The {@code result} that ended a turn. */
    record TurnEnd(double costUsd, long durationMillis, int numTurns, boolean error, String text) implements Block {
        public TurnEnd {
            Objects.requireNonNull(text, "text");
        }

        @Override
        public String parent() {
            return "";
        }
    }
}
