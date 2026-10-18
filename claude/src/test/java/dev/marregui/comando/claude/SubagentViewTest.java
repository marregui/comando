package dev.marregui.comando.claude;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.McpGateway;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.Placement;
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

/** A sub-agent's stored conversation shown as a lower view, and followed while it grows and its session runs. */
class SubagentViewTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    /** Long enough for at least three follow periods. */
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
        Path exe = tmp.resolve("fake-claude.sh");
        try (InputStream in = getClass().getResourceAsStream("/fake-claude.sh")) {
            Files.write(exe, in.readAllBytes());
        }
        exe.toFile().setExecutable(true);
        adapter = new ClaudeAdapter(sink, new TranscriptRegistry(), McpGateway.NONE,
                new ClaudeSettings(exe.toString(), tmp.resolve("run"), projects, UiInstructions.TEXT));
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

    private static String md(Intent i) {
        return ((ViewSpec.Markdown) (i instanceof Intent.ShowView v ? v.spec() : ((Intent.RefreshView) i).spec())).markdown();
    }

    private <T extends Intent> Optional<T> within(Class<T> type, Predicate<T> match, Duration d) {
        try {
            return Optional.of(sink.await(type, match, d));
        } catch (AssertionError e) {
            return Optional.empty();
        }
    }

    @Test
    void showsTheSubagentAsALowerViewAndFollowsItsFileWhileItGrows() throws Exception {
        live();
        agent("a1", "Count the widgets", false, "first step");

        adapter.showSubagent("work", "conv-1", "toolu_a1");

        Intent.ShowView shown = sink.await(Intent.ShowView.class, WAIT);
        assertThat(shown.session()).isEmpty();
        assertThat(shown.title()).isEqualTo("agent · Count the widgets");
        assertThat(shown.placement()).isEqualTo(Placement.LOWER);
        assertThat(md(shown)).contains("first step");
        assertThat(adapter.followedSubagents()).isEqualTo(1);

        grow("a1", "second step");

        Intent.RefreshView refreshed = sink.await(Intent.RefreshView.class, WAIT);
        assertThat(refreshed.title()).isEqualTo(shown.title());
        assertThat(md(refreshed)).contains("first step", "second step");
    }

    @Test
    void aFinishedSubagentIsShownOnceAndNotFollowed() throws Exception {
        live();
        agent("a3", "Read the sprockets", true, "done");

        adapter.showSubagent("work", "conv-1", "toolu_a3");

        sink.await(Intent.ShowView.class, WAIT);
        assertThat(adapter.followedSubagents()).isZero();
    }

    @Test
    void aStoppedSessionsSubagentIsShownOnceAndNotFollowed() throws Exception {
        agent("s1", "Late", false, "first");
        adapter.loadHistory(SessionSpec.imported("work", tmp.toString(), "conv-1"));

        adapter.showSubagent("work", "conv-1", "toolu_s1");

        sink.await(Intent.ShowView.class, WAIT);
        assertThat(adapter.followedSubagents()).as("its sub-agents write no more").isZero();
    }

    @Test
    void aForgottenSessionsSubagentIsNotFollowed() throws Exception {
        live();
        agent("f1", "Late", false, "first");
        adapter.forget("work");

        adapter.showSubagent("work", "conv-1", "toolu_f1");

        sink.await(Intent.ShowView.class, WAIT);
        assertThat(adapter.followedSubagents()).isZero();
    }

    @Test
    void aForgetQueuedBehindAPendingOpenStopsItsTail() throws Exception {
        live();
        agent("p1", "Pending", false, "first");

        adapter.showSubagent("work", "conv-1", "toolu_p1");
        adapter.forget("work");

        assertThat(adapter.followedSubagents()).isZero();
    }

    @Test
    void noFileForTheCallSaysSo() {
        adapter.showSubagent("work", "conv-1", "toolu_missing");

        Intent.Notify n = sink.await(Intent.Notify.class, WAIT);
        assertThat(n.level()).isEqualTo(Notice.Level.WARN);
        assertThat(n.text()).isEqualTo("Session work: transcript not found for this sub-agent");
        assertThat(sink.of(Intent.ShowView.class)).isEmpty();
    }

    @Test
    void anOpenThatFailsSaysSo() {
        adapter.showSubagent("work", "conv\0bad", "toolu_any");

        Intent.Notify n = sink.await(Intent.Notify.class, WAIT);
        assertThat(n.level()).isEqualTo(Notice.Level.WARN);
        assertThat(n.text()).isEqualTo("Session work: cannot open the sub-agent's transcript");
    }

    @Test
    void atMostFourFollowAndTheOldestStopsFirst() throws Exception {
        live();
        for (int i = 1; i <= 5; i++) {
            agent("b" + i, "agent " + i, false, "step");
            adapter.showSubagent("work", "conv-1", "toolu_b" + i);
        }
        assertThat(adapter.followedSubagents()).isEqualTo(ClaudeAdapter.TAILS);

        // the first one no longer follows: its growth refreshes nothing; the fifth's does
        grow("b1", "more");
        grow("b5", "more");
        sink.await(Intent.RefreshView.class, r -> r.title().equals("agent · agent 5"), WAIT);
        assertThat(sink.of(Intent.RefreshView.class)).extracting(Intent.RefreshView::title).doesNotContain("agent · agent 1");
    }

    @Test
    void twoSubagentsWithOneDescriptionGetTwoViews() throws Exception {
        live();
        agent("x1", "Review the diff", false, "A-first");
        agent("x2", "Review the diff", false, "B-first");

        adapter.showSubagent("work", "conv-1", "toolu_x1");
        Intent.ShowView a = sink.await(Intent.ShowView.class, v -> md(v).contains("A-first"), WAIT);
        adapter.showSubagent("work", "conv-1", "toolu_x2");
        Intent.ShowView b = sink.await(Intent.ShowView.class, v -> md(v).contains("B-first"), WAIT);
        assertThat(a.title()).isEqualTo("agent · Review the diff");
        assertThat(b.title()).isEqualTo("agent · Review the diff (2)");
        assertThat(adapter.followedSubagents()).isEqualTo(2);

        grow("x1", "A-more");

        Intent.RefreshView r = sink.await(Intent.RefreshView.class, x -> md(x).contains("A-more"), WAIT);
        assertThat(r.title()).as("A's growth refreshes A's view, never B's").isEqualTo(a.title());

        // the same sub-agent opened again keeps its title
        adapter.showSubagent("work", "conv-1", "toolu_x1");
        assertThat(sink.await(Intent.ShowView.class, v -> md(v).contains("A-more"), WAIT).title()).isEqualTo(a.title());
    }

    @Test
    void theSameSubagentOpenedTwiceIsFollowedOnce() throws Exception {
        live();
        agent("d1", "Twice", false, "first");

        adapter.showSubagent("work", "conv-1", "toolu_d1");
        adapter.showSubagent("work", "conv-1", "toolu_d1");
        assertThat(adapter.followedSubagents()).isEqualTo(1);

        grow("d1", "second");
        sink.await(Intent.RefreshView.class, r -> md(r).contains("second"), WAIT);
        within(Intent.RefreshView.class, r -> false, QUIET); // three more periods
        assertThat(sink.of(Intent.RefreshView.class)).filteredOn(r -> md(r).contains("second")).hasSize(1);
    }

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
