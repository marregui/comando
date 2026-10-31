package dev.marregui.comando.claude;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// [ClaudeProcess] streams process output, queues input, and reports exit after bounded output draining.
///
/// - Delivers complete stdout before reporting the exit status.
/// - Bounds exit reporting when a child retains stdout.
/// - Retains the final forty stderr lines without leaving a temporary file.
/// - Uses one platform reader thread per session process.
/// - Ends the processes Claude Code started with it, one that ignores SIGTERM by SIGKILL.
class ClaudeProcessTest {

    /** How long a signalled process is given to be gone. */
    private static final Duration GONE = Duration.ofSeconds(5);

    /// A process that prints `a` and `b` then exits `3` delivers both lines before the exit callback observes code `3`.
    @Test
    void theExitIsReportedAfterTheLastLine(@TempDir final Path dir) throws Exception {
        final List<String> lines = new CopyOnWriteArrayList<>();
        final AtomicInteger code = new AtomicInteger(-1);
        final CountDownLatch exited = new CountDownLatch(1);
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
    void theExitIsReportedWhileAChildStillHoldsTheOutputOpen(@TempDir final Path dir) throws Exception {
        final List<String> lines = new CopyOnWriteArrayList<>();
        final CountDownLatch exited = new CountDownLatch(1);
        ClaudeProcess.start("t", List.of("sh", "-c", "sleep 60 & echo $!; exit 0"), dir, RootLaunch.INHERIT, lines::add, c -> exited.countDown());
        try {
            assertThat(exited.await(ClaudeProcess.DRAIN_MILLIS + 8_000, TimeUnit.MILLISECONDS)).isTrue();
        } finally {
            for (final String pid : lines) {
                ProcessHandle.of(Long.parseLong(pid.strip())).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    /// One hundred stderr lines leave exactly `err 60` through `err 99` after exit, and the private stderr file is
    /// deleted once that tail has been captured.
    @Test
    void stderrsLastLinesAreKeptAtTheExitAndItsFileIsDeleted(@TempDir final Path dir) throws Exception {
        final CountDownLatch exited = new CountDownLatch(1);
        final AtomicReference<ClaudeProcess> process = new AtomicReference<>();
        process.set(ClaudeProcess.start("t", List.of("sh", "-c", "i=0; while [ $i -lt 100 ]; do echo \"err $i\" 1>&2; i=$((i+1)); done; exit 1"),
                dir, RootLaunch.INHERIT, line -> { }, c -> exited.countDown()));
        assertThat(exited.await(10, TimeUnit.SECONDS)).isTrue();
        // read before the exit is told, so it is there once it has been
        final List<String> tail = process.get().stderrTail().lines().toList();
        assertThat(tail).hasSize(40).startsWith("err 60").endsWith("err 99");
        assertThat(process.get().stderrFile()).as("the stderr file is deleted").doesNotExist();
    }

    /// A blocked session process creates only `claude-out-budget` as a platform thread; stdin and exit waits do not add
    /// platform threads before the test releases the process.
    @Test
    void aSessionsOnlyPlatformThreadIsItsStdoutReader(@TempDir final Path dir) throws Exception {
        final CountDownLatch exited = new CountDownLatch(1);
        final ClaudeProcess p = ClaudeProcess.start("budget", List.of("sh", "-c", "read x; exit 0"), dir, RootLaunch.INHERIT, line -> { }, c -> exited.countDown());
        try {
            final List<String> platform = Thread.getAllStackTraces().keySet().stream().filter(t -> !t.isVirtual())
                    .map(Thread::getName).filter(n -> n.startsWith("claude-")).toList();
            assertThat(platform).containsExactly("claude-out-budget");
        } finally {
            p.send("bye");
            assertThat(exited.await(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /// A process that starts a `nohup sleep 60` and waits: after [ClaudeProcess#terminate] the grandchild's pid is not
    /// alive within five seconds, though it ignores the hangup and is no longer a descendant once its parent exited.
    @Test
    void terminateEndsTheProcessesClaudeStarted(@TempDir final Path dir) throws Exception {
        final List<String> lines = new CopyOnWriteArrayList<>();
        final ClaudeProcess p = ClaudeProcess.start("t", List.of("sh", "-c", "nohup sleep 60 >/dev/null 2>&1 & echo $!; wait"),
                dir, RootLaunch.INHERIT, lines::add, c -> { });
        final long grandchild = awaitPid(lines);
        try {
            p.terminate();
            Awaitility.await("the grandchild ended").atMost(GONE).until(() -> !alive(grandchild));
            assertThat(p.awaitExit(GONE.toMillis())).isTrue();
        } finally {
            ProcessHandle.of(grandchild).ifPresent(ProcessHandle::destroyForcibly);
            p.kill();
        }
    }

    /// A grandchild that ignores SIGTERM survives [ClaudeProcess#terminate] and its parent's exit; [ClaudeProcess#kill]
    /// still ends it, from the processes the terminate signalled.
    @Test
    void killEndsAChildThatIgnoredTheTerminateAfterClaudeExited(@TempDir final Path dir) throws Exception {
        final List<String> lines = new CopyOnWriteArrayList<>();
        final ClaudeProcess p = ClaudeProcess.start("t", List.of("sh", "-c", "(trap '' TERM HUP; exec sleep 61) & echo $!; wait"),
                dir, RootLaunch.INHERIT, lines::add, c -> { });
        final long grandchild = awaitPid(lines);
        try {
            p.terminate();
            assertThat(p.awaitExit(GONE.toMillis())).as("the parent exits on the SIGTERM").isTrue();
            assertThat(alive(grandchild)).as("the grandchild ignored it").isTrue();
            p.kill();
            Awaitility.await("the grandchild ended").atMost(GONE).until(() -> !alive(grandchild));
        } finally {
            ProcessHandle.of(grandchild).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    /** The pid the process printed as its first line. */
    private static long awaitPid(final List<String> lines) {
        Awaitility.await("the grandchild's pid").atMost(GONE).until(() -> !lines.isEmpty());
        return Long.parseLong(lines.getFirst().strip());
    }

    private static boolean alive(final long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
