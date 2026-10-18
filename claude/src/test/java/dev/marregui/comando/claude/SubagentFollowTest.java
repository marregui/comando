package dev.marregui.comando.claude;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.McpGateway;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.RecordingSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a followed sub-agent view stops: at the end the session's stream reports (a foreground sub-agent's result, a
 * background one's task notification), at its meta file's {@code finished}, when the session stops, or after the idle
 * backstop; never because the file was quiet a while, as a sub-agent writes nothing while a tool runs or the model
 * answers. The fake CLI emits the events Claude Code 2.1.302 sends.
 */
class SubagentFollowTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    /** Three follow periods without growth: the old rule stopped a view after any quiet spell. */
    private static final Duration QUIET = Duration.ofMillis(3 * ClaudeAdapter.TAIL_EVERY_MILLIS + 500);

    @TempDir
    Path tmp;
    private final RecordingSink sink = new RecordingSink();
    private ClaudeAdapter adapter;
    private Path subagents;

    @BeforeEach
    void setUp() throws IOException {
        Path projects = tmp.resolve("projects");
        subagents = Files.createDirectories(projects.resolve("-w/conv-1/subagents"));
        Path exe = tmp.resolve("fake-claude-agents.sh");
        try (InputStream in = getClass().getResourceAsStream("/fake-claude-agents.sh")) {
            Files.write(exe, in.readAllBytes());
        }
        exe.toFile().setExecutable(true);
        adapter = new ClaudeAdapter(sink, new TranscriptRegistry(), McpGateway.NONE,
                new ClaudeSettings(exe.toString(), tmp.resolve("run"), projects, UiInstructions.TEXT));
        adapter.start(SessionSpec.fresh("work", tmp.toString()));
        sink.await(Intent.SessionStarted.class, WAIT);
    }

    @AfterEach
    void tearDown() {
        adapter.close();
    }

    /** Sends {@code command} to the fake CLI and waits for its turn to end, so its events were mapped. */
    private void stream(String command) {
        int turns = sink.of(Intent.SessionTurnEnded.class).size();
        adapter.send("work", command, "test");
        sink.await(Intent.SessionTurnEnded.class, e -> sink.of(Intent.SessionTurnEnded.class).size() > turns, WAIT);
    }

    private void agent(String id, String firstLine) throws IOException {
        Files.writeString(subagents.resolve("agent-" + id + ".meta.json"),
                "{\"agentType\":\"general-purpose\",\"description\":\"" + id + "\",\"toolUseId\":\"" + id + "\"}");
        Files.writeString(subagents.resolve("agent-" + id + ".jsonl"), text(firstLine) + "\n");
    }

    private void grow(String id, String line) throws IOException {
        Files.writeString(subagents.resolve("agent-" + id + ".jsonl"), text(line) + "\n", StandardOpenOption.APPEND);
    }

    private static String text(String t) {
        return "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"" + t + "\"}]}}";
    }

    private static String md(Intent i) {
        return ((ViewSpec.Markdown) (i instanceof Intent.ShowView v ? v.spec() : ((Intent.RefreshView) i).spec())).markdown();
    }

    private Intent.RefreshView refreshEndingWith(String footer) {
        return sink.await(Intent.RefreshView.class, r -> md(r).strip().endsWith(footer), WAIT);
    }

    /** Waits out {@link #QUIET}, failing if the view says in that time that it stopped being updated. */
    private void quiet() {
        assertThat(within(Intent.RefreshView.class, r -> !md(r).strip().endsWith(ClaudeAdapter.FOLLOWING), QUIET))
                .as("a stop during a quiet spell").isEmpty();
    }

    private <T extends Intent> Optional<T> within(Class<T> type, Predicate<T> match, Duration d) {
        try {
            return Optional.of(sink.await(type, match, d));
        } catch (AssertionError e) {
            return Optional.empty();
        }
    }

    @Test
    void aForegroundSubagentIsFollowedThroughAQuietSpellAndStopsAtItsResult() throws Exception {
        stream("agent:fg");
        agent("fg", "first step");

        adapter.showSubagent("work", "conv-1", "fg");
        Intent.ShowView shown = sink.await(Intent.ShowView.class, WAIT);
        assertThat(md(shown).strip()).startsWith("first step").endsWith(ClaudeAdapter.FOLLOWING);

        quiet(); // a tool runs: the file does not grow
        assertThat(adapter.followedSubagents()).as("followed through a quiet spell").isEqualTo(1);
        grow("fg", "after the quiet");
        Intent.RefreshView grown = sink.await(Intent.RefreshView.class, r -> md(r).contains("after the quiet"), WAIT);
        assertThat(md(grown).strip()).endsWith(ClaudeAdapter.FOLLOWING);

        grow("fg", "last word");
        stream("done:fg");

        Intent.RefreshView last = refreshEndingWith(ClaudeAdapter.ENDED);
        assertThat(md(last)).contains("first step", "after the quiet", "last word").doesNotContain(ClaudeAdapter.FOLLOWING);
        assertThat(adapter.followedSubagents()).isZero();
    }

    @Test
    void aBackgroundSubagentIsFollowedPastItsLaunchAndStopsAtItsTaskNotification() throws Exception {
        stream("agent:bg");
        stream("launch:bg");
        agent("bg", "first step");

        adapter.showSubagent("work", "conv-1", "bg");
        sink.await(Intent.ShowView.class, WAIT);
        quiet();
        assertThat(adapter.followedSubagents()).as("a launch is no end").isEqualTo(1);
        grow("bg", "working");
        sink.await(Intent.RefreshView.class, r -> md(r).contains("working"), WAIT);

        stream("notify:bg");

        assertThat(md(refreshEndingWith(ClaudeAdapter.ENDED))).contains("working");
        assertThat(adapter.followedSubagents()).isZero();
    }

    @Test
    void aSubagentWhoseMetaFileSaysFinishedStopsBeingFollowed() throws Exception {
        stream("agent:mf");
        agent("mf", "first step");
        adapter.showSubagent("work", "conv-1", "mf");
        sink.await(Intent.ShowView.class, WAIT);

        Files.writeString(subagents.resolve("agent-mf.meta.json"),
                "{\"agentType\":\"general-purpose\",\"description\":\"mf\",\"toolUseId\":\"mf\",\"finished\":true}");

        refreshEndingWith(ClaudeAdapter.ENDED);
        assertThat(adapter.followedSubagents()).isZero();
    }

    @Test
    void aSubagentThatEndedBeforeItsViewOpenedIsShownOnceWithoutTheFollowingLine() throws Exception {
        stream("agent:early");
        stream("done:early");
        agent("early", "all of it");

        adapter.showSubagent("work", "conv-1", "early");

        Intent.ShowView shown = sink.await(Intent.ShowView.class, WAIT);
        assertThat(md(shown)).contains("all of it").doesNotContain(ClaudeAdapter.FOLLOWING);
        assertThat(adapter.followedSubagents()).isZero();
    }

    @Test
    void theIdleBackstopStopsAndSaysSo() throws Exception {
        adapter.tailIdleMillis = 2 * ClaudeAdapter.TAIL_EVERY_MILLIS;
        stream("agent:idle");
        agent("idle", "first step");

        adapter.showSubagent("work", "conv-1", "idle");

        refreshEndingWith(ClaudeAdapter.STOPPED_IDLE);
        assertThat(adapter.followedSubagents()).isZero();
    }

    @Test
    void aStoppedSessionsViewSaysItIsNotUpdatedAnyMore() throws Exception {
        stream("agent:st");
        agent("st", "first step");
        adapter.showSubagent("work", "conv-1", "st");
        sink.await(Intent.ShowView.class, WAIT);

        adapter.stop("work");

        refreshEndingWith(ClaudeAdapter.STOPPED_SESSION);
        assertThat(adapter.followedSubagents()).isZero();
    }
}
