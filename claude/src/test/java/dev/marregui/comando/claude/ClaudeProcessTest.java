package dev.marregui.comando.claude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// [ClaudeProcess] streams process output, queues input, and reports exit after bounded output draining.
///
/// - Delivers complete stdout before reporting the exit status.
/// - Bounds exit reporting when a child retains stdout.
/// - Retains the final forty stderr lines without leaving a temporary file.
/// - Uses one platform reader thread per session process.
class ClaudeProcessTest {

    /// A process that prints `a` and `b` then exits `3` delivers both lines before the exit callback observes code `3`.
    @Test
    void theExitIsReportedAfterTheLastLine(@TempDir Path dir) throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        AtomicInteger code = new AtomicInteger(-1);
        CountDownLatch exited = new CountDownLatch(1);
        ClaudeProcess.start("t", List.of("sh", "-c", "printf 'a\\nb\\n'; exit 3"), dir, RootLaunch.INHERIT, lines::add, c -> {
            code.set(c);
            exited.countDown();
        });
        assertThat(exited.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(lines).containsExactly("a", "b");
        assertThat(code.get()).isEqualTo(3);
    }

    /// A background `sleep` retaining stdout cannot delay its parent's exit callback past `DRAIN_MILLIS`; its pid is
    /// collected afterward so the fixture leaves no child process running.
    @Test
    void theExitIsReportedWhileAChildStillHoldsTheOutputOpen(@TempDir Path dir) throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        CountDownLatch exited = new CountDownLatch(1);
        ClaudeProcess.start("t", List.of("sh", "-c", "sleep 60 & echo $!; exit 0"), dir, RootLaunch.INHERIT, lines::add, c -> exited.countDown());
        try {
            assertThat(exited.await(ClaudeProcess.DRAIN_MILLIS + 8_000, TimeUnit.MILLISECONDS)).isTrue();
        } finally {
            for (String pid : lines) {
                ProcessHandle.of(Long.parseLong(pid.strip())).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    /// One hundred stderr lines leave exactly `err 60` through `err 99` after exit, and the private stderr file is
    /// deleted once that tail has been captured.
    @Test
    void stderrsLastLinesAreKeptAtTheExitAndItsFileIsDeleted(@TempDir Path dir) throws Exception {
        CountDownLatch exited = new CountDownLatch(1);
        AtomicReference<ClaudeProcess> process = new AtomicReference<>();
        process.set(ClaudeProcess.start("t", List.of("sh", "-c", "i=0; while [ $i -lt 100 ]; do echo \"err $i\" 1>&2; i=$((i+1)); done; exit 1"),
                dir, RootLaunch.INHERIT, line -> { }, c -> exited.countDown()));
        assertThat(exited.await(10, TimeUnit.SECONDS)).isTrue();
        // read before the exit is told, so it is there once it has been
        List<String> tail = process.get().stderrTail().lines().toList();
        assertThat(tail).hasSize(40).startsWith("err 60").endsWith("err 99");
        assertThat(process.get().stderrFile()).as("the stderr file is deleted").doesNotExist();
    }

    /// A blocked session process creates only `claude-out-budget` as a platform thread; stdin and exit waits do not add
    /// platform threads before the test releases the process.
    @Test
    void aSessionsOnlyPlatformThreadIsItsStdoutReader(@TempDir Path dir) throws Exception {
        CountDownLatch exited = new CountDownLatch(1);
        ClaudeProcess p = ClaudeProcess.start("budget", List.of("sh", "-c", "read x; exit 0"), dir, RootLaunch.INHERIT, line -> { }, c -> exited.countDown());
        try {
            List<String> platform = Thread.getAllStackTraces().keySet().stream().filter(t -> !t.isVirtual())
                    .map(Thread::getName).filter(n -> n.startsWith("claude-")).toList();
            assertThat(platform).containsExactly("claude-out-budget");
        } finally {
            p.send("bye");
            assertThat(exited.await(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
