package dev.marregui.comando.ui.panels;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.plaf.UIResource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.marregui.comando.core.port.LabPort;
import dev.marregui.comando.core.port.LabPort.Counts;
import dev.marregui.comando.core.port.LabPort.Frame;
import dev.marregui.comando.core.port.LabPort.Link;
import dev.marregui.comando.core.port.LabPort.Node;
import dev.marregui.comando.core.port.LabPort.Totals;
import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;

import static org.assertj.core.api.Assertions.assertThat;

/// The frontear tab painted in a window off screen, under one dark and one light theme. Opens a window, so this runs in
/// `./gradlew :app:uiTest`.
/// - every opaque part of both views paints a background the look and feel gave or the theme's
/// - a link in trouble shows its status in the theme's danger colour, an ok one in the table's text colour
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class FrontearThemeUiTest {

    private JFrame frame;

    /// The look and feel, set up once before any component exists, as the application does at its start.
    @BeforeAll
    static void lookAndFeel() throws Exception {
        onEdt(() -> {
            Theme.install();
            return null;
        });
    }

    /// The window and the theme as the next test expects them.
    @AfterEach
    void asBefore() throws Exception {
        onEdt(() -> {
            if (frame != null) {
                frame.dispose();
            }
            Theme.choose(Ground.GRAPHITE, Tint.TREASURE);
            return null;
        });
    }

    /// Under the theme given, both views paint only the look and feel's or the theme's backgrounds, and the status of
    /// the link that is down is drawn in the danger colour, the one that is ok in the table's text colour.
    @ParameterizedTest(name = "{0} and {1}")
    @CsvSource({"GRAPHITE, TREASURE", "PAPER, OCHRE"})
    void theTabPaintsFromTheTheme(final Ground ground, final Tint tint) throws Exception {
        final FrontearTab tab = onEdt(() -> {
            Theme.choose(ground, tint);
            final FrontearTab t = new FrontearTab();
            t.lab(new OneNode());
            frame = offScreen(t.component());
            t.shown();
            return t;
        });
        final List<String> foreign = onEdt(() -> {
            final List<String> out = new ArrayList<>();
            final JTabbedPane views = (JTabbedPane) tab.component();
            for (int i = 0; i < views.getTabCount(); i++) {
                views.setSelectedIndex(i);
                views.validate();
                offTheme(views, out);
            }
            return out;
        });
        assertThat(foreign).as("parts painted outside the theme").isEmpty();

        final List<Color> status = onEdt(() -> {
            final JTable t = named(tab.component(), "frontear.health", JTable.class);
            final List<Color> out = new ArrayList<>();
            for (int row = 0; row < t.getRowCount(); row++) {
                out.add(t.prepareRenderer(t.getCellRenderer(row, 1), row, 1).getForeground());
            }
            out.add(t.getForeground());
            return out;
        });
        assertThat(status.get(0)).as("down").isEqualTo(Theme.DANGER);
        assertThat(status.get(1)).as("ok").isEqualTo(status.get(2));
    }

    /// A window left of every screen holding `content`, as the application's is under `comando.offScreen`.
    private static JFrame offScreen(final JComponent content) {
        Rectangle screens = new Rectangle();
        for (final GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            screens = screens.union(d.getDefaultConfiguration().getBounds());
        }
        final JFrame f = new JFrame("frontear");
        f.setUndecorated(true);
        f.setAutoRequestFocus(false);
        f.setFocusableWindowState(false);
        f.setContentPane(content);
        f.setBounds(screens.x - 1_800, screens.y, 1_000, 480);
        f.setVisible(true);
        f.validate();
        return f;
    }

    /// Each opaque component under `c` whose background is set in code and is not the theme's.
    private static void offTheme(final Component c, final List<String> out) {
        if (c instanceof final JComponent j && j.isOpaque() && j.isShowing()) {
            final Color b = j.getBackground();
            if (b != null && !(b instanceof UIResource) && b.getRGB() != Theme.SELECTION.getRGB()) {
                out.add(j.getClass().getSimpleName() + (j.getName() == null ? "" : " " + j.getName())
                        + " [colour=" + b + ']');
            }
        }
        if (c instanceof final Container k) {
            for (final Component child : k.getComponents()) {
                offTheme(child, out);
            }
        }
    }

    private static <T extends Component> T named(final Component c, final String name, final Class<T> type) {
        if (type.isInstance(c) && name.equals(c.getName())) {
            return type.cast(c);
        }
        if (c instanceof final Container k) {
            for (final Component child : k.getComponents()) {
                final T found = named(child, name, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T> T onEdt(final Callable<T> work) throws Exception {
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Exception> failed = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(work.call());
            } catch (final Exception e) {
                failed.set(e);
            }
        });
        if (failed.get() != null) {
            throw failed.get();
        }
        return result.get();
    }

    /// A node with a link down and a link that is ok, and no packets.
    private static final class OneNode implements LabPort {

        @Override
        public void open() {
        }

        @Override
        public void listen(final Runnable changed) {
        }

        @Override
        public Frame read(final long after, final int max) {
            final Node node = new Node("main", 18883, 10_000L, true, Totals.NONE,
                    List.of(new Link("bob", false, 3L, 0L, 3L, 0L, 0L, 0L, 0, 64, 0.0, 0.0, 0L, 1_000L, "refused"),
                            new Link("alice", true, 0L, 9L, 9L, 9L, 0L, 0L, 0, 64, 1.0, 10.0, 1_000L, 0L, "")),
                    List.of(), Counts.NONE);
            return new Frame(node, List.of(), 0L, after);
        }
    }
}
