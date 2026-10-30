package dev.marregui.comando.app;

import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.NamedTheme;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Window;
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
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class ThemeFadeUiTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    /** The layer FlatLaf's fade puts the old window's picture on. */
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
                MenuSelectionManager.defaultManager().clearSelectedPath();
                Theme.choose(Ground.GRAPHITE, Tint.TREASURE);
                return null;
            });
            app.close();
        }
    }

    /** Opens the swatch's menu, as a click does, and clicks {@code theme}'s entry. */
    private static void pick(NamedTheme theme) throws Exception {
        onEdt(() -> {
            JButton swatch = find(frame(), JButton.class, b -> "Choose the theme".equals(b.getAccessibleContext().getAccessibleName()));
            assertThat(swatch).as("the swatch beside New panel").isNotNull();
            swatch.doClick(0);
            return null;
        });
        onEdt(() -> {
            JRadioButtonMenuItem item = null;
            for (Window w : Window.getWindows()) {
                item = item != null ? item : find(w, JRadioButtonMenuItem.class, i -> i.isShowing() && i.getText().equals(theme.label));
            }
            assertThat(item).as("the menu's entry for " + theme.label).isNotNull();
            item.doClick(0);
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
