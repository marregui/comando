package dev.marregui.comando.app;

import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.panels.EditorPanel;

import static org.assertj.core.api.Assertions.assertThat;

/// Where the keyboard goes as views open and close, as the focus router routes it, in the real window. Opens the
/// window, so this runs in `./gradlew :app:uiTest`; the window is off screen and never focused, so the keyboard is
/// read as the window's most recent focus owner.
/// - a view opened in the lower area while the keyboard is in a panel's text takes nothing from it
/// - the view closed in the background moves nothing: the keyboard stays in the panel's text (record 112)
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class FocusRouterUiTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path home;

    /// With the keyboard in a file's text, a Markdown view shown in the lower area, as an agent's `ui_show_view` shows
    /// one, leaves it there, and closing the view leaves it there too.
    @Test
    void aViewOpenedAndClosedInTheBackgroundLeavesTheKeyboardWhereItIs() throws Exception {
        final Path file = Files.writeString(home.resolve("a.txt"), "a\n");
        try (final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(),
                home.resolve("projects"), false),
                "router", () -> {
        })) {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(()
                    -> loop.state().focused() != null && onEdt(() -> frame() != null));
            loop.dispatch(new Intent.OpenFile("", file.toString(), 0));
            Awaitility.await("a.txt in front").atMost(WAIT).until(() -> loop.state().focusedPanel().title().equals("a.txt"));
            final PanelId a = loop.state().panels().stream().filter(p -> p.title().equals("a.txt")).map(PanelState::id).findFirst()
                    .orElseThrow();
            final Component text = editorOf(a);
            keyboardTo(text);

            loop.dispatch(new Intent.ShowView("", "notes", new ViewSpec.Markdown("# Notes\n\nA view.\n"), Placement.LOWER));
            Awaitility.await("the view open").atMost(WAIT).until(() -> loop.state().views().stream().anyMatch(v -> v.title().equals("notes")));
            final long id = loop.state().views().stream().filter(v -> v.title().equals("notes")).mapToLong(ViewState::id).findFirst()
                    .orElseThrow();
            final JEditorPane view = awaitView("notes");
            assertThat(view).isNotNull();
            assertThat(onEdt(() -> frame().getMostRecentFocusOwner())).as("the keyboard left in the text").isSameAs(text);

            loop.dispatch(new Intent.CloseView(id));
            Awaitility.await("the view closed").atMost(WAIT).until(() -> loop.state().views().stream().noneMatch(v -> v.id() == id));
            Awaitility.await("its tab gone").atMost(WAIT).until(() -> onEdt(() -> find(frame(), JTabbedPane.class,
                    t -> t.indexOfTab("notes") >= 0) == null));
            assertThat(onEdt(() -> frame().getMostRecentFocusOwner())).as("nothing moved by a close in the background").isSameAs(text);
        }
    }

    /// The view's page: the editor pane in the lower area's tab titled {@code title}.
    private static JEditorPane awaitView(final String title) throws Exception {
        final AtomicReference<JEditorPane> out = new AtomicReference<>();
        Awaitility.await("the view's page shows").atMost(WAIT).until(() -> {
            out.set(onEdt(() -> {
                final JTabbedPane tabs = find(frame(), JTabbedPane.class, t -> t.indexOfTab(title) >= 0);
                return tabs == null ? null : find(tabs.getComponentAt(tabs.indexOfTab(title)), JEditorPane.class, JEditorPane::isFocusable);
            }));
            return out.get() != null;
        });
        return out.get();
    }

    private static Component editorOf(final PanelId id) throws Exception {
        final AtomicReference<Component> out = new AtomicReference<>();
        Awaitility.await("panel " + id.value() + " shows").atMost(WAIT).until(() -> {
            out.set(onEdt(() -> {
                final EditorPanel p = find(frame(), EditorPanel.class, e -> e.panel().id().equals(id));
                return p == null ? null : p.editor().area();
            }));
            return out.get() != null;
        });
        return out.get();
    }

    private static void keyboardTo(final Component c) throws Exception {
        Awaitility.await("the keyboard taken").atMost(WAIT).until(() -> onEdt(() -> {
            c.requestFocusInWindow();
            return frame().getMostRecentFocusOwner() == c;
        }));
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
