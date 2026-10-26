package dev.marregui.comando.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.marregui.comando.core.model.Topic;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeTopicsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private static String answer(String result, boolean error) {
        ObjectNode n = JSON.createObjectNode();
        n.put("type", "result");
        n.put("is_error", error);
        n.put("result", result);
        return n.toString();
    }

    @Test
    void theWholeAnswerIsTheSummaryOnOneParagraphWithoutTheQuotesOrHeadingAModelAdds() {
        assertThat(ClaudeTopics.parse(answer("\n\"In comando, fixing the flaky key test.\nUiKeysTest reads the focus owner.\"\n", false), 7L, JSON))
                .isEqualTo(new Topic("In comando, fixing the flaky key test. UiKeysTest reads the focus owner.", 7L, ""));
        assertThat(ClaudeTopics.parse(answer("Summary: `Sticky tab cards in comando.`", false), 1L, JSON).line()).isEqualTo("Sticky tab cards in comando.");
        assertThat(ClaudeTopics.parse(answer("Waiting...", false), 1L, JSON).line()).isEqualTo("Waiting...");
        assertThat(ClaudeTopics.parse(answer("x".repeat(600), false), 1L, JSON).line()).hasSize(ClaudeTopics.MAX_TEXT);
    }

    @Test
    void anAnswerWithoutALineSaysWhy() {
        assertThat(ClaudeTopics.parse(answer("Not logged in", true), 1L, JSON).problem()).isEqualTo("Not logged in");
        assertThat(ClaudeTopics.parse(answer(" \n ", false), 1L, JSON).problem()).isEqualTo("an empty answer from Haiku");
        assertThat(ClaudeTopics.parse("<html>proxy error</html>", 1L, JSON).problem()).isEqualTo("unreadable answer from Haiku");
    }

    @Test
    void theReadAsksHaikuWithoutToolsThinkingOrASavedSessionAndSendsTheSourceOnItsInput() throws Exception {
        Path args = dir.resolve("args.txt");
        Path input = dir.resolve("input.txt");
        Path env = dir.resolve("env.txt");
        Path exe = script("claude", "printf '%s\\n' \"$@\" > '" + args + "'\ncat > '" + input + "'\nprintf '%s' \"$MAX_THINKING_TOKENS\" > '" + env
                + "'\nprintf '%s' '" + answer("Sticky tab cards", false) + "'\n");

        Topic topic = ClaudeTopics.read(exe.toString(), RootLaunch.INHERIT, dir, JSON, "User: make the card sticky", Duration.ofSeconds(10));

        assertThat(topic.line()).isEqualTo("Sticky tab cards");
        assertThat(Files.readAllLines(args)).containsExactly("--safe-mode", "-p", "--model", "haiku", "--effort", "low", "--tools", "",
                "--system-prompt", ClaudeTopics.SYSTEM, "--no-session-persistence", "--output-format", "json");
        assertThat(Files.readString(input)).isEqualTo("User: make the card sticky");
        assertThat(Files.readString(env)).isEqualTo("0");
    }

    @Test
    void aReadThatHangsIsStoppedAndAMissingExecutableIsAProblem() throws Exception {
        Path exe = script("hang", "exec sleep 30\n");
        long start = System.nanoTime();
        Topic topic = ClaudeTopics.read(exe.toString(), RootLaunch.INHERIT, dir, JSON, "x", Duration.ofMillis(300));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(topic.problem()).isEqualTo("Haiku did not answer within 300 ms");
        assertThat(ClaudeTopics.read(exe.toString(), RootLaunch.INHERIT, dir, JSON, "x", Duration.ofSeconds(1)).problem()).isEqualTo("Haiku did not answer within 1 s");
        assertThat(ClaudeTopics.read(dir.resolve("absent").toString(), RootLaunch.INHERIT, dir, JSON, "x", Duration.ofSeconds(1)).problem()).startsWith("cannot run '");
    }

    @Test
    void asksAreReadOneAtATimeAndANewerAskForAWaitingKeyReplacesIt() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        List<String> read = new CopyOnWriteArrayList<>();
        try (ClaudeTopics topics = new ClaudeTopics(source -> {
            most.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            read.add(source);
            running.decrementAndGet();
            return new Topic("t " + source, 1L, "");
        })) {
            List<String> done = new CopyOnWriteArrayList<>();
            CountDownLatch all = new CountDownLatch(4);
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
                String source = "a" + i;
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

    @Test
    void closeDropsTheWaitingAsksAndStopsTheReadUnderWay() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        List<Topic> done = new CopyOnWriteArrayList<>();
        ClaudeTopics topics = new ClaudeTopics(source -> {
            reads.incrementAndGet();
            started.countDown();
            try {
                never.await(10, TimeUnit.SECONDS); // a read that outlasts the close
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Topic("late", 1L, "");
        });
        topics.read("a", "a", false, done::add);
        topics.read("b", "b", false, done::add);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        long start = System.nanoTime();
        topics.close();
        topics.close();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        topics.read("c", "c", false, done::add);
        Awaitility.await().during(Duration.ofMillis(100)).atMost(Duration.ofSeconds(2)).until(() -> reads.get() == 1 && done.isEmpty());
    }

    private Path script(String name, String body) throws Exception {
        Path exe = dir.resolve(name);
        Files.writeString(exe, "#!/bin/sh\n" + body);
        exe.toFile().setExecutable(true);
        return exe;
    }
}
