package dev.marregui.comando.app;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.PanelId;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JFrame;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/// comando's own log, opened from Help as a panel, is a live view while comando writes it: the line logged after it
/// opened shows, and the view stays at the end. Opens the window, so this runs in `./gradlew :app:uiTest`.
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class OwnLogUiTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path home;

    /// The log opened as a panel shows the lines already written, then a line logged while it is open, and its view
    /// stays at the end; after a restart the restored log panel is as live as one just opened.
    @Test
    void comandosOwnLogShowsWhatIsLoggedWhileItIsOpen() throws Exception {
        AppConfig config = new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(), home.resolve("projects"), false);
        Logging.configure(config.logDir());
        java.util.logging.Logger log = java.util.logging.Logger.getLogger("dev.marregui.comando.OwnLogUiTest");
        for (int i = 0; i < 300; i++) {
            log.info("filler " + i);
        }
        App app = new App(config, "ownlog", () -> {
        });
        try {
            app.start();
            StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null && frame() != null);
            Path file = config.logDir().resolve("comando-0.log");
            loop.dispatch(new Intent.OpenFile("", file.toString(), 0));
            Awaitility.await("the log's panel").atMost(WAIT).until(() -> loop.state().focusedPanel().title().startsWith("comando-0"));
            RSyntaxTextArea area = onEdt(() -> find(frame(), RSyntaxTextArea.class, a -> a.isShowing() && !a.isEditable()));
            Awaitility.await("the log shown").atMost(WAIT).until(() -> onEdt(area::getText).contains("filler 299"));
            Thread.sleep(1_500);

            log.info("written while the log is open");
            Awaitility.await("the new line shows").atMost(WAIT).until(() -> onEdt(area::getText).contains("written while the log is open"));
            JViewport v = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, area);
            Awaitility.await("the view at the end").atMost(WAIT).until(() -> onEdt(() -> {
                java.awt.Rectangle seen = v.getViewRect();
                return seen.y + seen.height >= v.getView().getHeight() - 2 * area.getLineHeight();
            }));
        } finally {
            app.close();
        }
        Awaitility.await("the first window gone").atMost(WAIT).until(() -> frame() == null);

        // the next start brings the log's panel back, as comando's restart does: it is as live as one just opened
        App again = new App(config, "ownlog", () -> {
        });
        try {
            again.start();
            StateLoop loop = again.loop();
            Awaitility.await("the window again").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null && frame() != null);
            Awaitility.await("the log's panel back").atMost(WAIT).until(() -> loop.state().panels().stream().anyMatch(p -> p.title().startsWith("comando-0")));
            loop.state().panels().stream().filter(p -> p.title().startsWith("comando-0")).findFirst()
                    .ifPresent(p -> loop.dispatch(new Intent.FocusPanel(p.id())));
            Awaitility.await("in front").atMost(WAIT).until(() -> loop.state().focusedPanel().title().startsWith("comando-0"));
            RSyntaxTextArea area = Awaitility.await("its text").atMost(WAIT).until(
                    () -> onEdt(() -> find(frame(), RSyntaxTextArea.class, a -> a.isShowing() && !a.isEditable())), java.util.Objects::nonNull);
            Thread.sleep(1_500);
            log.info("written after the restart");
            Awaitility.await("the line after the restart shows").atMost(WAIT).until(() -> onEdt(area::getText).contains("written after the restart"));
        } finally {
            again.close();
        }
    }

    private static JFrame frame() {
        for (Frame f : Frame.getFrames()) {
            if (f instanceof JFrame j && f.isDisplayable() && f.getTitle().startsWith("comando")) {
                return j;
            }
        }
        return null;
    }

    private static <T extends Component> T find(Component c, Class<T> type, Predicate<T> which) {
        if (c == null) {
            return null;
        }
        if (type.isInstance(c) && which.test(type.cast(c))) {
            return type.cast(c);
        }
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                T found = find(child, type, which);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private interface EdtCall<T> {
        T call() throws Exception;
    }

    private static <T> T onEdt(EdtCall<T> call) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                out.set(call.call());
            } catch (Exception e) {
                err.set(e);
            }
        });
        if (err.get() != null) {
            throw err.get();
        }
        return out.get();
    }
}
