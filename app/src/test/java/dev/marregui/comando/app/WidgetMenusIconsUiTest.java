package dev.marregui.comando.app;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.marregui.comando.core.console.ConsoleRegistry;
import dev.marregui.comando.core.model.KitJob;
import dev.marregui.comando.core.model.KitJobs;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.model.SearchHit;
import dev.marregui.comando.core.model.SearchQuery;
import dev.marregui.comando.core.port.SearchPort;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.dialogs.jobs.JobsEditor;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.HitMarks;
import dev.marregui.comando.ui.kit.text.Previews;
import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;
import dev.marregui.comando.ui.kit.vcs.RepoChanges;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widgets.changes.ChangesWidget;
import dev.marregui.comando.ui.widgets.console.ConsoleWidget;
import dev.marregui.comando.ui.widgets.search.SearchHub;
import dev.marregui.comando.ui.widgets.search.SearchWidget;
import dev.marregui.comando.ui.widgets.tree.TreeWidget;

import static org.assertj.core.api.Assertions.assertThat;

/// The right-click menus of the widgets and of the jobs editor, each opened by a right-button press on a row of the
/// real component in a window off screen, under one dark and one light theme. Opens a window, so this runs in
/// `./gradlew :app:uiTest`; the menus are laid out and painted without a popup on the desktop.
/// - every item of the tree view's, the console's, the Changes view's, the Search view's and the jobs editor's menus
///   carries an icon that paints
/// - each of those menus paints from the theme chosen: dark on a dark theme, light on a light one
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class WidgetMenusIconsUiTest {

    /// Each step's wait, as the other window tests have it.
    private static final Duration WAIT = Duration.ofSeconds(10);
    /// A mean of the three channels above this is a light colour, as `DiffThemeUiTest` reads it.
    private static final int MIDDLE = 128;
    private static final String WITH_ICON = " with its icon";

    @TempDir
    Path home;

    private PopupFactory popups;
    private JFrame frame;

    /// The look and feel, set up once before any component exists, as the application does at its start.
    @BeforeAll
    static void lookAndFeel() throws Exception {
        onEdt(() -> {
            Theme.install();
            return null;
        });
    }

    /// Menus are laid out but never shown, so no popup reaches the desktop; the window the widgets sit in is left of
    /// every screen, as the application's window is under `comando.offScreen`.
    @BeforeEach
    void offScreen() throws Exception {
        popups = PopupFactory.getSharedInstance();
        PopupFactory.setSharedInstance(new PopupFactory() {
            @Override
            public Popup getPopup(final Component owner, final Component contents, final int x, final int y) {
                return new Popup() {
                    @Override
                    public void hide() {
                        // nothing was put on the desktop, so there is no window to take down
                    }
                };
            }
        });
        frame = onEdt(() -> {
            Rectangle screens = new Rectangle();
            for (final GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                screens = screens.union(d.getDefaultConfiguration().getBounds());
            }
            final JFrame f = new JFrame("widget menus");
            f.setUndecorated(true);
            f.setAutoRequestFocus(false);
            f.setFocusableWindowState(false);
            f.setBounds(screens.x - 1_800, screens.y, 800, 600);
            f.setVisible(true);
            return f;
        });
    }

    /// The popups, the window and the theme as the next test expects them.
    @AfterEach
    void asBefore() throws Exception {
        PopupFactory.setSharedInstance(popups);
        onEdt(() -> {
            MenuSelectionManager.defaultManager().clearSelectedPath();
            frame.dispose();
            Theme.choose(Ground.GRAPHITE, Tint.TREASURE);
            return null;
        });
    }

    /// Under the theme given, each menu opened by a right click on a row lists its items, every one with an icon that
    /// paints at least one pixel, and the menu painted is light exactly when the theme is.
    @ParameterizedTest(name = "{0} and {1}")
    @CsvSource({"GRAPHITE, TREASURE, false", "PAPER, OCHRE, true"})
    void everyItemOfEachWidgetMenuCarriesAnIconAndTheMenuPaintsFromTheTheme(final Ground ground, final Tint tint,
                                                                            final boolean light) throws Exception {
        onEdt(() -> {
            Theme.choose(ground, tint);
            return null;
        });
        assertThat(onEdt(Theme::light)).as("the theme chosen is the one asked").isEqualTo(light);
        final Map<String, List<String>> menus = new LinkedHashMap<>();
        final Map<String, Boolean> lightMenus = new LinkedHashMap<>();

        final String notes = ViewSpec.TreeNode.fileLink(home.resolve("notes.md").toString(), 1);
        final TreeWidget tree = onEdt(() -> {
            final TreeWidget w = new TreeWidget(context());
            w.render(new ViewSpec.Tree(node("results",
                    new ViewSpec.TreeNode("notes.md", "", notes, List.of()),
                    new ViewSpec.TreeNode("SELECT 1", "", "comando-insert:SELECT 1", List.of()))));
            return w;
        });
        open("a file's node", tree.component(), "notes.md", menus, lightMenus);
        open("a text's node", tree.component(), "SELECT 1", menus, lightMenus);

        final ConsoleWidget console = onEdt(() -> {
            final ConsoleWidget w = new ConsoleWidget(context(), new ConsoleRegistry());
            w.render(new ViewSpec.Console(1, "gradle test", ViewSpec.RunState.FAILED, 1, 1_200,
                    node("tests", node("FooTest", node("✗ bar"))), Map.of("FooTest.bar", "expected 1"), ""));
            return w;
        });
        open("a failed test", console.component(), "✗ bar", menus, lightMenus);

        final RepoChanges repos = new RepoChanges();
        final ChangesWidget changes = onEdt(() -> {
            final ChangesWidget w = new ChangesWidget(context(), repos, file -> { });
            repos.set(List.of(new RepoStatus(home.toString(), "main",
                    List.of(new RepoStatus.FileChange("notes.md", "", RepoStatus.Kind.MODIFIED)), false, "", "")));
            return w;
        });
        open("a changed file", changes.component(), "notes.md", menus, lightMenus);

        final Previews previews = new Previews() {
            @Override
            public void preview(final Path file, final HitMarks marks, final boolean takeKeyboard) {
                // nothing is previewed: the menu is what is looked at
            }

            @Override
            public void pin(final Path file) {
                // nothing is pinned
            }
        };
        final SearchHub.Opener opener = new SearchHub.Opener() {
            @Override
            public void open(final SearchHit hit, final String query) {
                // nothing is opened
            }

            @Override
            public boolean inSession(final String conversation) {
                return false;
            }

            @Override
            public void showInFiles(final SearchHit hit) {
                // nothing is shown
            }
        };
        final SearchWidget search = onEdt(() -> new SearchWidget(new SearchHub(List::of, () -> new OneHit(home),
                previews, opener)));
        onEdt(() -> {
            find(search.component(), JTextField.class, f -> true).setText("notes");
            return null;
        });
        open("a found line", search.component(), OneHit.SNIPPET, menus, lightMenus);

        final JobsEditor jobs = onEdt(() -> new JobsEditor(List.of(job(home)), home, new JobsEditor.Actions() {
            @Override
            public Optional<Path> program(final Component owner, final Path tools) {
                return Optional.empty();
            }

            @Override
            public void run(final String name) {
                // nothing runs
            }

            @Override
            public void openLog(final Path log) {
                // nothing is opened
            }
        }));
        openJob(jobs.component(), menus, lightMenus);

        assertThat(menus).containsExactly(
                Map.entry("a file's node", List.of("Open" + WITH_ICON)),
                Map.entry("a text's node", List.of("Insert into the editor" + WITH_ICON)),
                Map.entry("a failed test", List.of("Send this failure to Claude" + WITH_ICON)),
                Map.entry("a changed file", List.of("Compare with HEAD" + WITH_ICON, "Open in a panel" + WITH_ICON,
                        "Roll back notes.md to HEAD…" + WITH_ICON, "Move notes.md to the Trash…" + WITH_ICON)),
                Map.entry("a found line", List.of("Open" + WITH_ICON, "Show in the sidebar" + WITH_ICON)),
                Map.entry("a job", List.of("Run now" + WITH_ICON, "Open the log" + WITH_ICON, "Remove" + WITH_ICON)));
        assertThat(lightMenus).as("each menu painted light exactly when the theme is").allSatisfy((menu, bright)
                -> assertThat(bright).as(menu).isEqualTo(light));
    }

    private static WidgetContext context() {
        return new WidgetContext(intent -> { }, new ActionRegistry());
    }

    private static ViewSpec.TreeNode node(final String label, final ViewSpec.TreeNode... children) {
        return new ViewSpec.TreeNode(label, "", List.of(children));
    }

    private static KitJobs.Entry job(final Path home) {
        final KitJob brief = new KitJob("daily-brief", "Daily brief", List.of("MON"), "10:15",
                List.of("bin/daily-brief"), Map.of(), "", true);
        return new KitJobs.Entry(brief, true, true, true, false, "2026-11-10T10:15+01:00", 0, "2026-11-17T10:15+01:00",
                home.resolve("daily-brief.log").toString());
    }

    /// A right-button press in the middle of `at` on `target`, then the menu it opened: each item's text, said with
    /// its icon when the icon paints, and whether the menu painted is light.
    private static void record(
            final String name,
            final Component target,
            final Rectangle at,
            final Map<String, List<String>> menus,
            final Map<String, Boolean> lightMenus
    ) throws Exception {
        onEdt(() -> {
            target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                    InputEvent.BUTTON3_DOWN_MASK, at.x + at.width / 2, at.y + at.height / 2, 1, true,
                    MouseEvent.BUTTON3));
            return null;
        });
        final AtomicReference<JPopupMenu> menu = new AtomicReference<>();
        Awaitility.await("the menu of " + name).atMost(WAIT).until(() -> {
            menu.set(onEdt(WidgetMenusIconsUiTest::opened));
            return menu.get() != null;
        });
        onEdt(() -> {
            final JPopupMenu m = menu.get();
            final List<String> items = new ArrayList<>();
            for (final Component c : m.getComponents()) {
                if (c instanceof JMenuItem i) {
                    items.add(i.getText() + (paints(i.getIcon()) ? WITH_ICON : " without an icon"));
                }
            }
            menus.put(name, items);
            lightMenus.put(name, paintedLight(m));
            m.setVisible(false);
            MenuSelectionManager.defaultManager().clearSelectedPath();
            return null;
        });
    }

    /// The popup menu open now, if any.
    private static JPopupMenu opened() {
        for (final MenuElement e : MenuSelectionManager.defaultManager().getSelectedPath()) {
            if (e instanceof JPopupMenu m && m.isVisible()) {
                return m;
            }
        }
        return null;
    }

    /// The bounds of the row of `tree` labelled `label`, every row opened first; null while no row is.
    private static Rectangle rowBounds(final JTree tree, final String label) {
        for (int r = 0; r < tree.getRowCount(); r++) {
            tree.expandRow(r);
        }
        for (int r = 0; r < tree.getRowCount(); r++) {
            final TreePath p = tree.getPathForRow(r);
            if (((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof ViewSpec.TreeNode n
                    && n.label().equals(label)) {
                return tree.getPathBounds(p);
            }
        }
        return null;
    }

    /// Whether `icon` paints a pixel that is not transparent and is not what a name with no SVG behind it paints: the
    /// SVG library's stand-in for a file it did not find.
    private static boolean paints(final Icon icon) {
        if (icon == null) {
            return false;
        }
        final int[] pixels = pixels(icon);
        return Arrays.stream(pixels).anyMatch(p -> p >>> 24 != 0)
                && !Arrays.equals(pixels, pixels(Icons.get("no icon has this name")));
    }

    private static int[] pixels(final Icon icon) {
        final BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(),
                BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = image.createGraphics();
        try {
            icon.paintIcon(null, g, 0, 0);
        } finally {
            g.dispose();
        }
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    /// `menu` laid out at its preferred size and painted: whether the mean of its pixels is a light colour.
    private static boolean paintedLight(final JPopupMenu menu) {
        menu.setSize(menu.getPreferredSize());
        menu.doLayout();
        final BufferedImage image = new BufferedImage(menu.getWidth(), menu.getHeight(), BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();
        try {
            menu.printAll(g);
        } finally {
            g.dispose();
        }
        long sum = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                final Color c = new Color(image.getRGB(x, y));
                sum += c.getRed() + c.getGreen() + c.getBlue();
            }
        }
        return sum / (3L * image.getWidth() * image.getHeight()) > MIDDLE;
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

    /// Puts `component` in the window, right-clicks the row labelled `label` of the tree in it once that row shows,
    /// and records the menu that opens under `name`.
    private void open(
            final String name,
            final JComponent component,
            final String label,
            final Map<String, List<String>> menus,
            final Map<String, Boolean> lightMenus
    ) throws Exception {
        final JTree tree = onEdt(() -> {
            show(component);
            return find(component, JTree.class, t -> true);
        });
        final AtomicReference<Rectangle> row = new AtomicReference<>();
        Awaitility.await("the row " + label).atMost(WAIT).until(() -> {
            row.set(onEdt(() -> rowBounds(tree, label)));
            return row.get() != null;
        });
        record(name, tree, row.get(), menus, lightMenus);
    }

    /// Puts the jobs editor in the window, right-clicks its first job's row and records the menu under "a job".
    private void openJob(
            final JComponent component,
            final Map<String, List<String>> menus,
            final Map<String, Boolean> lightMenus
    ) throws Exception {
        final JList<?> list = onEdt(() -> {
            show(component);
            return find(component, JList.class, l -> l.getModel().getSize() > 0);
        });
        record("a job", list, onEdt(() -> list.getCellBounds(0, 0)), menus, lightMenus);
    }

    private void show(final JComponent component) {
        frame.getContentPane().removeAll();
        frame.getContentPane().add(component);
        frame.validate();
    }

    /// A searcher that finds one line of one file for any text, at once.
    private static final class OneHit implements SearchPort {

        static final String SNIPPET = "the notes to keep";

        private final Path home;

        OneHit(final Path home) {
            this.home = home;
        }

        @Override
        public void search(final SearchQuery query, final Sink sink) {
            final SearchHit hit = new SearchHit(SearchHit.Kind.FILE, home.resolve("notes.md").toString(), "", "", 3,
                    SNIPPET, 4, 5, 0L, "", 0, "", "");
            sink.hits(List.of(hit.placed(home.toString(), "")));
            sink.done(false, 0);
        }

        @Override
        public void cancel() {
            // nothing runs
        }
    }
}
