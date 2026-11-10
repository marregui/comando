package dev.marregui.comando.app.uilock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.LauncherSessionListener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/// The uiTest lock between two JVMs and against a shared folder another account can write:
/// - the second JVM waits while the first lives, and takes the lock once the first is killed, as when a uiTest JVM
///   crashes or Gradle ends it
/// - a wait longer than its patience fails, naming the lock, instead of hanging
/// - a lock file planted as a link is refused and its target is left as it was
/// - JUnit finds the listener, so every uiTest JVM takes the lock
class UiTestLockTest {

    /** A JVM starts and writes its marker well within this, on the machines comando builds on. */
    private static final Duration START = Duration.ofSeconds(20);
    /** How long the second JVM must stay blocked in its lock call to count as waiting. */
    private static final Duration BLOCKED = Duration.ofSeconds(2);
    /** The kernel frees a killed process's lock at once; this bounds the second JVM's wake-up and marker write. */
    private static final Duration HANDOVER = Duration.ofSeconds(10);
    private static final long END_SECONDS = 10L;

    /// A second holder blocks while the first holds the lock, and gets it within the hand-over once the first is
    /// killed.
    @Test
    void aSecondJvmWaitsForTheLockUntilTheFirstIsKilled(@TempDir final Path dir) throws Exception {
        final Path lock = dir.resolve("uiTest.lock");
        final Path firstHeld = dir.resolve("first.held");
        final Path secondLocking = dir.resolve("second.locking");
        final Path secondHeld = dir.resolve("second.held");
        final Process first = holder(lock, dir.resolve("first.locking"), firstHeld, dir.resolve("first.log"));
        Process second = null;
        try {
            await().atMost(START).until(() -> Files.exists(firstHeld));
            second = holder(lock, secondLocking, secondHeld, dir.resolve("second.log"));
            await().atMost(START).until(() -> Files.exists(secondLocking));
            await().during(BLOCKED).atMost(BLOCKED.plus(HANDOVER)).until(() -> !Files.exists(secondHeld));
            first.destroyForcibly();
            await().atMost(HANDOVER).until(() -> Files.exists(secondHeld));
        } finally {
            end(first);
            if (second != null) {
                end(second);
            }
        }
    }

    /// While another JVM holds the lock, a hold with a patience of one second fails after it with the lock's path in
    /// its message, rather than waiting on.
    @Test
    void aWaitPastItsPatienceFailsNamingTheLock(@TempDir final Path dir) throws Exception {
        final Path lock = dir.resolve("uiTest.lock");
        final Path held = dir.resolve("first.held");
        final Process first = holder(lock, dir.resolve("first.locking"), held, dir.resolve("first.log"));
        try {
            await().atMost(START).until(() -> Files.exists(held));
            assertThatThrownBy(() -> UiTestLock.hold(lock, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(lock.toString());
        } finally {
            end(first);
        }
    }

    /// A link put where the lock file goes, pointing at a file only its owner may write, is refused, and the target
    /// keeps its permissions.
    @Test
    void aLockFilePlantedAsALinkIsRefusedAndItsTargetKept(@TempDir final Path dir) throws Exception {
        final Path target = Files.writeString(dir.resolve("private.txt"), "mine");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        final Path folder = Files.createDirectories(dir.resolve("shared"));
        final Path lock = Files.createSymbolicLink(folder.resolve("uiTest.lock"), target);

        assertThatThrownBy(() -> UiTestLock.hold(lock, Duration.ofSeconds(1))).isInstanceOf(IOException.class);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target))).isEqualTo("rw-------");
    }

    /// The service file names the listener, so JUnit opens it in every uiTest JVM.
    @Test
    void junitFindsTheListener() {
        assertThat(ServiceLoader.load(LauncherSessionListener.class).stream().map(ServiceLoader.Provider::type))
                .contains(UiTestLock.class);
    }

    private static Process holder(final Path lock, final Path locking, final Path held, final Path log)
            throws IOException {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("java.class.path"), UiTestLockHolder.class.getName(), lock.toString(),
                locking.toString(), held.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static void end(final Process process) throws InterruptedException {
        process.destroyForcibly();
        assertThat(process.waitFor(END_SECONDS, TimeUnit.SECONDS)).isTrue();
    }
}
