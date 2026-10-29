package dev.marregui.comando.app;

import dev.marregui.comando.claude.KitJson;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.KitRoot;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;
import dev.marregui.comando.ui.kit.tree.TreeView;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/// A tree that opens a folder on one click, as the Files tab's, and the Files tab of a window started on a kit's root.
/// The look opens and closes folders on a press, which needs a display, so this runs in `./gradlew :app:uiTest` (on
/// Linux under `xvfb-run`).
/// - the first click opens or closes a folder, and a double click puts it back as it was, once
/// - other trees keep the look's double click
/// - a window started on a kit's root lists the kit's folders first and names the kit in the status bar
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class TreeClicksUiTest {

    private static ViewSpec.TreeNode node(String label, ViewSpec.TreeNode... children) {
        return new ViewSpec.TreeNode(label, "", List.of(children));
    }

    /// One click opens a folder; the second click of a double click leaves it, and the double click puts it back as before,
    /// once only; two single clicks open and close it; a tree without one-click folders keeps a toggle click count of 2.
    @Test
    void theFirstClickOpensOrClosesAFolderAndADoubleClickCanPutItBack() throws Exception {
        TreeView view = new TreeView();
        JTree tree = view.tree();
        view.toggleOnClick(true);
        SwingUtilities.invokeAndWait(() -> {
            view.setRoot(node("root", node("a/", node("a1")), node("notes.md")), 0);
            tree.expandRow(0);
            tree.setSize(300, 200);
            tree.doLayout();
        });
        TreePath a = tree.getPathForRow(1);
        SwingUtilities.invokeAndWait(() -> press(tree, 1, 1));
        assertThat(tree.isExpanded(a)).as("one click opens a folder").isTrue();
        SwingUtilities.invokeAndWait(() -> press(tree, 1, 2));
        assertThat(tree.isExpanded(a)).as("the second click of a double click leaves it").isTrue();
        SwingUtilities.invokeAndWait(() -> view.undoClickToggle(a));
        assertThat(tree.isExpanded(a)).as("put back as it was before the double click").isFalse();
        SwingUtilities.invokeAndWait(() -> view.undoClickToggle(a));
        assertThat(tree.isExpanded(a)).as("put back once only").isFalse();
        SwingUtilities.invokeAndWait(() -> {
            press(tree, 1, 1);
            press(tree, 1, 1);
        });
        assertThat(tree.isExpanded(a)).as("two single clicks open and close it").isFalse();

        TreeView plain = new TreeView();
        assertThat(plain.tree().getToggleClickCount()).as("other trees keep the look's double click").isEqualTo(2);
    }

    /// comando started on a temporary folder named as `CLAUDE_CONFIG_DIR` would name it puts the kit's Memory, KB and Skills
    /// folders, with the kit's groups, before the user's own, saves them, shows the Brain group as one BRAIN row (record
    /// 118), and the status bar has no kit label (record 119); no `claude` runs.
    @Test
    void theKitsFoldersOpenTheFilesTabOfAWindowStartedOnItsRoot(@TempDir Path home) throws Exception {
        Path root = home.resolve("comando-kit");
        for (String dir : List.of("BRAIN/MEMORY", "BRAIN/KB", "skills/reflect")) {
            Files.createDirectories(root.resolve(dir));
        }
        Files.writeString(root.resolve("BRAIN/MEMORY/MEMORY.md"), "# Memory\n");
        Files.writeString(root.resolve(KitRoot.FILE), """
                {"kit": 1, "name": "test-kit", "brain": "BRAIN",
                 "folders": [
                   {"name": "Memory", "path": "BRAIN/MEMORY", "index": "MEMORY.md", "icon": "memory", "group": "Brain"},
                   {"name": "KB", "path": "BRAIN/KB", "icon": "knowledge", "group": "Brain"},
                   {"name": "Skills", "path": "skills", "icon": "skills"}],
                 "requirements": [], "credentials": [], "jobs": []}
                """);
        Path data = Files.createDirectories(home.resolve("data"));
        Path notes = Files.createDirectories(home.resolve("notes"));
        Files.writeString(data.resolve("folders.json"), "[{\"name\": \"Notes\", \"path\": \"" + notes + "\"}]");
        KitRoot kitRoot = KitRoot.resolve(root.toString(), home, KitJson::read);
        assertThat(kitRoot.kit()).isPresent();

        App app = new App(new AppConfig(data, home.resolve("no-claude").toString(), kitRoot, false), "kit", () -> {
        });
        try {
            app.start();
            StateLoop loop = app.loop();
            assertThat(loop.state().folders()).extracting(FolderRoot::name).as("the kit's first, the user's kept")
                    .containsExactly("Memory", "KB", "Skills", "Notes");
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> front("Files"));
            Awaitility.await("the Files tab's rows").atMost(Duration.ofSeconds(10)).until(() -> rows().size() >= 3);
            assertThat(rows()).as("in the Files tab: the Brain group one row; then, no Kit folder here, the jobs' row of its own")
                    .startsWith("BRAIN", "Skills", "Notes").doesNotContain("Memory", "KB");
            assertThat(loop.state().folders()).extracting(FolderRoot::group).as("the kit's groups (record 98)")
                    .containsExactly("Brain", "Brain", "", "");
            paintSidebar();
            Awaitility.await("folders.json saved").atMost(Duration.ofSeconds(10))
                    .until(() -> Files.readString(data.resolve("folders.json")).contains(root.resolve("BRAIN/MEMORY").toString()));
            assertThat(onEdt(() -> first(JLabel.class, l -> "Kit".equals(l.getAccessibleContext().getAccessibleName()))))
                    .as("the kit is no longer in the status bar (record 119)").isNull();
        } finally {
            app.close();
        }
    }

    /**
     * The sidebar as it shows, the Files tab in front with its second row selected, painted in three tints side by side to
     * {@code build/snapshots/sidebar.png} (records 98, 118): the BRAIN row, hairlines between groups, the selection in the tint.
     */
    private static void paintSidebar() throws Exception {
        Tint[] tints = {Tint.TREASURE, Tint.SILVER, Tint.ELECTRIC_BLUE};
        BufferedImage sheet = onEdt(() -> {
            JComponent sidebar = first(JComponent.class, c -> c.getAccessibleContext() != null && "Sidebar".equals(c.getAccessibleContext().getAccessibleName()) && c.isShowing());
            JTree tree = first(JTree.class, t -> "Files".equals(t.getAccessibleContext().getAccessibleName()) && t.isShowing());
            tree.setSelectionRow(1);
            int w = sidebar.getWidth();
            int h = Math.min(sidebar.getHeight(), 420);
            BufferedImage out = new BufferedImage(w * tints.length, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            for (int i = 0; i < tints.length; i++) {
                Theme.tint(tints[i]);
                Graphics2D one = (Graphics2D) g.create(i * w, 0, w, h);
                sidebar.paint(one);
                one.dispose();
            }
            g.dispose();
            Theme.tint(Tint.TREASURE);
            return out;
        });
        assertThat(sheet.getRGB(sheet.getWidth() / 6, sheet.getHeight() - 60) & 0xFFFFFF).as("the transcript's ground")
                .isEqualTo(Theme.ground().getRGB() & 0xFFFFFF);
        Path out = Path.of("build/snapshots/sidebar.png");
        Files.createDirectories(out.getParent());
        ImageIO.write(sheet, "png", out.toFile());
    }

    /** The Files tab's folder rows, top first. */
    private static List<String> rows() throws Exception {
        return onEdt(() -> {
            JTree tree = first(JTree.class, t -> "Files".equals(t.getAccessibleContext().getAccessibleName()) && t.isShowing());
            List<String> out = new ArrayList<>();
            if (tree != null) {
                for (int i = 0; i < tree.getRowCount(); i++) {
                    if (tree.getPathForRow(i).getPathCount() == 2) {
                        out.add(((ViewSpec.TreeNode) ((DefaultMutableTreeNode) tree.getPathForRow(i).getLastPathComponent()).getUserObject()).label());
                    }
                }
            }
            return out;
        });
    }

    /** Brings sidebar tab {@code tab} to the front; false while the window is not up. */
    private static boolean front(String tab) throws Exception {
        return onEdt(() -> {
            JToggleButton header = first(JToggleButton.class, b -> tab.equals(b.getAccessibleContext().getAccessibleName()) && b.isShowing());
            if (header != null) {
                header.doClick();
            }
            return header != null;
        });
    }

    private static <T extends Component> T first(Class<T> type, Predicate<T> which) {
        List<T> out = new ArrayList<>();
        for (Frame f : Frame.getFrames()) {
            if (f.isVisible()) {
                collect(f, type, which, out);
            }
        }
        return out.isEmpty() ? null : out.getFirst();
    }

    private static <T extends Component> void collect(Component c, Class<T> type, Predicate<T> which, List<T> out) {
        if (type.isInstance(c) && which.test(type.cast(c))) {
            out.add(type.cast(c));
        }
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                collect(child, type, which, out);
            }
        }
    }

    private static <T> T onEdt(Callable<T> call) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Exception> failed = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                out.set(call.call());
            } catch (Exception e) {
                failed.set(e);
            }
        });
        if (failed.get() != null) {
            throw failed.get();
        }
        return out.get();
    }

    /** A left press and release of {@code count} on row {@code row}. */
    private static void press(JTree tree, int row, int count) {
        Rectangle r = tree.getRowBounds(row);
        int x = r.x + r.width / 2;
        int y = r.y + r.height / 2;
        tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK, x, y, count, false, MouseEvent.BUTTON1));
        tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, x, y, count, false, MouseEvent.BUTTON1));
    }
}
