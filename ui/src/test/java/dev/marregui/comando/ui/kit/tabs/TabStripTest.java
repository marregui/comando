package dev.marregui.comando.ui.kit.tabs;

import java.awt.Component;
import java.awt.Container;
import java.awt.Insets;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.Border;

import com.formdev.flatlaf.FlatDarkLaf;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.ui.kit.theme.BracketBorder;
import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.NamedTheme;
import dev.marregui.comando.ui.kit.theme.Style;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;

import static org.assertj.core.api.Assertions.assertThat;

/// [TabStrip]: a tab's header keeps its margin through a look and feel update, as a theme change makes one; the
/// sidebar's frame takes room only under a console theme.
class TabStripTest {

    private static LookAndFeel previous;

    @BeforeAll
    static void installLook() throws Exception {
        previous = UIManager.getLookAndFeel();
        UIManager.setLookAndFeel(new FlatDarkLaf());
    }

    @AfterAll
    static void restore() throws Exception {
        UIManager.setLookAndFeel(previous);
    }

    /// The header's margin is the grid's 4, 8, 4, 8 when added and after the tree's UI is updated.
    @Test
    void aTabKeepsItsMarginThroughALookAndFeelUpdate() throws Exception {
        final Insets[] margins = new Insets[2];
        SwingUtilities.invokeAndWait(() -> {
            final TabStrip strip = new TabStrip("Tabs");
            strip.add("Files", "files", "the files", new JLabel("body"));
            final JToggleButton header = header(strip.component());
            margins[0] = header.getMargin();
            SwingUtilities.updateComponentTreeUI(strip.component());
            margins[1] = header.getMargin();
        });
        assertThat(margins).containsExactly(new Insets(4, 8, 4, 8), new Insets(4, 8, 4, 8));
    }

    /// Under a plain theme the frame, kept through a look and feel update, leaves the strip and its tabs at the bounds
    /// they have with the panel's own border; under a console theme they lay out inside it, 3 px in on each side.
    @Test
    void theFrameTakesRoomOnlyUnderAConsoleTheme() throws Exception {
        final List<List<Rectangle>> bounds = new ArrayList<>();
        final Border[] kept = new Border[1];
        final Style[] style = new Style[1];
        SwingUtilities.invokeAndWait(() -> {
            final TabStrip strip = new TabStrip("Tabs");
            strip.add("Files", "files", "the files", new JLabel("body"));
            final JComponent root = strip.component();
            SwingUtilities.updateComponentTreeUI(root);
            kept[0] = root.getBorder();
            style[0] = Theme.style();
            bounds.add(laidOut(root));
            root.setBorder(new JPanel().getBorder());
            bounds.add(laidOut(root));
            root.setBorder(kept[0]);
            final Ground ground = Theme.groundChosen();
            final Tint tint = Theme.tint();
            Theme.choose(NamedTheme.CONSOLE_AMBER.ground, NamedTheme.CONSOLE_AMBER.tint);
            try {
                bounds.add(laidOut(root));
            } finally {
                Theme.choose(ground, tint);
            }
        });
        assertThat(style[0]).isEqualTo(Style.PLAIN);
        assertThat(kept[0]).isInstanceOf(BracketBorder.class);
        assertThat(bounds.get(0)).as("plain: as with no frame").isEqualTo(bounds.get(1));
        final Rectangle plain = bounds.get(0).getFirst();
        assertThat(bounds.get(2).getFirst()).as("console: the strip inside the frame")
                .isEqualTo(new Rectangle(plain.x + 3, plain.y + 3, plain.width - 6, plain.height));
    }

    private static List<Rectangle> laidOut(final Container c) {
        c.setSize(400, 300);
        c.doLayout();
        return Arrays.stream(c.getComponents()).map(Component::getBounds).toList();
    }

    private static JToggleButton header(final Component c) {
        if (c instanceof JToggleButton b) {
            return b;
        }
        if (c instanceof Container parent) {
            for (final Component child : parent.getComponents()) {
                final JToggleButton found = header(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
