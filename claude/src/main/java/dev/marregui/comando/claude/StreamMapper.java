package dev.marregui.comando.claude;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.state.SessionInfo;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns Claude Code's stream-JSON events, or the lines of a stored transcript, into transcript blocks
 * and intents. One mapper per session; it runs on the session's strand, the transcript's only writer.
 *
 * <p>Streaming: a {@code content_block_start} opens a block keyed by message id and index, deltas grow
 * it, and the complete {@code assistant} event for that block replaces it with the final content.
 * Claude Code emits one {@code assistant} event per content block, in index order, so the k-th event of a
 * message closes block k. Tool results arrive in {@code user} events and attach to their call by id.</p>
 */
final class StreamMapper {

    private static final System.Logger LOG = System.getLogger(StreamMapper.class.getName());
    static final int MAX_RESULT_CHARS = 64 * 1024;
    private static final String COMPACT_SUMMARY_PREFIX = "This session is being continued from a previous conversation";

    private final String session;
    private final Transcript transcript;
    private final IntentSink sink;
    private final ObjectMapper json;
    private final Map<String, Integer> toolIndex = new HashMap<>();
    private final Map<String, Integer> streamIndex = new HashMap<>();
    private final Map<String, Integer> closedBlocks = new HashMap<>();
    private String lastModel = "";
    /** The context size: input tokens of the last main-thread API call; -1 until one is seen. */
    private long contextTokens = -1;

    StreamMapper(String session, Transcript transcript, IntentSink sink, ObjectMapper json) {
        this.session = session;
        this.transcript = transcript;
        this.sink = sink;
        this.json = json;
    }

    /** Maps one line of the live stream; returns the event type, empty for a line that is not JSON. */
    String accept(String line) {
        JsonNode e = parse(line);
        if (e == null) {
            return "";
        }
        String type = e.path("type").asText();
        switch (type) {
            case "system" -> system(e);
            case "stream_event" -> streamEvent(e);
            case "assistant" -> assistant(e);
            case "user" -> user(e, false);
            case "result" -> result(e);
            case "rate_limit_event" -> rateLimit(e);
            default -> LOG.log(System.Logger.Level.DEBUG, "event ignored [session=" + session + ", type=" + type + ']');
        }
        return type;
    }

    /** Maps one line of a stored transcript: user and assistant messages, compaction, nothing live. */
    void acceptHistory(String line) {
        JsonNode e = parse(line);
        if (e == null || e.path("isMeta").asBoolean(false)) {
            return;
        }
        switch (e.path("type").asText()) {
            case "assistant" -> assistant(e);
            case "user" -> user(e, true);
            case "system" -> {
                if (e.path("subtype").asText().equals("compact_boundary")) {
                    transcript.append(new Block.Note(Block.NoteKind.COMPACT, "Conversation compacted", ""));
                }
            }
            default -> {
                // titles, snapshots, mode changes and attachments carry nothing to show
            }
        }
    }

    /** Appends the prompt comando sent; called on the strand before the line goes to the process. */
    void prompt(String text, String origin) {
        transcript.append(new Block.UserPrompt(text, origin, ""));
    }

    void note(Block.NoteKind kind, String text) {
        transcript.append(new Block.Note(kind, text, ""));
    }

    /** The result a tool call gets when its process ended, or its stored conversation stopped, before it returned. */
    static final String NO_RESULT = "no result: the session ended before the tool returned";

    /**
     * Ends every tool call still running with an error result: the process that ran it is gone (or a stored
     * conversation stopped there), so no result will come and the call must not read "running" for ever.
     *
     * @return how many calls it ended
     */
    int endRunningTools() {
        int ended = 0;
        for (int i = 0; i < transcript.size(); i++) {
            if (transcript.get(i) instanceof Block.ToolCall call && call.status() == Block.ToolStatus.RUNNING) {
                transcript.replace(i, call.withResult(NO_RESULT, true));
                ended++;
            }
        }
        return ended;
    }

    /** Clears the per-process maps after a restart; the transcript keeps its blocks. */
    void resetStreams() {
        streamIndex.clear();
        closedBlocks.clear();
    }

    /**
     * The transcript was emptied: every index into it is gone. A delta or a result for a block written before arrives
     * to no block and is dropped, or, for a finished message, appended as a block of its own.
     */
    void forgetBlocks() {
        resetStreams();
        toolIndex.clear();
    }

    // ---- system

    private void system(JsonNode e) {
        String subtype = e.path("subtype").asText();
        switch (subtype) {
            case "init" -> init(e);
            case "status" -> {
                String status = e.path("status").isNull() ? "" : e.path("status").asText("");
                sink.dispatch(new Intent.SessionActivity(session, status));
                if (e.path("compact_result").asText().equals("success")) {
                    note(Block.NoteKind.COMPACT, "Compaction finished");
                }
            }
            case "compact_boundary" -> {
                JsonNode m = e.path("compact_metadata");
                note(Block.NoteKind.COMPACT, "Compacted " + m.path("trigger").asText("") + ": "
                        + m.path("pre_tokens").asLong() + " → " + m.path("post_tokens").asLong() + " tokens");
                // the context before compaction no longer applies; 0 means unknown until the next call reports it
                contextTokens = Math.max(0L, m.path("post_tokens").asLong());
            }
            case "hook_response" -> {
                String out = e.path("output").asText("").strip();
                if (!out.isEmpty()) {
                    transcript.append(new Block.Note(Block.NoteKind.HOOK, e.path("hook_name").asText() + ": " + clip(out, 2_000),
                            parent(e)));
                }
            }
            case "api_retry" -> note(Block.NoteKind.RETRY, "API retry " + e.path("attempt").asInt() + "/" + e.path("max_retries").asInt()
                    + " after " + e.path("error_status").asText(e.path("error").asText("error")));
            case "task_started" -> sink.dispatch(new Intent.SessionActivity(session, e.path("description").asText("task")));
            case "task_notification" -> sink.dispatch(new Intent.SessionActivity(session, "requesting"));
            case "background_tasks_changed" -> backgroundTasks(e.path("tasks"));
            case "permission_denied" -> note(Block.NoteKind.DENIED, "Denied: " + e.path("tool_name").asText("tool"));
            default -> LOG.log(System.Logger.Level.DEBUG, "system event ignored [session=" + session + ", subtype=" + subtype + ']');
        }
    }

    /**
     * The background set, whole, as Claude Code sends it at each change (2.1.300: an agent or a shell command left
     * running past its turn; a foreground agent and a background agent's own commands are not in it). A task Claude
     * Code runs by itself ({@code ambient}) is not the session's work.
     */
    private void backgroundTasks(JsonNode tasks) {
        List<BackgroundTask> open = new ArrayList<>();
        for (JsonNode t : tasks) {
            String id = t.path("task_id").asText("");
            if (!id.isEmpty() && !t.path("ambient").asBoolean(false)) {
                open.add(new BackgroundTask(id, t.path("task_type").asText(""), t.path("description").asText(""), 0L));
            }
        }
        sink.dispatch(new Intent.SessionTasksChanged(session, open));
    }

    private void init(JsonNode e) {
        List<String> caps = new ArrayList<>();
        for (JsonNode c : e.path("capabilities")) {
            caps.add(c.asText());
        }
        SessionInfo info = new SessionInfo(
                e.path("session_id").asText(""),
                e.path("model").asText(""),
                e.path("cwd").asText(""),
                e.path("claude_code_version").asText(""),
                e.path("permissionMode").asText(""),
                caps);
        if (!info.model().equals(lastModel)) {
            note(Block.NoteKind.INIT, "Model " + info.model() + " · Claude Code " + info.cliVersion() + " · " + info.permissionMode());
            lastModel = info.model();
        }
        sink.dispatch(new Intent.SessionInit(session, info));
    }

    // ---- streaming

    private void streamEvent(JsonNode e) {
        JsonNode ev = e.path("event");
        String messageId = e.path("api_message_id").asText(ev.path("message").path("id").asText(""));
        int index = ev.path("index").asInt(-1);
        String key = messageId + ':' + index;
        switch (ev.path("type").asText()) {
            case "message_start" -> {
                // the main conversation calls the model: a turn runs, also one Claude Code started itself. Claude Code
                // 2.1.300 forwards no subagent's stream events and sends a null parent on these; one forwarded with its
                // parent call would be a subagent's, inside a turn or in the background, and start none
                if (parent(e).isEmpty()) {
                    sink.dispatch(new Intent.SessionTurnRunning(session));
                }
            }
            case "content_block_start" -> {
                JsonNode block = ev.path("content_block");
                String parent = parent(e);
                switch (block.path("type").asText()) {
                    case "text" -> streamIndex.put(key, transcript.append(new Block.AssistantText(messageId, index, "", true, parent)));
                    case "thinking" -> streamIndex.put(key, transcript.append(new Block.Thinking(messageId, index, "", 0L, true, parent)));
                    case "tool_use" -> {
                        String id = block.path("id").asText();
                        int at = transcript.append(new Block.ToolCall(id, block.path("name").asText(), "{}", "", Block.ToolStatus.RUNNING, parent));
                        streamIndex.put(key, at);
                        toolIndex.put(id, at);
                    }
                    default -> {
                        // server tool blocks and others appear in the final message
                    }
                }
            }
            case "content_block_delta" -> {
                Integer at = streamIndex.get(key);
                if (at == null) {
                    return;
                }
                JsonNode delta = ev.path("delta");
                Block current = transcript.get(at);
                switch (delta.path("type").asText()) {
                    case "text_delta" -> {
                        if (current instanceof Block.AssistantText t) {
                            transcript.replace(at, t.appended(delta.path("text").asText("")));
                        }
                    }
                    case "thinking_delta" -> {
                        if (current instanceof Block.Thinking t) {
                            transcript.replace(at, new Block.Thinking(t.messageId(), t.index(), t.text() + delta.path("thinking").asText(""),
                                    Math.max(t.tokens(), delta.path("estimated_tokens").asLong(0)), true, t.parent()));
                        }
                    }
                    default -> {
                        // tool input arrives whole in the final message
                    }
                }
            }
            default -> {
                // message_start, message_delta, message_stop, content_block_stop carry nothing new to show
            }
        }
    }

    // ---- complete messages

    private void assistant(JsonNode e) {
        JsonNode message = e.path("message");
        String messageId = message.path("id").asText("");
        String parent = parent(e);
        JsonNode usage = message.path("usage");
        if (!usage.isMissingNode() && parent.isEmpty()) {
            long tokens = usage.path("input_tokens").asLong() + usage.path("cache_creation_input_tokens").asLong()
                    + usage.path("cache_read_input_tokens").asLong();
            if (tokens > 0) {
                contextTokens = tokens;
            }
        }
        for (JsonNode block : message.path("content")) {
            int index = closedBlocks.merge(messageId, 1, Integer::sum) - 1;
            Integer at = streamIndex.remove(messageId + ':' + index);
            switch (block.path("type").asText()) {
                case "text" -> {
                    Block.AssistantText text = new Block.AssistantText(messageId, index, block.path("text").asText(""), false, parent);
                    put(at, text);
                }
                case "thinking", "redacted_thinking" -> {
                    long tokens = at != null && transcript.get(at) instanceof Block.Thinking t ? t.tokens() : 0L;
                    put(at, new Block.Thinking(messageId, index, block.path("thinking").asText(""), tokens, false, parent));
                }
                case "tool_use", "server_tool_use" -> {
                    String id = block.path("id").asText();
                    String input = block.path("input").toString();
                    Integer known = toolIndex.get(id);
                    if (known != null && transcript.get(known) instanceof Block.ToolCall call) {
                        transcript.replace(known, call.withInput(input));
                    } else {
                        toolIndex.put(id, transcript.append(new Block.ToolCall(id, block.path("name").asText(), input, "",
                                Block.ToolStatus.RUNNING, parent)));
                    }
                }
                default -> {
                    // web search results and other server blocks are not shown
                }
            }
        }
    }

    private void put(Integer at, Block block) {
        if (at != null) {
            transcript.replace(at, block);
        } else {
            transcript.append(block);
        }
    }

    private void user(JsonNode e, boolean history) {
        JsonNode content = e.path("message").path("content");
        String parent = parent(e);
        if (content.isTextual()) {
            userText(content.asText(), parent, history);
            return;
        }
        for (JsonNode block : content) {
            switch (block.path("type").asText()) {
                case "tool_result" -> toolResult(block);
                case "text" -> userText(block.path("text").asText(""), parent, history);
                default -> {
                    // images and documents are not shown
                }
            }
        }
    }

    private void userText(String text, String parent, boolean history) {
        String t = text.strip();
        if (t.isEmpty()) {
            return;
        }
        if (t.startsWith(COMPACT_SUMMARY_PREFIX)) {
            transcript.append(new Block.Note(Block.NoteKind.COMPACT, "Conversation continued from a summary", parent));
        } else if (t.startsWith("<local-command-stdout>")) {
            String out = t.replace("<local-command-stdout>", "").replace("</local-command-stdout>", "").strip();
            transcript.append(new Block.Note(Block.NoteKind.COMMAND, out.isEmpty() ? "command done" : clip(out, 2_000), parent));
        } else if (t.startsWith("[Request interrupted")) {
            transcript.append(new Block.Note(Block.NoteKind.INTERRUPT, "Interrupted", parent));
        } else if (t.startsWith("<command-name>") || t.startsWith("<local-command-caveat>") || t.startsWith("<system-reminder>")) {
            if (history && t.startsWith("<command-name>")) {
                transcript.append(new Block.Note(Block.NoteKind.COMMAND, commandName(t), parent));
            }
        } else if (history || !parent.isEmpty()) {
            transcript.append(new Block.UserPrompt(t, parent.isEmpty() ? "" : "subagent", parent));
        }
        // a live main-conversation prompt was appended by prompt() when comando sent it
    }

    private void toolResult(JsonNode block) {
        String id = block.path("tool_use_id").asText();
        Integer at = toolIndex.get(id);
        if (at == null || !(transcript.get(at) instanceof Block.ToolCall call)) {
            return;
        }
        JsonNode content = block.path("content");
        String text;
        if (content.isTextual()) {
            text = content.asText();
        } else {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part.path("type").asText().equals("text")) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(part.path("text").asText(""));
                }
            }
            text = sb.toString();
        }
        transcript.replace(at, call.withResult(clip(text, MAX_RESULT_CHARS), block.path("is_error").asBoolean(false)));
    }

    // ---- result

    private void result(JsonNode e) {
        boolean error = e.path("is_error").asBoolean(false);
        String subtype = e.path("subtype").asText("");
        String message = error ? errorText(e) : "";
        // result.usage sums every API call of the turn; the context is the last call's input
        long tokens = contextTokens;
        transcript.append(new Block.TurnEnd(e.path("total_cost_usd").asDouble(), e.path("duration_ms").asLong(),
                e.path("num_turns").asInt(), error, message.isEmpty() ? subtype : message));
        long window = 0;
        for (JsonNode model : e.path("modelUsage")) {
            window = Math.max(window, model.path("contextWindow").asLong());
        }
        sink.dispatch(new Intent.SessionTurnEnded(session, e.path("queued_turn_count").asInt(0), e.path("total_cost_usd").asDouble(),
                tokens, window, error, message));
        ClaudeTurnEvent turn = new ClaudeTurnEvent();
        if (turn.shouldCommit()) {
            turn.session = session;
            turn.turnMillis = e.path("duration_ms").asLong();
            turn.costUsd = e.path("total_cost_usd").asDouble();
            turn.contextTokens = tokens;
            turn.error = error;
            turn.commit();
        }
    }

    private static String errorText(JsonNode e) {
        if (e.path("terminal_reason").asText().equals("aborted_tools") || e.path("subtype").asText().equals("error_during_execution")) {
            return "interrupted";
        }
        String r = e.path("result").asText("");
        if (!r.isEmpty()) {
            return clip(r, 500);
        }
        StringBuilder sb = new StringBuilder(e.path("subtype").asText("error"));
        for (JsonNode x : e.path("errors")) {
            sb.append("; ").append(x.asText());
        }
        return clip(sb.toString(), 500);
    }

    private void rateLimit(JsonNode e) {
        JsonNode info = e.path("rate_limit_info");
        String status = info.path("status").asText("allowed");
        if (!status.equals("allowed")) {
            note(Block.NoteKind.INFO, "Rate limit " + status + " (" + info.path("rateLimitType").asText("") + ")");
        }
    }

    // ---- helpers

    private JsonNode parse(String line) {
        try {
            return json.readTree(line);
        } catch (JsonProcessingException ex) {
            LOG.log(System.Logger.Level.WARNING, "not JSON, ignored [session=" + session + ", line=" + clip(line, 200) + ']');
            return null;
        }
    }

    private static String parent(JsonNode e) {
        JsonNode p = e.path("parent_tool_use_id");
        return p.isTextual() ? p.asText() : "";
    }

    private static String commandName(String t) {
        int a = t.indexOf("<command-name>") + "<command-name>".length();
        int b = t.indexOf("</command-name>");
        String name = b > a ? t.substring(a, b) : "command";
        int c = t.indexOf("<command-args>");
        int d = t.indexOf("</command-args>");
        String args = c >= 0 && d > c ? t.substring(c + "<command-args>".length(), d).strip() : "";
        return args.isEmpty() ? name : name + ' ' + args;
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "… [" + (s.length() - max) + " more characters]";
    }
}
