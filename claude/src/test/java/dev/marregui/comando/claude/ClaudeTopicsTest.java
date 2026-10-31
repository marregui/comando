package dev.marregui.comando.claude;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import dev.marregui.comando.core.model.Topic;

import static org.assertj.core.api.Assertions.assertThat;

/// [ClaudeTopics] reads tab topics one at a time and cleans each answer for display.
///
/// - Cleans successful answers into a single capped line.
/// - Explains malformed, empty, timed-out, and unavailable reads.
/// - Queues the latest request per panel and puts urgent requests first.
/// - Stops pending and active work on close.
class ClaudeTopicsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private static String answer(final String result, final boolean error) {
        final ObjectNode n = JSON.createObjectNode();
        n.put("type", "result");
        n.put("is_error", error);
        n.put("result", result);
        return n.toString();
    }

    /// Successful JSON results collapse whitespace, remove an added heading or matched wrapper quotes, and cap a long
    /// answer at `MAX_TEXT`, so a card receives one readable line.
    @Test
    void theWholeAnswerIsTheSummaryOnOneParagraphWithoutTheQuotesOrHeadingAModelAdds() {
        assertThat(ClaudeTopics.parse(answer("\n\"In comando, fixing the flaky key test.\nUiKeysTest reads the focus owner.\"\n", false), 7L, JSON))
                .isEqualTo(new Topic("In comando, fixing the flaky key test. UiKeysTest reads the focus owner.", 7L, ""));
        assertThat(ClaudeTopics.parse(answer("Summary: `Sticky tab cards in comando.`", false), 1L, JSON).line()).isEqualTo("Sticky tab cards in comando.");
        assertThat(ClaudeTopics.parse(answer("Waiting...", false), 1L, JSON).line()).isEqualTo("Waiting...");
        assertThat(ClaudeTopics.parse(answer("x".repeat(600), false), 1L, JSON).line()).hasSize(ClaudeTopics.MAX_TEXT);
    }

    /// Error JSON, blank success text, and non-JSON output each become a specific problem instead of a displayable
    /// topic line.
    @Test
    void anAnswerWithoutALineSaysWhy() {
        assertThat(ClaudeTopics.parse(answer("Not logged in", true), 1L, JSON).problem()).isEqualTo("Not logged in");
        assertThat(ClaudeTopics.parse(answer(" \n ", false), 1L, JSON).problem()).isEqualTo("an empty answer from Haiku");
        assertThat(ClaudeTopics.parse("<html>proxy error</html>", 1L, JSON).problem()).isEqualTo("unreadable answer from Haiku");
    }

    /// A real read invokes the safe, low-effort Haiku command without tools or persisted session, sends the source on
    /// stdin, and sets `MAX_THINKING_TOKENS` to zero.
    @Test
    void theReadAsksHaikuWithoutToolsThinkingOrASavedSessionAndSendsTheSourceOnItsInput() throws Exception {
        final Path args = dir.resolve("args.txt");
        final Path input = dir.resolve("input.txt");
        final Path env = dir.resolve("env.txt");
        final Path exe = script("claude", "printf '%s\\n' \"$@\" > '" + args + "'\ncat > '" + input + "'\nprintf '%s' \"$MAX_THINKING_TOKENS\" > '" + env
                + "'\nprintf '%s' '" + answer("Sticky tab cards", false) + "'\n");

        final Topic topic = ClaudeTopics.read(exe.toString(), RootLaunch.INHERIT, dir, JSON, "User: make the card sticky", Duration.ofSeconds(10));

        assertThat(topic.line()).isEqualTo("Sticky tab cards");
        assertThat(Files.readAllLines(args)).containsExactly("--safe-mode", "-p", "--model", "haiku", "--effort", "low", "--tools", "",
                "--system-prompt", ClaudeTopics.SYSTEM, "--no-session-persistence", "--output-format", "json");
        assertThat(Files.readString(input)).isEqualTo("User: make the card sticky");
        assertThat(Files.readString(env)).isEqualTo("0");
    }

    /// A process exceeding a 300 ms or one-second deadline is killed with a duration-specific problem, while a missing
    /// executable reports that it cannot run.
    @Test
    void aReadThatHangsIsStoppedAndAMissingExecutableIsAProblem() throws Exception {
        final Path exe = script("hang", "exec sleep 30\n");
        final long start = System.nanoTime();
        final Topic topic = ClaudeTopics.read(exe.toString(), RootLaunch.INHERIT, dir, JSON, "x", Duration.ofMillis(300));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(topic.problem()).isEqualTo("Haiku did not answer within 300 ms");
        assertThat(ClaudeTopics.read(exe.toString(), RootLaunch.INHERIT, dir, JSON, "x", Duration.ofSeconds(1)).problem()).isEqualTo("Haiku did not answer within 1 s");
        assertThat(ClaudeTopics.read(dir.resolve("absent").toString(), RootLaunch.INHERIT, dir, JSON, "x", Duration.ofSeconds(1)).problem()).startsWith("cannot run '");
    }

    /// While `a0` is reading, newer requests for `a` coalesce to `a3`, urgent `c0` moves ahead of waiting `b0`, and
    /// the single worker never runs two reads concurrently.
    @Test
    void asksAreReadOneAtATimeAndANewerAskForAWaitingKeyReplacesIt() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger most = new AtomicInteger();
        final List<String> read = new CopyOnWriteArrayList<>();
        try (ClaudeTopics topics = new ClaudeTopics(source -> {
            most.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            read.add(source);
            running.decrementAndGet();
            return new Topic("t " + source, 1L, "");
        })) {
            final List<String> done = new CopyOnWriteArrayList<>();
            final CountDownLatch all = new CountDownLatch(4);
            topics.read("a", "a0", false, t -> {
                done.add(t.line());
                all.countDown();
            });
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> running.get() == 1);
            // while a0 is read: b waits, a is asked again three times, c is urgent
            topics.read("b", "b0", false, t -> {
                done.add(t.line());
                all.countDown();
            });
            for (int i = 1; i <= 3; i++) {
                final String source = "a" + i;
                topics.read("a", source, false, t -> {
                    done.add(t.line());
                    all.countDown();
                });
            }
            topics.read("c", "c0", true, t -> {
                done.add(t.line());
                all.countDown();
            });
            release.countDown();
            assertThat(all.await(5, TimeUnit.SECONDS)).isTrue();
            // a fifth read, were there one, would land here
            Awaitility.await().during(Duration.ofMillis(100)).atMost(Duration.ofSeconds(2)).until(() -> read.size() == 4);

            assertThat(most.get()).as("one read at a time").isEqualTo(1);
            assertThat(read).as("the urgent ask first, then the waiting ones in order, a's latest source only")
                    .containsExactly("a0", "c0", "b0", "a3");
            assertThat(done).containsExactly("t a0", "t c0", "t b0", "t a3");
        }
    }

    /// Closing during read `a` interrupts it, drops queued `b`, and ignores later `c`, so no callback runs and only one
    /// reader invocation occurs.
    @Test
    void closeDropsTheWaitingAsksAndStopsTheReadUnderWay() throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch never = new CountDownLatch(1);
        final AtomicInteger reads = new AtomicInteger();
        final List<Topic> done = new CopyOnWriteArrayList<>();
        final ClaudeTopics topics = new ClaudeTopics(source -> {
            reads.incrementAndGet();
            started.countDown();
            try {
                never.await(10, TimeUnit.SECONDS); // a read that outlasts the close
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Topic("late", 1L, "");
        });
        topics.read("a", "a", false, done::add);
        topics.read("b", "b", false, done::add);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        final long start = System.nanoTime();
        topics.close();
        topics.close();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        topics.read("c", "c", false, done::add);
        Awaitility.await().during(Duration.ofMillis(100)).atMost(Duration.ofSeconds(2)).until(() -> reads.get() == 1 && done.isEmpty());
    }

    private Path script(final String name, final String body) throws Exception {
        final Path exe = dir.resolve(name);
        Files.writeString(exe, "#!/bin/sh\n" + body);
        exe.toFile().setExecutable(true);
        return exe;
    }
}
