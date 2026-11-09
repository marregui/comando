package dev.marregui.comando.app;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.theme.Theme;

import static org.assertj.core.api.Assertions.assertThat;

/// The Files written view in the real window (record 143): the files the session with the keyboard wrote, in the side
/// pane. Opens windows, so this runs in `./gradlew :app:uiTest`.
/// - one row per file the session's transcript wrote, in the order it wrote them, gold until opened
/// - selecting a row shows its file in the workspace's preview tab; ⏎ keeps the tab and the row turns plain
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class DeliverablesUiTest {

    private static final String SESSION = "writer";
    private static final Duration WAIT = Duration.ofSeconds(15);

    @TempDir
    Path home;

    /// A window whose session with the keyboard wrote two files, `plan.md` before `notes.md`: the view opened lists
    /// both files in the order written, each gold; selecting the first shows it in the preview tab, ⏎ keeps the tab,
    /// and the first row turns plain while the second stays gold.
    @Test
    void theFilesWrittenListInOrderAndEnterKeepsTheFirstOpen() throws Exception {
        final Path work = Files.createDirectories(home.resolve("work"));
        final Path plan = Files.writeString(work.resolve("plan.md"), "# Plan\n\nthe quarterly budget stays\n");
        final Path notes = Files.writeString(work.resolve("notes.md"), "# Notes\n");
        try (final App app = start(plan, notes)) {
            final StateLoop loop = app.loop();
            loop.dispatch(new Intent.ShowView("", ViewSpec.Deliverables.TITLE, new ViewSpec.Deliverables(),
                    Placement.SIDE));

            final JTree files = files();
            Awaitility.await("both files").atMost(WAIT).until(() -> onEdt(() -> rows(files))
                    .equals(List.of("plan.md", "notes.md")));
            assertThat(onEdt(() -> colorOf(files, 0))).as("the first file not opened yet")
                    .isEqualTo(Theme.tint().body);
            assertThat(onEdt(() -> colorOf(files, 1))).as("the second file not opened yet")
                    .isEqualTo(Theme.tint().body);

            onEdt(() -> {
                files.setSelectionRow(0);
                return null;
            });
            Awaitility.await("the preview tab").atMost(WAIT).until(() -> previewPanel(loop, "plan.md").isPresent());

            press(files, KeyEvent.VK_ENTER, 0);

            Awaitility.await("the tab kept").atMost(WAIT).until(() -> panel(loop, "plan.md").map(p -> !p.preview())
                    .orElse(false));
            Awaitility.await("the first row plain").atMost(WAIT).until(() -> onEdt(()
                    -> !Theme.tint().body.equals(colorOf(files, 0))));
            assertThat(onEdt(() -> colorOf(files, 1))).as("the second file still not opened")
                    .isEqualTo(Theme.tint().body);
        }
    }

    private static Block.ToolCall write(final String id, final Path file) {
        return new Block.ToolCall(id, "Write", input(file), "", Block.ToolStatus.DONE, "");
    }

    private static String input(final Path file) {
        return "{\"file_path\":\"" + file + "\"}";
    }

    private static Optional<PanelState> panel(final StateLoop loop, final String title) {
        return loop.state().panels().stream().filter(p -> p.title().equals(title)).findFirst();
    }

    private static Optional<PanelState> previewPanel(final StateLoop loop, final String title) {
        return panel(loop, title).filter(PanelState::preview);
    }

    /// The view's rows, once the view shows.
    private static JTree files() {
        final AtomicReference<JTree> out = new AtomicReference<>();
        Awaitility.await("the Files written view").atMost(WAIT).until(() -> {
            out.set(onEdt(() -> find(frame(), JTree.class, t -> t.isShowing()
                    && ViewSpec.Deliverables.TITLE.equals(t.getAccessibleContext().getAccessibleName()))));
            return out.get() != null;
        });
        return out.get();
    }

    /// The label of each row the tree shows.
    private static List<String> rows(final JTree tree) {
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < tree.getRowCount(); i++) {
            if (((DefaultMutableTreeNode) tree.getPathForRow(i).getLastPathComponent()).getUserObject()
                    instanceof ViewSpec.TreeNode n) {
                out.add(n.label());
            }
        }
        return out;
    }

    /// The colour row `row` is painted in while not selected.
    private static Color colorOf(final JTree tree, final int row) {
        final Object value = tree.getPathForRow(row).getLastPathComponent();
        final Component c = tree.getCellRenderer().getTreeCellRendererComponent(tree, value, false, false, true, row,
                false);
        final JLabel label = find(c, JLabel.class, _ -> true);
        return label == null ? null : label.getForeground();
    }

    private static void press(final Component target, final int code, final int modifiers) throws Exception {
        onEdt(() -> {
            final long now = System.currentTimeMillis();
            target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_PRESSED, now, modifiers, code,
                    KeyEvent.CHAR_UNDEFINED));
            target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_RELEASED, now, modifiers, code,
                    KeyEvent.CHAR_UNDEFINED));
            return null;
        });
    }

    private static JFrame frame() {
        for (final Frame f : Frame.getFrames()) {
            if (f instanceof JFrame j && f.isDisplayable() && f.getTitle().startsWith("comando")) {
                return j;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Component> T find(
            final Component c,
            final Class<? super T> type,
            final Predicate<T> which
    ) {
        if (type.isInstance(c) && which.test((T) c)) {
            return (T) c;
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

    private static <T> T onEdt(final EdtCall<T> call) throws Exception {
        final AtomicReference<T> out = new AtomicReference<>();
        final AtomicReference<Exception> failed = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                out.set(call.call());
            } catch (final Exception e) {
                failed.set(e);
            }
        });
        if (failed.get() != null) {
            throw failed.get();
        }
        return out.get();
    }

    /// A window whose session with the keyboard wrote `first`, read `second`, wrote `second`, and wrote `first` again.
    private App start(final Path first, final Path second) throws Exception {
        final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(),
                home.resolve("projects"), false), "deliverables", () -> {
        });
        try {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null
                    && onEdt(() -> frame() != null));
            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh(SESSION, home.toString())));
            loop.dispatch(new Intent.BindSession(loop.state().focusedPanel().id(), SESSION));
            Awaitility.await("the session").atMost(WAIT).until(() -> loop.state().session(SESSION).isPresent()
                    && loop.state().focusedPanel().session().equals(SESSION));
            onEdt(() -> {
                final Transcript t = app.transcripts().of(SESSION);
                t.append(write("toolu_1", first));
                t.append(new Block.ToolCall("toolu_2", "Read", input(second), "", Block.ToolStatus.DONE, ""));
                t.append(write("toolu_3", second));
                t.append(write("toolu_4", first));
                t.changed();
                return null;
            });
        } catch (final Exception | AssertionError e) {
            // the caller's try-with-resources holds the window only once it is returned
            app.close();
            throw e;
        }
        return app;
    }

    private interface EdtCall<T> {
        T call() throws Exception;
    }
}
