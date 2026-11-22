package dev.marregui.comando.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// [FrontearOctopus] and its registry's lock, each test on a registry and a port of its own in a temporary folder:
/// - a start's and a stop's notices are said in the order said, with neither the registry's lock nor the octopus's
///   monitor held, the word that one begins before its work
/// - a hold of the lock file by another process, which `FileChannel.tryLock` answers with null (a hold in this JVM
///   throws instead), runs nothing under the lock and starts nothing within the bound, and says so to a start by hand
class FrontearOctopusLockTest {

    /// Each wait's bound: a start takes well under a second, a child JVM a few, a held lock gives up after
    /// [FrontearOctopus#LOCK_WITHIN].
    private static final Duration WAIT = Duration.ofSeconds(30);
    /// What the child writes on its standard output once it holds the lock.
    private static final String LOCKED = "locked";

    @TempDir
    Path dir;

    /// A start by hand and then a stop say "Starting frontear…", "frontear is up", "Stopping frontear…" and
    /// "frontear is down", in that order, each once the thread that says it holds neither the octopus's monitor nor
    /// the registry's lock, which a probe of the lock file from this JVM then takes at once; "Starting frontear…"
    /// while no node runs yet and "Stopping frontear…" while it still runs, so each is said before its work.
    @Test
    void aStartsAndAStopsNoticesAreSaidInOrderOutsideTheLocks() throws Exception {
        final Path registry = dir.resolve("comando-octopus.json");
        final Path lock = FrontearOctopus.lockFile(registry);
        final AtomicReference<FrontearOctopus> self = new AtomicReference<>();
        final List<String> said = new CopyOnWriteArrayList<>();
        final IntentSink sink = intent -> {
            if (intent instanceof Intent.Notify(_, final String text, _, _)) {
                said.add(text + " [" + holds(self.get(), lock) + ", " + (self.get().isRunning() ? "running"
                        : "stopped") + ']');
            }
        };
        try (final FrontearOctopus octopus = new FrontearOctopus(dir.resolve("a/fabric"), dir.resolve("a"), "a", sink,
                registry, 0)) {
            self.set(octopus);

            octopus.toggle();
            await().atMost(WAIT).until(() -> said.size() == 2 && !octopus.busy());
            final int port = octopus.mqttPort();
            octopus.toggle();
            await().atMost(WAIT).until(() -> said.size() == 4 && !octopus.busy());

            assertThat(said).containsExactly("Starting frontear… [none, stopped]",
                    "frontear is up: MQTT 127.0.0.1:" + port + " [none, running]", "Stopping frontear… [none, running]",
                    "frontear is down [none, stopped]");
        }
    }

    /// While a second JVM holds the registry's lock file, `underLock` runs nothing and answers false once its bound
    /// has passed, not before; a start by hand meanwhile neither hosts nor writes the registry, and after its early
    /// "Starting frontear…" says another comando is starting or stopping frontear. Once the child lets go, at the end
    /// of its standard input, `underLock` runs its action.
    @Test
    void anotherProcessHoldingTheLockRunsNothingAndStartsNothingWithinTheBound() throws Exception {
        final Path registry = dir.resolve("comando-octopus.json");
        final Path lock = FrontearOctopus.lockFile(registry);
        final AtomicBoolean ran = new AtomicBoolean();
        final RecordingSink sink = new RecordingSink();
        final Process child = holder(lock);
        try (final FrontearOctopus octopus = new FrontearOctopus(dir.resolve("a/fabric"), dir.resolve("a"), "a", sink,
                registry, 0)) {
            awaitLocked(child);

            final long from = System.nanoTime();
            final boolean held = FrontearOctopus.underLock(lock, () -> ran.set(true));
            final Duration waited = Duration.ofNanos(System.nanoTime() - from);

            assertThat(held).as("ran under another process's lock").isFalse();
            assertThat(ran).isFalse();
            assertThat(waited).isGreaterThanOrEqualTo(FrontearOctopus.LOCK_WITHIN).isLessThan(WAIT);

            octopus.toggle();
            sink.await(Intent.Notify.class, notice -> notice.text().startsWith("Another comando"), WAIT);
            await().atMost(WAIT).until(() -> !octopus.busy());

            assertThat(sink.of(Intent.Notify.class)).extracting(Intent.Notify::text).containsExactly(
                    "Starting frontear…", "Another comando is starting or stopping frontear; try again in a moment");
            assertThat(octopus.isRunning()).isFalse();
            assertThat(registry).doesNotExist();
        } finally {
            child.getOutputStream().close();
            if (!child.waitFor(WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                // a child that did not let go is ended, not left running (own rule 12); the exit check then fails
                child.destroyForcibly().waitFor(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
        assertThat(child.exitValue()).as("the child let go").isZero();
        assertThat(FrontearOctopus.underLock(lock, () -> ran.set(true))).isTrue();
        assertThat(ran).isTrue();
    }

    /// What is held as the current thread says a notice: "monitor" when it holds `octopus`'s monitor, "registry lock"
    /// when this JVM holds the lock file's lock; "none" when neither is, the lock taken by the probe and let go.
    private static String holds(final FrontearOctopus octopus, final Path lock) {
        final List<String> held = new ArrayList<>();
        if (Thread.holdsLock(octopus)) {
            held.add("monitor");
        }
        try (final FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                final FileLock probe = channel.tryLock()) {
            if (probe == null) {
                held.add("another process's lock");
            }
        } catch (final OverlappingFileLockException _) {
            held.add("registry lock");
        } catch (final IOException e) {
            held.add("unreadable lock file: " + e.getMessage());
        }
        return held.isEmpty() ? "none" : String.join(", ", held);
    }

    /// A second JVM running [Holder] on `lock`, from this test's own class folder, its errors to a file beside.
    private Process holder(final Path lock) throws Exception {
        final String java = ProcessHandle.current().info().command().orElseThrow();
        final String classes = Path.of(Holder.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        return new ProcessBuilder(java, "-cp", classes, Holder.class.getName(), lock.toString())
                .redirectError(dir.resolve("holder.err").toFile()).start();
    }

    /// Waits until `child` says it holds the lock, failing with its errors when it ends first.
    private void awaitLocked(final Process child) throws IOException {
        final InputStream out = child.getInputStream();
        await().atMost(WAIT).until(() -> !child.isAlive() || out.available() >= LOCKED.length());
        final String line = new String(out.readNBytes(LOCKED.length()), StandardCharsets.US_ASCII);
        assertThat(line).as(this::errors).isEqualTo(LOCKED);
    }

    private String errors() {
        try {
            return Files.readString(dir.resolve("holder.err"));
        } catch (final IOException e) {
            return "no errors file: " + e.getMessage();
        }
    }

    /// The child: takes the lock of the file named by its one argument, says [#LOCKED] on its standard output, and
    /// lets go at the end of its standard input; it exits 2 when the lock is held already.
    static final class Holder {

        private Holder() {
        }

        public static void main(final String[] args) throws IOException {
            try (final FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
                    final FileLock held = channel.tryLock()) {
                if (held == null) {
                    System.exit(2);
                }
                System.out.print(LOCKED);
                System.out.flush();
                while (System.in.read() >= 0) {
                    // held until the parent closes the pipe
                }
            }
        }
    }
}
