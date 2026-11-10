package dev.marregui.comando.app;

import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.JFrame;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.view.Layout;

import static org.assertj.core.api.Assertions.assertThat;

/// The window's panes as the layout keeper lays them out and keeps them, in the real window. Opens the window, so this
/// runs in `./gradlew :app:uiTest`; the window is off screen.
/// - the sidebar's width kept in `settings.json` comes back at the start; a value that is no number gives the default
/// - a session's arrangement hides the sidebar to its rail and shows it again at its kept width
/// - the width the user leaves is saved a second after the window last changed size, and the next window opens with it
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class LayoutKeeperUiTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path home;

    /// `divider.sidebar` 333 in `settings.json` opens the sidebar 333 px wide; hidden by an arrangement it shrinks to
    /// its rail, shown again it is 333 px again; moved to 300, it is saved once the window has changed size, and the
    /// next window opens it at 300.
    @Test
    void theSidebarsWidthIsKeptAndAnArrangementHidesAndShowsIt() throws Exception {
        final Path data = Files.createDirectories(home.resolve("data"));
        Files.writeString(data.resolve("settings.json"), "{\"version\": 1, \"divider.sidebar\": 333}");
        try (final App app = start("layout")) {
            final StateLoop loop = app.loop();
            final JSplitPane outer = outer();
            Awaitility.await("restored at its kept width").atMost(WAIT).until(() -> onEdt(outer::getDividerLocation) == 333);

            loop.dispatch(new Intent.Arrange(new Layout(Layout.Toggle.HIDE, Layout.Toggle.KEEP, Double.NaN)));
            Awaitility.await("hidden to its rail").atMost(WAIT).until(() -> onEdt(outer::getDividerLocation) < 100);
            loop.dispatch(new Intent.Arrange(new Layout(Layout.Toggle.SHOW, Layout.Toggle.KEEP, Double.NaN)));
            Awaitility.await("shown at its kept width").atMost(WAIT).until(() -> onEdt(outer::getDividerLocation) == 333);

            onEdt(() -> {
                outer.setDividerLocation(300);
                return null;
            });
            Awaitility.await("the move heard").atMost(WAIT).until(() -> onEdt(outer::getDividerLocation) == 300);
            onEdt(() -> {
                frame().setSize(frame().getWidth() + 1, frame().getHeight());
                return null;
            });
            Awaitility.await("saved a second after the resize").atMost(WAIT)
                    .until(() -> Files.readString(data.resolve("settings.json")).matches("(?s).*\"divider\\.sidebar\"\\s*:\\s*300\\b.*"));
        }
        try (final App _ = start("layout-again")) {
            final JSplitPane outer = outer();
            Awaitility.await("the width left kept for the next window").atMost(WAIT).until(() -> onEdt(outer::getDividerLocation) == 300);
        }
    }

    /// A `divider.sidebar` that is no number is not taken: the sidebar opens at its default width, 260 px, and the
    /// window starts.
    @Test
    void aValueThatIsNoNumberGivesTheDefault() throws Exception {
        final Path data = Files.createDirectories(home.resolve("data"));
        Files.writeString(data.resolve("settings.json"), "{\"version\": 1, \"divider.sidebar\": \"wide\"}");
        try (final App _ = start("layout-default")) {
            final JSplitPane outer = outer();
            Awaitility.await("the default width").atMost(WAIT).until(() -> onEdt(outer::getDividerLocation) == 260);
            assertThat(onEdt(() -> frame().isDisplayable())).isTrue();
        }
    }

    /// The application closed with no quit flow, as on the shutdown hook's path, keeps a width moved the moment before,
    /// with no change of the window's size to start the save's timer: `settings.json` holds it once the close returns.
    @Test
    void aCloseWithoutTheQuitFlowKeepsTheWidthMovedJustBefore() throws Exception {
        final Path data = Files.createDirectories(home.resolve("data"));
        Files.writeString(data.resolve("settings.json"), "{\"version\": 1, \"divider.sidebar\": 333}");
        try (final App _ = start("layout-close")) {
            final JSplitPane outer = outer();
            Awaitility.await("restored at its kept width").atMost(WAIT)
                    .until(() -> onEdt(outer::getDividerLocation) == 333);
            onEdt(() -> {
                outer.setDividerLocation(310);
                return null;
            });
            Awaitility.await("the move heard").atMost(WAIT).until(() -> onEdt(outer::getDividerLocation) == 310);
        }
        assertThat(Files.readString(data.resolve("settings.json"))).as("written by the close")
                .matches("(?s).*\"divider\\.sidebar\"\\s*:\\s*310\\b.*");
    }

    private App start(final String name) {
        final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(), home.resolve("projects"), false),
                name, () -> {
        });
        try {
            app.start();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(()
                    -> app.loop().state().focused() != null && onEdt(() -> frame() != null));
        } catch (final RuntimeException | AssertionError e) {
            // the caller's try-with-resources holds the window only once it is returned
            app.close();
            throw e;
        }
        return app;
    }

    /// The split between the sidebar and the rest: the window's horizontal split whose left side is the sidebar.
    private static JSplitPane outer() throws Exception {
        final AtomicReference<JSplitPane> out = new AtomicReference<>();
        Awaitility.await("the outer split").atMost(WAIT).until(() -> {
            out.set(onEdt(() -> find(frame(), JSplitPane.class, p -> p.getOrientation() == JSplitPane.HORIZONTAL_SPLIT
                    && SwingUtilities.getAncestorOfClass(JSplitPane.class, p) == null)));
            return out.get() != null;
        });
        return out.get();
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
        if (type.isInstance(c) && c.isShowing() && which.test(type.cast(c))) {
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
