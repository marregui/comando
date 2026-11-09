package dev.marregui.comando.app;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.SwingUtilities;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;

import static org.assertj.core.api.Assertions.assertThat;

/// The status bar's work list in the real window. Opens the window, so this runs in `./gradlew :app:uiTest`.
/// - a click on the count opens one line per session at work, and a click on a line goes to that session's panel
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class WorkListUiTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path home;

    /// With `work` running a turn behind another panel, a click on the count lists it, and a click on its line brings
    /// its panel forward.
    @Test
    void aClickOnTheCountListsWhoWorksAndALineGoesThere() throws Exception {
        try (final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(),
                home.resolve("projects"), false), "work-list", () -> {
        })) {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null);
            final PanelId first = loop.state().focused();
            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("work", home.toString())));
            Awaitility.await("its panel").atMost(WAIT).until(() -> loop.state().panels().stream()
                    .anyMatch(p -> p.session().equals("work")));
            final PanelId bound = loop.state().panels().stream().filter(p -> p.session().equals("work")).findFirst()
                    .orElseThrow().id();
            loop.dispatch(new Intent.FocusPanel(first));
            loop.dispatch(new Intent.SessionTurnRunning("work"));
            Awaitility.await("behind another panel").atMost(WAIT).until(() -> first.equals(loop.state().focused())
                    && loop.state().session("work").orElseThrow().isBusy());

            final JLabel count = await(JLabel.class, l -> "Work".equals(l.getAccessibleContext().getAccessibleName())
                    && l.getText().contains("1 session working"));
            click(count, onEdt(() -> new Rectangle(0, 0, count.getWidth(), count.getHeight())));

            @SuppressWarnings("unchecked")
            final JList<Object> list = await(JList.class,
                    l -> "Work".equals(l.getAccessibleContext().getAccessibleName()));
            assertThat(onEdt(() -> list.getModel().getSize())).isEqualTo(1);
            assertThat(onEdt(() -> list.getModel().getElementAt(0).toString())).contains("work · ");
            click(list, onEdt(() -> list.getCellBounds(0, 0)));

            Awaitility.await("its panel in front").atMost(WAIT).until(() -> bound.equals(loop.state().focused()));
        }
    }

    private static void click(final Component c, final Rectangle at) throws Exception {
        SwingUtilities.invokeAndWait(() -> c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, 0L, 0, at.x + 2,
                at.y + 2, 1, false, MouseEvent.BUTTON1)));
    }

    private static <T extends Component> T await(final Class<T> type, final Predicate<T> which) {
        final AtomicReference<T> out = new AtomicReference<>();
        Awaitility.await(type.getSimpleName()).atMost(WAIT).until(() -> {
            out.set(onEdt(() -> {
                for (final Window w : Window.getWindows()) {
                    final T found = find(w, type, c -> c.isShowing() && which.test(c));
                    if (found != null) {
                        return found;
                    }
                }
                return null;
            }));
            return out.get() != null;
        });
        return out.get();
    }

    private static <T extends Component> T find(final Component c, final Class<T> type, final Predicate<T> which) {
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

    private static <T> T onEdt(final Callable<T> call) throws Exception {
        final AtomicReference<T> out = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                out.set(call.call());
            } catch (final Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return out.get();
    }
}
