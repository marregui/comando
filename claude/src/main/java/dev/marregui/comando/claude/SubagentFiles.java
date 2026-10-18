package dev.marregui.comando.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The conversations Claude Code stores for a session's sub-agents. Not documented (2.1.302): beside the session's
 * {@code <session-id>.jsonl} sits a directory {@code <session-id>/subagents/} holding, per sub-agent,
 * {@code agent-<id>.jsonl} (its whole conversation) and {@code agent-<id>.meta.json}, whose {@code toolUseId} is the
 * id of the Agent call that started it. Read off the EDT only.
 */
final class SubagentFiles {

    private static final System.Logger LOG = System.getLogger(SubagentFiles.class.getName());

    /** The most of a conversation read; a longer one shows its end. */
    static final long LIMIT_BYTES = 2L * 1024 * 1024;
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
            LOG.log(System.Logger.Level.WARNING, "cannot list " + projectsDir + ": " + e.getMessage());
        }
        return Optional.empty();
    }

    private static Optional<Found> findIn(Path dir, String toolUseId, ObjectMapper json) throws IOException {
        try (var metas = Files.newDirectoryStream(dir, "agent-*" + META)) {
            for (Path meta : metas) {
                JsonNode m = readMeta(meta, json);
                if (m != null && toolUseId.equals(m.path("toolUseId").asText(""))) {
                    String name = meta.getFileName().toString();
                    Path transcript = dir.resolve(name.substring(0, name.length() - META.length()) + ".jsonl");
                    return Optional.of(new Found(transcript, meta, m.path("description").asText(m.path("agentType").asText(""))));
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
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "cannot read " + meta + ": " + e.getMessage());
            return null;
        }
    }

    /** True when the meta file says the sub-agent finished; a later Claude Code may not write it, then false. */
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
     * The conversation as Markdown: the task as a quote, each answer's text, each tool call as one list item with its
     * result clipped to {@link #RESULT_CHARS} characters. A file over {@code limitBytes} is read from where its last
     * {@code limitBytes} start, at the first whole line, after its task (the file's first line, when it fits in
     * {@link #HEAD_BYTES}), and the output says so after the task: a view refreshed while the file grows keeps its start.
     */
    static String render(Path file, long limitBytes, ObjectMapper json) throws IOException {
        String text;
        String head = "";
        long size;
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            size = in.length();
            // one byte before the cut too: when it is a line's end, the first line read is whole
            long from = Math.max(0L, size - limitBytes - 1);
            byte[] bytes = new byte[(int) (size - from)];
            in.seek(from);
            in.readFully(bytes);
            text = new String(bytes, StandardCharsets.UTF_8);
            if (size > limitBytes) {
                int eol = text.indexOf('\n');
                text = eol < 0 ? "" : text.substring(eol + 1); // up to the first line's end: cut, or the byte before the cut
                byte[] first = new byte[(int) Math.min(HEAD_BYTES, from)];
                in.seek(0);
                in.readFully(first);
                String f = new String(first, StandardCharsets.UTF_8);
                int end = f.indexOf('\n');
                head = end < 0 ? "" : f.substring(0, end);
            }
        } catch (FileNotFoundException e) {
            return "_The sub-agent has written nothing yet._\n";
        }
        List<String> parts = new ArrayList<>();
        Map<String, Integer> callAt = new HashMap<>();
        if (size > limitBytes) {
            JsonNode task = parse(head, json);
            if (task != null && task.path("type").asText("").equals("user") && task.path("message").path("content").isTextual()) {
                parts.add(quote(task.path("message").path("content").asText()));
            }
            // no size that grows: the view's start stays the same while the file grows
            parts.add("_The conversation is longer than " + megabytes(limitBytes) + ": only its last " + megabytes(limitBytes)
                    + " are shown._");
        }
        for (String line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode n = parse(line, json);
            if (n == null) {
                continue; // a line still being written
            }
            JsonNode content = n.path("message").path("content");
            switch (n.path("type").asText("")) {
                case "user" -> user(content, parts, callAt);
                case "assistant" -> assistant(content, parts, callAt);
                default -> {
                }
            }
        }
        return parts.isEmpty() ? "_The sub-agent has written nothing yet._\n" : String.join("\n\n", parts) + "\n";
    }

    /** The line's JSON; null when it is not whole JSON. */
    private static JsonNode parse(String line, ObjectMapper json) {
        if (line.isBlank()) {
            return null;
        }
        try {
            return json.readTree(line);
        } catch (IOException e) {
            return null;
        }
    }

    private static void user(JsonNode content, List<String> parts, Map<String, Integer> callAt) {
        if (content.isTextual()) {
            parts.add(quote(content.asText()));
            return;
        }
        for (JsonNode c : content) {
            switch (c.path("type").asText("")) {
                case "text" -> parts.add(quote(c.path("text").asText("")));
                case "tool_result" -> {
                    Integer at = callAt.remove(c.path("tool_use_id").asText(""));
                    String result = oneLine(resultText(c.path("content")));
                    if (at != null && !result.isEmpty()) {
                        parts.set(at, parts.get(at) + (c.path("is_error").asBoolean(false) ? " → failed: " : " → ") + result);
                    }
                }
                default -> {
                }
            }
        }
    }

    private static void assistant(JsonNode content, List<String> parts, Map<String, Integer> callAt) {
        for (JsonNode c : content) {
            switch (c.path("type").asText("")) {
                case "text" -> {
                    String t = c.path("text").asText("").strip();
                    if (!t.isEmpty()) {
                        parts.add(t);
                    }
                }
                case "tool_use" -> {
                    String summary = oneLine(summary(c.path("input")));
                    parts.add("- `" + c.path("name").asText("tool") + '`' + (summary.isEmpty() ? "" : " " + summary));
                    callAt.put(c.path("id").asText(""), parts.size() - 1);
                }
                default -> {
                }
            }
        }
    }

    private static String quote(String text) {
        return "> " + text.strip().replace("\n", "\n> ");
    }

    /** What a tool call works on: the first of the inputs a call is usually known by, else its first text input. */
    private static String summary(JsonNode in) {
        for (String key : List.of("command", "file_path", "notebook_path", "pattern", "url", "query", "description", "skill")) {
            if (in.path(key).isTextual()) {
                return in.path(key).asText();
            }
        }
        var fields = in.properties().iterator();
        while (fields.hasNext()) {
            var f = fields.next();
            if (f.getValue().isValueNode()) {
                return f.getKey() + "=" + f.getValue().asText();
            }
        }
        return "";
    }

    private static String resultText(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : content) {
            if (c.path("text").isTextual()) {
                sb.append(c.path("text").asText()).append(' ');
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
