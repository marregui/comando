package dev.marregui.comando.claude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeProcessTest {

    @Test
    void theExitIsReportedAfterTheLastLine(@TempDir Path dir) throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        AtomicInteger code = new AtomicInteger(-1);
        CountDownLatch exited = new CountDownLatch(1);
        ClaudeProcess.start("t", List.of("sh", "-c", "printf 'a\\nb\\n'; exit 3"), dir, Map.of(), lines::add, c -> {
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
        ClaudeProcess.start("t", List.of("sh", "-c", "sleep 60 & echo $!; exit 0"), dir, Map.of(), lines::add, c -> exited.countDown());
        try {
            assertThat(exited.await(ClaudeProcess.DRAIN_MILLIS + 8_000, TimeUnit.MILLISECONDS)).isTrue();
        } finally {
            for (String pid : lines) {
                ProcessHandle.of(Long.parseLong(pid.strip())).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }
}
