package dev.marregui.comando.app;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.NamedTheme;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/// A theme chosen from the swatch beside New panel fades in over the one before. Opens the window, so this runs in
/// `./gradlew :app:uiTest`.
/// - the ground and the tint are both applied at once, and the window shows the old theme's picture over the new one
/// - the picture is gone within a second, the window on the new ground
/// - the theme chosen already is applied without a fade
/// - a light theme puts the look and feel, the text and the title bars in light, and a dark one back
/// - the chooser closed with Esc opens again at the swatch's next click, at once
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class ThemeFadeUiTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    /** The layer the fade puts the old window's picture on. */
    private static final int FADE_LAYER = JLayeredPane.DRAG_LAYER + 1;

    @TempDir
    Path home;

    /// Picking Midnight and electric blue from the swatch's menu sets both at once and lays the old picture over the
    /// window; within a second it is gone and the look's background is midnight's. Picking it again lays no picture.
    @Test
    void aThemeChosenFadesInOverTheOneBefore() throws Exception {
        App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(), home.resolve("projects"), false),
                "theme-fade", () -> {
        });
        try {
            app.start();
            StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null && frame() != null);
            Awaitility.await("laid out").atMost(WAIT).until(() -> onEdt(() -> frame().getHeight()) > 400);
            assertThat(onEdt(Theme::groundChosen)).isEqualTo(Ground.GRAPHITE);

            pick(NamedTheme.MIDNIGHT_BLUE);
            assertThat(onEdt(() -> Theme.groundChosen() + "/" + Theme.tint())).as("both in one choice").isEqualTo("MIDNIGHT/ELECTRIC_BLUE");
            assertThat(onEdt(ThemeFadeUiTest::fading)).as("the old theme's picture over the window").isTrue();
            Awaitility.await("the fade ends").atMost(Duration.ofSeconds(2)).until(() -> !onEdt(ThemeFadeUiTest::fading));
            assertThat(onEdt(() -> UIManager.getColor("Panel.background").getRGB())).isEqualTo(Ground.MIDNIGHT.color.getRGB());

            pick(NamedTheme.MIDNIGHT_BLUE);
            assertThat(onEdt(ThemeFadeUiTest::fading)).as("the theme chosen already: no fade").isFalse();
        } finally {
            onEdt(() -> {
                Theme.choose(Ground.GRAPHITE, Tint.TREASURE);
                return null;
            });
            app.close();
        }
    }

    /// The chooser closed with Esc opens again at the swatch's next click, even at once: the deactivation its own close
    /// brings, which X11 delivers after the dispose, is not the click elsewhere that the swatch's own click must not undo.
    @Test
    void aChooserClosedWithEscOpensAgainAtOnce() throws Exception {
        App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(), home.resolve("projects"), false),
                "theme-reopen", () -> {
        });
        try {
            app.start();
            StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null && frame() != null);
            for (int round = 0; round < 2; round++) {
                JDialog chooser = onEdt(() -> {
                    find(frame(), JButton.class, b -> "Choose the theme".equals(b.getAccessibleContext().getAccessibleName())).doClick(0);
                    return chooser();
                });
                assertThat(chooser).as("the chooser open, round " + round).isNotNull();
                onEdt(() -> {
                    chooser.getRootPane().getActionMap().get("close").actionPerformed(null);
                    // the deactivation X11 delivers after the dispose, straight to the listeners: dispatched, the focus
                    // manager drops it for a window not active, and macOS sends none to a window it never activated
                    WindowEvent deactivated = new WindowEvent(chooser, WindowEvent.WINDOW_DEACTIVATED);
                    for (WindowListener l : chooser.getWindowListeners()) {
                        l.windowDeactivated(deactivated);
                    }
                    return null;
                });
                assertThat(onEdt(ThemeFadeUiTest::chooser)).as("closed by Esc").isNull();
            }
        } finally {
            app.close();
        }
    }

    /// A theme file in the data root's `themes` folder is offered after the twelve built in, by its label, and chosen
    /// from the swatch it applies its own ground and tint and is kept by its id; a broken file beside it is left out
    /// with a notice that names it.
    @Test
    void aThemeFileInTheDataRootIsOfferedAndChosen() throws Exception {
        Path themes = Files.createDirectories(home.resolve("data").resolve("themes"));
        Files.writeString(themes.resolve("ink_rose.json"), """
                {"id": "ink_rose", "label": "Ink and rose", "mode": "dark", "ground": {"label": "Ink", "color": "#14101A"},
                 "tint": {"label": "Rose", "highlight": "#FFE3EC", "body": "#F2A7C0", "shade": "#B5627F"}}
                """);
        Files.writeString(themes.resolve("broken.json"), "{\"id\": ");
        App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(), home.resolve("projects"), false),
                "theme-file", () -> {
        });
        try {
            app.start();
            StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null && frame() != null);
            Awaitility.await("the notice for the broken file").atMost(WAIT).until(() -> loop.state().notices().stream()
                    .anyMatch(n -> n.text().startsWith("Theme file left out: broken.json: not JSON")));
            assertThat(NamedTheme.values()).extracting(t -> t.label).hasSize(13).last().isEqualTo("Ink and rose");

            pick(NamedTheme.withId("ink_rose").orElseThrow());
            assertThat(onEdt(() -> Theme.groundChosen().name() + "/" + Theme.tint().name())).isEqualTo("INK_ROSE/INK_ROSE");
            assertThat(onEdt(() -> UIManager.getColor("Panel.background").getRGB())).isEqualTo(0xFF14101A);
            Awaitility.await("kept by its id").atMost(WAIT).until(() -> Files.readString(home.resolve("data").resolve("settings.json"))
                    .matches("(?s).*\"theme\\.id\"\\s*:\\s*\"ink_rose\".*"));
        } finally {
            onEdt(() -> {
                Theme.choose(Ground.GRAPHITE, Tint.TREASURE);
                return null;
            });
            app.close();
        }
    }

    /// Paper and ochre, chosen from the swatch, puts the window on the light look and feel: the panels on paper, the
    /// transcript's text dark, each window's title bar light; choosing a dark theme again puts all of it back. With
    /// `-Dcomando.lightShot=<file>` the light window is also written to that PNG, to be looked at.
    @Test
    void aLightThemeTurnsTheWholeWindowLightAndBack() throws Exception {
        App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(), home.resolve("projects"), false),
                "theme-light", () -> {
        });
        try {
            app.start();
            StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null && frame() != null);
            Awaitility.await("laid out").atMost(WAIT).until(() -> onEdt(() -> frame().getHeight()) > 400);

            pick(NamedTheme.PAPER_OCHRE);
            Awaitility.await("the fade ends").atMost(Duration.ofSeconds(2)).until(() -> !onEdt(ThemeFadeUiTest::fading));
            assertThat(onEdt(() -> UIManager.getLookAndFeel().getName())).as("the light look and feel").contains("Light");
            assertThat(onEdt(() -> UIManager.getColor("Panel.background").getRGB())).isEqualTo(Ground.PAPER.color.getRGB());
            assertThat(onEdt(() -> Theme.TRANSCRIPT_TEXT.getRGB())).as("the transcript's text, dark").isEqualTo(0xFF2B2620);
            assertThat(onEdt(() -> frame().getRootPane().getClientProperty("apple.awt.windowAppearance"))).isEqualTo("NSAppearanceNameAqua");
            shoot("");

            pick(NamedTheme.GRAPHITE_GOLD);
            Awaitility.await("the fade ends").atMost(Duration.ofSeconds(2)).until(() -> !onEdt(ThemeFadeUiTest::fading));
            assertThat(onEdt(() -> UIManager.getLookAndFeel().getName())).as("dark again").contains("Dark");
            shoot("-dark");
            assertThat(onEdt(() -> Theme.TRANSCRIPT_TEXT.getRGB())).isEqualTo(0xFFE2DCCD);
            assertThat(onEdt(() -> frame().getRootPane().getClientProperty("apple.awt.windowAppearance"))).isEqualTo("NSAppearanceNameDarkAqua");
        } finally {
            onEdt(() -> {
                Theme.choose(Ground.GRAPHITE, Tint.TREASURE);
                return null;
            });
            app.close();
        }
    }

    /// The picture of the new theme the fade ends on, taken once the window has settled, is the window as it paints
    /// once the fade is over, so nothing jumps when the pictures go; and one picture lies over the window, opaque, so
    /// the window under it is not painted at each frame. Before, FlatLaf's fade took its second picture at once, 2.7 %
    /// of whose pixels differed from the window after it (the panel's header, the plan's usage). Checked from Graphite
    /// and gold to Paper and ochre and back, at most 0.5 % of the pixels apart (a caret's blink, an antialiased edge).
    @Test
    void theFadeEndsOnTheWindowAsItThenPaints() throws Exception {
        App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(), home.resolve("projects"), false),
                "theme-end", () -> {
        });
        try {
            app.start();
            StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null && frame() != null);
            Awaitility.await("laid out").atMost(WAIT).until(() -> onEdt(() -> frame().getHeight()) > 400);
            for (NamedTheme t : new NamedTheme[]{NamedTheme.PAPER_OCHRE, NamedTheme.GRAPHITE_GOLD}) {
                // the switch turns the window to the twin: its knob slides, then the fade runs
                flip(t.ground.light);
                Thread.sleep(250 + 160);
                assertThat(onEdt(ThemeFadeUiTest::fading)).as("halfway, to " + t.label).isTrue();
                Component[] layer = onEdt(() -> frame().getLayeredPane().getComponentsInLayer(FADE_LAYER));
                assertThat(layer).as("one picture over the window").hasSize(1)
                        .allMatch(c -> c.getClass().getName().endsWith("ThemeFade$Picture")).allMatch(Component::isOpaque);
                BufferedImage end = onEdt(() -> newPicture(layer[0]));
                assertThat(end).as("the picture of the new theme, taken").isNotNull();
                Awaitility.await("the fade ends").atMost(Duration.ofSeconds(2)).until(() -> !onEdt(ThemeFadeUiTest::fading));
                BufferedImage live = onEdt(() -> shoot(frame()));
                closeChooser();
                assertThat(onEdt(Theme::groundChosen)).as("the twin chosen by the switch").isEqualTo(t.ground);
                String shot = System.getProperty("comando.lightShot");
                if (shot != null) {
                    ImageIO.write(end, "png", Path.of(shot.replace(".png", "-end-" + t.id + ".png")).toFile());
                    ImageIO.write(live, "png", Path.of(shot.replace(".png", "-live-" + t.id + ".png")).toFile());
                }
                assertThat(apart(end, live)).as("pixels apart between the fade's end and the window, to " + t.label).isLessThan(0.005);
            }
        } finally {
            onEdt(() -> {
                Theme.choose(Ground.GRAPHITE, Tint.TREASURE);
                return null;
            });
            app.close();
        }
    }

    /// A panel in a tab behind when a light theme is chosen shows it the first time its tab comes forward: the window,
    /// painted in the same event as the tab is selected, holds no pixel of the dark ground before (no blink of the
    /// old ground before the new one). And back to dark.
    @Test
    void aTabBehindShowsTheNewThemeTheFirstTimeItComesForward() throws Exception {
        Path one = Files.writeString(home.resolve("one.md"), "# One\n\nText.\n");
        Path two = Files.writeString(home.resolve("two.md"), "# Two\n\nText.\n");
        App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(), home.resolve("projects"), false),
                "theme-tabs", () -> {
        });
        try {
            app.start();
            StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null && frame() != null);
            loop.dispatch(new Intent.OpenFile("", one.toString(), 0));
            Awaitility.await("one's panel").atMost(WAIT).until(() -> loop.state().focusedPanel().title().equals("one.md"));
            loop.dispatch(new Intent.OpenFile("", two.toString(), 0));
            Awaitility.await("two's panel").atMost(WAIT).until(() -> loop.state().focusedPanel().title().equals("two.md"));
            Awaitility.await("laid out").atMost(WAIT).until(() -> onEdt(() -> frame().getHeight()) > 400);
            JTabbedPane tabs = onEdt(() -> find(frame(), JTabbedPane.class, t -> "workspace".equals(t.getName())));
            for (NamedTheme t : new NamedTheme[]{NamedTheme.PAPER_OCHRE, NamedTheme.GRAPHITE_GOLD}) {
                Color before = Theme.groundChosen().color;
                pick(t);
                Awaitility.await("the fade ends").atMost(Duration.ofSeconds(2)).until(() -> !onEdt(ThemeFadeUiTest::fading));
                for (int round = 0; round < 2; round++) {
                    BufferedImage first = onEdt(() -> {
                        tabs.setSelectedIndex((tabs.getSelectedIndex() + 1) % tabs.getTabCount());
                        JFrame f = frame();
                        f.validate();
                        return paint(f);
                    });
                    String shot = System.getProperty("comando.lightShot");
                    if (shot != null) {
                        ImageIO.write(first, "png", Path.of(shot.replace(".png", "-tab-" + t.id + "-" + round + ".png")).toFile());
                    }
                    assertThat(share(first, before)).as("pixels of the ground before, a tab brought forward after " + t.label)
                            .isLessThan(0.001);
                }
            }
        } finally {
            onEdt(() -> {
                Theme.choose(Ground.GRAPHITE, Tint.TREASURE);
                return null;
            });
            app.close();
        }
    }

    /** The share of {@code img}'s pixels that are {@code c}. */
    private static double share(BufferedImage img, Color c) {
        int rgb = c.getRGB() & 0xFFFFFF;
        long n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) == rgb) {
                    n++;
                }
            }
        }
        return (double) n / ((long) img.getWidth() * img.getHeight());
    }

    /** The picture of the new theme a fade's layer holds. */
    private static BufferedImage newPicture(Component picture) throws Exception {
        java.lang.reflect.Field f = picture.getClass().getDeclaredField("now");
        f.setAccessible(true);
        return (BufferedImage) f.get(picture);
    }

    /** The window's layered pane painted at the screen's scale, as the fade takes its pictures. */
    private static BufferedImage shoot(JFrame f) {
        java.awt.geom.AffineTransform scale = f.getGraphicsConfiguration().getDefaultTransform();
        JComponent pane = f.getLayeredPane();
        BufferedImage img = new BufferedImage((int) Math.ceil(pane.getWidth() * scale.getScaleX()),
                (int) Math.ceil(pane.getHeight() * scale.getScaleY()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.scale(scale.getScaleX(), scale.getScaleY());
        pane.paint(g);
        g.dispose();
        return img;
    }

    /** The window's content as it paints, without what lies over it: the fade's picture, a popup. */
    private static BufferedImage paint(JFrame f) {
        BufferedImage img = new BufferedImage(f.getContentPane().getWidth(), f.getContentPane().getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        f.getContentPane().paint(g);
        g.dispose();
        return img;
    }

    /** The share of pixels that differ between {@code a} and {@code b}, the same size. */
    private static double apart(BufferedImage a, BufferedImage b) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        long differ = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((a.getRGB(x, y) & 0xFFFFFF) != (b.getRGB(x, y) & 0xFFFFFF)) {
                    differ++;
                }
            }
        }
        return (double) differ / ((long) w * h);
    }

    /** Writes the window to the PNG {@code -Dcomando.lightShot} names, {@code suffix} before its extension; given none, nothing. */
    private static void shoot(String suffix) throws Exception {
        String shot = System.getProperty("comando.lightShot");
        if (shot == null) {
            return;
        }
        onEdt(() -> {
            JFrame f = frame();
            BufferedImage img = new BufferedImage(f.getWidth(), f.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            f.paint(g);
            g.dispose();
            ImageIO.write(img, "png", Path.of(shot.replace(".png", suffix + ".png")).toFile());
            return null;
        });
    }

    /**
     * Opens the theme chooser from the swatch, as a click does, clicks {@code theme}'s card, and closes the chooser
     * with Esc, as the user does once done trying.
     */
    private static void pick(NamedTheme theme) throws Exception {
        onEdt(() -> {
            JButton swatch = find(frame(), JButton.class, b -> "Choose the theme".equals(b.getAccessibleContext().getAccessibleName()));
            assertThat(swatch).as("the swatch beside New panel").isNotNull();
            swatch.doClick(0);
            return null;
        });
        // the chooser shows one mode's themes: the switch to the theme's mode first, which slides, then turns the window
        onEdt(() -> {
            for (Window w : frame().getOwnedWindows()) {
                if (w.isShowing() && w instanceof JDialog d && "Theme".equals(d.getTitle())) {
                    d.getRootPane().getActionMap().get(theme.ground.light ? "light" : "dark").actionPerformed(null);
                }
            }
            return null;
        });
        Awaitility.await("the switch at the theme's mode").atMost(WAIT).until(() -> onEdt(Theme::light) == theme.ground.light);
        Awaitility.await("the fade of the switch ends").atMost(Duration.ofSeconds(2)).until(() -> !onEdt(ThemeFadeUiTest::fading));
        onEdt(() -> {
            JButton card = null;
            for (Window w : frame().getOwnedWindows()) {
                card = card != null ? card : find(w, JButton.class, b -> b.isShowing() && theme.label.equals(b.getAccessibleContext().getAccessibleName()));
            }
            assertThat(card).as("the chooser's card for " + theme.label).isNotNull();
            String shot = System.getProperty("comando.lightShot");
            if (shot != null) {
                Window chooser = SwingUtilities.getWindowAncestor(card);
                chooser.validate();
                BufferedImage img = new BufferedImage(chooser.getWidth(), chooser.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                chooser.paint(g);
                g.dispose();
                ImageIO.write(img, "png", Path.of(shot.replace(".png", "-chooser-" + theme.id + ".png")).toFile());
            }
            // the cards are made again after a choice: the window first
            JDialog chooser = (JDialog) SwingUtilities.getWindowAncestor(card);
            card.doClick(0);
            assertThat(chooser.isShowing()).as("open after a choice, to try the next").isTrue();
            chooser.getRootPane().getActionMap().get("close").actionPerformed(null);
            return null;
        });
    }

    /** The theme chooser showing, or null. EDT only. */
    private static JDialog chooser() {
        for (Window w : frame().getOwnedWindows()) {
            if (w.isShowing() && w instanceof JDialog d && "Theme".equals(d.getTitle())) {
                return d;
            }
        }
        return null;
    }

    /** Opens the theme chooser from the swatch and flips its switch to {@code light}, without waiting. */
    private static void flip(boolean light) throws Exception {
        onEdt(() -> {
            JButton swatch = find(frame(), JButton.class, b -> "Choose the theme".equals(b.getAccessibleContext().getAccessibleName()));
            swatch.doClick(0);
            return null;
        });
        onEdt(() -> {
            for (Window w : frame().getOwnedWindows()) {
                if (w.isShowing() && w instanceof JDialog d && "Theme".equals(d.getTitle())) {
                    d.getRootPane().getActionMap().get(light ? "light" : "dark").actionPerformed(null);
                }
            }
            return null;
        });
    }

    /** Closes the theme chooser with Esc, as the user does once done trying. */
    private static void closeChooser() throws Exception {
        onEdt(() -> {
            for (Window w : frame().getOwnedWindows()) {
                if (w.isShowing() && w instanceof JDialog d && "Theme".equals(d.getTitle())) {
                    d.getRootPane().getActionMap().get("close").actionPerformed(null);
                }
            }
            return null;
        });
    }

    /** Whether the window shows the fade's picture of the theme before. */
    private static boolean fading() {
        JLayeredPane layers = frame().getLayeredPane();
        return layers.getComponentCountInLayer(FADE_LAYER) > 0;
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
