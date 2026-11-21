package dev.marregui.comando.claude;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.McpGateway;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;

/// The sub-agent tail of [ClaudeAdapter] and [SubagentFiles#render] at their edges.
/// - a cut inside or after a CRLF line ending keeps every line after it whole
/// - a file at the size limit renders whole; one byte over, it is cut with a notice
/// - a stopped session's sub-agents are not followed; restarted, they are followed again
/// - a followed sub-agent keeps its title against another with the same description, past the title map's 256 entries
/// - a FIFO in place of a transcript is refused with a notice, never opened
class SubagentTailEdgesTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @TempDir
    Path tmp;
    private final RecordingSink sink = new RecordingSink();
    private final TranscriptRegistry transcripts = new TranscriptRegistry();
    private ClaudeAdapter adapter;
    private Path subagents;

    @BeforeEach
    void setUp() throws IOException {
        final Path projects = tmp.resolve("projects");
        subagents = Files.createDirectories(projects.resolve("-w/conv-1/subagents"));
        final Path exe = tmp.resolve("fake-claude.sh");
        try (final InputStream in = getClass().getResourceAsStream("/fake-claude.sh")) {
            Files.write(exe, in.readAllBytes());
        }
        exe.toFile().setExecutable(true);
        adapter = new ClaudeAdapter(sink, transcripts, McpGateway.NONE,
                new ClaudeSettings(exe.toString(), tmp.resolve("run"), projects, UiInstructions.text("")));
        adapter.tailEveryMillis = 100; // the app's 2 s period, shortened so the suite does not wait it out
    }

    @AfterEach
    void tearDown() {
        adapter.close();
    }

    private void live() {
        adapter.start(SessionSpec.fresh("work", tmp.toString()));
        sink.await(Intent.SessionStarted.class, WAIT);
    }

    private void agent(final String id, final String description) throws IOException {
        Files.writeString(subagents.resolve("agent-" + id + ".meta.json"), "{\"agentType\":\"general-purpose\",\"description\":\""
                + description + "\",\"toolUseId\":\"toolu_" + id + "\"}");
        Files.writeString(subagents.resolve("agent-" + id + ".jsonl"), text(id + "-first") + "\n");
    }

    private static String text(final String t) {
        return "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"" + t + "\"}]}}";
    }

    /** The sub-agent tab shown whose transcript holds {@code marker} (record 109). */
    private Intent.ShowView shownWith(final String marker) {
        return sink.await(Intent.ShowView.class, v -> v.spec() instanceof ViewSpec.Agent a && says(a, marker), WAIT);
    }

    private boolean says(final ViewSpec.Agent a, final String marker) {
        final Transcript t = transcripts.of(TranscriptRegistry.subagentKey(a.session(), a.toolUseId()));
        for (int i = 0; i < t.size(); i++) {
            if (t.get(i) instanceof Block.AssistantText x && x.text().contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /// Rendering the last bytes of a CRLF file from just after a line's `\r\n`, from between its `\r` and `\n`, and
    /// from on its `\r` each drops the cut line and keeps the following lines whole, under the "longer than" notice.
    @Test
    void aCutBetweenCrAndLfAndACutAfterCrLfKeepTheWholeLine() throws IOException {
        final String first = text("FIRST");
        final String second = text("SECOND");
        final String third = text("THIRD");
        final Path f = tmp.resolve("crlf.jsonl");
        Files.writeString(f, first + "\r\n" + second + "\r\n" + third + "\r\n");
        final long afterCrLf = (second + "\r\n" + third + "\r\n").getBytes(StandardCharsets.UTF_8).length;
        final String md = SubagentFiles.render(f, afterCrLf);
        assertThat(md).startsWith("_The conversation is longer than").doesNotContain("FIRST").contains("SECOND", "THIRD");

        // the cut between the \r and the \n ending FIRST: the line from the cut is only "\n"; SECOND is whole
        final String md2 = SubagentFiles.render(f, afterCrLf + 1);
        assertThat(md2).doesNotContain("FIRST").contains("SECOND", "THIRD");
        // the cut on the \r itself: FIRST is cut
        final String md3 = SubagentFiles.render(f, afterCrLf + 2);
        assertThat(md3).doesNotContain("FIRST").contains("SECOND", "THIRD");
    }

    /// A CRLF file rendered with a limit equal to its size has no notice and both lines; one byte under its size it
    /// starts with the notice and keeps only the last line.
    @Test
    void sizeAtTheLimitAndOneOverWithCrLf() throws IOException {
        final String first = text("FIRST");
        final String last = text("LAST");
        final Path f = tmp.resolve("lim.jsonl");
        Files.writeString(f, first + "\r\n" + last + "\r\n");
        final long size = Files.size(f);
        assertThat(SubagentFiles.render(f, size)).doesNotContain("is longer than").contains("FIRST", "LAST");
        assertThat(SubagentFiles.render(f, size - 1)).startsWith("_The conversation is longer than")
                .doesNotContain("FIRST").contains("LAST");
    }

    /// A sub-agent shown while its session is stopped is not followed (`followedSubagents()` is 0); shown again after
    /// the session restarts, it is followed (1).
    @Test
    void aStoppedThenRestartedSessionIsFollowedAgainAndNotInBetween() throws Exception {
        live();
        agent("r1", "Restart");
        adapter.stop("work");
        sink.await(Intent.SessionExited.class, WAIT);

        adapter.showSubagent("work", "conv-1", "toolu_r1");
        shownWith("r1-first");
        assertThat(adapter.followedSubagents()).as("stopped").isZero();

        sink.clear();
        live();
        adapter.showSubagent("work", "conv-1", "toolu_r1");
        shownWith("r1-first");
        assertThat(adapter.followedSubagents()).as("restarted").isEqualTo(1);
    }

    /// A followed sub-agent re-opened between 256 other sub-agents keeps its tail; a later sub-agent with the same
    /// description gets a different title, though the title map, which evicts by first insertion, has dropped the
    /// first's.
    @Test
    void aLiveTailsTitleIsNotGivenToAnotherSubagent() throws Exception {
        live();
        agent("a", "Review the diff");
        adapter.showSubagent("work", "conv-1", "toolu_a");
        final Intent.ShowView a = shownWith("a-first");
        for (int i = 0; i < 256; i++) {
            adapter.showSubagent("work", "conv-1", "toolu_a"); // still followed, among the newest tails
            agent("x" + i, "other " + i);
            adapter.showSubagent("work", "conv-1", "toolu_x" + i);
        }
        agent("b", "Review the diff");
        adapter.showSubagent("work", "conv-1", "toolu_b");
        final Intent.ShowView b = shownWith("b-first");
        assertThat(adapter.followedSubagents()).isEqualTo(ClaudeAdapter.TAILS);
        assertThat(adapter.followedSubagents()).as("A followed").isPositive();

        assertThat(b.title()).as("B's title while A, titled '%s', is still followed", a.title()).isNotEqualTo(a.title());
    }

    /// A FIFO in place of a sub-agent's transcript is refused, not opened: the session is told the transcript cannot be
    /// read and no view opens. Opened, the FIFO would hold the tail's thread until a writer came, and no notice would.
    @Test
    void aFifoInPlaceOfTheTranscriptIsRefusedWithANotice() throws Exception {
        live();
        Files.writeString(subagents.resolve("agent-s.meta.json"), "{\"description\":\"Slow\",\"toolUseId\":\"toolu_s\"}");
        final Path fifo = subagents.resolve("agent-s.jsonl");
        final Process mk = new ProcessBuilder("mkfifo", fifo.toString()).start();
        assertThat(mk.waitFor()).isZero();
        final int before = sink.intents().size();

        adapter.showSubagent("work", "conv-1", "toolu_s");

        final Intent.Notify notice = sink.await(Intent.Notify.class, n -> n.text().contains("cannot read the sub-agent"), WAIT);
        assertThat(notice.level()).isEqualTo(Notice.Level.WARN);
        assertThat(sink.intents().subList(before, sink.intents().size()))
                .as("no view opened for the refused transcript").noneMatch(Intent.ShowView.class::isInstance);
    }
}
