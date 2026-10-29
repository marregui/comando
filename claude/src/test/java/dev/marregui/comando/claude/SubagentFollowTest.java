package dev.marregui.comando.claude;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.McpGateway;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.RecordingSink;
import org.awaitility.Awaitility;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/// When [ClaudeAdapter] stops following a sub-agent view: at the end the session's stream reports (a foreground
/// sub-agent's result, a background one's task notification), at its meta file's `finished`, when the session stops, or
/// after the idle backstop; never because the file was quiet a while, as a sub-agent writes nothing while a tool runs
/// or the model answers. The fake CLI emits the events the CLI 2.1.302 sends.
/// - a foreground sub-agent is followed through a quiet spell and stops at its result
/// - a background one is followed past its launch and stops at its task notification
/// - a meta file marked `finished`, a stopped session and the idle backstop each end the following, and the view says
///   so
/// - a sub-agent that ended before its view opened is shown once, as ended
class SubagentFollowTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    /** The follow period here, shorter than the app's so the suite does not wait out its 2 s. */
    private static final long PERIOD_MILLIS = 100;
    /** Three follow periods without growth: a quiet spell alone must not stop a view. */
    private static final Duration QUIET = Duration.ofMillis(3 * PERIOD_MILLIS + 500);

    @TempDir
    Path tmp;
    private final RecordingSink sink = new RecordingSink();
    private final TranscriptRegistry transcripts = new TranscriptRegistry();
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
        adapter = new ClaudeAdapter(sink, transcripts, McpGateway.NONE,
                new ClaudeSettings(exe.toString(), tmp.resolve("run"), projects, UiInstructions.TEXT));
        adapter.tailEveryMillis = PERIOD_MILLIS;
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

    /** What the sub-agent's tab says of its following (record 109). */
    private static String state(Intent i) {
        return ((ViewSpec.Agent) (i instanceof Intent.ShowView v ? v.spec() : ((Intent.RefreshView) i).spec())).state();
    }

    /** The texts in sub-agent {@code id}'s transcript, a paragraph each. */
    private String said(String id) {
        Transcript t = transcripts.of(TranscriptRegistry.subagentKey("work", id));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < t.size(); i++) {
            if (t.get(i) instanceof Block.AssistantText a) {
                out.add(a.text());
            }
        }
        return String.join("\n\n", out);
    }

    /** Waits until sub-agent {@code id}'s transcript holds {@code text}. */
    private void awaitSaid(String id, String text) {
        Awaitility.await("the tab shows " + text).atMost(WAIT).until(() -> said(id).contains(text));
    }

    private Intent.RefreshView refreshEndingWith(String footer) {
        return sink.await(Intent.RefreshView.class, r -> state(r).equals(footer), WAIT);
    }

    /** Waits out {@link #QUIET}, failing if the view says in that time that it stopped being updated. */
    private void quiet() {
        assertThat(within(Intent.RefreshView.class, r -> !state(r).equals(ClaudeAdapter.FOLLOWING), QUIET))
                .as("a stop during a quiet spell").isEmpty();
    }

    private <T extends Intent> Optional<T> within(Class<T> type, Predicate<T> match, Duration d) {
        try {
            return Optional.of(sink.await(type, match, d));
        } catch (AssertionError e) {
            return Optional.empty();
        }
    }

    /// A foreground sub-agent's tab opens as `FOLLOWING` in its session with its transcript, stays followed through
    /// three periods without growth, and stops being followed once its result arrives, its transcript holding every
    /// step.
    @Test
    void aForegroundSubagentIsFollowedThroughAQuietSpellAndStopsAtItsResult() throws Exception {
        stream("agent:fg");
        agent("fg", "first step");

        adapter.showSubagent("work", "conv-1", "fg");
        Intent.ShowView shown = sink.await(Intent.ShowView.class, WAIT);
        assertThat(state(shown)).isEqualTo(ClaudeAdapter.FOLLOWING);
        assertThat(((ViewSpec.Agent) shown.spec()).session()).as("a tab of the session that started it").isEqualTo("work");
        assertThat(said("fg")).as("its conversation, as a transcript").startsWith("first step");

        quiet(); // a tool runs: the file does not grow
        assertThat(adapter.followedSubagents()).as("followed through a quiet spell").isEqualTo(1);
        grow("fg", "after the quiet");
        awaitSaid("fg", "after the quiet");

        grow("fg", "last word");
        stream("done:fg");

        refreshEndingWith(ClaudeAdapter.ENDED);
        assertThat(said("fg")).contains("first step", "after the quiet", "last word");
        assertThat(adapter.followedSubagents()).isZero();
    }

    /// A background sub-agent is still followed after the launch result, and stops being followed at its task
    /// notification with its later text in the transcript.
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
        awaitSaid("bg", "working");

        stream("notify:bg");

        refreshEndingWith(ClaudeAdapter.ENDED);
        assertThat(said("bg")).contains("working");
        assertThat(adapter.followedSubagents()).isZero();
    }

    /// Writing `"finished":true` to the meta file refreshes the view as `ENDED` and stops the following.
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

    /// A sub-agent whose end the stream reported before its view opened is shown as `ENDED` with its whole transcript
    /// and is never followed.
    @Test
    void aSubagentThatEndedBeforeItsViewOpenedIsShownOnceAsEnded() throws Exception {
        stream("agent:early");
        stream("done:early");
        agent("early", "all of it");

        adapter.showSubagent("work", "conv-1", "early");

        Intent.ShowView shown = sink.await(Intent.ShowView.class, WAIT);
        assertThat(state(shown)).isEqualTo(ClaudeAdapter.ENDED);
        assertThat(said("early")).contains("all of it");
        assertThat(adapter.followedSubagents()).isZero();
    }

    /// With the idle backstop at two periods, a sub-agent that writes nothing is refreshed as `STOPPED_IDLE` and no
    /// longer followed.
    @Test
    void theIdleBackstopStopsAndSaysSo() throws Exception {
        adapter.tailIdleMillis = 2 * PERIOD_MILLIS;
        stream("agent:idle");
        agent("idle", "first step");

        adapter.showSubagent("work", "conv-1", "idle");

        refreshEndingWith(ClaudeAdapter.STOPPED_IDLE);
        assertThat(adapter.followedSubagents()).isZero();
    }

    /// Stopping the session refreshes its sub-agent view as `STOPPED_SESSION` and ends the following.
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
