package dev.marregui.comando.claude;

import dev.marregui.comando.core.intent.Intent;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SubagentViewTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @TempDir
    Path tmp;
    private final RecordingSink sink = new RecordingSink();
    private ClaudeAdapter adapter;
    private Path subagents;

    @BeforeEach
    void setUp() throws IOException {
        Path projects = tmp.resolve("projects");
        subagents = Files.createDirectories(projects.resolve("-w/conv-1/subagents"));
        adapter = new ClaudeAdapter(sink, new TranscriptRegistry(), McpGateway.NONE,
                new ClaudeSettings("claude", tmp.resolve("run"), projects, UiInstructions.TEXT));
    }

    @AfterEach
    void tearDown() {
        adapter.close();
    }

    private void agent(String id, String description, boolean finished, String firstLine) throws IOException {
        Files.writeString(subagents.resolve("agent-" + id + ".meta.json"), "{\"agentType\":\"general-purpose\",\"description\":\""
                + description + "\",\"toolUseId\":\"toolu_" + id + "\"" + (finished ? ",\"finished\":true" : "") + "}");
        Files.writeString(subagents.resolve("agent-" + id + ".jsonl"), firstLine + "\n");
    }

    private static String text(String t) {
        return "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"" + t + "\"}]}}";
    }

    @Test
    void showsTheSubagentAsALowerViewAndFollowsItsFileWhileItGrows() throws Exception {
        agent("a1", "Count the widgets", false, text("first step"));

        adapter.showSubagent("work", "conv-1", "toolu_a1");

        Intent.ShowView shown = sink.await(Intent.ShowView.class, WAIT);
        assertThat(shown.session()).isEmpty();
        assertThat(shown.title()).isEqualTo("agent · Count the widgets");
        assertThat(shown.placement()).isEqualTo(Placement.LOWER);
        assertThat(((ViewSpec.Markdown) shown.spec()).markdown()).contains("first step");
        assertThat(adapter.followedSubagents()).isEqualTo(1);

        Files.writeString(subagents.resolve("agent-a1.jsonl"), text("second step") + "\n", StandardOpenOption.APPEND);

        Intent.RefreshView refreshed = sink.await(Intent.RefreshView.class, WAIT);
        assertThat(refreshed.title()).isEqualTo(shown.title());
        assertThat(((ViewSpec.Markdown) refreshed.spec()).markdown()).contains("first step", "second step");
    }

    @Test
    void aFinishedSubagentIsShownOnceAndNotFollowed() throws Exception {
        agent("a3", "Read the sprockets", true, text("done"));

        adapter.showSubagent("work", "conv-1", "toolu_a3");

        sink.await(Intent.ShowView.class, WAIT);
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
    void atMostFourFollowAndTheOldestStopsFirst() throws Exception {
        for (int i = 1; i <= 5; i++) {
            agent("b" + i, "agent " + i, false, text("step"));
            adapter.showSubagent("work", "conv-1", "toolu_b" + i);
        }
        assertThat(adapter.followedSubagents()).isEqualTo(ClaudeAdapter.TAILS);

        // the first one no longer follows: its growth refreshes nothing; the fifth's does
        Files.writeString(subagents.resolve("agent-b1.jsonl"), text("more") + "\n", StandardOpenOption.APPEND);
        Files.writeString(subagents.resolve("agent-b5.jsonl"), text("more") + "\n", StandardOpenOption.APPEND);
        sink.await(Intent.RefreshView.class, r -> r.title().equals("agent · agent 5"), WAIT);
        assertThat(sink.of(Intent.RefreshView.class)).extracting(Intent.RefreshView::title).doesNotContain("agent · agent 1");
    }
}
