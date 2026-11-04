package dev.marregui.comando.ui.kit.tabs;

import java.awt.Component;
import java.awt.Container;
import java.awt.Insets;
import javax.swing.JLabel;
import javax.swing.JToggleButton;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatDarkLaf;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [TabStrip]: a tab's header keeps its margin through a look and feel update, as a theme change makes one.
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
