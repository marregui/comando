package dev.marregui.comando.app;

import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.logging.Logger;
import javax.swing.JFrame;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;

import org.awaitility.Awaitility;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;

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
        final AppConfig config = new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(), home.resolve("projects"), false);
        Logging.configure(config.logDir());
        final Logger log = Logger.getLogger("dev.marregui.comando.OwnLogUiTest");
        for (int i = 0; i < 300; i++) {
            log.info("filler " + i);
        }
        try (final App app = new App(config, "ownlog", () -> {
        })) {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(()
                    -> loop.state().focused() != null && onEdt(() -> frame() != null));
            final Path file = config.logDir().resolve("comando-0.log");
            loop.dispatch(new Intent.OpenFile("", file.toString(), 0));
            Awaitility.await("the log's panel").atMost(WAIT).until(() -> loop.state().focusedPanel().title().startsWith("comando-0"));
            final RSyntaxTextArea area = onEdt(() -> find(frame(), RSyntaxTextArea.class, a -> a.isShowing() && !a.isEditable()));
            // the read that shows the text records its stamp: a line written after it is a change the panel sees
            Awaitility.await("the log shown").atMost(WAIT).until(() -> onEdt(area::getText).contains("filler 299"));

            log.info("written while the log is open");
            Awaitility.await("the new line shows").atMost(WAIT).until(() -> onEdt(area::getText).contains("written while the log is open"));
            final JViewport v = onEdt(() -> (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, area));
            Awaitility.await("the view at the end").atMost(WAIT).until(() -> onEdt(() -> {
                final Rectangle seen = v.getViewRect();
                return seen.y + seen.height >= v.getView().getHeight() - 2 * area.getLineHeight();
            }));
        }
        Awaitility.await("the first window gone").atMost(WAIT).until(() -> onEdt(() -> frame() == null));

        // the next start brings the log's panel back, as comando's restart does: it is as live as one just opened
        try (final App again = new App(config, "ownlog", () -> {
        })) {
            again.start();
            final StateLoop loop = again.loop();
            Awaitility.await("the window again").atMost(Duration.ofSeconds(20)).until(()
                    -> loop.state().focused() != null && onEdt(() -> frame() != null));
            Awaitility.await("the log's panel back").atMost(WAIT).until(() -> loop.state().panels().stream().anyMatch(p -> p.title().startsWith("comando-0")));
            loop.state().panels().stream().filter(p -> p.title().startsWith("comando-0")).findFirst()
                    .ifPresent(p -> loop.dispatch(new Intent.FocusPanel(p.id())));
            Awaitility.await("in front").atMost(WAIT).until(() -> loop.state().focusedPanel().title().startsWith("comando-0"));
            final RSyntaxTextArea area = Awaitility.await("its text").atMost(WAIT).until(
                    () -> onEdt(() -> find(frame(), RSyntaxTextArea.class, a -> a.isShowing() && !a.isEditable())), Objects::nonNull);
            Awaitility.await("the log shown again").atMost(WAIT).until(() -> onEdt(area::getText).contains("filler 299"));
            log.info("written after the restart");
            Awaitility.await("the line after the restart shows").atMost(WAIT).until(() -> onEdt(area::getText).contains("written after the restart"));
        }
    }

    private static JFrame frame() {
        for (final Frame f : Frame.getFrames()) {
            if (f instanceof JFrame j && f.isDisplayable() && f.getTitle().startsWith("comando")) {
                return j;
            }
        }
        return null;
    }

    private static <T extends Component> T find(final Component c, final Class<T> type, final Predicate<T> which) {
        if (c == null) {
            return null;
        }
        if (type.isInstance(c) && which.test(type.cast(c))) {
            return type.cast(c);
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                final T found = find(child, type, which);
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
