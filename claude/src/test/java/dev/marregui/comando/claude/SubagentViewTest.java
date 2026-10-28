package dev.marregui.comando.claude;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.McpGateway;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.Placement;
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

/// [ClaudeAdapter#showSubagent]: a sub-agent's stored conversation shown as a lower view, and followed while it grows
/// and its session runs.
/// - the view opens in the lower panel titled by the description and reads on as the file grows
/// - a finished sub-agent, a stopped session's and a forgotten session's are shown once and not followed
/// - a missing file or a failing open posts a warning and shows nothing
/// - at most [ClaudeAdapter#TAILS] follow at once, the oldest stopping first and saying so once
/// - sub-agents sharing a description get distinct titles and tabs; the same one opened twice is followed once
/// - `close()` returns quickly with opens queued, and nothing is shown or noticed after it
class SubagentViewTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    /** The follow period here, shorter than the app's so the suite does not wait out its 2 s. */
    private static final long PERIOD_MILLIS = 100;
    /** Long enough for at least three follow periods. */
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
        Path exe = tmp.resolve("fake-claude.sh");
        try (InputStream in = getClass().getResourceAsStream("/fake-claude.sh")) {
            Files.write(exe, in.readAllBytes());
        }
        exe.toFile().setExecutable(true);
        adapter = new ClaudeAdapter(sink, transcripts, McpGateway.NONE,
                new ClaudeSettings(exe.toString(), tmp.resolve("run"), projects, UiInstructions.TEXT));
        adapter.tailEveryMillis = PERIOD_MILLIS;
    }

    @AfterEach
    void tearDown() {
        adapter.close();
    }

    /** Starts the session {@code work}, whose sub-agents are then followed. */
    private void live() {
        adapter.start(SessionSpec.fresh("work", tmp.toString()));
        sink.await(Intent.SessionStarted.class, WAIT);
    }

    private void agent(String id, String description, boolean finished, String firstLine) throws IOException {
        Files.writeString(subagents.resolve("agent-" + id + ".meta.json"), "{\"agentType\":\"general-purpose\",\"description\":\""
                + description + "\",\"toolUseId\":\"toolu_" + id + "\"" + (finished ? ",\"finished\":true" : "") + "}");
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

    /** The texts of the tab's transcript: sub-agent {@code toolUseId}'s, as read so far. */
    private List<String> said(String toolUseId) {
        Transcript t = transcripts.of(TranscriptRegistry.subagentKey("work", toolUseId));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < t.size(); i++) {
            if (t.get(i) instanceof Block.AssistantText a) {
                out.add(a.text());
            }
        }
        return out;
    }

    private void awaitSaid(String toolUseId, String text) {
        Awaitility.await("the tab shows " + text).atMost(WAIT).until(() -> said(toolUseId).contains(text));
    }

    private boolean tabOf(Intent.ShowView v, String toolUseId) {
        return v.spec() instanceof ViewSpec.Agent a && a.toolUseId().equals(toolUseId);
    }

    private <T extends Intent> Optional<T> within(Class<T> type, Predicate<T> match, Duration d) {
        try {
            return Optional.of(sink.await(type, match, d));
        } catch (AssertionError e) {
            return Optional.empty();
        }
    }

    /// The view opens in the lower placement titled "agent · Count the widgets" as `FOLLOWING`, holds the first step, is
    /// followed, and after the file grows holds both steps, read on from where it was.
    @Test
    void showsTheSubagentAsALowerViewAndFollowsItsFileWhileItGrows() throws Exception {
        live();
        agent("a1", "Count the widgets", false, "first step");

        adapter.showSubagent("work", "conv-1", "toolu_a1");

        Intent.ShowView shown = sink.await(Intent.ShowView.class, WAIT);
        assertThat(shown.session()).isEmpty();
        assertThat(shown.title()).isEqualTo("agent · Count the widgets");
        assertThat(shown.placement()).isEqualTo(Placement.LOWER);
        assertThat(shown.spec()).isEqualTo(new ViewSpec.Agent("work", "toolu_a1", "Count the widgets", ClaudeAdapter.FOLLOWING));
        assertThat(said("toolu_a1")).containsExactly("first step");
        assertThat(adapter.followedSubagents()).isEqualTo(1);

        grow("a1", "second step");

        awaitSaid("toolu_a1", "second step");
        assertThat(said("toolu_a1")).as("read on from where it was").containsExactly("first step", "second step");
    }

    /// A sub-agent whose meta file says it finished is shown and not followed.
    @Test
    void aFinishedSubagentIsShownOnceAndNotFollowed() throws Exception {
        live();
        agent("a3", "Read the sprockets", true, "done");

        adapter.showSubagent("work", "conv-1", "toolu_a3");

        sink.await(Intent.ShowView.class, WAIT);
        assertThat(adapter.followedSubagents()).isZero();
    }

    /// A sub-agent of a session that is not running is shown and not followed, as its sub-agents write no more.
    @Test
    void aStoppedSessionsSubagentIsShownOnceAndNotFollowed() throws Exception {
        agent("s1", "Late", false, "first");
        adapter.loadHistory(SessionSpec.imported("work", tmp.toString(), "conv-1"));

        adapter.showSubagent("work", "conv-1", "toolu_s1");

        sink.await(Intent.ShowView.class, WAIT);
        assertThat(adapter.followedSubagents()).as("its sub-agents write no more").isZero();
    }

    /// After its session is forgotten, a sub-agent shown for it is not followed.
    @Test
    void aForgottenSessionsSubagentIsNotFollowed() throws Exception {
        live();
        agent("f1", "Late", false, "first");
        adapter.forget("work");

        adapter.showSubagent("work", "conv-1", "toolu_f1");

        sink.await(Intent.ShowView.class, WAIT);
        assertThat(adapter.followedSubagents()).isZero();
    }

    /// A `forget` issued right after a `showSubagent`, before its open completes, leaves no sub-agent followed.
    @Test
    void aForgetQueuedBehindAPendingOpenStopsItsTail() throws Exception {
        live();
        agent("p1", "Pending", false, "first");

        adapter.showSubagent("work", "conv-1", "toolu_p1");
        adapter.forget("work");

        assertThat(adapter.followedSubagents()).isZero();
    }

    /// An id with no transcript file posts the warning "Session work: transcript not found for this sub-agent" and shows no
    /// view.
    @Test
    void noFileForTheCallSaysSo() {
        adapter.showSubagent("work", "conv-1", "toolu_missing");

        Intent.Notify n = sink.await(Intent.Notify.class, WAIT);
        assertThat(n.level()).isEqualTo(Notice.Level.WARN);
        assertThat(n.text()).isEqualTo("Session work: transcript not found for this sub-agent");
        assertThat(sink.of(Intent.ShowView.class)).isEmpty();
    }

    /// A conversation id with a NUL character, which no path can hold, posts the warning "Session work: cannot open the
    /// sub-agent's transcript".
    @Test
    void anOpenThatFailsSaysSo() {
        adapter.showSubagent("work", "conv\0bad", "toolu_any");

        Intent.Notify n = sink.await(Intent.Notify.class, WAIT);
        assertThat(n.level()).isEqualTo(Notice.Level.WARN);
        assertThat(n.text()).isEqualTo("Session work: cannot open the sub-agent's transcript");
    }

    /// Opening one more than [ClaudeAdapter#TAILS] sub-agents keeps that many followed: the first is refreshed once as
    /// `STOPPED_TOO_MANY`, its later growth is not read, and the newest one's growth is.
    @Test
    void atMostFourFollowAndTheOldestStopsFirst() throws Exception {
        live();
        for (int i = 1; i <= 5; i++) {
            agent("b" + i, "agent " + i, false, "step");
            adapter.showSubagent("work", "conv-1", "toolu_b" + i);
        }
        assertThat(adapter.followedSubagents()).isEqualTo(ClaudeAdapter.TAILS);

        // the first one no longer follows: its view says so once, and its growth refreshes nothing; the fifth's does
        Intent.RefreshView stopped = sink.await(Intent.RefreshView.class, r -> r.title().equals("agent · agent 1"), WAIT);
        assertThat(state(stopped)).isEqualTo(ClaudeAdapter.STOPPED_TOO_MANY);
        grow("b1", "more");
        grow("b5", "more");
        awaitSaid("toolu_b5", "more");
        within(Intent.RefreshView.class, r -> false, QUIET);
        assertThat(said("toolu_b1")).as("no longer read").doesNotContain("more");
        assertThat(sink.of(Intent.RefreshView.class)).filteredOn(r -> r.title().equals("agent · agent 1")).hasSize(1);
    }

    /// Two sub-agents described "Review the diff" get "agent · Review the diff" and "… (2)", each with its own transcript
    /// and growth; the first opened again keeps its title and is read again whole.
    @Test
    void twoSubagentsWithOneDescriptionGetTwoViews() throws Exception {
        live();
        agent("x1", "Review the diff", false, "A-first");
        agent("x2", "Review the diff", false, "B-first");

        adapter.showSubagent("work", "conv-1", "toolu_x1");
        Intent.ShowView a = sink.await(Intent.ShowView.class, v -> tabOf(v, "toolu_x1"), WAIT);
        adapter.showSubagent("work", "conv-1", "toolu_x2");
        Intent.ShowView b = sink.await(Intent.ShowView.class, v -> tabOf(v, "toolu_x2"), WAIT);
        assertThat(said("toolu_x1")).containsExactly("A-first");
        assertThat(said("toolu_x2")).containsExactly("B-first");
        assertThat(a.title()).isEqualTo("agent · Review the diff");
        assertThat(b.title()).isEqualTo("agent · Review the diff (2)");
        assertThat(adapter.followedSubagents()).isEqualTo(2);

        grow("x1", "A-more");

        awaitSaid("toolu_x1", "A-more");
        assertThat(said("toolu_x2")).as("A's growth goes to A's tab, never B's").containsExactly("B-first");

        // the same sub-agent opened again keeps its title, read again whole
        int shownBefore = sink.of(Intent.ShowView.class).size();
        adapter.showSubagent("work", "conv-1", "toolu_x1");
        assertThat(sink.await(Intent.ShowView.class, v -> tabOf(v, "toolu_x1") && sink.of(Intent.ShowView.class).size() > shownBefore, WAIT).title())
                .isEqualTo(a.title());
        assertThat(said("toolu_x1")).containsExactly("A-first", "A-more");
    }

    /// Showing the same sub-agent twice leaves one follower, whose transcript reads each step once.
    @Test
    void theSameSubagentOpenedTwiceIsFollowedOnce() throws Exception {
        live();
        agent("d1", "Twice", false, "first");

        adapter.showSubagent("work", "conv-1", "toolu_d1");
        adapter.showSubagent("work", "conv-1", "toolu_d1");
        assertThat(adapter.followedSubagents()).isEqualTo(1);

        grow("d1", "second");
        awaitSaid("toolu_d1", "second");
        within(Intent.RefreshView.class, r -> false, QUIET); // three more periods
        assertThat(said("toolu_d1")).as("read once, by one follower").containsExactly("first", "second");
    }

    /// With six opens queued, `close()` returns in under 5 s, and a show afterwards posts no view and no notice within 1 s.
    @Test
    void closeWithOpensQueuedReturnsAndALaterShowDoesNothing() throws Exception {
        live();
        for (int i = 1; i <= 6; i++) {
            agent("q" + i, "q" + i, false, "first");
            adapter.showSubagent("work", "conv-1", "toolu_q" + i);
        }
        long t0 = System.nanoTime();
        adapter.close();
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));

        sink.clear();
        adapter.showSubagent("work", "conv-1", "toolu_q1");
        assertThat(within(Intent.class, x -> x instanceof Intent.ShowView || x instanceof Intent.Notify, Duration.ofSeconds(1))).isEmpty();
    }
}
