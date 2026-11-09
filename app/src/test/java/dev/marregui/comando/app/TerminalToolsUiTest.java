package dev.marregui.comando.app;

import java.awt.Frame;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.port.TerminalPort;

import static org.assertj.core.api.Assertions.assertThat;

/// An agent drives the Terminal of a panel the user is not looking at, through the window's terminal port (record
/// 148): start, type, read the answer, interrupt a job, stop. Opens the window and real shells, so this runs in
/// `./gradlew :app:uiTest`.
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class TerminalToolsUiTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path home;

    /// With the second panel in front, the first panel's shell starts, answers a typed command, has its job
    /// interrupted with the shell alive, and stops; the focused panel never changes and the answer is read by its
    /// line numbers.
    @Test
    void anAgentDrivesTheTerminalOfAPanelBehind() throws Exception {
        final Path one = Files.writeString(home.resolve("one.md"), "# One\n");
        final Path two = Files.writeString(home.resolve("two.md"), "# Two\n");
        try (final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(),
                home.resolve("projects"), false),
                "terminals", () -> {
        })) {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(()
                    -> loop.state().focused() != null && onEdt(TerminalToolsUiTest::windowShown));
            loop.dispatch(new Intent.OpenFile("", one.toString(), 0));
            Awaitility.await("one's panel").atMost(WAIT).until(() -> loop.state().focusedPanel().title().equals("one.md"));
            final PanelId behind = loop.state().focused();
            loop.dispatch(new Intent.OpenFile("", two.toString(), 0));
            Awaitility.await("two's panel").atMost(WAIT).until(() -> loop.state().focusedPanel().title().equals("two.md"));
            final PanelId front = loop.state().focused();
            final TerminalPort terminals = app.ui().terminals();

            assertThat(terminals.start(behind, Optional.of(home))).doesNotStartWith("refused");
            Awaitility.await("the shell runs").atMost(WAIT).until(() -> terminal(terminals, behind).running());
            final long since = terminal(terminals, behind).next();

            Awaitility.await("the shell takes input").atMost(WAIT).until(()
                    -> !terminals.send(behind, "echo t86-$((6*7))", true).startsWith("refused"));
            Awaitility.await("its answer").atMost(WAIT).until(()
                    -> terminals.read(behind, since, 200, 65_536).orElseThrow().text().lines().anyMatch("t86-42"::equals));
            final TerminalPort.Page page = terminals.read(behind, since, 200, 65_536).orElseThrow();
            assertThat(page.first()).as("read from the kept line number").isGreaterThanOrEqualTo(since);
            assertThat(page.next()).as("the cursor moved past the answer").isGreaterThan(since);

            final long pid = terminal(terminals, behind).pid();
            assertThat(terminals.interrupt(behind)).as("no job runs at the prompt").startsWith("refused");
            assertThat(terminals.send(behind, "sleep 30", true)).doesNotStartWith("refused");
            Awaitility.await("the job runs").atMost(WAIT).until(() -> !terminal(terminals, behind).job().isEmpty());
            assertThat(terminals.interrupt(behind)).doesNotStartWith("refused");
            Awaitility.await("the job ends").atMost(WAIT).until(() -> terminal(terminals, behind).job().isEmpty());
            assertThat(terminal(terminals, behind).pid()).as("the shell survives Ctrl-C").isEqualTo(pid);

            assertThat(terminals.stop(behind)).doesNotStartWith("refused");
            Awaitility.await("the shell ends").atMost(WAIT).until(() -> !terminal(terminals, behind).running());
            assertThat(loop.state().focused()).as("the user's panel stayed in front").isEqualTo(front);
        }
    }

    private static TerminalPort.Terminal terminal(final TerminalPort terminals, final PanelId panel) {
        return terminals.list().stream().filter(t -> t.panel().equals(panel)).findFirst().orElseThrow();
    }

    private static boolean windowShown() {
        for (final Frame f : Frame.getFrames()) {
            if (f.isDisplayable() && f.getTitle().startsWith("comando")) {
                return true;
            }
        }
        return false;
    }

    private interface EdtCall<T> {
        T call() throws Exception;
    }

    private static <T> T onEdt(final EdtCall<T> call) throws Exception {
        final AtomicReference<T> out = new AtomicReference<>();
        final AtomicReference<Exception> err = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                out.set(call.call());
            } catch (final Exception e) {
                err.set(e);
            }
        });
        if (err.get() != null) {
            throw err.get();
        }
        return out.get();
    }
}
