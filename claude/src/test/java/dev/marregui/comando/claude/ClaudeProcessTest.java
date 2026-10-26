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

class ClaudeProcessTest {

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

    /** A child that outlives the process and keeps its output open does not keep the exit from being reported. */
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

    /** stderr goes to a file, not a thread: its last lines are kept at the exit and the file is deleted. */
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
