package dev.marregui.comando.app;

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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;

import static org.assertj.core.api.Assertions.assertThat;

/// The Sessions tab's multiple selection in the real window, under a dark and a light theme: a right click inside a
/// selection of several opens the menu for all of them and keeps the selection, one outside it selects that row alone
/// and opens its session's menu. Opens the window, so this runs in `./gradlew :app:uiTest`; the window is off screen,
/// and the menus are built and laid out without a popup on the desktop.
/// - inside a selection of two: the group's menu, both rows still selected
/// - outside it: the row's own menu, that row alone selected, Reflect in place of Checkpoint
/// - every item of both menus has an icon, the Model and Effort submenus too, and each menu paints in the theme's
///   ground
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class SessionsMultiSelectUiTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    /// A dark start, whatever theme an earlier test of the run chose.
    private static final String DARK_SETTINGS =
            "{\"version\": 1, \"theme.ground\": \"GRAPHITE\", \"theme.tint\": \"TREASURE\"}";
    /// The brightness, of 255, between a dark ground's and a light one's.
    private static final int MIDDLE = 128;

    @TempDir
    Path home;

    private PopupFactory popups;

    /// Menus are laid out but never shown, so no popup reaches the desktop.
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

    /// Under the dark theme: the group's menu inside the selection, the row's own outside it, every item with an icon
    /// and each menu painted dark.
    @Test
    void aRightClickInsideTheSelectionOpensTheGroupsMenuAndOutsideTheRowsOwnInTheDarkTheme() throws Exception {
        rightClicks("multi-select-dark", Ground.GRAPHITE, Tint.TREASURE, false);
    }

    /// Under the light theme: the same menus, every item with an icon and each menu painted light.
    @Test
    void aRightClickInsideTheSelectionOpensTheGroupsMenuAndOutsideTheRowsOwnInTheLightTheme() throws Exception {
        rightClicks("multi-select-light", Ground.FOG, Tint.SLATE_INK, true);
    }

    /// Every item of `menu` has an icon, and the menu, laid out at its size, paints on the theme's ground: dark under
    /// a dark theme, light under a light one.
    private static void menuIsThemedAndIconed(final JPopupMenu menu, final boolean light) throws Exception {
        assertThat(onEdt(() -> {
            final List<String> bare = new ArrayList<>();
            for (final Component c : menu.getComponents()) {
                if (c instanceof JMenuItem i && i.getIcon() == null) {
                    bare.add(i.getText());
                }
            }
            return bare;
        })).as("items with no icon").isEmpty();
        final int brightness = onEdt(() -> {
            menu.setSize(menu.getPreferredSize());
            menu.doLayout();
            final BufferedImage image = new BufferedImage(menu.getWidth(), menu.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            final Graphics2D g = image.createGraphics();
            try {
                menu.paint(g);
            } finally {
                g.dispose();
            }
            return meanBrightness(image);
        });
        if (light) {
            assertThat(brightness).as("painted light").isGreaterThan(MIDDLE);
        } else {
            assertThat(brightness).as("painted dark").isLessThan(MIDDLE);
        }
    }

    /// The mean perceived brightness of `image`, 0 to 255, by the ITU-R BT.601 weights.
    private static int meanBrightness(final BufferedImage image) {
        long sum = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                final int rgb = image.getRGB(x, y);
                sum += (((rgb >> 16) & 0xff) * 299 + ((rgb >> 8) & 0xff) * 587 + (rgb & 0xff) * 114) / 1000;
            }
        }
        return (int) (sum / ((long) image.getWidth() * image.getHeight()));
    }

    /// A right-button press, the platform's menu trigger, in the middle of row `index` of `list`.
    private static void rightClick(final JList<?> list, final int index) throws Exception {
        onEdt(() -> {
            final Rectangle cell = list.getCellBounds(index, index);
            list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                    InputEvent.BUTTON3_DOWN_MASK, cell.x + cell.width / 2, cell.y + cell.height / 2, 1, true,
                    MouseEvent.BUTTON3));
            return null;
        });
    }

    /// The popup menu open now.
    private static JPopupMenu openMenu() throws Exception {
        final AtomicReference<JPopupMenu> out = new AtomicReference<>();
        Awaitility.await("a menu open").atMost(WAIT).until(() -> {
            out.set(onEdt(() -> {
                for (final MenuElement e : MenuSelectionManager.defaultManager().getSelectedPath()) {
                    if (e instanceof JPopupMenu m && m.isVisible()) {
                        return m;
                    }
                }
                return null;
            }));
            return out.get() != null;
        });
        return out.get();
    }

    private static void close(final JPopupMenu menu) throws Exception {
        onEdt(() -> {
            menu.setVisible(false);
            MenuSelectionManager.defaultManager().clearSelectedPath();
            return null;
        });
    }

    private static List<String> texts(final JPopupMenu menu) {
        final List<String> out = new ArrayList<>();
        for (final Component c : menu.getComponents()) {
            if (c instanceof JMenuItem i) {
                out.add(i.getText());
            }
        }
        return out;
    }

    // ---- the window

    /// The Sessions tab's list in the window.
    private static JList<?> sessions() throws Exception {
        final AtomicReference<JList<?>> out = new AtomicReference<>();
        Awaitility.await("the Sessions list").atMost(WAIT).until(() -> {
            out.set(onEdt(() -> find(frame(), JList.class,
                    l -> "Sessions".equals(l.getAccessibleContext().getAccessibleName()))));
            return out.get() != null;
        });
        return out.get();
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

    /// Three sessions, the first two selected: a right click on the second opens the group's menu, one on the third
    /// its own; each is checked for icons and for the theme's ground.
    private void rightClicks(final String name, final Ground ground, final Tint tint, final boolean light)
            throws Exception {
        try (final App app = start(name)) {
            for (final String session : List.of("one", "three", "two")) {
                app.loop().dispatch(new Intent.SaveSession("", SessionSpec.fresh(session, home.toString())));
            }
            onEdt(() -> {
                Theme.choose(ground, tint);
                return null;
            });
            assertThat(onEdt(Theme::light)).as("the theme chosen").isEqualTo(light);
            final JList<?> list = sessions();
            Awaitility.await("three rows").atMost(WAIT).until(() -> onEdt(() -> list.getModel().getSize() == 3
                    && list.getCellBounds(2, 2) != null));
            onEdt(() -> {
                list.setSelectionInterval(0, 1);
                return null;
            });

            rightClick(list, 1);
            final JPopupMenu group = openMenu();
            assertThat(onEdt(() -> texts(group))).as("inside: the group's menu, no session's own items")
                    .isNotEmpty().doesNotContain("Edit…", "Copy details");
            assertThat(onEdt(list::getSelectedIndices)).as("the selection kept").containsExactly(0, 1);
            menuIsThemedAndIconed(group, light);
            close(group);

            rightClick(list, 2);
            final JPopupMenu own = openMenu();
            assertThat(own).as("a new menu").isNotSameAs(group);
            assertThat(onEdt(() -> texts(own))).as("outside: the row's own menu")
                    .contains("Edit…", "Copy details").doesNotContain("Checkpoint", "Checkpoint, due")
                    .anyMatch(t -> t.startsWith("Reflect")).anyMatch(t -> t.startsWith("Model: "))
                    .anyMatch(t -> t.startsWith("Effort: "));
            assertThat(onEdt(list::getSelectedIndices)).as("that row alone").containsExactly(2);
            menuIsThemedAndIconed(own, light);
            close(own);
        }
    }

    private App start(final String name) throws Exception {
        final Path data = Files.createDirectories(home.resolve("data"));
        Files.writeString(data.resolve("settings.json"), DARK_SETTINGS);
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

    private interface EdtCall<T> {
        T call() throws Exception;
    }
}
