package dev.marregui.comando.claude.codex;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.json.ArrayNode;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.json.ObjectNode;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;

/**
 * Turns one Codex session's app-server traffic into the transcript blocks, intents and metrics a Claude session makes
 * (ADR 0197 §5). One Codex item is one block: {@code item/started} opens it, its deltas grow it and
 * {@code item/completed}, which carries the whole item, replaces it. One mapper per session, called on the session's
 * strand in the adapter, the transcript's only writer.
 */
public final class CodexMapper implements CodexEvents {

    /**
     * Not a Codex method: what the adapter sends after a stored turn's items, {@code params.turn} the stored turn. It
     * ends the turn in the transcript without a turn event or an intent, as the turn ended long ago.
     */
    static final String HISTORY_TURN_ENDED = "comando/historyTurnEnded";
    private static final System.Logger LOG = System.getLogger(CodexMapper.class.getName());
    /** A tool's result is clipped where a Claude session's is. */
    private static final int MAX_RESULT_CHARS = 64 * 1024;
    /** The result a tool call gets when its process ended before it returned, as for a Claude session. */
    private static final String NO_RESULT = "no result: the session ended before the tool returned";

    /** Each open item's block in the transcript, by the item's id; emptied as each turn starts. */
    private final Map<String, Integer> items = new HashMap<>();
    private final String session;
    private final IntentSink sink;
    private final Transcript transcript;
    /** Each sub-agent's spawn call id, by the sub-agent's thread id; it outlives the turn that spawned it. */
    private final Map<String, String> agents = new HashMap<>();
    /** The context size: input tokens of the thread's last model call; 0 until one is reported. */
    private long contextTokens;
    /** The model's context window, as the last token report gave it; 0 when unknown. */
    private long contextWindow;
    /** The thread's id: what the session resumes and a metric names. */
    private String conversation = "";
    private String effort = "";
    /** The running turn's last answer, empty until an assistant message completes. */
    private String lastAnswer = "";
    /** Where each turn's {@code turn} event goes (record 110); nothing by default. */
    private Consumer<ObjectNode> metrics = _ -> { };
    private String model = "";
    /** What the running turn's model calls used, summed from each call's own report. */
    private long turnCachedTokens;
    private long turnCacheWriteTokens;
    private long turnInputTokens;
    /** The running turn's model calls: one token report each. */
    private int turnModelCalls;
    private long turnOutputTokens;
    private long turnReasoningTokens;
    private boolean turnRunning;
    /** When the running turn started, {@link System#nanoTime()}; a turn the process ends is timed from it. */
    private long turnStartedNanos;

    public CodexMapper(final String session, final Transcript transcript, final IntentSink sink) {
        this.session = session;
        this.transcript = transcript;
        this.sink = sink;
    }

    @Override
    public void exited(final String error) {
        endRunningTools();
        items.clear();
        if (!turnRunning && error.isEmpty()) {
            return;
        }
        final String message = error.isEmpty() ? "the session ended during the turn" : clip(error, 500);
        final long durationMs = turnRunning ? (System.nanoTime() - turnStartedNanos) / 1_000_000L : 0L;
        turnRunning = false;
        transcript.append(new Block.TurnEnd(0, durationMs, turnModelCalls, true, message)
                .at(System.currentTimeMillis()));
        sink.dispatch(new Intent.SessionTurnEnded(session, 0, 0, contextTokens, contextWindow, true, message));
    }

    /**
     * Where each turn's {@code turn} event goes (record 110): the fields of a Claude session's event that Codex has,
     * for the receiver to stamp and keep.
     */
    public void metrics(final Consumer<ObjectNode> turnEvents) {
        metrics = turnEvents;
    }

    @Override
    public void note(final String text) {
        transcript.append(new Block.Note(Block.NoteKind.INFO, text, ""));
    }

    @Override
    public void notification(final String method, final ObjectNode params) {
        switch (method) {
            case "turn/started" -> turnStarted();
            case "item/started" -> item(params.path("item"), false);
            case "item/completed" -> item(params.path("item"), true);
            case "item/agentMessage/delta" -> textDelta(params);
            case "item/reasoning/summaryTextDelta" -> thinkingDelta(params);
            case "thread/tokenUsage/updated" -> tokenUsage(params.path("tokenUsage"));
            case "turn/completed" -> turnCompleted(params.path("turn"));
            case "error" -> error(params);
            case HISTORY_TURN_ENDED -> historyTurnEnded(params.path("turn"));
            default -> LOG.log(System.Logger.Level.DEBUG,
                    "notification ignored [session=" + session + ", method=" + method + ']');
        }
    }

    @Override
    public void prompt(final String text, final String origin, final boolean delivered) {
        transcript.append(new Block.UserPrompt(text, origin, "", System.currentTimeMillis(), delivered));
    }

    @Override
    public void threadStarted(final String threadId, final String model, final String effort) {
        if (!conversation.isEmpty() && !conversation.equals(threadId)) {
            contextTokens = 0; // a new thread (/clear) starts with an empty context
            lastAnswer = "";
            agents.clear();
        }
        conversation = threadId;
        if (!model.equals(this.model)) {
            final String text = "Model " + model + " · Codex · effort " + effort;
            transcript.append(new Block.Note(Block.NoteKind.INIT, text, ""));
        }
        this.model = model;
        this.effort = effort;
        // the adapter dispatches SessionInit with the thread's folder and policy; this adds what ran
        sink.dispatch(new Intent.SessionRanAt(session, model, effort, true));
    }

    /** A sub-agent's name: its path below the root agent, {@code /root/pong} shown as {@code pong}. */
    static String agentName(final JsonNode item) {
        final String path = item.path("agentPath").asString("");
        final String name = path.startsWith("/root/") ? path.substring("/root/".length()) : path;
        return name.isEmpty() ? item.path("agentThreadId").asString("sub-agent") : name;
    }

    private static String clip(final String s, final int max) {
        return s.length() <= max ? s : s.substring(0, max) + "… [" + (s.length() - max) + " more characters]";
    }

    /** The text parts of an MCP tool's result, one per line. */
    private static String mcpText(final JsonNode result) {
        final StringBuilder sb = new StringBuilder();
        for (final JsonNode part : result.path("content")) {
            if (part.path("type").asString("").equals("text")) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(part.path("text").asString(""));
            }
        }
        return sb.toString();
    }

    /** A reasoning item's summary, else its raw text, the parts a blank line apart. */
    private static String reasoningText(final JsonNode item) {
        final JsonNode summary = item.path("summary");
        final JsonNode parts = summary.isArray() && !summary.isEmpty() ? summary : item.path("content");
        final StringBuilder sb = new StringBuilder();
        for (final JsonNode part : parts) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(part.asString(""));
        }
        return sb.toString();
    }

    private void agentMessage(final String id, final JsonNode item, final boolean completed) {
        final String text = item.path("text").asString("");
        final Integer at = items.get(id);
        if (!completed) {
            if (at == null) {
                items.put(id, transcript.append(new Block.AssistantText(id, 0, text, true, "")));
            }
            return;
        }
        lastAnswer = text;
        put(at, new Block.AssistantText(id, 0, text, false, ""));
    }

    private void commandExecution(final String id, final JsonNode item, final boolean completed) {
        final ObjectNode input = Json.object();
        input.put("command", item.path("command").asString(""));
        input.put("cwd", item.path("cwd").asString(""));
        final JsonNode exitCode = item.path("exitCode");
        final boolean ok = exitCode.isNumber() ? exitCode.asInt(-1) == 0
                : item.path("status").asString("").equals("completed");
        tool(id, "Bash", input, completed, item.path("aggregatedOutput").asString(""), !ok);
    }

    /** Ends every tool call still running with an error result: the process that ran it is gone. */
    private void endRunningTools() {
        for (int i = 0; i < transcript.size(); i++) {
            if (transcript.get(i) instanceof Block.ToolCall call && call.status() == Block.ToolStatus.RUNNING) {
                transcript.replace(i, call.withResult(NO_RESULT, true));
            }
        }
    }

    /** An {@code error} notification; one Codex retries leaves the turn running and shows nothing. */
    private void error(final ObjectNode params) {
        final String message = params.path("error").path("message").asString("");
        if (params.path("willRetry").asBoolean(false)) {
            LOG.log(System.Logger.Level.DEBUG, "error retried [session=" + session + ", message=" + message + ']');
            return;
        }
        transcript.append(new Block.Note(Block.NoteKind.ERROR, "Error: " + clip(message, 2_000), ""));
    }

    private void fileChange(final String id, final JsonNode item, final boolean completed) {
        final ObjectNode input = Json.object();
        final ArrayNode changes = input.putArray("changes");
        String first = "";
        for (final JsonNode change : item.path("changes")) {
            final String path = change.path("path").asString("");
            first = first.isEmpty() ? path : first;
            changes.addObject().put("path", path).put("kind", change.path("kind").path("type").asString(""))
                    .put("diff", change.path("diff").asString(""));
        }
        input.put("file_path", first);
        final String status = item.path("status").asString("");
        tool(id, "Edit", input, completed, status, !status.equals("completed"));
    }

    /** A stored turn's end: its block, as {@link #turnCompleted} writes it, without the intent or the metric. */
    private void historyTurnEnded(final JsonNode turn) {
        final String status = turn.path("status").asString("");
        final boolean error = !status.equals("completed");
        final String message = error ? turn.path("error").path("message").asString(status) : status;
        final long completedSeconds = turn.path("completedAt").asLong(0);
        items.clear();
        transcript.append(new Block.TurnEnd(0, turn.path("durationMs").asLong(0), 1, error, clip(message, 500))
                .at(completedSeconds > 0 ? completedSeconds * 1_000L : System.currentTimeMillis()));
    }

    private void item(final JsonNode item, final boolean completed) {
        final String id = item.path("id").asString("");
        switch (item.path("type").asString("")) {
            case "agentMessage" -> agentMessage(id, item, completed);
            case "reasoning" -> reasoning(id, item, completed);
            case "commandExecution" -> commandExecution(id, item, completed);
            case "fileChange" -> fileChange(id, item, completed);
            case "mcpToolCall" -> mcpToolCall(id, item, completed);
            case "subAgentActivity" -> subAgent(id, item);
            case "contextCompaction" -> {
                if (completed) {
                    transcript.append(new Block.Note(Block.NoteKind.COMPACT, "Conversation compacted", ""));
                }
            }
            default -> {
                // the prompt is already shown; plans, searches and other items have no block yet
            }
        }
    }

    /**
     * A sub-agent shows as Claude's do, an {@code Agent} call under the spawn call's id, running from its
     * {@code started} activity until its {@code completed} or {@code interrupted} one (ADR 0197).
     */
    private void subAgent(final String id, final JsonNode item) {
        final String thread = item.path("agentThreadId").asString("");
        if (thread.isEmpty()) {
            return;
        }
        switch (item.path("kind").asString("")) {
            case "started" -> {
                agents.putIfAbsent(thread, id);
                if (agentCall(agents.get(thread)) < 0) { // also when a cleared transcript is read again
                    final ObjectNode input = Json.object().put("description", agentName(item))
                            .put("subagent_type", "codex").put("model", item.path("model").asString(""));
                    transcript.append(new Block.ToolCall(agents.get(thread), "Agent", Json.write(input), "",
                            Block.ToolStatus.RUNNING, ""));
                }
            }
            case "completed", "interrupted" -> {
                final int at = agentCall(agents.getOrDefault(thread, ""));
                if (at >= 0 && transcript.get(at) instanceof Block.ToolCall call) {
                    final boolean interrupted = "interrupted".equals(item.path("kind").asString(""));
                    transcript.replace(at, call.withResult("Sub-agent " + agentName(item)
                            + (interrupted ? " interrupted" : " completed"), interrupted));
                }
            }
            default -> {
                // "interacted": the call shows no change
            }
        }
    }

    /** Where the {@code Agent} call {@code callId} is in the transcript, -1 when it is not (cleared, never shown). */
    private int agentCall(final String callId) {
        for (int i = transcript.size() - 1; i >= 0; i--) {
            if (transcript.get(i) instanceof Block.ToolCall call && call.toolUseId().equals(callId)) {
                return i;
            }
        }
        return -1;
    }

    private void mcpToolCall(final String id, final JsonNode item, final boolean completed) {
        final String name = "mcp__" + item.path("server").asString("") + "__" + item.path("tool").asString("");
        final JsonNode arguments = item.path("arguments");
        final ObjectNode input = arguments instanceof ObjectNode args ? args.deepCopy() : Json.object();
        final JsonNode error = item.path("error");
        final boolean failed = error.isObject() || item.path("status").asString("").equals("failed");
        final String result = error.isObject() ? error.path("message").asString("") : mcpText(item.path("result"));
        tool(id, name, input, completed, result, failed);
    }

    private void put(final Integer at, final Block block) {
        if (at != null) {
            transcript.replace(at, block);
        } else {
            transcript.append(block);
        }
    }

    private void reasoning(final String id, final JsonNode item, final boolean completed) {
        final Integer at = items.get(id);
        if (!completed) {
            if (at == null) {
                items.put(id, transcript.append(new Block.Thinking(id, 0, reasoningText(item), 0L, true, "")));
            }
            return;
        }
        put(at, new Block.Thinking(id, 0, reasoningText(item), 0L, false, ""));
    }

    private void textDelta(final ObjectNode params) {
        final String id = params.path("itemId").asString("");
        final String delta = params.path("delta").asString("");
        final Integer at = items.get(id);
        if (at == null) {
            items.put(id, transcript.append(new Block.AssistantText(id, 0, delta, true, "")));
        } else if (transcript.get(at) instanceof Block.AssistantText t) {
            transcript.replace(at, t.appended(delta));
        }
    }

    private void thinkingDelta(final ObjectNode params) {
        final String id = params.path("itemId").asString("");
        final String delta = params.path("delta").asString("");
        final Integer at = items.get(id);
        if (at == null) {
            items.put(id, transcript.append(new Block.Thinking(id, 0, delta, 0L, true, "")));
        } else if (transcript.get(at) instanceof Block.Thinking(String messageId, int index, String text,
                long tokens, boolean _, String parent)) {
            transcript.replace(at, new Block.Thinking(messageId, index, text + delta, tokens, true, parent));
        }
    }

    /**
     * A tool item: {@code item/started} opens its call, running; {@code item/completed} gives it its whole input and
     * its result.
     */
    private void tool(final String id, final String name, final ObjectNode input, final boolean completed,
            final String result, final boolean failed) {
        final Integer at = items.get(id);
        final Block.ToolCall call = at != null && transcript.get(at) instanceof Block.ToolCall known
                ? known.withInput(Json.write(input))
                : new Block.ToolCall(id, name, Json.write(input), "", Block.ToolStatus.RUNNING, "");
        if (!completed) {
            if (at == null) {
                items.put(id, transcript.append(call));
            }
            return;
        }
        put(at, call.withResult(clip(result, MAX_RESULT_CHARS), failed));
    }

    /**
     * A model call's tokens: {@code last} is the call's own, {@code total} the thread's since it started, so a turn's
     * use is the sum of its calls' and holds across a resume.
     */
    private void tokenUsage(final JsonNode usage) {
        final JsonNode last = usage.path("last");
        final long input = last.path("inputTokens").asLong(0);
        turnModelCalls++;
        turnInputTokens += input;
        turnCachedTokens += last.path("cachedInputTokens").asLong(0);
        turnCacheWriteTokens += last.path("cacheWriteInputTokens").asLong(0);
        turnOutputTokens += last.path("outputTokens").asLong(0);
        turnReasoningTokens += last.path("reasoningOutputTokens").asLong(0);
        final long window = usage.path("modelContextWindow").asLong(0);
        if (window > 0) {
            contextWindow = window;
        }
        if (input > 0) {
            contextTokens = input;
            sink.dispatch(new Intent.SessionContext(session, input, contextWindow));
        }
    }

    private void turnCompleted(final JsonNode turn) {
        final String status = turn.path("status").asString("");
        final boolean error = !status.equals("completed");
        final String message = switch (status) {
            case "completed" -> "";
            case "interrupted" -> "interrupted";
            default -> clip(turn.path("error").path("message").asString(status.isEmpty() ? "failed" : status), 500);
        };
        final long durationMs = turn.path("durationMs").asLong(0);
        turnRunning = false;
        items.clear();
        // a completed turn made at least one model call, though no token report came for it
        final int turns = error ? turnModelCalls : Math.max(1, turnModelCalls);
        transcript.append(new Block.TurnEnd(0, durationMs, turns, error, error ? message : status)
                .at(System.currentTimeMillis()));
        sink.dispatch(new Intent.SessionTurnEnded(session, 0, 0, contextTokens, contextWindow, error, message,
                error ? "" : lastAnswer, false));
        turnMetric(durationMs, error, status);
    }

    /**
     * A turn ended: its {@code turn} event, as a Claude session's (record 110). Codex reports tokens with the cached
     * input inside the input; the event keeps Claude's split, the input read fresh apart from the input read from the
     * cache. A subscription turn has no price, so its cost is 0.
     */
    private void turnMetric(final long durationMs, final boolean error, final String status) {
        final ObjectNode m = Json.object();
        m.put("session", session);
        m.put("conversation", conversation);
        m.put("model", model);
        m.put("effort", effort);
        m.put("duration_ms", durationMs);
        m.put("context", contextTokens);
        m.put("window", contextWindow);
        m.put("error", error);
        m.put("subtype", status);
        final ObjectNode usage = m.putObject("usage");
        usage.put("input_tokens", Math.max(0L, turnInputTokens - turnCachedTokens));
        usage.put("cache_read_input_tokens", turnCachedTokens);
        usage.put("cache_creation_input_tokens", turnCacheWriteTokens);
        usage.put("output_tokens", turnOutputTokens);
        usage.putObject("output_tokens_details").put("thinking_tokens", turnReasoningTokens);
        m.put("cost_usd", 0.0);
        metrics.accept(m);
    }

    private void turnStarted() {
        turnRunning = true;
        turnStartedNanos = System.nanoTime();
        turnInputTokens = 0L;
        turnModelCalls = 0;
        turnCachedTokens = 0L;
        turnCacheWriteTokens = 0L;
        turnOutputTokens = 0L;
        turnReasoningTokens = 0L;
        lastAnswer = "";
        items.clear();
        sink.dispatch(new Intent.SessionTurnRunning(session));
    }
}
