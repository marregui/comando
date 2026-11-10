package dev.marregui.comando.app;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicSplitPaneUI;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.desktop.Platform;
import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;
import dev.marregui.comando.ui.panels.EditorPanel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/// The workspace's right-hand partition in the real window: panels and views moved there and back by their tabs'
/// right-click menus, the keys across the two strips, its look in a theme chosen while it was hidden, and what of it
/// comes back after a restart. Opens the window, so this runs in `./gradlew :app:uiTest`; the window is off screen, and
/// the tabs' menus are built and clicked without a popup on the desktop.
/// - a panel and the Changes view go to the right side by their tab's menu, and back
/// - a file moved to the right side leaves the lower panel shown, at the width a side view opens at
/// - ⌘1 to ⌘3 go to the main tabs' panels, then the right side's
/// - ⌘W closes the side panel that has the keyboard; ⌘⇧T brings it back to the right side
/// - shown after a change to a light theme made while it was hidden, the partition's tabs are in the light theme
/// - a side panel, and the Search and Changes views on the right side, are there again after a restart
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class PartitionUiTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    /// A dark start, whatever theme an earlier test of the run chose.
    private static final String DARK_SETTINGS =
            "{\"version\": 1, \"theme.ground\": \"GRAPHITE\", \"theme.tint\": \"TREASURE\"}";
    /// The least brightness, of 255, a light ground has; the dark grounds are below 80.
    private static final int LIGHT = 160;

    @TempDir
    Path home;

    private PopupFactory popups;

    /// Tab menus are laid out but never shown, so no popup reaches the desktop.
    @BeforeEach
    void noPopupOnTheDesktop() {
        popups = PopupFactory.getSharedInstance();
        PopupFactory.setSharedInstance(new PopupFactory() {
            @Override
            public Popup getPopup(final Component owner, final Component contents, final int x, final int y) {
                return new Popup() {
                };
            }
        });
    }

    /// The popups and the theme as the next test expects them.
    @AfterEach
    void popupsAndThemeAsBefore() throws Exception {
        PopupFactory.setSharedInstance(popups);
        onEdt(() -> {
            MenuSelectionManager.defaultManager().clearSelectedPath();
            Theme.choose(Ground.GRAPHITE, Tint.TREASURE);
            return null;
        });
    }

    /// A second panel goes to the right side by "Move to the right side" on its tab and shows there, the partition with
    /// it; "Move to the main tabs" on its side tab brings it back. The Changes view, opened in the lower panel, goes to
    /// the right side by "Move to the right side" on its tab and back by "Move to the lower panel".
    @Test
    void aPanelAndTheChangesViewMoveToTheRightSideByTheirTabsMenuAndBack() throws Exception {
        try (final App app = start("partition-move")) {
            final PanelId notes = addPanel(app, "notes");
            final JTabbedPane main = strip("workspace");
            final JTabbedPane side = strip("side");

            rightClickTab(main, onEdt(() -> indexOf(main, notes)));
            clickItem("Move to the right side");
            until("on the right side", () -> panel(app, notes).side()
                    && onEdt(() -> indexOf(side, notes) >= 0 && side.isShowing()));
            assertThat(onEdt(() -> indexOf(main, notes))).as("no longer in the main tabs").isNegative();

            rightClickTab(side, onEdt(() -> indexOf(side, notes)));
            clickItem("Move to the main tabs");
            until("back in the main tabs", () -> !panel(app, notes).side() && onEdt(() -> indexOf(main, notes) >= 0));

            showChanges(app, Placement.LOWER);
            until("the Changes view below", () -> changes(app).isPresent());
            final long id = changes(app).orElseThrow().id();
            final JTabbedPane lower = strip("lower");
            until("its tab below", () -> onEdt(() -> titled(lower, ViewSpec.Changes.TITLE)) >= 0);
            rightClickTab(lower, onEdt(() -> titled(lower, ViewSpec.Changes.TITLE)));
            clickItem("Move to the right side");
            until("the Changes view on the right side", () -> placement(app, id) == Placement.SIDE
                    && onEdt(() -> titled(side, ViewSpec.Changes.TITLE) >= 0 && side.isShowing()));

            rightClickTab(side, onEdt(() -> titled(side, ViewSpec.Changes.TITLE)));
            clickItem("Move to the lower panel");
            until("the Changes view below again", () -> placement(app, id) == Placement.LOWER
                    && onEdt(() -> titled(lower, ViewSpec.Changes.TITLE) >= 0));
        }
    }

    /// A file opened to read, in front of the main tabs, hides the lower panel; moved to the right side it sits beside
    /// the lower panel, which shows again for the main tabs' panel, and the side opens at the width the Changes view
    /// opens at on the right side of a window started the same way.
    @Test
    void aFileMovedToTheRightSideLeavesTheLowerPanelAndOpensAtASideViewsWidth() throws Exception {
        final int viewWidth;
        try (final App first = start("partition-width-view", "data-view")) {
            showChanges(first, Placement.SIDE);
            final JTabbedPane side = strip("side");
            until("the Changes view on the right side", () -> onEdt(side::isShowing));
            viewWidth = onEdt(() -> sideWidth(side));
        }
        try (final App app = start("partition-width-file", "data-file")) {
            final PanelId file = panelId("readme");
            app.loop().dispatch(new Intent.AddPanel(file, "README.md", true));
            final JTabbedPane main = strip("workspace");
            final JTabbedPane lower = strip("lower");
            until("the file in front, the lower panel hidden", () -> onEdt(() -> indexOf(main, file) >= 0
                    && main.getSelectedIndex() == indexOf(main, file) && !lower.isShowing()));

            rightClickTab(main, onEdt(() -> indexOf(main, file)));
            clickItem("Move to the right side");
            final JTabbedPane side = strip("side");
            until("on the right side, the lower panel shown", () -> onEdt(() -> indexOf(side, file) >= 0
                    && side.isShowing() && lower.isShowing()));
            assertThat(app.loop().state().focused()).as("the file keeps the keyboard").isEqualTo(file);
            assertThat(onEdt(() -> sideWidth(side))).as("a side view's width").isCloseTo(viewWidth, within(2));
        }
    }

    /// Three panels, the third on the right side: ⌘3 focuses the side panel, ⌘1 the first main tab, ⌘2 the
    /// second; ⌘W with the side panel focused closes it there, and ⌘⇧T reopens it on the right side.
    @Test
    void theKeysGoAcrossBothStripsAndCloseAndReopenOnTheRightSide() throws Exception {
        try (final App app = start("partition-keys")) {
            final PanelId first = app.loop().state().focused();
            final PanelId second = addPanel(app, "second");
            final PanelId third = addPanel(app, "third");
            app.loop().dispatch(new Intent.PlacePanel(third, true));
            final JTabbedPane side = strip("side");
            until("the third on the right side", () -> onEdt(() -> indexOf(side, third) >= 0));
            final int shortcut = Platform.menuShortcutMask();

            press(KeyEvent.VK_3, shortcut);
            until("⌘3: the side panel", () -> third.equals(app.loop().state().focused()));
            press(KeyEvent.VK_1, shortcut);
            until("⌘1: the first main tab", () -> first.equals(app.loop().state().focused()));
            press(KeyEvent.VK_2, shortcut);
            until("⌘2: the second main tab", () -> second.equals(app.loop().state().focused()));

            press(KeyEvent.VK_3, shortcut);
            until("the side panel again", () -> third.equals(app.loop().state().focused()));
            press(KeyEvent.VK_W, shortcut);
            until("⌘W: closed on the right side", () -> app.loop().state().panel(third).isEmpty()
                    && onEdt(() -> indexOf(side, third) < 0));
            assertThat(app.loop().state().panel(first)).as("the main tabs keep theirs").isPresent();
            assertThat(app.loop().state().panel(second)).isPresent();

            press(KeyEvent.VK_T, shortcut | InputEvent.SHIFT_DOWN_MASK);
            until("⌘⇧T: back on the right side", () -> app.loop().state().panel(third).map(PanelState::side)
                    .orElse(false) && onEdt(() -> indexOf(side, third) >= 0));
        }
    }

    /// The window starts dark with nothing on the right side; the light theme is chosen while the partition is hidden;
    /// a panel moved there then shows it with its tab area in the light ground the main tabs have, and the divider
    /// before it in the colour of the sidebar's divider.
    @Test
    void thePartitionShownAfterALightThemeChosenWhileHiddenIsLight() throws Exception {
        try (final App app = start("partition-theme")) {
            final JTabbedPane main = strip("workspace");
            assertThat(onEdt(() -> find(frame(), JTabbedPane.class, t -> "side".equals(t.getName()) && t.isShowing())))
                    .as("hidden: nothing on the right side").isNull();
            assertThat(onEdt(Theme::light)).as("started dark").isFalse();

            onEdt(() -> {
                Theme.choose(Ground.FOG, Tint.SLATE_INK);
                return null;
            });
            final PanelId notes = addPanel(app, "notes");
            app.loop().dispatch(new Intent.PlacePanel(notes, true));
            final JTabbedPane side = strip("side");
            until("the partition shows", () -> onEdt(side::isShowing));

            final Color tabs = onEdt(side::getBackground);
            assertThat(brightness(tabs)).as("the partition's tab area is light [colour=" + tabs + ']')
                    .isGreaterThan(LIGHT);
            assertThat(tabs.getRGB()).as("the main tabs' ground").isEqualTo(onEdt(main::getBackground).getRGB());
            final Color divider = onEdt(() -> divider(
                    (JSplitPane) SwingUtilities.getAncestorOfClass(JSplitPane.class, side)));
            final Color sidebars = onEdt(() -> divider(find(frame(), JSplitPane.class,
                    p -> p.getOrientation() == JSplitPane.HORIZONTAL_SPLIT
                            && SwingUtilities.getAncestorOfClass(JSplitPane.class, p) == null)));
            assertThat(divider.getRGB()).as("the partition's divider in the theme's [colour=" + divider + ']')
                    .isEqualTo(sidebars.getRGB());
        }
    }

    /// A panel on the right side, and the Search and the Changes views there, are kept: after the window closes and a
    /// new one starts on the same data root, the panel is on the right side again and both views are there.
    @Test
    void aSidePanelAndTheSideViewsComeBackAfterARestart() throws Exception {
        final Path settings = home.resolve("data").resolve("settings.json");
        final PanelId notes = panelId("notes");
        try (final App app = start("partition-restart")) {
            addPanel(app, "notes");
            app.loop().dispatch(new Intent.PlacePanel(notes, true));
            app.loop().dispatch(new Intent.ShowView("", ViewSpec.Search.TITLE, new ViewSpec.Search(), Placement.SIDE));
            showChanges(app, Placement.SIDE);
            final JTabbedPane side = strip("side");
            until("the panel and both views on the right side", () -> onEdt(() -> indexOf(side, notes) >= 0
                    && titled(side, ViewSpec.Search.TITLE) >= 0 && titled(side, ViewSpec.Changes.TITLE) >= 0));
        }
        final String kept = JsonFile.JSON.readTree(settings.toFile()).path("sidePane.views").asString("");
        assertThat(Arrays.stream(kept.split(",")).map(String::strip)).as("the side views saved by the close")
                .containsExactlyInAnyOrder("search", "changes");
        try (final App again = start("partition-restart-again")) {
            until("the panel on the right side again", () -> again.loop().state().panel(notes).map(PanelState::side)
                    .orElse(false));
            final JTabbedPane side = strip("side");
            until("in the partition, with both views", () -> onEdt(() -> indexOf(side, notes) >= 0
                    && titled(side, ViewSpec.Search.TITLE) >= 0 && titled(side, ViewSpec.Changes.TITLE) >= 0
                    && side.isShowing()));
            assertThat(again.loop().state().views()).filteredOn(v -> v.placement() == Placement.SIDE)
                    .extracting(v -> v.spec().type()).containsExactlyInAnyOrder("search", "changes");
        }
    }

    // ---- the window

    private App start(final String name) throws Exception {
        return start(name, "data");
    }

    /// A window started on the data folder `folder` of the test's home.
    private App start(final String name, final String folder) throws Exception {
        final Path data = Files.createDirectories(home.resolve(folder));
        if (!Files.exists(data.resolve("settings.json"))) {
            Files.writeString(data.resolve("settings.json"), DARK_SETTINGS);
        }
        final AppConfig config = new AppConfig(data, home.resolve("no-claude").toString(), home.resolve("projects"),
                false);
        final App app = new App(config, name, () -> {
        });
        try {
            app.start();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20))
                    .until(() -> app.loop().state().focused() != null && onEdt(() -> frame() != null));
        } catch (final Exception | AssertionError e) {
            // the caller's try-with-resources holds the window only once it is returned
            app.close();
            throw e;
        }
        return app;
    }

    private static PanelId panelId(final String title) {
        return new PanelId("partition-" + title);
    }

    /// A panel titled `title`, added to the main tabs and shown there.
    private static PanelId addPanel(final App app, final String title) throws Exception {
        final PanelId id = panelId(title);
        app.loop().dispatch(new Intent.AddPanel(id, title));
        final JTabbedPane main = strip("workspace");
        Awaitility.await("panel " + title).atMost(WAIT).until(() -> onEdt(() -> indexOf(main, id) >= 0));
        return id;
    }

    private static void until(final String what, final Callable<Boolean> condition) {
        Awaitility.await(what).atMost(WAIT).until(condition);
    }

    private static void showChanges(final App app, final Placement placement) {
        app.loop().dispatch(new Intent.ShowView("", ViewSpec.Changes.TITLE, new ViewSpec.Changes(), placement));
    }

    private static PanelState panel(final App app, final PanelId id) {
        return app.loop().state().panel(id).orElseThrow();
    }

    private static Optional<ViewState> changes(final App app) {
        return app.loop().state().views().stream().filter(v -> v.spec() instanceof ViewSpec.Changes).findFirst();
    }

    private static Placement placement(final App app, final long id) {
        return app.loop().state().view(id).map(ViewState::placement).orElse(null);
    }

    /// The key pressed and released in the component that has the window's keyboard, else in the main tabs: comando
    /// routes keys by the component they are pressed in, so nothing here needs the desktop's focus.
    private static void press(final int code, final int modifiers) throws Exception {
        final JTabbedPane main = strip("workspace");
        onEdt(() -> {
            final Component owner = frame().getMostRecentFocusOwner();
            final Component target = owner != null ? owner : main;
            final long now = System.currentTimeMillis();
            for (final int id : new int[] {KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED}) {
                target.dispatchEvent(new KeyEvent(target, id, now, modifiers, code, KeyEvent.CHAR_UNDEFINED));
            }
            return null;
        });
    }

    /// A right-button press on tab `index` of `strip`: a panel's tab takes it on the strip, a view's on its header.
    private static void rightClickTab(final JTabbedPane strip, final int index) throws Exception {
        assertThat(index).as("the tab is there").isNotNegative();
        onEdt(() -> {
            final Component header = strip.getTabComponentAt(index);
            final boolean onStrip = strip.getComponentAt(index) instanceof EditorPanel || header == null;
            final Component target = onStrip ? strip : header;
            final Rectangle tab = strip.getBoundsAt(index);
            final int x = onStrip ? tab.x + tab.width / 2 : 4;
            final int y = onStrip ? tab.y + tab.height / 2 : 4;
            target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                    InputEvent.BUTTON3_DOWN_MASK, x, y, 1, true, MouseEvent.BUTTON3));
            return null;
        });
    }

    /// Clicks the enabled item `text` of the menu just opened, then closes the menu.
    private static void clickItem(final String text) throws Exception {
        final AtomicReference<JMenuItem> item = new AtomicReference<>();
        Awaitility.await("the menu's " + text).atMost(WAIT).until(() -> {
            item.set(onEdt(() -> openItem(text)));
            return item.get() != null;
        });
        onEdt(() -> {
            item.get().doClick(0);
            if (SwingUtilities.getAncestorOfClass(JPopupMenu.class, item.get()) instanceof JPopupMenu m) {
                m.setVisible(false);
            }
            MenuSelectionManager.defaultManager().clearSelectedPath();
            return null;
        });
    }

    /// The enabled item `text` of a popup menu open now.
    private static JMenuItem openItem(final String text) {
        for (final MenuElement e : MenuSelectionManager.defaultManager().getSelectedPath()) {
            if (e instanceof JPopupMenu m && m.isVisible()) {
                for (final Component c : m.getComponents()) {
                    if (c instanceof JMenuItem i && text.equals(i.getText()) && i.isEnabled()) {
                        return i;
                    }
                }
            }
        }
        return null;
    }

    /// The tab strip named `name` in the window, shown or not.
    private static JTabbedPane strip(final String name) throws Exception {
        final AtomicReference<JTabbedPane> out = new AtomicReference<>();
        Awaitility.await("the " + name + " tabs").atMost(WAIT).until(() -> {
            out.set(onEdt(() -> find(frame(), JTabbedPane.class, t -> name.equals(t.getName()))));
            return out.get() != null;
        });
        return out.get();
    }

    /// The width of the right side holding `side`: the split's component that holds it.
    private static int sideWidth(final JTabbedPane side) {
        for (Container c = side.getParent(); c != null; c = c.getParent()) {
            if (c instanceof JSplitPane split && split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT
                    && split.getRightComponent() instanceof Container right && right.isAncestorOf(side)) {
                return right.getWidth();
            }
        }
        throw new AssertionError("the right side is in no split");
    }

    private static int indexOf(final JTabbedPane strip, final PanelId id) {
        for (int i = 0; i < strip.getTabCount(); i++) {
            if (strip.getComponentAt(i) instanceof EditorPanel p && p.panel().id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /// The view tab of `strip` whose title is `title`, read from its tab or its header.
    private static int titled(final JTabbedPane strip, final String title) {
        for (int i = 0; i < strip.getTabCount(); i++) {
            if (strip.getComponentAt(i) instanceof EditorPanel) {
                continue;
            }
            final Component header = strip.getTabComponentAt(i);
            if (title.equals(strip.getTitleAt(i)) || header != null && holdsText(header, title)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean holdsText(final Component c, final String text) {
        if (c instanceof JLabel l && text.equals(l.getText())) {
            return true;
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                if (holdsText(child, text)) {
                    return true;
                }
            }
        }
        return false;
    }

    /// The colour the divider of `split` is painted in.
    private static Color divider(final JSplitPane split) {
        return ((BasicSplitPaneUI) split.getUI()).getDivider().getBackground();
    }

    /// The perceived brightness of `c`, 0 to 255, by the ITU-R BT.601 weights.
    private static int brightness(final Color c) {
        return (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000;
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

    private interface EdtCall<T> {
        T call() throws Exception;
    }
}
