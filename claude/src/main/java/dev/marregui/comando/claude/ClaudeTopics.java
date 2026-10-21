package dev.marregui.comando.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.Topic;
import dev.marregui.comando.core.port.TopicPort;
import dev.marregui.comando.core.view.Clip;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Reads panels' topics with Haiku through Claude Code: {@code claude --safe-mode -p --model haiku} with no tools, no
 * thinking, a one-sentence system prompt and no saved session, the source on its input. Measured on Claude Code
 * 2.1.304: about 1.9 s and US$0.00014 a read. One thread reads every ask, one at a time, so however many panels ask,
 * comando runs at most one such process; the asks wait in one queue, at most one per panel.
 */
public final class ClaudeTopics implements TopicPort, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ClaudeTopics.class.getName());
    static final Duration TIMEOUT = Duration.ofSeconds(30);
    static final String MODEL = "haiku";
    static final String SYSTEM = "You name what a coding session is working on, or what a document is about, for a tab's tooltip. "
            + "Answer with one line of at most 80 characters: no preamble, no quotes, no final period.";
    static final int MAX_LINE = 120;

    private record Ask(String key, String source, Consumer<Topic> done) {
    }

    private final Function<String, Topic> reader;
    private final Object lock = new Object();
    /** Asks not yet read, in reading order, one per key. Guarded by {@code lock}. */
    private final LinkedHashMap<String, Ask> waiting = new LinkedHashMap<>();
    private final ExecutorService worker =
            Threads.strand("topic-reader");
    /** The last problem logged, so a reader that keeps failing logs once. Worker thread only. */
    private String lastProblem = "";
    private volatile boolean closed;

    public ClaudeTopics(String executable, Path workDir) {
        this(source -> read(executable, workDir, new ObjectMapper(), source, TIMEOUT));
    }

    /** With the read itself; tests count and pace reads. */
    ClaudeTopics(Function<String, Topic> reader) {
        this.reader = reader;
    }

    @Override
    public void read(String key, String source, boolean urgent, Consumer<Topic> done) {
        Ask ask = new Ask(key, source, done);
        boolean added;
        synchronized (lock) {
            if (closed) {
                return;
            }
            added = !waiting.containsKey(key);
            if (urgent) {
                waiting.putFirst(key, ask);
            } else {
                waiting.put(key, ask); // a replaced ask keeps its place in the queue
            }
        }
        if (added) {
            // one task per waiting key: a task reads whichever ask is first when it runs
            try {
                worker.execute(this::readFirst);
            } catch (RejectedExecutionException e) {
                // closed meanwhile; the ask is dropped with the others
            }
        }
    }

    private void readFirst() {
        Ask ask;
        synchronized (lock) {
            Map.Entry<String, Ask> first = waiting.pollFirstEntry();
            if (first == null) {
                return;
            }
            ask = first.getValue();
        }
        Topic topic;
        try {
            topic = reader.apply(ask.source());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "topic read failed", e);
            topic = Topic.failed("the read failed: " + e.getMessage(), System.currentTimeMillis());
        }
        if (!topic.problem().isEmpty() && !topic.problem().equals(lastProblem)) {
            LOG.log(System.Logger.Level.WARNING, "cannot read a panel's topic: " + topic.problem());
        }
        lastProblem = topic.problem();
        if (!closed) {
            ask.done().accept(topic);
        }
    }

    /** Runs one read; blocks up to {@code timeout}. */
    static Topic read(String executable, Path workDir, ObjectMapper json, String source, Duration timeout) {
        Path out = null;
        Process p = null;
        try {
            out = Files.createTempFile("comando-topic", ".json");
            ProcessBuilder pb = new ProcessBuilder(executable, "--safe-mode", "-p", "--model", MODEL, "--effort", "low", "--tools", "",
                    "--system-prompt", SYSTEM, "--no-session-persistence", "--output-format", "json")
                    .redirectOutput(out.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.environment().put("MAX_THINKING_TOKENS", "0");
            if (workDir != null && Files.isDirectory(workDir)) {
                pb.directory(workDir.toFile());
            }
            p = pb.start();
            try (OutputStream in = p.getOutputStream()) {
                in.write(source.getBytes(StandardCharsets.UTF_8));
            }
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return Topic.failed("Haiku did not answer within " + timeout.toMillis() + " ms", System.currentTimeMillis());
            }
            return parse(Files.readString(out, StandardCharsets.UTF_8), System.currentTimeMillis(), json);
        } catch (IOException e) {
            return Topic.failed("cannot run '" + executable + "': " + e.getMessage(), System.currentTimeMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Topic.failed("interrupted", System.currentTimeMillis());
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (IOException e) {
                    // a temporary file left behind; the system cleans its temporary folder
                }
            }
        }
    }

    /** The line of the command's JSON result, or why there is none. */
    static Topic parse(String output, long readAtMillis, ObjectMapper json) {
        JsonNode root;
        try {
            root = json.readTree(output == null ? "" : output.strip());
        } catch (IOException e) {
            return Topic.failed("unreadable answer from Haiku", readAtMillis);
        }
        if (root == null || !root.isObject()) {
            return Topic.failed("no answer from Haiku", readAtMillis);
        }
        String text = root.path("result").asText("");
        if (root.path("is_error").asBoolean(false)) {
            return Topic.failed(text.isBlank() ? "Haiku failed" : Clip.keepStart(firstLine(text), 200), readAtMillis);
        }
        String line = clean(firstLine(text));
        return line.isEmpty() ? Topic.failed("an empty answer from Haiku", readAtMillis) : new Topic(Clip.keepStart(line, MAX_LINE), readAtMillis, "");
    }

    private static String firstLine(String text) {
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return "";
    }

    /** Without the quotes, backticks or final period a model adds against its instructions. */
    static String clean(String line) {
        String s = line.strip();
        while (s.length() >= 2 && "\"'`“”".indexOf(s.charAt(0)) >= 0 && "\"'`“”".indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(1, s.length() - 1).strip();
        }
        if (s.endsWith(".") && !s.endsWith("..")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Drops the asks still waiting and stops the reader; a read under way is stopped and reports nothing. Idempotent. */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            waiting.clear();
        }
        worker.shutdownNow();
        try {
            worker.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
