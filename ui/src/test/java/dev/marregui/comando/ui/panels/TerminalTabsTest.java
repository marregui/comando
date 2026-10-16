package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.widgets.terminal.TerminalWidget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalTabsTest {

    private static final PanelId P1 = new PanelId("p1");
    private static final PanelId P2 = new PanelId("p2");
    private static final Path HOME = Path.of(System.getProperty("user.home"));

    @Test
    void aTerminalNotStartedTakesItsPanelsCurrentDirectoryOnEveryRender(@TempDir Path root) throws Exception {
        Path edge = Files.createDirectory(root.resolve("edge"));
        Path edge2 = Files.createDirectory(root.resolve("edge2"));
        List<SessionState> sessions = List.of(SessionState.closed(SessionSpec.fresh("edge", edge.toString())),
                SessionState.closed(SessionSpec.fresh("edge2", edge2.toString())));
        TerminalTabs tabs = new TerminalTabs();

        AppState unbound = AppState.initial(List.of(new PanelState(P1, "one", "", ""), new PanelState(P2, "two", "", "")), List.of(), sessions);
        tabs.render(unbound, false);
        assertThat(tabs.terminal(P1).directory()).isEqualTo(HOME);

        AppState bound = AppState.initial(List.of(new PanelState(P1, "one", "", "edge"), new PanelState(P2, "two", "", "")), List.of(), sessions);
        tabs.render(bound, false);
        assertThat(tabs.terminal(P1).directory()).as("bound after the terminal was created").isEqualTo(edge);

        AppState focusTwo = bound.withFocused(P2);
        tabs.render(focusTwo, false);
        AppState rebound = AppState.initial(List.of(new PanelState(P1, "one", "", "edge2"), new PanelState(P2, "two", "", "")), List.of(), sessions)
                .withFocused(P2);
        tabs.render(rebound, false);
        assertThat(tabs.terminal(P1).directory()).as("not only the shown terminal").isEqualTo(edge2);
        assertThat(tabs.terminal(P2).directory()).isEqualTo(HOME);
        assertThat(tabs.focused()).isSameAs(tabs.terminal(P2));
    }

    /**
     * The panel is rebound while its shell runs, so the render leaves that terminal alone; the shell then exits
     * with nothing rendered since. The exit bar and Restart take the directory the panel is bound to now.
     */
    @Test
    void anExitedShellRestartsWhereItsPanelIsBoundNow(@TempDir Path root) throws Exception {
        Path edge = Files.createDirectory(root.resolve("edge"));
        Path edge2 = Files.createDirectory(root.resolve("edge2"));
        List<SessionState> sessions = List.of(SessionState.closed(SessionSpec.fresh("edge", edge.toString())),
                SessionState.closed(SessionSpec.fresh("edge2", edge2.toString())));
        AppState bound = AppState.initial(List.of(new PanelState(P1, "one", "", "edge")), List.of(), sessions);
        AppState rebound = AppState.initial(List.of(new PanelState(P1, "one", "", "edge2")), List.of(), sessions);
        TerminalTabs tabs = new TerminalTabs(dir -> new TerminalWidget(dir, List.of("/bin/sh", "-c", "sleep 1")));
        AtomicBoolean runningAtRebind = new AtomicBoolean();
        AtomicReference<Path> atRebind = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                tabs.render(bound, true);
                tabs.render(rebound, true);
                runningAtRebind.set(tabs.terminal(P1).isRunning());
                atRebind.set(tabs.terminal(P1).directory());
            });
            assertThat(runningAtRebind).as("the shell ran when the panel was rebound").isTrue();
            assertThat(atRebind.get()).as("a running shell keeps its directory").isEqualTo(edge);

            TerminalWidget t = tabs.terminal(P1);
            AtomicReference<Path> dir = new AtomicReference<>(edge);
            AtomicBoolean running = new AtomicBoolean(true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            while (System.nanoTime() < deadline && (running.get() || !dir.get().equals(edge2))) {
                Thread.sleep(50);
                SwingUtilities.invokeAndWait(() -> {
                    running.set(t.isRunning());
                    dir.set(t.directory());
                });
            }
            assertThat(running).as("the shell exited").isFalse();
            assertThat(dir.get()).as("the exit bar names the directory the panel is bound to now").isEqualTo(edge2);

            SwingUtilities.invokeAndWait(() -> {
                t.restart();
                dir.set(t.directory());
                running.set(t.isRunning());
            });
            assertThat(dir.get()).isEqualTo(edge2);
            assertThat(running).isTrue();
        } finally {
            SwingUtilities.invokeAndWait(tabs::closeAll);
        }
    }

    @Test
    void aSessionDirectoryThatDoesNotExistFallsBackToHome() {
        AppState s = AppState.initial(List.of(new PanelState(P1, "one", "", "gone")), List.of(),
                List.of(SessionState.closed(SessionSpec.fresh("gone", "/no/such/dir"))));
        assertThat(TerminalTabs.directory(s)).isEqualTo(HOME);
    }

    @Test
    void terminalsWithoutShellsRunNoJobs() {
        TerminalTabs tabs = new TerminalTabs();
        AppState s = AppState.initial(List.of(new PanelState(P1, "one", "", "")), List.of(), List.of());
        tabs.render(s, false);
        assertThat(tabs.hasRunningJob(P1)).isFalse();
        assertThat(tabs.hasRunningJob(P2)).isFalse();
        assertThat(tabs.runningJobs()).isZero();
    }
}
