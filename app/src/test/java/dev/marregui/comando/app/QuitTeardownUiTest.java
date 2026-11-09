package dev.marregui.comando.app;

import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.ui.kit.async.UiErrands;
import dev.marregui.comando.ui.shell.ClosingSteps;

import static org.assertj.core.api.Assertions.assertThat;

/// Quitting in the real window when a step of closing throws, or the teardown or the close itself. Opens the window,
/// so this runs in `./gradlew :app:uiTest`; the window is off screen. Each test fails when one guard alone is taken out.
/// - a step of the window's teardown that throws does not stop the quit: the steps after it still run, the
///   application is still told to close, and the window's errands still running are interrupted
/// - a step of the window's close that throws leaves the steps after it to run and the window disposed
/// - a teardown that throws outside any step still tells the application to close
/// - a close that throws outside any step still interrupts the window's errands
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class QuitTeardownUiTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @TempDir
    Path home;

    /// What a quit with one failing name did: how often the application was told to close, whether the held errand
    /// was interrupted, the names the hook was told in order, and whether the window was disposed.
    private record Quit(int quits, boolean interrupted, List<String> seen, boolean disposed) {
    }

    /// The terminals' step of the teardown throws at quit: the steps after it still run, the application's quit is
    /// still called, once, and an errand still running when the application closes is interrupted.
    @Test
    void aTeardownStepThatThrowsLeavesTheNextStepsAndStillQuits() throws Exception {
        final Quit q = quitWithAFailing("terminals");
        assertThat(q.seen()).as("the steps after the failing one ran")
                .containsSubsequence("terminals", "live plots", "watchdog", "key priority", "listeners");
        assertThat(q.quits()).isEqualTo(1);
        assertThat(q.interrupted()).as("the held errand interrupted").isTrue();
    }

    /// The jobs' step of the window's close throws as the application closes: the comparison window's step still
    /// runs, the window is disposed, and an errand still running is interrupted all the same.
    @Test
    void aCloseStepThatThrowsLeavesTheNextStepsAndDisposesTheWindow() throws Exception {
        final Quit q = quitWithAFailing("jobs");
        assertThat(q.seen()).as("the step after the failing one ran").containsSubsequence("jobs", "comparison window");
        assertThat(q.disposed()).as("the window disposed").isTrue();
        assertThat(q.interrupted()).as("the held errand interrupted").isTrue();
    }

    /// The teardown throws before any of its steps, outside their guard: the application is still told to close,
    /// once.
    @Test
    void aTeardownThatThrowsStillTellsTheApplicationToClose() throws Exception {
        final Quit q = quitWithAFailing("teardown");
        assertThat(q.quits()).as("the application told to close").isEqualTo(1);
        assertThat(q.interrupted()).as("the held errand interrupted").isTrue();
    }

    /// The window's close throws after its steps, outside their guard: an errand still running is interrupted all the
    /// same.
    @Test
    void aCloseThatThrowsStillInterruptsTheErrands() throws Exception {
        final Quit q = quitWithAFailing("close");
        assertThat(q.quits()).isEqualTo(1);
        assertThat(q.interrupted()).as("the held errand interrupted").isTrue();
    }

    /// Quits the window by its close button with the hook throwing at {@code failing}, and tells what happened once
    /// the held errand was interrupted or {@link #WAIT} passed.
    private Quit quitWithAFailing(final String failing) throws Exception {
        final AtomicInteger quits = new AtomicInteger();
        final List<String> seen = new CopyOnWriteArrayList<>();
        final App[] app = new App[1];
        // as the launcher does: the application closes on a thread of its own once the window has quit
        app[0] = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(), home.resolve("projects"), false),
                "quit-" + failing, () -> {
                    quits.incrementAndGet();
                    Thread.ofPlatform().name("exit").start(app[0]::close);
                });
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        // this test's window is the one the start adds: another test's, left showing, must not be the one closed
        final Set<Frame> before = onEdt(() -> Set.of(Frame.getFrames()));
        JFrame f = null;
        try {
            app[0].start();
            Awaitility.await("the window").atMost(WAIT).until(()
                    -> app[0].loop().state().focused() != null && onEdt(() -> frame(before) != null));
            ClosingSteps.beforeEach(app[0].ui(), step -> {
                seen.add(step);
                if (step.equals(failing)) {
                    throw new IllegalStateException("simulated failure of " + step);
                }
            });
            UiErrands.start("held", () -> {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    interrupted.countDown();
                }
            });

            // posted as the window manager posts it: what the quit throws stays on the event thread
            f = onEdt(() -> frame(before));
            Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new WindowEvent(f, WindowEvent.WINDOW_CLOSING));

            final boolean wasInterrupted = interrupted.await(WAIT.toSeconds(), TimeUnit.SECONDS);
            final JFrame shown = f;
            final boolean disposed = onEdt(() -> !shown.isDisplayable());
            return new Quit(quits.get(), wasInterrupted, List.copyOf(seen), disposed);
        } finally {
            release.countDown();
            app[0].close();
            // a close that failed before its window was disposed leaves it to the test, never to the next one
            if (f != null) {
                SwingUtilities.invokeAndWait(f::dispose);
            }
        }
    }

    /** The comando window showing that is not among {@code before}; null while there is none. */
    private static JFrame frame(final Set<Frame> before) {
        for (final Frame f : Frame.getFrames()) {
            if (f instanceof JFrame j && !before.contains(f) && f.isDisplayable() && f.getTitle().startsWith("comando")) {
                return j;
            }
        }
        return null;
    }

    /// What `call` returns, run on the EDT; its failure is thrown here.
    private static <T> T onEdt(final Callable<T> call) throws Exception {
        final AtomicReference<T> out = new AtomicReference<>();
        final AtomicReference<Exception> failed = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                out.set(call.call());
            } catch (final Exception e) {
                failed.set(e);
            }
        });
        if (failed.get() != null) {
            throw failed.get();
        }
        return out.get();
    }
}
