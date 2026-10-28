package dev.marregui.comando.app;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.ui.panels.EditorPanel;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JFrame;
import javax.swing.JList;
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

/**
 * Closing what a double click opened gives the keyboard back to what opened it (record 112), in the real window:
 * A opens B, B opens C; closing C brings B forward with the keyboard where it was in B, closing B brings A back. A
 * file opened while the keyboard was in the sidebar's session list gives it back there. Opens the window, so this
 * runs in {@code ./gradlew :app:uiTest}; the window is off screen and never focused, so the keyboard is read as the
 * window's most recent focus owner.
 */
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class FocusBackUiTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path home;

    @Test
    void closingWhatWasOpenedGivesTheKeyboardBackToWhatOpenedItAndOpeningsChain() throws Exception {
        Path work = Files.createDirectories(home.resolve("work"));
        for (String n : new String[]{"a.txt", "b.txt", "c.txt", "d.txt"}) {
            Files.writeString(work.resolve(n), n + "\n");
        }
        App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(), home.resolve("projects"), false),
                "focus", () -> {
        });
        try {
            app.start();
            StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null && frame() != null);

            PanelId a = open(loop, work.resolve("a.txt"));
            keyboardTo(editorOf(a));
            PanelId b = open(loop, work.resolve("b.txt"));
            keyboardTo(editorOf(b)); // the double click in B that opens C puts the keyboard in B first
            PanelId c = open(loop, work.resolve("c.txt"));
            keyboardTo(editorOf(c));

            loop.dispatch(new Intent.ClosePanel(c));
            Awaitility.await("B in front, the keyboard in B").atMost(WAIT)
                    .until(() -> loop.state().focused().equals(b) && keyboardIn(b));
            loop.dispatch(new Intent.ClosePanel(b));
            Awaitility.await("A in front, the keyboard in A").atMost(WAIT)
                    .until(() -> loop.state().focused().equals(a) && keyboardIn(a));

            // the keyboard in the sidebar's session list, outside every panel: closing the file gives it back there
            Component list = onEdt(() -> find(frame(), JList.class, l -> "Sessions".equals(l.getAccessibleContext().getAccessibleName())));
            assertThat(list).isNotNull();
            keyboardTo(list);
            PanelId d = open(loop, work.resolve("d.txt"));
            keyboardTo(editorOf(d));
            loop.dispatch(new Intent.ClosePanel(d));
            Awaitility.await("the keyboard back in the list").atMost(WAIT).until(() -> onEdt(() -> frame().getMostRecentFocusOwner()) == list);
        } finally {
            app.close();
        }
    }

    /** Opens {@code file} as a file link without a line opens it, and waits for its panel in front. */
    private static PanelId open(StateLoop loop, Path file) {
        loop.dispatch(new Intent.OpenFile("", file.toString(), 0));
        String title = file.getFileName().toString();
        Awaitility.await(title + " in front").atMost(WAIT).until(() -> loop.state().focusedPanel().title().equals(title));
        return loop.state().panels().stream().filter(p -> p.title().equals(title)).map(PanelState::id).findFirst().orElseThrow();
    }

    private static Component editorOf(PanelId id) throws Exception {
        AtomicReference<Component> out = new AtomicReference<>();
        Awaitility.await("panel " + id.value() + " shows").atMost(WAIT).until(() -> {
            out.set(onEdt(() -> {
                EditorPanel p = panelOf(frame(), id);
                return p == null || !p.isShowing() ? null : p.editor().area();
            }));
            return out.get() != null;
        });
        return out.get();
    }

    private static void keyboardTo(Component c) throws Exception {
        Awaitility.await("the keyboard taken").atMost(WAIT).until(() -> onEdt(() -> {
            c.requestFocusInWindow();
            return frame().getMostRecentFocusOwner() == c;
        }));
    }

    private static boolean keyboardIn(PanelId id) throws Exception {
        return onEdt(() -> {
            Component owner = frame().getMostRecentFocusOwner();
            EditorPanel p = owner == null ? null : (EditorPanel) SwingUtilities.getAncestorOfClass(EditorPanel.class, owner);
            return p != null && p.panel().id().equals(id) && owner == p.editor().area();
        });
    }

    private static JFrame frame() {
        for (Frame f : Frame.getFrames()) {
            if (f instanceof JFrame j && f.isDisplayable() && f.getTitle().startsWith("comando")) {
                return j;
            }
        }
        return null;
    }

    private static EditorPanel panelOf(Component c, PanelId id) {
        if (c instanceof EditorPanel p && p.panel().id().equals(id)) {
            return p;
        }
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                EditorPanel found = panelOf(child, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T extends Component> T find(Component c, Class<T> type, Predicate<T> which) {
        if (type.isInstance(c) && c.isShowing() && which.test(type.cast(c))) {
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
