package dev.marregui.comando.claude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.state.SessionInfo;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;

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
    /** The conversation's id, from its {@code system/init}: what a metric names. */
    private String conversation = "";
    /** Where the turns' and sub-agents' metrics go (record 110); nothing by default. */
    private MetricsLog metrics = MetricsLog.NONE;
    /** Hears a sub-agent's end, live: the adapter adds what its file says it spent, and keeps it. */
    private Consumer<ObjectNode> agentEnds = e -> { };
    /** When each live Agent call started, by its id; gone at its end. */
    private final Map<String, Long> agentStarted = new ConcurrentHashMap<>();
    /** While a stored conversation is read: nothing it says is a metric. */
    private boolean replaying;
    /** The context size: input tokens of the last main-thread API call; -1 until one is seen. */
    private long contextTokens = -1;
    /**
     * The context window the last turn reported, or the one the stored conversation's last turn kept; 0 when unknown.
     */
    private long contextWindow;
    /** While a stored conversation is read: the message that ended its turn, until its last line is read; else null. */
    private String turnEndingMessage;
    /**
     * The Agent calls whose sub-agent ended: a foreground one by its call's result, a background one by its task's
     * notification. Written on the strand, read by the sub-agent thread.
     */
    private final Set<String> endedAgents = ConcurrentHashMap.newKeySet();
    /** How long a message's model and effort wait between reads of the transcript, and how long in all. */
    static final long RAN_RETRY_MILLIS = 100;
    static final long RAN_GIVE_UP_MILLIS = 2_000;
    /** The base effort Claude Code's settings give a model id; read at each {@code system/init}. */
    private Function<String, String> effortOf = model -> "";
    /** What a message of a conversation ran at, from its transcript; none until {@link #ranFrom} names a reader. */
    private BiFunction<String, String, Optional<RunSettings.Ran>> ranOf = (conversationId, messageId) -> Optional.empty();
    /** Runs a read again on the mapper's thread {@link #RAN_RETRY_MILLIS} ms later; none until {@link #ranFrom}. */
    private Consumer<Runnable> ranLater = retry -> { };
    private boolean ranRetryArmed;
    /** The running turn's latest top-level message whose model and effort are still to be read, null when none is. */
    private String ranPending;
    private String ranModel = "";
    private boolean ranFirst;
    private long ranPendingSince;
    private long ranTriedAt;
    /** The latest top-level message whose model and effort were asked for, so a message's later blocks ask no more. */
    private String ranLast = "";
    /** Whether the running turn has had a top-level message: its first runs at the session's own setting. */
    private boolean turnHadMessage;

    StreamMapper(final String session, final Transcript transcript, final IntentSink sink, final ObjectMapper json) {
        this.session = session;
        this.transcript = transcript;
        this.sink = sink;
        this.json = json;
    }

    /** Maps one line of the live stream; returns the event type, empty for a line that is not JSON. */
    String accept(final String line) {
        final JsonNode e = parse(line);
        if (e == null) {
            return "";
        }
        final String type = e.path("type").asString("");
        if (ranPending != null) {
            readRan(false);
        }
        switch (type) {
            case "system" -> system(e);
            case "stream_event" -> streamEvent(e);
            case "assistant" -> assistant(e);
            case "user" -> user(e, false);
            case "result" -> result(e);
            case "rate_limit_event" -> rateLimit(e);
            case "conversation_reset" -> {
                // /clear: a system/init with the new conversation's id follows; the old context no longer applies, and
                // the clear's turn makes no model call to report the new one
                contextTokens = 0L;
                note(Block.NoteKind.INFO, "Conversation cleared (" + e.path("trigger").asString("clear") + "); a new one starts");
            }
            default -> LOG.log(System.Logger.Level.DEBUG, "event ignored [session=" + session + ", type=" + type + ']');
        }
        return type;
    }

    /**
     * Maps one line of a stored transcript: user and assistant messages, compaction, nothing live. A stored
     * conversation has no {@code result} lines; a turn ends where a message stopped at the end of its turn
     * ({@code stop_reason} {@code end_turn}), which gets a {@link Block.TurnEnd#stored()} after the message's last
     * line.
     */
    void acceptHistory(final String line) {
        replaying = true;
        try {
            history(line);
        } finally {
            replaying = false;
        }
    }

    private void history(final String line) {
        final JsonNode e = parse(line);
        if (e == null || e.path("isMeta").asBoolean(false)) {
            return;
        }
        final String type = e.path("type").asString("");
        final String messageId = e.path("message").path("id").asString("");
        // a message is stored a content block per line, each with its stop reason: the turn ends after its last one
        if (turnEndingMessage != null && (type.equals("user") || type.equals("assistant") && !messageId.equals(turnEndingMessage))) {
            endHistoryTurn();
        }
        switch (type) {
            case "assistant" -> {
                assistant(e);
                if (parent(e).isEmpty() && !e.path("isSidechain").asBoolean(false)
                        && e.path("message").path("stop_reason").asString("").equals("end_turn")) {
                    turnEndingMessage = messageId;
                }
            }
            case "user" -> user(e, true);
            case "system" -> {
                if (e.path("subtype").asString("").equals("compact_boundary")) {
                    transcript.append(new Block.Note(Block.NoteKind.COMPACT, "Conversation compacted", ""));
                }
            }
            default -> {
                // titles, snapshots, mode changes and attachments carry nothing to show
            }
        }
    }

    /**
     * The stored conversation's context, sent once it is loaded (record 120): the tokens its last model call read, else
     * {@code kept}'s, the context and window the metrics log kept of its last turn; nothing when neither is known.
     */
    void reportContext(final Optional<long[]> kept) {
        long tokens = contextTokens;
        if (kept.isPresent()) {
            contextWindow = Math.max(contextWindow, kept.get()[1]);
            if (tokens <= 0) {
                tokens = kept.get()[0];
                contextTokens = tokens;
            }
        }
        if (tokens > 0) {
            sink.dispatch(new Intent.SessionContext(session, tokens, contextWindow));
        }
    }

    /** The stored conversation is read: the turn its last message ended, if it did, ends now. */
    void endHistory() {
        if (turnEndingMessage != null) {
            endHistoryTurn();
        }
    }

    private void endHistoryTurn() {
        transcript.append(Block.TurnEnd.stored());
        turnEndingMessage = null;
    }

    /** Appends the prompt comando sent; called on the strand before the line goes to the process. */
    void prompt(final String text, final String origin) {
        transcript.append(new Block.UserPrompt(text, origin, ""));
    }

    void note(final Block.NoteKind kind, final String text) {
        transcript.append(new Block.Note(kind, text, ""));
    }

    /**
     * The message id of the summary a checkpoint carried into the next prompt (record 86), shown as the transcript's
     * answer; a session saved before record 129 may still carry one.
     */
    static final String CARRIED_ID = "carried";

    /**
     * Shows {@code summary}, which a checkpoint carried out of the conversation it cleared, as the answer of a turn
     * that ended: it is what the next prompt takes with it.
     */
    void carried(final String summary) {
        note(Block.NoteKind.INFO, "Conversation cleared after the checkpoint; its summary below goes in front of the next prompt");
        transcript.append(new Block.AssistantText(CARRIED_ID, 0, summary, false, ""));
        transcript.append(Block.TurnEnd.stored());
    }

    /**
     * Where the model and effort a message ran at are read from: the conversation's transcript ({@link RunSettings}),
     * which Claude Code writes a message's line to some 25 to 150 ms after the stream's event (measured on 2.1.310);
     * {@code later} runs a read again on this mapper's thread {@link #RAN_RETRY_MILLIS} ms on, so a message is read in
     * time even when the stream falls silent while a tool runs.
     */
    void ranFrom(final BiFunction<String, String, Optional<RunSettings.Ran>> reader, final Consumer<Runnable> later) {
        ranOf = reader;
        ranLater = later;
    }

    /** Where the base effort of the model a {@code system/init} names is read from. */
    void effortFrom(final Function<String, String> effort) {
        effortOf = effort;
    }

    /** The result a tool call gets when its process ended, or its stored conversation stopped, before it returned. */
    static final String NO_RESULT = "no result: the session ended before the tool returned";

    /**
     * Ends every tool call still running with an error result and returns how many it ended: the process that ran it
     * is gone (or a stored conversation stopped there), so no result will come and the call must not read "running"
     * for ever.
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

    private void system(final JsonNode e) {
        final String subtype = e.path("subtype").asString("");
        switch (subtype) {
            case "init" -> init(e);
            case "status" -> {
                final String status = e.path("status").isNull() ? "" : e.path("status").asString("");
                sink.dispatch(new Intent.SessionActivity(session, status));
                if (e.path("compact_result").asString("").equals("success")) {
                    note(Block.NoteKind.COMPACT, "Compaction finished");
                }
            }
            case "compact_boundary" -> {
                final JsonNode m = e.path("compact_metadata");
                note(Block.NoteKind.COMPACT, "Compacted " + m.path("trigger").asString("") + ": "
                        + m.path("pre_tokens").asLong(0) + " → " + m.path("post_tokens").asLong(0) + " tokens");
                // the context before compaction no longer applies; 0 means unknown until the next call reports it
                contextTokens = Math.max(0L, m.path("post_tokens").asLong(0));
            }
            case "hook_response" -> {
                final String out = e.path("output").asString("").strip();
                if (!out.isEmpty()) {
                    transcript.append(new Block.Note(Block.NoteKind.HOOK, e.path("hook_name").asString("") + ": " + clip(out, 2_000),
                            parent(e)));
                }
            }
            case "api_retry" -> note(Block.NoteKind.RETRY, "API retry " + e.path("attempt").asInt(0) + "/" + e.path("max_retries").asInt(0)
                    + " after " + e.path("error_status").asString(e.path("error").asString("error")));
            case "task_started" -> sink.dispatch(new Intent.SessionActivity(session, e.path("description").asString("task")));
            case "task_notification" -> {
                // a background task's end, named by the call that started it (2.1.302: status "completed")
                final String call = e.path("tool_use_id").asString("");
                if (!call.isEmpty() && !e.path("status").asString("").equals("running")) {
                    endedAgents.add(call);
                    agentEnded(call, e.path("status").asString("completed").equals("completed") ? "done" : "error", true);
                }
                sink.dispatch(new Intent.SessionActivity(session, "requesting"));
            }
            case "background_tasks_changed" -> backgroundTasks(e.path("tasks"));
            case "permission_denied" -> note(Block.NoteKind.DENIED, "Denied: " + e.path("tool_name").asString("tool"));
            default -> LOG.log(System.Logger.Level.DEBUG, "system event ignored [session=" + session + ", subtype=" + subtype + ']');
        }
    }

    /**
     * The background set, whole, as Claude Code sends it at each change (2.1.300: an agent or a shell command left
     * running past its turn; a foreground agent and a background agent's own commands are not in it). A task Claude
     * Code runs by itself ({@code ambient}) is not the session's work.
     */
    private void backgroundTasks(final JsonNode tasks) {
        final List<BackgroundTask> open = new ArrayList<>();
        for (final JsonNode t : tasks) {
            final String id = t.path("task_id").asString("");
            if (!id.isEmpty() && !t.path("ambient").asBoolean(false)) {
                open.add(new BackgroundTask(id, t.path("task_type").asString(""), t.path("description").asString(""), 0L));
            }
        }
        sink.dispatch(new Intent.SessionTasksChanged(session, open));
    }

    private void init(final JsonNode e) {
        final List<String> caps = new ArrayList<>();
        for (final JsonNode c : e.path("capabilities")) {
            caps.add(c.asString(""));
        }
        final SessionInfo info = new SessionInfo(
                e.path("session_id").asString(""),
                e.path("model").asString(""),
                e.path("cwd").asString(""),
                e.path("claude_code_version").asString(""),
                e.path("permissionMode").asString(""),
                caps,
                effortOf.apply(e.path("model").asString("")));
        if (!info.model().equals(lastModel)) {
            note(Block.NoteKind.INIT, "Model " + info.model() + " · Claude Code " + info.cliVersion() + " · " + info.permissionMode());
            lastModel = info.model();
        }
        conversation = info.claudeSessionId();
        sink.dispatch(new Intent.SessionInit(session, info));
    }

    private void streamEvent(final JsonNode e) {
        final JsonNode ev = e.path("event");
        final String messageId = e.path("api_message_id").asString(ev.path("message").path("id").asString(""));
        final int index = ev.path("index").asInt(-1);
        final String key = messageId + ':' + index;
        switch (ev.path("type").asString("")) {
            case "message_start" -> {
                // the main conversation calls the model: a turn runs, also one Claude Code started itself. Claude Code
                // 2.1.300 forwards no subagent's stream events and sends a null parent on these; one forwarded with its
                // parent call would be a subagent's, inside a turn or in the background, and start none
                if (parent(e).isEmpty()) {
                    sink.dispatch(new Intent.SessionTurnRunning(session));
                }
            }
            case "content_block_start" -> {
                final JsonNode block = ev.path("content_block");
                final String parent = parent(e);
                switch (block.path("type").asString("")) {
                    case "text" -> streamIndex.put(key, transcript.append(new Block.AssistantText(messageId, index, "", true, parent)));
                    case "thinking" -> streamIndex.put(key, transcript.append(new Block.Thinking(messageId, index, "", 0L, true, parent)));
                    case "tool_use" -> {
                        final String id = block.path("id").asString("");
                        final int at = transcript.append(new Block.ToolCall(id, block.path("name").asString(""), "{}", "", Block.ToolStatus.RUNNING, parent));
                        streamIndex.put(key, at);
                        toolIndex.put(id, at);
                        agentStarts(id, block.path("name").asString(""));
                    }
                    default -> {
                        // server tool blocks and others appear in the final message
                    }
                }
            }
            case "content_block_delta" -> {
                final Integer at = streamIndex.get(key);
                if (at == null) {
                    return;
                }
                final JsonNode delta = ev.path("delta");
                final Block current = transcript.get(at);
                switch (delta.path("type").asString("")) {
                    case "text_delta" -> {
                        if (current instanceof Block.AssistantText t) {
                            transcript.replace(at, t.appended(delta.path("text").asString("")));
                        }
                    }
                    case "thinking_delta" -> {
                        if (current instanceof Block.Thinking t) {
                            transcript.replace(at, new Block.Thinking(t.messageId(), t.index(), t.text() + delta.path("thinking").asString(""),
                                    Math.max(t.tokens(), delta.path("estimated_tokens").asLong(0)), true, t.parent()));
                        }
                    }
                    default -> {
                        // tool input arrives whole in the final message
                    }
                }
            }
            default -> {
                // message_delta, message_stop and content_block_stop carry nothing new to show
            }
        }
    }

    private void assistant(final JsonNode e) {
        final JsonNode message = e.path("message");
        final String messageId = message.path("id").asString("");
        final String parent = parent(e);
        if (!replaying && parent.isEmpty() && !messageId.isEmpty() && !messageId.equals(ranLast)) {
            ranLast = messageId;
            ranPending = messageId;
            ranModel = message.path("model").asString("");
            ranFirst = !turnHadMessage;
            turnHadMessage = true;
            ranPendingSince = ranTriedAt = 0L;
            readRan(false);
        }
        final JsonNode usage = message.path("usage");
        if (!usage.isMissingNode() && parent.isEmpty()) {
            final long tokens = usage.path("input_tokens").asLong(0) + usage.path("cache_creation_input_tokens").asLong(0)
                    + usage.path("cache_read_input_tokens").asLong(0);
            if (tokens > 0) {
                contextTokens = tokens;
                if (!replaying) {
                    // the session bar follows each model call, not only the turn's end (record 120)
                    sink.dispatch(new Intent.SessionContext(session, tokens, contextWindow));
                }
            }
        }
        for (final JsonNode block : message.path("content")) {
            final int index = closedBlocks.merge(messageId, 1, Integer::sum) - 1;
            final Integer at = streamIndex.remove(messageId + ':' + index);
            switch (block.path("type").asString("")) {
                case "text" -> {
                    final Block.AssistantText text = new Block.AssistantText(messageId, index, block.path("text").asString(""), false, parent);
                    put(at, text);
                }
                case "thinking", "redacted_thinking" -> {
                    final long tokens = at != null && transcript.get(at) instanceof Block.Thinking t ? t.tokens() : 0L;
                    put(at, new Block.Thinking(messageId, index, block.path("thinking").asString(""), tokens, false, parent));
                }
                case "tool_use", "server_tool_use" -> {
                    final String id = block.path("id").asString("");
                    final String input = block.path("input").toString();
                    final Integer known = toolIndex.get(id);
                    if (known != null && transcript.get(known) instanceof Block.ToolCall call) {
                        transcript.replace(known, call.withInput(input));
                    } else {
                        toolIndex.put(id, transcript.append(new Block.ToolCall(id, block.path("name").asString(""), input, "",
                                Block.ToolStatus.RUNNING, parent)));
                        agentStarts(id, block.path("name").asString(""));
                    }
                }
                default -> {
                    // web search results and other server blocks are not shown
                }
            }
        }
    }

    private void put(final Integer at, final Block block) {
        if (at != null) {
            transcript.replace(at, block);
        } else {
            transcript.append(block);
        }
    }

    private void user(final JsonNode e, final boolean history) {
        final JsonNode content = e.path("message").path("content");
        final String parent = parent(e);
        if (content.isString()) {
            userText(content.asString(""), parent, history);
            return;
        }
        for (final JsonNode block : content) {
            switch (block.path("type").asString("")) {
                case "tool_result" -> toolResult(block);
                case "text" -> userText(block.path("text").asString(""), parent, history);
                default -> {
                    // images and documents are not shown
                }
            }
        }
    }

    private void userText(final String text, final String parent, final boolean history) {
        final String t = text.strip();
        if (t.isEmpty()) {
            return;
        }
        if (t.startsWith(COMPACT_SUMMARY_PREFIX)) {
            transcript.append(new Block.Note(Block.NoteKind.COMPACT, "Conversation continued from a summary", parent));
        } else if (t.startsWith("<local-command-stdout>")) {
            final String out = t.replace("<local-command-stdout>", "").replace("</local-command-stdout>", "").strip();
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

    private void toolResult(final JsonNode block) {
        final String id = block.path("tool_use_id").asString("");
        final Integer at = toolIndex.get(id);
        if (at == null || !(transcript.get(at) instanceof Block.ToolCall call)) {
            return;
        }
        final JsonNode content = block.path("content");
        String text;
        if (content.isString()) {
            text = content.asString("");
        } else {
            final StringBuilder sb = new StringBuilder();
            for (final JsonNode part : content) {
                if (part.path("type").asString("").equals("text")) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(part.path("text").asString(""));
                }
            }
            text = sb.toString();
        }
        transcript.replace(at, call.withResult(clip(text, MAX_RESULT_CHARS), block.path("is_error").asBoolean(false)));
        if (call.isSubagent() && Block.ToolCall.launchedAgent(text).isEmpty()) {
            endedAgents.add(id); // a launch is no end: the background sub-agent's task notification ends it
            agentEnded(id, block.path("is_error").asBoolean(false) ? "error" : "done", false);
        }
    }

    /** Where the metrics go, and who hears a sub-agent's end to add its spend (record 110). */
    void metrics(final MetricsLog log, final Consumer<ObjectNode> onAgentEnd) {
        metrics = log;
        agentEnds = onAgentEnd;
    }

    private void agentStarts(final String id, final String tool) {
        if (!replaying && (tool.equals("Agent") || tool.equals("Task"))) {
            agentStarted.putIfAbsent(id, metrics.now());
        }
    }

    /**
     * A sub-agent ended, live: its {@code agent} event, with what its call asked for (type, model, description,
     * background), how long it ran, how it ended, and the tool calls inside it at any depth. The adapter adds its
     * spend.
     */
    private void agentEnded(final String id, final String status, final boolean background) {
        final Long started = agentStarted.remove(id);
        final Integer at = toolIndex.get(id);
        if (replaying || started == null || at == null || !(transcript.get(at) instanceof Block.ToolCall call)) {
            return;
        }
        final JsonNode in = parse(call.inputJson());
        final ObjectNode e = metrics.event("agent");
        e.put("session", session);
        e.put("conversation", conversation);
        e.put("tool_use_id", id);
        e.put("type", in == null ? "" : in.path("subagent_type").asString(""));
        e.put("description", in == null ? "" : in.path("description").asString(""));
        e.put("model_asked", in == null ? "" : in.path("model").asString(""));
        e.put("parent_model", lastModel);
        e.put("background", background || in != null && in.path("run_in_background").asBoolean(false));
        e.put("status", status);
        e.put("started", Instant.ofEpochMilli(started).toString());
        e.put("duration_ms", metrics.now() - started);
        e.put("tools", nestedTools(id));
        agentEnds.accept(e);
    }

    /** The tool calls in the transcript that ran inside the call {@code id}, at any depth. */
    private int nestedTools(final String id) {
        final Map<String, String> parentOf = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        for (int i = 0; i < transcript.size(); i++) {
            if (transcript.get(i) instanceof Block.ToolCall c) {
                parentOf.put(c.toolUseId(), c.parent());
                calls.add(c.toolUseId());
            }
        }
        int n = 0;
        for (final String c : calls) {
            String up = parentOf.getOrDefault(c, "");
            for (int depth = 0; !up.isEmpty() && depth < 64; depth++) {
                if (up.equals(id)) {
                    n++;
                    break;
                }
                up = parentOf.getOrDefault(up, "");
            }
        }
        return n;
    }

    /** True when the sub-agent the Agent call {@code toolUseId} started has ended, as the stream told; any thread. */
    boolean agentEnded(final String toolUseId) {
        return endedAgents.contains(toolUseId);
    }

    /**
     * Reads what the pending message ran at and tells the window: at once when its line is in the transcript; else
     * again {@value #RAN_RETRY_MILLIS} ms later, or on a later line of the stream, and for the last time at the turn's
     * end or {@value #RAN_GIVE_UP_MILLIS} ms after the message, with the stream's model alone.
     */
    private void readRan(final boolean last) {
        final long now = System.nanoTime();
        if (ranPendingSince == 0L) {
            ranPendingSince = now;
        } else if (!last && now - ranTriedAt < RAN_RETRY_MILLIS * 1_000_000L) {
            armRanRetry();
            return;
        }
        ranTriedAt = now;
        final Optional<RunSettings.Ran> ran = ranOf.apply(conversation, ranPending);
        final boolean giveUp = last || now - ranPendingSince >= RAN_GIVE_UP_MILLIS * 1_000_000L;
        if (ran.isEmpty() && !giveUp) {
            armRanRetry();
            return;
        }
        final String model = ran.map(RunSettings.Ran::model).filter(m -> !m.isEmpty()).orElse(ranModel);
        final String effort = ran.map(RunSettings.Ran::effort).orElse("");
        ranPending = null;
        sink.dispatch(new Intent.SessionRanAt(session, model, effort, ranFirst));
    }

    /**
     * One read again later while a message waits for its line; a read the turn's end made first finds nothing pending.
     */
    private void armRanRetry() {
        if (ranRetryArmed) {
            return;
        }
        ranRetryArmed = true;
        ranLater.accept(() -> {
            ranRetryArmed = false;
            if (ranPending != null) {
                ranTriedAt = 0L; // the retry's own delay was the wait
                readRan(false);
            }
        });
    }

    private void result(final JsonNode e) {
        if (ranPending != null) {
            readRan(true); // before the turn's end, which clears what the turn ran at
        }
        turnHadMessage = false;
        final boolean error = e.path("is_error").asBoolean(false);
        final String subtype = e.path("subtype").asString("");
        final String message = error ? errorText(e) : "";
        // result.usage sums every API call of the turn; the context is the last call's input
        final long tokens = contextTokens;
        transcript.append(new Block.TurnEnd(e.path("total_cost_usd").asDouble(0), e.path("duration_ms").asLong(0),
                e.path("num_turns").asInt(0), error, message.isEmpty() ? subtype : message));
        long window = 0;
        for (final JsonNode model : e.path("modelUsage")) {
            window = Math.max(window, model.path("contextWindow").asLong(0));
        }
        if (window > 0) {
            contextWindow = window;
        }
        sink.dispatch(new Intent.SessionTurnEnded(session, e.path("queued_turn_count").asInt(0), e.path("total_cost_usd").asDouble(0),
                tokens, window, error, message, error ? "" : e.path("result").asString("")));
        turnMetric(e, tokens, window, error, subtype);
    }

    /**
     * A turn ended: its {@code turn} event (record 110): the model and its base effort, how long it ran and called the
     * API, how many model calls, the tokens it used, by model as Claude Code sums them, the context it ended with, and
     * how it ended. The money field is kept as it arrives.
     */
    private void turnMetric(final JsonNode e, final long context, final long window, final boolean error, final String subtype) {
        final ObjectNode m = metrics.event("turn");
        m.put("session", session);
        m.put("conversation", conversation);
        m.put("model", lastModel);
        m.put("effort", effortOf.apply(lastModel));
        m.put("duration_ms", e.path("duration_ms").asLong(0));
        m.put("api_ms", e.path("duration_api_ms").asLong(0));
        m.put("calls", e.path("num_turns").asInt(0));
        m.put("context", context);
        m.put("window", window);
        m.put("queued", e.path("queued_turn_count").asInt(0));
        m.put("error", error);
        m.put("subtype", subtype);
        m.set("usage", e.path("usage").isObject() ? e.path("usage").deepCopy() : m.objectNode());
        m.set("models", e.path("modelUsage").isObject() ? e.path("modelUsage").deepCopy() : m.objectNode());
        m.put("cost_usd", e.path("total_cost_usd").asDouble(0));
        metrics.write(m);
    }

    private static String errorText(final JsonNode e) {
        if (e.path("terminal_reason").asString("").equals("aborted_tools") || e.path("subtype").asString("").equals("error_during_execution")) {
            return "interrupted";
        }
        final String r = e.path("result").asString("");
        if (!r.isEmpty()) {
            return clip(r, 500);
        }
        final StringBuilder sb = new StringBuilder(e.path("subtype").asString("error"));
        for (final JsonNode x : e.path("errors")) {
            sb.append("; ").append(x.asString(""));
        }
        return clip(sb.toString(), 500);
    }

    private void rateLimit(final JsonNode e) {
        final JsonNode info = e.path("rate_limit_info");
        final String status = info.path("status").asString("allowed");
        if (!status.equals("allowed")) {
            note(Block.NoteKind.INFO, "Rate limit " + status + " (" + info.path("rateLimitType").asString("") + ")");
        }
    }

    private JsonNode parse(final String line) {
        try {
            return json.readTree(line);
        } catch (final JacksonException ex) {
            LOG.log(System.Logger.Level.WARNING, "not JSON, ignored [session=" + session + ", line=" + clip(line, 200) + ']');
            return null;
        }
    }

    private static String parent(final JsonNode e) {
        final JsonNode p = e.path("parent_tool_use_id");
        return p.isString() ? p.asString("") : "";
    }

    private static String commandName(final String t) {
        final int a = t.indexOf("<command-name>") + "<command-name>".length();
        final int b = t.indexOf("</command-name>");
        final String name = b > a ? t.substring(a, b) : "command";
        final int c = t.indexOf("<command-args>");
        final int d = t.indexOf("</command-args>");
        final String args = c >= 0 && d > c ? t.substring(c + "<command-args>".length(), d).strip() : "";
        return args.isEmpty() ? name : name + ' ' + args;
    }

    static String clip(final String s, final int max) {
        return s.length() <= max ? s : s.substring(0, max) + "… [" + (s.length() - max) + " more characters]";
    }
}
