package dev.marregui.comando.core.loop;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// [Processes#run] runs a program with a time limit and collects its output without ever blocking on a full pipe.
/// - The exit code and standard output come back.
/// - Output far past a pipe's capacity does not block and is cut at `maxOut`, `cut` set.
/// - A program past its time limit is ended with every process it started, within the limit plus a second.
/// - Standard input reaches the program; standard error is kept apart from standard output.
class ProcessesTest {

    private static Processes.Result sh(String script, byte[] stdin, Duration timeout, int maxOut) throws Exception {
        return Processes.run(List.of("/bin/sh", "-c", script), env -> env.put("COMANDO_TEST", "on"), null, stdin, timeout, maxOut);
    }

    /// A script that prints a line, reads an added environment variable and exits 3 answers exit code 3 and its whole
    /// output, neither timed out nor cut.
    @Test
    void theExitCodeAndTheOutputComeBack() throws Exception {
        Processes.Result r = sh("echo hello $COMANDO_TEST; exit 3", null, Duration.ofSeconds(10), 1_024);
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.out()).isEqualTo("hello on\n");
        assertThat(r.timedOut()).isFalse();
        assertThat(r.cut()).isFalse();
    }

    /// 1 MiB on standard output, sixteen times a 64 KiB pipe, finishes rather than blocking the program, and only the
    /// first 1 000 bytes are kept, with `cut` set.
    @Test
    void outputPastThePipeDoesNotBlockAndIsCutAtMaxOut() throws Exception {
        Processes.Result r = sh("head -c 1048576 /dev/zero | tr '\\0' x; exit 0", null, Duration.ofSeconds(20), 1_000);
        assertThat(r.timedOut()).isFalse();
        assertThat(r.exitCode()).isZero();
        assertThat(r.stdout()).hasSize(1_000);
        assertThat(r.out()).matches("x{1000}");
        assertThat(r.cut()).isTrue();
    }

    /// A script that starts a 60 s `sleep` child and waits for it, under a 500 ms limit, answers `timedOut` with exit
    /// code -1 within 1.5 s, and the child it started has been ended: dead, or dying, within 5 s of the answer.
    @Test
    void aProgramPastItsLimitIsEndedWithItsChildren() throws Exception {
        long start = System.nanoTime();
        Processes.Result r = sh("sleep 60 & echo $!; wait", null, Duration.ofMillis(500), 1_024);
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(r.timedOut()).isTrue();
        assertThat(r.exitCode()).isEqualTo(-1);
        assertThat(millis).isLessThan(1_500);
        long child = Long.parseLong(r.out().strip());
        Optional<ProcessHandle> sleeper = ProcessHandle.of(child);
        if (sleeper.isPresent()) {
            sleeper.get().onExit().get(5, TimeUnit.SECONDS);
        }
        assertThat(ProcessHandle.of(child).map(ProcessHandle::isAlive)).isNotEqualTo(Optional.of(true));
    }

    /// The bytes given as standard input reach the program, which sees its input end once they are written.
    @Test
    void standardInputReachesTheProgram() throws Exception {
        Processes.Result r = sh("tr a-z A-Z", "topic of the panel".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(10), 1_024);
        assertThat(r.exitCode()).isZero();
        assertThat(r.out()).isEqualTo("TOPIC OF THE PANEL");
    }

    /// What the program writes to standard error comes back as text of its own and never mixes into standard output.
    @Test
    void standardErrorIsKeptApart() throws Exception {
        Processes.Result r = sh("echo out; echo 'boom: no such file' >&2; exit 1", null, Duration.ofSeconds(10), 1_024);
        assertThat(r.exitCode()).isEqualTo(1);
        assertThat(r.out()).isEqualTo("out\n");
        assertThat(r.stderr()).isEqualTo("boom: no such file\n");
    }
}
