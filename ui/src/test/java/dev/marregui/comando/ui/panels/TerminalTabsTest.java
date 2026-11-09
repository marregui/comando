package dev.marregui.comando.ui.panels;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.widgets.terminal.TerminalWidget;

import static org.assertj.core.api.Assertions.assertThat;

/// [TerminalTabs] keeps one terminal per panel, started in the bound session's directory.
/// - a terminal not started follows its panel's directory on every render, shown or not
/// - an exited shell restarts where its panel is bound now
/// - a session directory that does not exist falls back to home
/// - terminals without shells run no jobs; a job is named with its panel
/// - a shell the user stopped stays stopped at the next render, and starts when its tab is shown again
/// - a terminal made for a panel not focused is not shown; a panel's shell is stopped without focusing it
class TerminalTabsTest {

    private static final PanelId P1 = new PanelId("p1");
    private static final PanelId P2 = new PanelId("p2");
    private static final Path HOME = Path.of(System.getProperty("user.home"));

    /// A terminal without a shell starts at home, then follows its panel's binding on each render, also when another
    /// panel is focused.
    @Test
    void aTerminalNotStartedTakesItsPanelsCurrentDirectoryOnEveryRender(@TempDir final Path root) throws Exception {
        final Path edge = Files.createDirectory(root.resolve("edge"));
        final Path lane2 = Files.createDirectory(root.resolve("lane2"));
        final List<SessionState> sessions = List.of(SessionState.closed(SessionSpec.fresh("edge", edge.toString())),
                SessionState.closed(SessionSpec.fresh("lane2", lane2.toString())));
        final TerminalTabs tabs = new TerminalTabs();

        final AppState unbound = AppState.initial(List.of(new PanelState(P1, "one", "", ""), new PanelState(P2, "two", "", "")), List.of(), sessions);
        tabs.render(unbound, false);
        assertThat(tabs.terminal(P1).directory()).isEqualTo(HOME);

        final AppState bound = AppState.initial(List.of(new PanelState(P1, "one", "", "edge"), new PanelState(P2, "two", "", "")), List.of(), sessions);
        tabs.render(bound, false);
        assertThat(tabs.terminal(P1).directory()).as("bound after the terminal was created").isEqualTo(edge);

        final AppState focusTwo = bound.withFocused(P2);
        tabs.render(focusTwo, false);
        final AppState rebound = AppState.initial(List.of(new PanelState(P1, "one", "", "lane2"), new PanelState(P2, "two", "", "")), List.of(), sessions)
                .withFocused(P2);
        tabs.render(rebound, false);
        assertThat(tabs.terminal(P1).directory()).as("not only the shown terminal").isEqualTo(lane2);
        assertThat(tabs.terminal(P2).directory()).isEqualTo(HOME);
        assertThat(tabs.focused()).isSameAs(tabs.terminal(P2));
    }

    /// A running shell keeps its directory when the panel is rebound; once it exits, with nothing rendered since, the
    /// exit bar and Restart use the directory the panel is bound to now.
    @Test
    void anExitedShellRestartsWhereItsPanelIsBoundNow(@TempDir final Path root) throws Exception {
        final Path edge = Files.createDirectory(root.resolve("edge"));
        final Path lane2 = Files.createDirectory(root.resolve("lane2"));
        final List<SessionState> sessions = List.of(SessionState.closed(SessionSpec.fresh("edge", edge.toString())),
                SessionState.closed(SessionSpec.fresh("lane2", lane2.toString())));
        final AppState bound = AppState.initial(List.of(new PanelState(P1, "one", "", "edge")), List.of(), sessions);
        final AppState rebound = AppState.initial(List.of(new PanelState(P1, "one", "", "lane2")), List.of(), sessions);
        final TerminalTabs tabs = new TerminalTabs(dir -> new TerminalWidget(dir, List.of("/bin/sh", "-c", "sleep 1")));
        final AtomicBoolean runningAtRebind = new AtomicBoolean();
        final AtomicReference<Path> atRebind = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                tabs.render(bound, true);
                tabs.render(rebound, true);
                runningAtRebind.set(tabs.terminal(P1).isRunning());
                atRebind.set(tabs.terminal(P1).directory());
            });
            assertThat(runningAtRebind).as("the shell ran when the panel was rebound").isTrue();
            assertThat(atRebind.get()).as("a running shell keeps its directory").isEqualTo(edge);

            final TerminalWidget t = tabs.terminal(P1);
            final AtomicReference<Path> dir = new AtomicReference<>(edge);
            final AtomicBoolean running = new AtomicBoolean(true);
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            while (System.nanoTime() - deadline < 0 && (running.get() || !dir.get().equals(lane2))) {
                Thread.sleep(50);
                SwingUtilities.invokeAndWait(() -> {
                    running.set(t.isRunning());
                    dir.set(t.directory());
                });
            }
            assertThat(running).as("the shell exited").isFalse();
            assertThat(dir.get()).as("the exit bar names the directory the panel is bound to now").isEqualTo(lane2);

            SwingUtilities.invokeAndWait(() -> {
                t.restart();
                dir.set(t.directory());
                running.set(t.isRunning());
            });
            assertThat(dir.get()).isEqualTo(lane2);
            assertThat(running).isTrue();
        } finally {
            SwingUtilities.invokeAndWait(tabs::closeAll);
        }
    }

    /// A panel bound to a session whose directory is missing gets the user's home.
    @Test
    void aSessionDirectoryThatDoesNotExistFallsBackToHome() {
        final AppState s = AppState.initial(List.of(new PanelState(P1, "one", "", "gone")), List.of(),
                List.of(SessionState.closed(SessionSpec.fresh("gone", "/no/such/dir"))));
        assertThat(TerminalTabs.directory(s)).isEqualTo(HOME);
    }

    /// Terminals that never started a shell report no running job, also for a panel without a terminal; the
    /// asynchronous check answers at once, empty, when there is no shell to ask.
    @Test
    void terminalsWithoutShellsRunNoJobs() {
        final TerminalTabs tabs = new TerminalTabs();
        final AppState s = AppState.initial(List.of(new PanelState(P1, "one", "", "")), List.of(), List.of());
        tabs.render(s, false);
        assertThat(tabs.hasRunningJob(P1)).isFalse();
        assertThat(tabs.hasRunningJob(P2)).isFalse();
        final AtomicReference<Map<PanelId, String>> answered = new AtomicReference<>();
        tabs.checkAllJobs(answered::set);
        assertThat(answered.get()).as("answered at once, with no shell to ask").isEmpty();
    }

    /// [TerminalTabs#jobLine] names the job with the panel's possessive, an apostrophe alone after a trailing `s`.
    @Test
    void aJobIsNamedWithThePanelWhoseTerminalTabRunsIt() {
        assertThat(TerminalTabs.jobLine("man cat", "comando developer")).isEqualTo("man cat in comando developer's Terminal tab");
        assertThat(TerminalTabs.jobLine("vim x", "notes")).isEqualTo("vim x in notes' Terminal tab");
    }

    /// The palette's Stop the shell ends the focused panel's shell; the next render with the Terminal tab in front, as
    /// any intent anywhere brings, leaves it stopped. Showing the tab again, after another tab was in front, starts it.
    @Test
    void aShellStoppedByTheUserStaysStoppedUntilItsTabIsShownAgain() throws Exception {
        final AppState s = AppState.initial(List.of(new PanelState(P1, "one", "", "")), List.of(), List.of());
        final TerminalTabs tabs = new TerminalTabs(dir -> new TerminalWidget(dir, List.of("/bin/sh", "-c", "sleep 30")));
        final AtomicBoolean afterStop = new AtomicBoolean();
        final AtomicBoolean afterRender = new AtomicBoolean();
        final AtomicBoolean afterShowing = new AtomicBoolean();
        try {
            SwingUtilities.invokeAndWait(() -> {
                tabs.render(s, true);
                assertThat(tabs.focusedRunning()).isTrue();
                tabs.stopFocused();
                afterStop.set(tabs.focusedRunning());
                tabs.render(s, true);
                afterRender.set(tabs.focusedRunning());
                tabs.render(s, false);
                tabs.render(s, true);
                afterShowing.set(tabs.focusedRunning());
            });
            assertThat(afterStop).as("stopped").isFalse();
            assertThat(afterRender).as("still stopped at the next render").isFalse();
            assertThat(afterShowing).as("started as its tab is shown again").isTrue();
        } finally {
            SwingUtilities.invokeAndWait(tabs::closeAll);
        }
    }

    /// A terminal made for the panel not focused opens where its panel is bound, and leaves the focused panel's terminal
    /// the one in front; a second call gives the same terminal.
    @Test
    void aTerminalMadeForAPanelNotFocusedIsNotShown(@TempDir final Path root) throws Exception {
        final Path edge = Files.createDirectory(root.resolve("edge"));
        final AppState s = AppState.initial(List.of(new PanelState(P1, "one", "", ""), new PanelState(P2, "two", "", "edge")), List.of(),
                List.of(SessionState.closed(SessionSpec.fresh("edge", edge.toString()))));
        final TerminalTabs tabs = new TerminalTabs();
        final AtomicReference<TerminalWidget> made = new AtomicReference<>();
        final AtomicReference<TerminalWidget> front = new AtomicReference<>();
        final AtomicBoolean madeVisible = new AtomicBoolean(true);
        final AtomicBoolean frontVisible = new AtomicBoolean();
        final AtomicBoolean same = new AtomicBoolean();
        try {
            SwingUtilities.invokeAndWait(() -> {
                tabs.render(s, false);
                made.set(tabs.ensure(P2));
                same.set(tabs.ensure(P2) == made.get());
                front.set(tabs.focused());
                madeVisible.set(made.get().component().isVisible());
                frontVisible.set(tabs.terminal(P1).component().isVisible());
            });
            assertThat(made.get()).isSameAs(tabs.terminal(P2));
            assertThat(made.get().directory()).isEqualTo(edge);
            assertThat(same).as("made once").isTrue();
            assertThat(front.get()).as("the focused panel's terminal stays in front").isSameAs(tabs.terminal(P1));
            assertThat(madeVisible).as("the new card is not shown").isFalse();
            assertThat(frontVisible).isTrue();
            assertThat(made.get().isStarted()).as("made, not started").isFalse();
        } finally {
            SwingUtilities.invokeAndWait(tabs::closeAll);
        }
    }

    /// A stop ends the shell of the panel named, focused or not, and leaves the others running; a panel without a
    /// terminal is left as it is.
    @Test
    void aStopEndsTheShellOfThePanelNamed() throws Exception {
        final AppState s = AppState.initial(List.of(new PanelState(P1, "one", "", ""), new PanelState(P2, "two", "", "")), List.of(), List.of());
        final TerminalTabs tabs = new TerminalTabs(dir -> new TerminalWidget(dir, List.of("/bin/sh", "-c", "sleep 30")));
        final AtomicBoolean focusedRunning = new AtomicBoolean();
        final AtomicBoolean otherRunning = new AtomicBoolean(true);
        try {
            SwingUtilities.invokeAndWait(() -> {
                tabs.render(s, true);
                tabs.ensure(P2).start();
                tabs.stop(P2);
                tabs.stop(new PanelId("none"));
                focusedRunning.set(tabs.focusedRunning());
                otherRunning.set(tabs.terminal(P2).isRunning());
            });
            assertThat(otherRunning).as("stopped").isFalse();
            assertThat(focusedRunning).as("the focused panel's shell still runs").isTrue();
        } finally {
            SwingUtilities.invokeAndWait(tabs::closeAll);
        }
    }
}
