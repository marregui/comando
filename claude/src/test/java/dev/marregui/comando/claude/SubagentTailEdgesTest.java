package dev.marregui.comando.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.Threads;
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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The sub-agent tail at its edges: close with a read under way, titles kept by followed sub-agents, restarts, the cut. */
class SubagentTailEdgesTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @TempDir
    Path tmp;
    private final ObjectMapper json = new ObjectMapper();
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

    private Intent.ShowView shownWith(String marker) {
        return sink.await(Intent.ShowView.class, v -> ((ViewSpec.Markdown) v.spec()).markdown().contains(marker), WAIT);
    }

    // ---- the cut, with CRLF lines

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

    // ---- the live flag across a stop and a restart

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

    // ---- the title map's eviction

    /**
     * A followed sub-agent re-opened keeps its title, but the map evicts by first insertion: after 256 other titles, its
     * title is free while its tail still runs, and another sub-agent with its description takes the same title.
     */
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

    // ---- close while an open is under way

    /**
     * close() says a read under way sends nothing after it returns. A read that blocks past the 2 s wait (a FIFO stands
     * for a slow file system) sends its view after close, then fails to schedule its tail and reports a false failure.
     */
    @Test
    void anOpenUnderWayWhenCloseReturnsSendsNothingAfterward() throws Exception {
        live();
        Files.writeString(subagents.resolve("agent-s.meta.json"), "{\"description\":\"Slow\",\"toolUseId\":\"toolu_s\"}");
        Path fifo = subagents.resolve("agent-s.jsonl");
        Process mk = new ProcessBuilder("mkfifo", fifo.toString()).start();
        assertThat(mk.waitFor()).isZero();

        adapter.showSubagent("work", "conv-1", "toolu_s");
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!blockedInOpen() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(blockedInOpen()).as("tail thread blocked opening the FIFO").isTrue();

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
