package dev.marregui.comando.claude;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.McpGateway;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.RecordingSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// The sub-agent tail of [ClaudeAdapter] and [SubagentFiles#render] at their edges.
/// - a cut inside or after a CRLF line ending keeps every line after it whole
/// - a file at the size limit renders whole; one byte over, it is cut with a notice
/// - a stopped session's sub-agents are not followed; restarted, they are followed again
/// - a followed sub-agent keeps its title against another with the same description, past the title map's 256 entries
/// - an open under way when `close()` returns sends nothing afterward
class SubagentTailEdgesTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @TempDir
    Path tmp;
    private final ObjectMapper json = new ObjectMapper();
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

    private void agent(String id, String description) throws IOException {
        Files.writeString(subagents.resolve("agent-" + id + ".meta.json"), "{\"agentType\":\"general-purpose\",\"description\":\""
                + description + "\",\"toolUseId\":\"toolu_" + id + "\"}");
        Files.writeString(subagents.resolve("agent-" + id + ".jsonl"), text(id + "-first") + "\n");
    }

    private static String text(String t) {
        return "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"" + t + "\"}]}}";
    }

    /** The sub-agent tab shown whose transcript holds {@code marker} (record 109). */
    private Intent.ShowView shownWith(String marker) {
        return sink.await(Intent.ShowView.class, v -> v.spec() instanceof ViewSpec.Agent a && says(a, marker), WAIT);
    }

    private boolean says(ViewSpec.Agent a, String marker) {
        Transcript t = transcripts.of(TranscriptRegistry.subagentKey(a.session(), a.toolUseId()));
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
        String first = text("FIRST");
        String second = text("SECOND");
        String third = text("THIRD");
        Path f = tmp.resolve("crlf.jsonl");
        Files.writeString(f, first + "\r\n" + second + "\r\n" + third + "\r\n");
        long afterCrLf = (second + "\r\n" + third + "\r\n").getBytes(StandardCharsets.UTF_8).length;
        String md = SubagentFiles.render(f, afterCrLf, json);
        assertThat(md).startsWith("_The conversation is longer than").doesNotContain("FIRST").contains("SECOND", "THIRD");

        // the cut between the \r and the \n ending FIRST: the line from the cut is only "\n"; SECOND is whole
        String md2 = SubagentFiles.render(f, afterCrLf + 1, json);
        assertThat(md2).doesNotContain("FIRST").contains("SECOND", "THIRD");
        // the cut on the \r itself: FIRST is cut
        String md3 = SubagentFiles.render(f, afterCrLf + 2, json);
        assertThat(md3).doesNotContain("FIRST").contains("SECOND", "THIRD");
    }

    /// A CRLF file rendered with a limit equal to its size has no notice and both lines; one byte under its size it
    /// starts with the notice and keeps only the last line.
    @Test
    void sizeAtTheLimitAndOneOverWithCrLf() throws IOException {
        String first = text("FIRST");
        String last = text("LAST");
        Path f = tmp.resolve("lim.jsonl");
        Files.writeString(f, first + "\r\n" + last + "\r\n");
        long size = Files.size(f);
        assertThat(SubagentFiles.render(f, size, json)).doesNotContain("is longer than").contains("FIRST", "LAST");
        assertThat(SubagentFiles.render(f, size - 1, json)).startsWith("_The conversation is longer than").doesNotContain("FIRST").contains("LAST");
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
        Intent.ShowView a = shownWith("a-first");
        for (int i = 0; i < 256; i++) {
            adapter.showSubagent("work", "conv-1", "toolu_a"); // still followed, among the newest tails
            agent("x" + i, "other " + i);
            adapter.showSubagent("work", "conv-1", "toolu_x" + i);
        }
        agent("b", "Review the diff");
        adapter.showSubagent("work", "conv-1", "toolu_b");
        Intent.ShowView b = shownWith("b-first");
        assertThat(adapter.followedSubagents()).isEqualTo(ClaudeAdapter.TAILS);
        assertThat(adapter.followedSubagents()).as("A followed").isPositive();

        assertThat(b.title()).as("B's title while A, titled '%s', is still followed", a.title()).isNotEqualTo(a.title());
    }

    /// With the tail's thread blocked opening a FIFO past the 2 s wait, a `close()` followed by releasing the FIFO
    /// sends no further intent, neither a late view nor a false failure, and the tail thread ends.
    @Test
    void anOpenUnderWayWhenCloseReturnsSendsNothingAfterward() throws Exception {
        live();
        Files.writeString(subagents.resolve("agent-s.meta.json"), "{\"description\":\"Slow\",\"toolUseId\":\"toolu_s\"}");
        Path fifo = subagents.resolve("agent-s.jsonl");
        Process mk = new ProcessBuilder("mkfifo", fifo.toString()).start();
        assertThat(mk.waitFor()).isZero();

        adapter.showSubagent("work", "conv-1", "toolu_s");
        // one sample decides: the tail's thread is virtual, and a virtual thread held in native file I/O does not give
        // the same stack to every sample
        long deadline = System.nanoTime() + WAIT.toNanos();
        boolean blocked = blockedInOpen();
        while (!blocked && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            blocked = blockedInOpen();
        }
        assertThat(blocked).as("tail thread blocked opening the FIFO").isTrue();

        adapter.close();
        int before = sink.intents().size();
        try (OutputStream out = Files.newOutputStream(fifo)) { // releases the blocked open
            out.write((text("late") + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // the reader may have closed already: a broken pipe
        }
        // the read released, the tail thread finishes its task and ends
        long end = System.nanoTime() + WAIT.toNanos();
        while (tailThreadAlive() && System.nanoTime() < end) {
            Thread.onSpinWait();
        }
        assertThat(tailThreadAlive()).as("the tail thread ended").isFalse();
        List<Intent> after = sink.intents().subList(before, sink.intents().size());
        assertThat(after).as("intents sent after close() returned").isEmpty();
    }

    private static boolean tailThreadAlive() {
        return !Threads.alive("subagent-tail").isEmpty();
    }

    private static boolean blockedInOpen() {
        return Threads.alive("subagent-tail").stream()
                .anyMatch(t -> Arrays.stream(t.getStackTrace()).anyMatch(s -> (s.getClassName().equals("java.io.RandomAccessFile")
                        || s.getClassName().startsWith("sun.nio.fs.")) && s.getMethodName().startsWith("open")));
    }
}
