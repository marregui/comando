package dev.marregui.comando.claude;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * The conversations Claude Code stores for a session's sub-agents. Not documented (2.1.302): beside the session's
 * {@code <session-id>.jsonl} sits a directory {@code <session-id>/subagents/} holding, per sub-agent,
 * {@code agent-<id>.jsonl} (its whole conversation) and {@code agent-<id>.meta.json}, whose {@code toolUseId} is the
 * id of the Agent call that started it. Read off the EDT only.
 */
final class SubagentFiles {

    private static final System.Logger LOG = System.getLogger(SubagentFiles.class.getName());

    /** The most of a conversation read; a longer one is read from where its last this many bytes start. */
    static final long SCAN_BYTES = 256L * 1024 * 1024;
    /** The tool calls a view keeps, the latest; the steps before the oldest kept are not shown. */
    static final int LAST_TOOLS = 100;
    /** The most steps a view keeps, tool calls and texts together. */
    static final int MAX_STEPS = 4 * LAST_TOOLS;
    static final int CHUNK_BYTES = 1 << 20;
    /** A meta file is a few hundred bytes; a larger one is not one. */
    static final long META_BYTES = 4096;
    static final int RESULT_CHARS = 200;
    /** The most of a file's first line read for its task when the file is cut. */
    static final long HEAD_BYTES = 64 * 1024;
    private static final String META = ".meta.json";

    private SubagentFiles() {
    }

    /** A sub-agent's files and the description its Agent call gave it. */
    record Found(Path transcript, Path meta, String description) {
    }

    /** The files of the sub-agent that the Agent call {@code toolUseId} of conversation {@code sessionId} started. */
    static Optional<Found> find(Path projectsDir, String sessionId, String toolUseId, ObjectMapper json) {
        if (sessionId.isEmpty() || toolUseId.isEmpty() || !Files.isDirectory(projectsDir)) {
            return Optional.empty();
        }
        try (var projects = Files.newDirectoryStream(projectsDir)) {
            for (Path project : projects) {
                Path dir = project.resolve(sessionId).resolve("subagents");
                if (Files.isDirectory(dir)) {
                    Optional<Found> found = findIn(dir, toolUseId, json);
                    if (found.isPresent()) {
                        return found;
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot list [dir=" + projectsDir + ']', e);
        }
        return Optional.empty();
    }

    private static Optional<Found> findIn(Path dir, String toolUseId, ObjectMapper json) throws IOException {
        try (var metas = Files.newDirectoryStream(dir, "agent-*" + META)) {
            for (Path meta : metas) {
                JsonNode m = readMeta(meta, json);
                if (m != null && toolUseId.equals(m.path("toolUseId").asString(""))) {
                    String name = meta.getFileName().toString();
                    Path transcript = dir.resolve(name.substring(0, name.length() - META.length()) + ".jsonl");
                    return Optional.of(new Found(transcript, meta, m.path("description").asString(m.path("agentType").asString(""))));
                }
            }
        }
        return Optional.empty();
    }

    /** The meta file's content; null when it cannot be read or is too large to be one. */
    static JsonNode readMeta(Path meta, ObjectMapper json) {
        try {
            if (Files.size(meta) > META_BYTES) {
                return null;
            }
            return json.readTree(Files.readAllBytes(meta));
        } catch (IOException | JacksonException e) {
            LOG.log(System.Logger.Level.DEBUG, "cannot read [file=" + meta + ']', e);
            return null;
        }
    }

    /**
     * What a sub-agent spent, from its file's assistant records (record 110): the model calls, each message counted
     * once (a message is stored a content block a line, each with the same usage), the tokens by kind, and the models
     * it ran on. An unreadable file gives what was read.
     */
    static ObjectNode spend(Path file, ObjectMapper json) {
        ObjectNode out = json.createObjectNode();
        long calls = 0;
        long input = 0;
        long output = 0;
        long cacheRead = 0;
        long cacheCreate = 0;
        Set<String> seen = new HashSet<>();
        Set<String> models = new TreeSet<>();
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                JsonNode n = parse(line, json);
                if (n == null || !n.path("type").asString("").equals("assistant")) {
                    continue;
                }
                JsonNode m = n.path("message");
                String id = m.path("id").asString("");
                if (!id.isEmpty() && !seen.add(id)) {
                    continue;
                }
                calls++;
                if (!m.path("model").asString("").isEmpty()) {
                    models.add(m.path("model").asString(""));
                }
                JsonNode u = m.path("usage");
                input += u.path("input_tokens").asLong(0);
                output += u.path("output_tokens").asLong(0);
                cacheRead += u.path("cache_read_input_tokens").asLong(0);
                cacheCreate += u.path("cache_creation_input_tokens").asLong(0);
            }
        } catch (IOException | UncheckedIOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read [file=" + file + ']', e);
        }
        out.put("calls", calls);
        out.put("input", input);
        out.put("output", output);
        out.put("cache_read", cacheRead);
        out.put("cache_create", cacheCreate);
        models.forEach(out.withArray("models")::add);
        return out;
    }

    /** True when the meta file says the sub-agent finished; false when it does not say. */
    static boolean finished(Path meta, ObjectMapper json) {
        JsonNode m = readMeta(meta, json);
        return m != null && m.path("finished").asBoolean(false);
    }

    /** The size of {@code file}; 0 when it does not exist yet. */
    static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * The conversation as Markdown, read once: {@link Conversation#render} of a {@link Conversation} that reads at most
     * the last {@code scanBytes} of the file.
     */
    static String render(Path file, long scanBytes, ObjectMapper json) throws IOException {
        Conversation c = new Conversation(file, json, scanBytes);
        c.read();
        return c.render("");
    }

    /**
     * A sub-agent's conversation, read as its file grows: each {@link #read} parses only the whole lines added since
     * the last, so a long sub-agent followed every few seconds costs its growth, not its length. It keeps the task (the
     * file's first line), and the steps from the last {@link #LAST_TOOLS} tool calls on: each answer's text, and each
     * tool call as one list item with its result clipped to {@link #RESULT_CHARS} characters. A file longer than
     * {@code scanBytes} at the first read is read from where its last {@code scanBytes} start, at the first whole line,
     * and the output says so after the task. Confined to one thread.
     */
    static final class Conversation {

        private final Path file;
        private final ObjectMapper json;
        private final long scanBytes;
        /** The byte the next read starts at, always a line's start; -1 before the first read. */
        private long offset = -1;
        /** The first read started after the file's start: it was longer than {@link #scanBytes}. */
        private boolean cut;
        /** No line was read yet: the next user text is the task. */
        private boolean first = true;
        private String task = "";
        private final ArrayDeque<Step> steps = new ArrayDeque<>();
        /** The tool calls kept that wait for their result, by id. */
        private final Map<String, Step> waiting = new HashMap<>();
        /** Every tool call read, and those kept. */
        private int tools;
        private int toolsKept;

        Conversation(Path file, ObjectMapper json, long scanBytes) {
            this.file = file;
            this.json = json;
            this.scanBytes = scanBytes;
        }

        /** One step: an answer's text, a prompt, or a tool call with its result once it arrives. */
        private static final class Step {
            final String toolId;
            String text;

            Step(String toolId, String text) {
                this.toolId = toolId;
                this.text = text;
            }
        }

        /** Hears every whole line read, before it is parsed here: a sub-agent's tab maps them into its transcript. */
        private Consumer<String> lines = line -> { };

        void onLine(Consumer<String> listener) {
            lines = listener;
        }

        /** Whether the first read started after the file's start: it was longer than the bytes scanned. */
        boolean cut() {
            return cut;
        }

        /** Every tool call read, kept or not. */
        int tools() {
            return tools;
        }

        /**
         * Reads the whole lines the file gained since the last read; a line still being written waits for the next.
         * A file shorter than what was read was replaced, and is read again from its start.
         */
        void read() throws IOException {
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
                long size = ch.size();
                if (offset < 0 || size < offset) {
                    reset();
                    offset = 0;
                    if (size > scanBytes) {
                        cut = true;
                        task = taskOf(firstLine(ch));
                        first = false;
                        offset = lineStart(ch, size - scanBytes, size);
                    }
                }
                consume(ch, size);
            } catch (NoSuchFileException e) {
                // not written yet
            }
        }

        private void reset() {
            cut = false;
            first = true;
            task = "";
            steps.clear();
            waiting.clear();
            tools = 0;
            toolsKept = 0;
        }

        /** The task, then the steps kept, then {@code footer} when not empty. */
        String render(String footer) {
            List<String> parts = new ArrayList<>();
            if (!task.isBlank()) {
                parts.add(quote(task));
            }
            if (cut) {
                // no size that grows: the view's start stays the same while the file grows
                parts.add("_The conversation is longer than " + megabytes(scanBytes) + ": only its last " + megabytes(scanBytes)
                        + " are read._");
            }
            if (tools > toolsKept) {
                parts.add("_Earlier steps are not shown: the view keeps the last " + LAST_TOOLS + " tool calls._");
            }
            for (Step s : steps) {
                parts.add(s.text);
            }
            if (tools > toolsKept) {
                parts.add("_" + toolsKept + " of " + tools + " tool calls shown._");
            }
            if (task.isBlank() && steps.isEmpty() && !cut) {
                parts.add("_The sub-agent has written nothing yet._");
            }
            if (!footer.isEmpty()) {
                parts.add(footer);
            }
            return String.join("\n\n", parts) + "\n";
        }

        /**
         * Parses the whole lines from {@link #offset} to {@code size}, read a chunk at a time; a line may span chunks.
         */
        private void consume(FileChannel ch, long size) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            ByteBuffer buf = ByteBuffer.allocate(CHUNK_BYTES);
            long at = offset;
            while (at < size) {
                buf.clear();
                buf.limit((int) Math.min(CHUNK_BYTES, size - at));
                int n = ch.read(buf, at);
                if (n <= 0) {
                    break;
                }
                byte[] b = buf.array();
                int from = 0;
                for (int i = 0; i < n; i++) {
                    if (b[i] == '\n') {
                        line.write(b, from, i - from);
                        accept(line.toString(StandardCharsets.UTF_8));
                        line.reset();
                        from = i + 1;
                        offset = at + i + 1;
                    }
                }
                line.write(b, from, n - from);
                at += n;
            }
        }

        private void accept(String line) {
            if (!line.isBlank()) {
                lines.accept(line);
            }
            JsonNode n = parse(line, json);
            boolean wasFirst = first;
            first = false;
            if (n == null) {
                return;
            }
            JsonNode content = n.path("message").path("content");
            switch (n.path("type").asString("")) {
                case "user" -> user(content, wasFirst);
                case "assistant" -> assistant(content);
                default -> {
                }
            }
        }

        private void user(JsonNode content, boolean isTask) {
            if (content.isString()) {
                text(content.asString(""), isTask);
                return;
            }
            for (JsonNode c : content) {
                switch (c.path("type").asString("")) {
                    case "text" -> {
                        text(c.path("text").asString(""), isTask);
                        isTask = false;
                    }
                    case "tool_result" -> {
                        Step call = waiting.remove(c.path("tool_use_id").asString(""));
                        String result = oneLine(resultText(c.path("content")));
                        if (call != null && !result.isEmpty()) {
                            call.text += (c.path("is_error").asBoolean(false) ? " → failed: " : " → ") + result;
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        private void text(String text, boolean isTask) {
            if (isTask) {
                task = text;
            } else {
                add(new Step(null, quote(text)));
            }
        }

        private void assistant(JsonNode content) {
            for (JsonNode c : content) {
                switch (c.path("type").asString("")) {
                    case "text" -> {
                        String t = c.path("text").asString("").strip();
                        if (!t.isEmpty()) {
                            add(new Step(null, t));
                        }
                    }
                    case "tool_use" -> {
                        String summary = oneLine(summary(c.path("input")));
                        Step call = new Step(c.path("id").asString(""), "- `" + c.path("name").asString("tool") + '`'
                                + (summary.isEmpty() ? "" : " " + summary));
                        tools++;
                        toolsKept++;
                        add(call);
                        waiting.put(call.toolId, call);
                    }
                    default -> {
                    }
                }
            }
        }

        /**
         * Keeps {@code step}, dropping the oldest steps while more than {@link #LAST_TOOLS} tool calls or
         * {@link #MAX_STEPS} steps are kept.
         */
        private void add(Step step) {
            steps.addLast(step);
            while (toolsKept > LAST_TOOLS || steps.size() > MAX_STEPS) {
                Step old = steps.removeFirst();
                if (old.toolId != null) {
                    toolsKept--;
                    waiting.remove(old.toolId);
                }
            }
        }

        /** The task in a stored first line: its prompt when it is a user line with text, else empty. */
        private String taskOf(String line) {
            JsonNode n = parse(line, json);
            if (n == null || !n.path("type").asString("").equals("user")) {
                return "";
            }
            JsonNode content = n.path("message").path("content");
            if (content.isString()) {
                return content.asString("");
            }
            for (JsonNode c : content) {
                if (c.path("type").asString("").equals("text")) {
                    return c.path("text").asString("");
                }
            }
            return "";
        }

        /** The file's first line when it ends within {@link #HEAD_BYTES}; else empty. */
        private static String firstLine(FileChannel ch) throws IOException {
            ByteBuffer buf = ByteBuffer.allocate((int) Math.min(HEAD_BYTES, ch.size()));
            ch.read(buf, 0);
            byte[] b = buf.array();
            for (int i = 0; i < buf.position(); i++) {
                if (b[i] == '\n') {
                    return new String(b, 0, i, StandardCharsets.UTF_8);
                }
            }
            return "";
        }

        /**
         * The first line start at or after {@code pos}: {@code pos} itself when the byte before it ends a line;
         * {@code size} when none.
         */
        private static long lineStart(FileChannel ch, long pos, long size) throws IOException {
            ByteBuffer buf = ByteBuffer.allocate(CHUNK_BYTES);
            for (long at = pos - 1; at < size; ) {
                buf.clear();
                buf.limit((int) Math.min(CHUNK_BYTES, size - at));
                int n = ch.read(buf, at);
                if (n <= 0) {
                    break;
                }
                for (int i = 0; i < n; i++) {
                    if (buf.get(i) == '\n') {
                        return at + i + 1;
                    }
                }
                at += n;
            }
            return size;
        }
    }

    /** The line's JSON; null when it is not whole JSON. */
    private static JsonNode parse(String line, ObjectMapper json) {
        if (line.isBlank()) {
            return null;
        }
        try {
            return json.readTree(line);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String quote(String text) {
        return "> " + text.strip().replace("\n", "\n> ");
    }

    /** What a tool call works on: the input a call is usually known by, else its first scalar, {@code key=value}. */
    private static String summary(JsonNode in) {
        for (String key : List.of("command", "file_path", "notebook_path", "pattern", "url", "query", "description", "skill")) {
            if (in.path(key).isString()) {
                return in.path(key).asString("");
            }
        }
        var fields = in.properties().iterator();
        while (fields.hasNext()) {
            var f = fields.next();
            if (f.getValue().isValueNode()) {
                return f.getKey() + "=" + f.getValue().asString("");
            }
        }
        return "";
    }

    private static String resultText(JsonNode content) {
        if (content.isString()) {
            return content.asString("");
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : content) {
            if (c.path("text").isString()) {
                sb.append(c.path("text").asString("")).append(' ');
            }
        }
        return sb.toString();
    }

    /** {@code s} on one line, whitespace collapsed, clipped to {@link #RESULT_CHARS} characters. */
    private static String oneLine(String s) {
        String flat = s.strip().replaceAll("\\s+", " ");
        return flat.length() <= RESULT_CHARS ? flat : flat.substring(0, RESULT_CHARS) + " …";
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
