package dev.marregui.comando.app;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.AbstractButton;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/// A Markdown document's outline in the real window. Opens the window, so this runs in `./gradlew :app:uiTest`.
/// - the Outline button in the panel's tools shows the outline at the right
/// - a double click on a heading far down scrolls the rendered page there
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class OutlineUiTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path home;

    /// The Outline button shows the document's headings beside the rendered page, and a double click on a heading below the
    /// first screen scrolls the page so that heading is in view.
    @Test
    void theOutlineButtonShowsTheHeadingsAndADoubleClickScrollsThePageToOne() throws Exception {
        Path doc = home.resolve("plan.md");
        Files.writeString(doc, "# Plan\n\n" + "A line of text.\n\n".repeat(120) + "## Far section\n\nThe end.\n");
        App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(), home.resolve("projects"), false),
                "outline", () -> {
        });
        try {
            app.start();
            StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null && frame() != null);
            loop.dispatch(new Intent.OpenFile("", doc.toString(), 0));
            Awaitility.await("its panel").atMost(WAIT).until(() -> loop.state().focusedPanel().title().equals("plan.md"));

            AbstractButton outline = await(AbstractButton.class, b -> "Outline".equals(b.getAccessibleContext().getAccessibleName()));
            SwingUtilities.invokeAndWait(outline::doClick);
            @SuppressWarnings("unchecked")
            JList<Object> list = await(JList.class, l -> "outline-list".equals(l.getName()) && l.getModel().getSize() == 2);
            JViewport page = onEdt(() -> {
                Component pane = find(frame(), Component.class, c -> c.getClass().getSimpleName().equals("HtmlPane") && c.isShowing());
                return (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, pane);
            });
            assertThat(page).isNotNull();
            // at the panel's right, beside the button that shows it: right of the page
            Awaitility.await("the outline placed").atMost(WAIT).until(() -> onEdt(() -> {
                Point at = SwingUtilities.convertPoint(list, 0, 0, frame());
                Point text = SwingUtilities.convertPoint(page, 0, 0, frame());
                return at.x > text.x + page.getWidth() - 1;
            }));
            assertThat(onEdt(() -> page.getViewPosition().y)).as("the page at its top").isZero();

            SwingUtilities.invokeAndWait(() -> {
                Rectangle r = list.getCellBounds(1, 1);
                list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, r.x + 5, r.y + 2, 2, false,
                        MouseEvent.BUTTON1));
            });
            Awaitility.await("scrolled to the far section").atMost(WAIT).until(() -> onEdt(() -> page.getViewPosition().y) > 500);
        } finally {
            app.close();
        }
    }

    private static <T extends Component> T await(Class<T> type, Predicate<T> which) {
        AtomicReference<T> out = new AtomicReference<>();
        Awaitility.await(type.getSimpleName()).atMost(WAIT).until(() -> {
            out.set(onEdt(() -> find(frame(), type, c -> c.isShowing() && which.test(c))));
            return out.get() != null;
        });
        return out.get();
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
