package dev.marregui.comando.app;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.view.ViewSpec;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.AbstractButton;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/// A panel's Locate button in the real window (record 119). Opens the window, so this runs in `./gradlew :app:uiTest`.
/// - on a document's panel it brings the Files tab to the front with the file selected, its closed folders opened
/// - on a session's panel it brings the Sessions tab to the front with the session selected
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class LocateUiTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @TempDir
    Path home;

    /// A file two folders down in a folder of the Files tab, never opened there: Locate shows it selected; then a panel
    /// bound to a session: Locate selects the session in the Sessions tab.
    @Test
    void locateShowsTheFileInFilesAndTheSessionInSessions() throws Exception {
        Path notes = Files.createDirectories(home.resolve("notes/deep/er"));
        Path doc = notes.resolve("plan.md");
        Files.writeString(doc, "# Plan\n");
        Path data = Files.createDirectories(home.resolve("data"));
        Files.writeString(data.resolve("folders.json"), "[{\"name\": \"Notes\", \"path\": \"" + home.resolve("notes") + "\"}]");
        App app = new App(new AppConfig(data, home.resolve("no-claude").toString(), home.resolve("projects"), false), "locate", () -> {
        });
        try {
            app.start();
            StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null && frame() != null);
            loop.dispatch(new Intent.OpenFile("", doc.toString(), 0));
            Awaitility.await("its panel").atMost(WAIT).until(() -> loop.state().focusedPanel().title().equals("plan.md"));

            AbstractButton locate = await(AbstractButton.class, "Show in Files", b -> "Show in Files".equals(b.getAccessibleContext().getAccessibleName()));
            SwingUtilities.invokeAndWait(locate::doClick);
            JTree files = await(JTree.class, "the Files tree", t -> "Files".equals(t.getAccessibleContext().getAccessibleName()));
            Awaitility.await("the file selected").atMost(WAIT).until(() -> onEdt(() -> {
                TreePath p = files.getSelectionPath();
                return p != null && ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof ViewSpec.TreeNode n
                        && n.label().equals("plan.md") && files.isVisible(p);
            }));

            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("helper", home.toString())));
            // a new session comes with its own panel (record 88)
            Awaitility.await("the session's panel").atMost(WAIT).until(() -> loop.state().panels().stream().anyMatch(p -> p.session().equals("helper")));
            PanelId own = loop.state().panels().stream().filter(p -> p.session().equals("helper")).findFirst().orElseThrow().id();
            loop.dispatch(new Intent.FocusPanel(own));
            AbstractButton toSession = await(AbstractButton.class, "Show in Sessions", b -> "Show in Sessions".equals(b.getAccessibleContext().getAccessibleName()));
            SwingUtilities.invokeAndWait(toSession::doClick);
            @SuppressWarnings("unchecked")
            JList<Object> sessions = await(JList.class, "the Sessions list", l -> "Sessions".equals(l.getAccessibleContext().getAccessibleName()));
            Awaitility.await("the session selected").atMost(WAIT).until(() -> onEdt(() -> selectedName(sessions).equals("helper")));
        } finally {
            app.close();
        }
    }

    private static String selectedName(JList<Object> list) {
        Object v = list.getSelectedValue();
        return v instanceof SessionState s ? s.name() : "";
    }

    private static <T extends Component> T await(Class<T> type, String what, Predicate<T> which) {
        AtomicReference<T> out = new AtomicReference<>();
        Awaitility.await(what).atMost(WAIT).until(() -> {
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
