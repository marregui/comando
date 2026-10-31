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
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.state.LowerTab;

import static org.assertj.core.api.Assertions.assertThat;

/// The divider between the panels and the lower panel is each panel's own. Opens the window, so this runs in
/// `./gradlew :app:uiTest`.
/// - moved while one panel is in front, it returns to another panel's position when that one comes forward, and back
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class LowerDividerUiTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path home;

    /// Two files opened to read hide the lower area until it is asked for; asked for, a panel never in front
    /// takes the last height shown, 150 px; the divider moved with one panel in front goes back to the other panel's
    /// position when that one comes forward, and to the moved position when the first returns.
    @Test
    void eachPanelBringsBackItsOwnLowerDivider() throws Exception {
        final Path one = Files.writeString(home.resolve("one.md"), "# One\n");
        final Path two = Files.writeString(home.resolve("two.md"), "# Two\n");
        final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(), home.resolve("projects"), false),
                "divider", () -> {
        });
        try {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null && frame() != null);
            loop.dispatch(new Intent.OpenFile("", one.toString(), 0));
            Awaitility.await("one's panel").atMost(WAIT).until(() -> loop.state().focusedPanel().title().equals("one.md"));
            final PanelId first = loop.state().focused();
            final JSplitPane vertical = onEdt(() -> find(frame(), JSplitPane.class, p -> p.getOrientation() == JSplitPane.VERTICAL_SPLIT));
            Awaitility.await("laid out").atMost(WAIT).until(() -> onEdt(vertical::getHeight) > 400);
            Awaitility.await("a file to read hides the lower area").atMost(WAIT).until(() -> onEdt(() -> !vertical.getBottomComponent().isVisible()));
            loop.dispatch(new Intent.SelectLowerTab(LowerTab.RESULTS));
            Awaitility.await("asked for, it shows").atMost(WAIT).until(() -> onEdt(() -> vertical.getBottomComponent().isVisible()));
            onEdt(() -> {
                vertical.setDividerLocation(150);
                return null;
            });

            loop.dispatch(new Intent.OpenFile("", two.toString(), 0));
            Awaitility.await("two's panel").atMost(WAIT).until(() -> loop.state().focusedPanel().title().equals("two.md"));
            Awaitility.await("hidden for two").atMost(WAIT).until(() -> onEdt(() -> !vertical.getBottomComponent().isVisible()));
            loop.dispatch(new Intent.SelectLowerTab(LowerTab.SESSION));
            Awaitility.await("asked for two").atMost(WAIT).until(() -> onEdt(() -> vertical.getBottomComponent().isVisible()));
            assertThat(onEdt(vertical::getDividerLocation)).as("a panel never in front takes the last height shown").isEqualTo(150);
            onEdt(() -> {
                vertical.setDividerLocation(350);
                return null;
            });
            final PanelId second = loop.state().focused();

            loop.dispatch(new Intent.FocusPanel(first));
            Awaitility.await("one's divider back").atMost(WAIT).until(() -> onEdt(vertical::getDividerLocation) == 150);
            loop.dispatch(new Intent.FocusPanel(second));
            Awaitility.await("two's divider back").atMost(WAIT).until(() -> onEdt(vertical::getDividerLocation) == 350);
        } finally {
            app.close();
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
