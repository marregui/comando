package dev.marregui.comando.ui.panels;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.app.App;
import dev.marregui.comando.app.AppConfig;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.port.InstancesPort;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.InstancesState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.testkit.States;
import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;

import static org.assertj.core.api.Assertions.assertThat;

/// The panels' own context menus on the theme, with comando running (`./gradlew :app:uiTest`): an instance's row menu,
/// the recent notices and a notice's menu, a panel's connection menu. Each is built as a right click builds it, after
/// the theme was chosen, and painted into an image, so no popup reaches the desktop.
/// - under a dark theme every item has an icon and the popup paints the dark ground
/// - under a light theme the same menus have their icons and paint the light ground
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class PanelMenusIconsUiTest {

    private static final int MIDDLE = 128;

    @TempDir
    Path home;

    /// With the window up, Graphite and Treasure chosen, then Fog and Slate Ink: under each, every item of every menu
    /// carries an icon, the popup's background is the theme's, and its painted pixels are dark, then light.
    @Test
    void thePanelsMenusCarryIconsAndPaintTheDarkThenTheLightTheme() throws Exception {
        try (final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(),
                home.resolve("projects"), false), "panel-menus", () -> {
        })) {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null);

            onTheme(Ground.GRAPHITE, Tint.TREASURE, false);
            onTheme(Ground.FOG, Tint.SLATE_INK, true);
        } finally {
            choose(Ground.GRAPHITE, Tint.TREASURE);
        }
    }

    /// Chooses the theme, then builds every menu and checks its icons, its background and its paint against `light`.
    private static void onTheme(final Ground ground, final Tint tint, final boolean light) throws Exception {
        choose(ground, tint);
        final String theme = light ? "the light theme" : "the dark theme";
        assertThat(onEdt(Theme::light)).as(theme).isEqualTo(light);
        final List<JPopupMenu> menus = onEdt(PanelMenusIconsUiTest::menus);
        assertThat(menus).hasSize(8);
        for (final JPopupMenu menu : menus) {
            assertThat(onEdt(() -> bare(menu))).as("items without an icon under " + theme).isEmpty();
            assertThat(onEdt(() -> bright(menu.getBackground()))).as("the popup's background under " + theme)
                    .isEqualTo(light);
            assertThat(onEdt(() -> paintedBright(menu))).as("the popup as painted under " + theme).isEqualTo(light);
        }
    }

    /// An instance's row menu for this instance, one that left and one present; the recent notices with none and with
    /// one, and a notice's own menu; the connection menu of a bound panel and of one with no connection to bind.
    private static List<JPopupMenu> menus() {
        final InstancesTab tab = new InstancesTab(_ -> { }, () -> InstancesPort.NONE, () -> null);
        final StatusBar empty = new StatusBar(_ -> { });
        empty.render(States.twoPanels());
        final Notice saved = new Notice(Notice.Level.INFO, "Saved notes", 6L);
        final StatusBar some = new StatusBar(_ -> { });
        some.render(States.twoPanels().withNotices(saved, List.of(saved)));
        final AppState bound = States.twoPanels();
        final PanelState one = bound.panels().getFirst();
        final EditorPanel panel = new EditorPanel(one, _ -> { }, new SchemaRegistry());
        panel.render(one, bound);
        final PanelState unbound = new PanelState(States.P2, "two", "", "");
        final EditorPanel alone = new EditorPanel(unbound, _ -> { }, new SchemaRegistry());
        alone.render(unbound, AppState.initial(List.of(unbound), List.of(), List.of()));
        return List.of(tab.menuFor(new InstancesTab.Row(instance("main", "comando", true), null, true)),
                tab.menuFor(new InstancesTab.Row(instance("gone", "Expert", false), null, false)),
                tab.menuFor(new InstancesTab.Row(instance("x", "Reviewer", true), null, false)),
                empty.historyMenu(), some.historyMenu(), StatusBar.noticeMenu(saved),
                panel.connectionMenu(), alone.connectionMenu());
    }

    private static InstancesState.Instance instance(final String slug, final String name, final boolean present) {
        return new InstancesState.Instance(slug, name, "2.0.0", "", "", false, "", List.of(), false, present, 0L);
    }

    /// The text of each item of `menu`, its submenus' included, whose icon is missing or paints nothing.
    private static List<String> bare(final JPopupMenu menu) {
        final List<JMenuItem> items = new ArrayList<>();
        collect(menu.getComponents(), items);
        assertThat(items).as("a menu with items").isNotEmpty();
        return items.stream().filter(i -> i.getIcon() == null || i.getIcon().getIconWidth() <= 0)
                .map(JMenuItem::getText).toList();
    }

    private static void collect(final Component[] components, final List<JMenuItem> out) {
        for (final Component c : components) {
            if (c instanceof JMenu m) {
                out.add(m);
                collect(m.getMenuComponents(), out);
            } else if (c instanceof JMenuItem i) {
                out.add(i);
            }
        }
    }

    /// Whether `menu`, laid out at its preferred size and painted into an image, is bright on average.
    private static boolean paintedBright(final JPopupMenu menu) {
        menu.setSize(menu.getPreferredSize());
        menu.doLayout();
        final BufferedImage image = new BufferedImage(menu.getWidth(), menu.getHeight(), BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();
        try {
            menu.paint(g);
        } finally {
            g.dispose();
        }
        long sum = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                final int rgb = image.getRGB(x, y);
                sum += ((rgb >> 16 & 0xFF) + (rgb >> 8 & 0xFF) + (rgb & 0xFF)) / 3;
            }
        }
        return sum / ((long) image.getWidth() * image.getHeight()) > MIDDLE;
    }

    private static boolean bright(final Color c) {
        return (c.getRed() + c.getGreen() + c.getBlue()) / 3 > MIDDLE;
    }

    private static void choose(final Ground ground, final Tint tint) throws Exception {
        SwingUtilities.invokeAndWait(() -> Theme.choose(ground, tint));
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
