package dev.marregui.comando.app;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.ui.kit.icon.BlendIcon;
import dev.marregui.comando.ui.kit.theme.NamedTheme;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.panels.EditorPanel;

import static org.assertj.core.api.Assertions.assertThat;

/// The session bar's Reflect, Compact and Clear and its popups in the real window, under a dark and a light theme.
/// Opens the window, so this runs in `./gradlew :app:uiTest`.
/// - the three buttons sit right of the context, each with its icon, enabled by its own rule
/// - Reflect's icon at the threshold is the theme's purple, the dark one or the light one
/// - every item of the chip's, the model's and the effort's popups has an icon, and the popup paints from the theme
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class SessionBarButtonsUiTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    /** A mean of the three channels above this is a light colour, as `DiffThemeUiTest` reads it. */
    private static final int MIDDLE = 128;
    private static final List<String> BUTTONS = List.of("Reflect", "Compact the conversation",
            "Clear the conversation");
    private static final List<String> MENUS = List.of("Session", "Model", "Effort");

    @TempDir
    Path home;

    @AfterEach
    void plainTheme() throws Exception {
        choose(NamedTheme.GRAPHITE_GOLD);
    }

    /// Under Graphite and gold, a stopped session's bar shows Reflect, Compact and Clear after the context, in that
    /// order and with their icons; Reflect is on, Compact and Clear wait for the session to run. Reflect's icon at the
    /// threshold is the dark purple; each popup's items have icons and the popup is dark.
    @Test
    void theButtonsAndPopupsFollowADarkTheme() throws Exception {
        inTheme(NamedTheme.GRAPHITE_GOLD);
    }

    /// The same under Paper and ochre, where Reflect's icon at the threshold is the light purple and the popups are
    /// pale.
    @Test
    void theButtonsAndPopupsFollowALightTheme() throws Exception {
        inTheme(NamedTheme.PAPER_OCHRE);
    }

    /**
     * Sets the blend icon's share. Called by name: {@link BlendIcon} is a FlatLaf icon, and FlatLaf is the ui module's
     * own dependency, not on this module's test compile path.
     */
    private static void share(final Icon icon, final double fraction) throws ReflectiveOperationException {
        BlendIcon.class.getMethod("share", double.class).invoke(icon, fraction);
    }

    /** Chooses {@code theme} as the chooser does, its ground and its tint. */
    private static void choose(final NamedTheme theme) throws Exception {
        SwingUtilities.invokeAndWait(() -> Theme.choose(theme.ground, theme.tint));
    }

    /** The popup open now, as a button's click shows it; null when none is. */
    private static JPopupMenu popup() {
        final MenuElement[] path = MenuSelectionManager.defaultManager().getSelectedPath();
        return path.length > 0 && path[0] instanceof JPopupMenu m && m.isShowing() ? m : null;
    }

    /** The window's session bar. */
    private static Container bar() {
        final JComponent bar = first(JComponent.class, c -> "session bar".equals(c.getName()) && c.isShowing());
        assertThat(bar).as("the session bar").isNotNull();
        return bar;
    }

    /** The visible button of {@code root} by its accessible name, or null. */
    private static AbstractButton visible(final Container root, final String name) {
        return named(root, AbstractButton.class, name);
    }

    /** The visible {@code type} of {@code root} by its accessible name, or null. */
    private static <T extends JComponent> T named(final Container root, final Class<T> type, final String name) {
        final List<T> out = new ArrayList<>();
        collect(root, type, c -> name.equals(c.getAccessibleContext().getAccessibleName()) && c.isVisible(), out);
        return out.isEmpty() ? null : out.getFirst();
    }

    private static int indexIn(final Container row, final Component c) {
        return List.of(row.getComponents()).indexOf(c);
    }

    /** The icon's colour a little above its centre, on the bookmark's body, as `SessionBarTest` reads it. */
    private static int centre(final Icon icon) {
        final BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(),
                BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = image.createGraphics();
        try {
            icon.paintIcon(null, g, 0, 0);
        } finally {
            g.dispose();
        }
        return image.getRGB(icon.getIconWidth() / 2, icon.getIconHeight() / 2 - 2) & 0xFFFFFF;
    }

    private static boolean bright(final Color c) {
        return (c.getRed() + c.getGreen() + c.getBlue()) / 3 > MIDDLE;
    }

    /** The focused window's editor panel, or null before the window shows one. */
    private static EditorPanel editor() throws Exception {
        return onEdt(() -> first(EditorPanel.class, Component::isShowing));
    }

    private static <T extends Component> T first(final Class<T> type, final Predicate<T> which) {
        final List<T> out = new ArrayList<>();
        for (final Frame f : Frame.getFrames()) {
            if (f.isVisible()) {
                collect(f, type, which, out);
            }
        }
        return out.isEmpty() ? null : out.getFirst();
    }

    private static <T extends Component> void collect(
            final Component c,
            final Class<T> type,
            final Predicate<T> which,
            final List<T> out
    ) {
        if (type.isInstance(c) && which.test(type.cast(c))) {
            out.add(type.cast(c));
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                collect(child, type, which, out);
            }
        }
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

    private void inTheme(final NamedTheme theme) throws Exception {
        try (final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(),
                home.resolve("projects"), false), "bar-buttons", () -> {
        })) {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20))
                    .until(() -> loop.state().focused() != null && editor() != null);
            choose(theme);
            assertThat(onEdt(Theme::light)).as("the theme's mode").isEqualTo(theme.ground.light);
            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("work", home.toString())));
            Awaitility.await("its own panel").atMost(WAIT).until(() -> loop.state().homePanel("work").isPresent());
            final PanelId panel = loop.state().homePanel("work").orElseThrow().id();
            Awaitility.await("its panel shown").atMost(WAIT).until(() -> {
                final EditorPanel e = editor();
                return e != null && onEdt(e::panel).id().equals(panel);
            });
            Awaitility.await("the bar shows the session").atMost(WAIT)
                    .until(() -> onEdt(() -> visible(bar(), "Reflect") != null));

            onEdt(() -> {
                final Container bar = bar();
                final JComponent context = named(bar, JComponent.class, "Context");
                assertThat(context).as("the context").isNotNull();
                final Container row = context.getParent();
                int after = indexIn(row, context);
                for (final String name : BUTTONS) {
                    final AbstractButton b = visible(bar, name);
                    assertThat(b).as("a visible %s", name).isNotNull();
                    assertThat(b.getParent()).as("%s in the context's row", name).isSameAs(row);
                    assertThat(indexIn(row, b)).as("%s after the one before it", name).isGreaterThan(after);
                    after = indexIn(row, b);
                    assertThat(b.getIcon()).as("%s's icon", name).isNotNull();
                    assertThat(b.getToolTipText()).as("%s's tooltip", name).isNotBlank();
                }
                assertThat(List.of(visible(bar, "Reflect").isEnabled(),
                        visible(bar, "Compact the conversation").isEnabled(),
                        visible(bar, "Clear the conversation").isEnabled()))
                        .as("Reflect, Compact, Clear on a stopped session").containsExactly(true, false, false);

                final Icon reflect = visible(bar, "Reflect").getIcon();
                assertThat(reflect).as("Reflect's icon blends").isInstanceOf(BlendIcon.class);
                share(reflect, 1.0);
                try {
                    assertThat(Integer.toHexString(centre(reflect)))
                            .as("Reflect at the threshold, in the mode's purple")
                            .isEqualTo(Integer.toHexString(Theme.THINKING.getRGB() & 0xFFFFFF));
                } finally {
                    share(reflect, 0.0);
                }
                return null;
            });

            for (final String name : MENUS) {
                SwingUtilities.invokeAndWait(() -> visible(bar(), name).doClick(0));
                try {
                    final JPopupMenu popup = onEdt(SessionBarButtonsUiTest::popup);
                    assertThat(popup).as("the %s popup open", name).isNotNull();
                    final List<String> bare = new ArrayList<>();
                    final int items = onEdt(() -> {
                        int n = 0;
                        for (final Component c : popup.getComponents()) {
                            if (c instanceof JMenuItem item) {
                                n++;
                                if (item.getIcon() == null) {
                                    bare.add(item.getText());
                                }
                            }
                        }
                        return n;
                    });
                    assertThat(items).as("the %s popup's items", name).isPositive();
                    assertThat(bare).as("the %s popup's items without an icon", name).isEmpty();
                    assertThat(onEdt(() -> bright(popup.getBackground())))
                            .as("the %s popup paints from the theme", name).isEqualTo(theme.ground.light);
                } finally {
                    SwingUtilities.invokeAndWait(() -> MenuSelectionManager.defaultManager().clearSelectedPath());
                }
            }
        }
    }

    private interface EdtCall<T> {
        T call() throws Exception;
    }
}
