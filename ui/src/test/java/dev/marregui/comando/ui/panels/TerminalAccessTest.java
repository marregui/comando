package dev.marregui.comando.ui.panels;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.TerminalPort;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.widgets.terminal.TerminalWidget;

import static org.assertj.core.api.Assertions.assertThat;

/// [TerminalAccess] lets agents reach every editor panel's Terminal without bringing it to the front.
/// - a start opens the shell of a panel not focused, in the panel's folder or the directory asked for
/// - the list names every editor panel, with whether its shell started and runs
/// - a send to a shell that is not running is refused and starts nothing; a stop ends the shell
/// - an unknown panel, and every action before the window is built, are refused
class TerminalAccessTest {

    private static final PanelId P1 = new PanelId("p1");
    private static final PanelId P2 = new PanelId("p2");

    @TempDir
    Path root;
    private TerminalTabs tabs;

    @AfterEach
    void closeTerminals() throws Exception {
        if (tabs != null) {
            SwingUtilities.invokeAndWait(tabs::closeAll);
        }
    }

    /// Two panels, the first focused; the second bound to a session working in `edge`.
    private AppState state(final Path edge) {
        return AppState.initial(List.of(new PanelState(P1, "one", "", ""), new PanelState(P2, "two", "", "edge")), List.of(),
                List.of(SessionState.closed(SessionSpec.fresh("edge", edge.toString()))));
    }

    /// Terminals whose shell runs `command`, shown for the focused panel as the lower panel's render does.
    private TerminalAccess access(final AppState s, final List<String> command) throws Exception {
        tabs = new TerminalTabs(dir -> new TerminalWidget(dir, command));
        SwingUtilities.invokeAndWait(() -> tabs.render(s, false));
        return new TerminalAccess(() -> tabs, () -> s);
    }

    private boolean running(final PanelId panel) throws Exception {
        final AtomicBoolean running = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> running.set(tabs.terminal(panel) != null && tabs.terminal(panel).isRunning()));
        return running.get();
    }

    /// A start on the panel that is not focused starts its shell in the panel's folder; the focused panel's terminal stays
    /// in front, not started. A second start leaves the running shell as it is and says so.
    @Test
    void aStartOnAPanelNotFocusedStartsItsShellInThePanelsFolder() throws Exception {
        final Path edge = Files.createDirectory(root.resolve("edge"));
        final TerminalAccess access = access(state(edge), List.of("/bin/sh", "-c", "sleep 30"));

        assertThat(access.start(P2, Optional.empty())).startsWith("started a shell in " + edge);
        assertThat(running(P2)).isTrue();
        final AtomicReference<TerminalWidget> front = new AtomicReference<>();
        final AtomicReference<Path> dir = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            front.set(tabs.focused());
            dir.set(tabs.terminal(P2).directory());
        });
        assertThat(dir.get()).isEqualTo(edge);
        assertThat(front.get()).as("the focused panel's terminal stays in front").isSameAs(tabs.terminal(P1));
        assertThat(running(P1)).isFalse();
        assertThat(access.start(P2, Optional.empty())).startsWith("the shell of two already runs");
    }

    /// A start in a directory asked for opens the shell there; one that does not exist is refused.
    @Test
    void aStartOpensTheShellInTheDirectoryAskedFor() throws Exception {
        final Path edge = Files.createDirectory(root.resolve("edge"));
        final Path other = Files.createDirectory(root.resolve("other"));
        final TerminalAccess access = access(state(edge), List.of("/bin/sh", "-c", "sleep 30"));

        assertThat(access.start(P2, Optional.of(root.resolve("missing")))).startsWith("refused: no such directory");
        assertThat(running(P2)).isFalse();
        assertThat(access.start(P2, Optional.of(other))).startsWith("started a shell in " + other);
        assertThat(running(P2)).isTrue();
    }

    /// The list names every editor panel in order, with its title, whether its shell started and runs, and where it
    /// opens; a panel without a shell has no process id.
    @Test
    void theListNamesEveryEditorPanelWithWhetherItsShellStartedAndRuns() throws Exception {
        final Path edge = Files.createDirectory(root.resolve("edge"));
        final TerminalAccess access = access(state(edge), List.of("/bin/sh", "-c", "sleep 30"));
        access.start(P2, Optional.empty());

        final List<TerminalPort.Terminal> listed = access.list();
        assertThat(listed).extracting(TerminalPort.Terminal::panel).containsExactly(P1, P2);
        assertThat(listed).extracting(TerminalPort.Terminal::title).containsExactly("one", "two");
        assertThat(listed).extracting(TerminalPort.Terminal::started).containsExactly(false, true);
        assertThat(listed).extracting(TerminalPort.Terminal::running).containsExactly(false, true);
        assertThat(listed.getFirst().pid()).isEqualTo(-1);
        assertThat(listed.getLast().directory()).isEqualTo(edge);
    }

    /// A send to a shell that never started, or that was stopped, is refused and starts nothing; a stop ends the
    /// running shell, and a second stop says it was not running.
    @Test
    void aSendToAStoppedShellIsRefusedAndAStopEndsTheShell() throws Exception {
        final Path edge = Files.createDirectory(root.resolve("edge"));
        final TerminalAccess access = access(state(edge), List.of("/bin/sh", "-c", "sleep 30"));

        assertThat(access.send(P2, "ls", true)).isEqualTo("refused: the shell of two is not running");
        assertThat(tabs.terminal(P2)).as("no terminal made for a refused send").isNull();

        access.start(P2, Optional.empty());
        assertThat(access.stop(P2)).isEqualTo("stopped the shell of two");
        assertThat(running(P2)).isFalse();
        assertThat(access.stop(P2)).isEqualTo("the shell of two was not running");
        assertThat(access.send(P2, "ls", true)).isEqualTo("refused: the shell of two is not running");
        assertThat(running(P2)).as("the send started nothing").isFalse();
        assertThat(access.interrupt(P2)).isEqualTo("refused: the shell of two is not running");
    }

    /// Every action on a panel the state does not hold is refused, and no terminal is made for it.
    @Test
    void anUnknownPanelIsRefused() throws Exception {
        final Path edge = Files.createDirectory(root.resolve("edge"));
        final TerminalAccess access = access(state(edge), List.of("/bin/sh", "-c", "sleep 30"));
        final PanelId gone = new PanelId("gone");

        assertThat(access.start(gone, Optional.empty())).isEqualTo("refused: no such panel [panel=gone]");
        assertThat(access.send(gone, "ls", true)).isEqualTo("refused: no such panel [panel=gone]");
        assertThat(access.interrupt(gone)).isEqualTo("refused: no such panel [panel=gone]");
        assertThat(access.stop(gone)).isEqualTo("refused: no such panel [panel=gone]");
        assertThat(access.read(gone, -1, 10, 1_000)).isEmpty();
        assertThat(tabs.terminal(gone)).isNull();
    }

    /// Before the window is built every action is refused as not ready, and the list names the panels, none started.
    @Test
    void beforeTheWindowIsBuiltEveryActionIsRefused() throws Exception {
        final AppState s = state(Files.createDirectory(root.resolve("edge")));
        final TerminalAccess access = new TerminalAccess(() -> null, () -> s);

        assertThat(access.start(P1, Optional.empty())).isEqualTo("refused: terminals are not ready");
        assertThat(access.send(P1, "ls", true)).isEqualTo("refused: terminals are not ready");
        assertThat(access.interrupt(P1)).isEqualTo("refused: terminals are not ready");
        assertThat(access.stop(P1)).isEqualTo("refused: terminals are not ready");
        assertThat(access.read(P1, -1, 10, 1_000)).isEmpty();
        assertThat(access.lastOutputNanos(P1)).isEmpty();
        assertThat(access.list()).extracting(TerminalPort.Terminal::started).containsExactly(false, false);
    }
}
