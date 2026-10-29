package dev.marregui.comando.claude;

import dev.marregui.comando.core.loop.Processes;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.Topic;
import dev.marregui.comando.core.port.TopicPort;
import dev.marregui.comando.core.view.Clip;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Reads panels' topics with Haiku through Claude Code: {@code claude --safe-mode -p --model haiku} with no tools, no
 * thinking, a short system prompt and no saved session, the source on its input; the answer is one or two sentences
 * naming the repository, the task and its step. Measured on Claude Code 2.1.308 with 2 000 characters in: a median of
 * 2.0 s and US$0.0001–0.0003 a read. One thread reads every ask, one at a time, so however many panels ask, comando
 * runs at most one such process; the asks wait in one queue, at most one per panel.
 */
public final class ClaudeTopics implements TopicPort, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ClaudeTopics.class.getName());
    /** A read that hangs is stopped after this; a slow one takes what it takes. */
    static final Duration TIMEOUT = Duration.ofSeconds(30);
    static final String MODEL = "haiku";
    static final String SYSTEM = "You summarise, for a tab's card, what a coding session is working on, or what a document is about. "
            + "Write one or two short sentences, at most 200 characters in all: the repository or project by its folder name, "
            + "the task, and the step it is on now. When the text gives a most recent paragraph, the summary is about that "
            + "command and where the session's last response left it; the rest is context. Be specific: name the files, "
            + "classes, tickets or commands the text names. "
            + "Never repeat the full path or the session's name. No preamble, no heading, no quotes, no markdown.";
    /** A summary longer than this is cut: the card shows about four lines. */
    static final int MAX_TEXT = 260;

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

    public ClaudeTopics(String executable, RootLaunch launch, Path workDir) {
        this(source -> read(executable, launch, workDir, new ObjectMapper(), source, TIMEOUT));
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
            LOG.log(System.Logger.Level.WARNING, "cannot read a panel's topic [problem=" + topic.problem() + ']');
        }
        lastProblem = topic.problem();
        if (!closed) {
            ask.done().accept(topic);
        }
    }

    /** Runs one read; blocks up to {@code timeout}. */
    static Topic read(String executable, RootLaunch launch, Path workDir, ObjectMapper json, String source, Duration timeout) {
        try {
            Processes.Result r = Processes.run(launch.argv(List.of(executable, "--safe-mode", "-p", "--model", MODEL, "--effort", "low",
                    "--tools", "", "--system-prompt", SYSTEM, "--no-session-persistence", "--output-format", "json")), env -> {
                launch.environment(env);
                env.put("MAX_THINKING_TOKENS", "0");
            }, workDir != null && Files.isDirectory(workDir) ? workDir : null, source.getBytes(StandardCharsets.UTF_8), timeout, MAX_OUT);
            if (r.timedOut()) {
                long ms = timeout.toMillis();
                return Topic.failed("Haiku did not answer within " + (ms % 1_000 == 0 ? ms / 1_000 + " s" : ms + " ms"),
                        System.currentTimeMillis());
            }
            return parse(r.out(), System.currentTimeMillis(), json);
        } catch (IOException e) {
            return Topic.failed("cannot run '" + executable + "': " + e.getMessage(), System.currentTimeMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Topic.failed("interrupted", System.currentTimeMillis());
        }
    }

    /** The most of the command's answer kept: its JSON is a few kilobytes. */
    static final int MAX_OUT = 1 << 20;

    /** The topic in the command's JSON result, or why there is none. */
    static Topic parse(String output, long readAtMillis, ObjectMapper json) {
        JsonNode root;
        try {
            root = json.readTree(output == null ? "" : output.strip());
        } catch (JacksonException e) {
            return Topic.failed("unreadable answer from Haiku", readAtMillis);
        }
        if (root == null || !root.isObject()) {
            return Topic.failed("no answer from Haiku", readAtMillis);
        }
        String text = root.path("result").asString("");
        if (root.path("is_error").asBoolean(false)) {
            return Topic.failed(text.isBlank() ? "Haiku failed" : Clip.keepStart(firstLine(text), 200), readAtMillis);
        }
        String summary = clean(text.strip().replaceAll("\\s+", " "));
        return summary.isEmpty() ? Topic.failed("an empty answer from Haiku", readAtMillis) : new Topic(Clip.keepStart(summary, MAX_TEXT), readAtMillis, "");
    }

    private static String firstLine(String text) {
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return "";
    }

    /** Without the quotes, backticks or "Summary:" heading a model adds against its instructions. */
    static String clean(String text) {
        String s = text.strip();
        if (s.regionMatches(true, 0, "summary:", 0, 8)) {
            s = s.substring(8).strip();
        }
        while (s.length() >= 2 && "\"'`“”".indexOf(s.charAt(0)) >= 0 && "\"'`“”".indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(1, s.length() - 1).strip();
        }
        return s;
    }

    /**
     * Drops the asks still waiting and stops the reader; a read under way is stopped and reports nothing. Idempotent.
     */
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
