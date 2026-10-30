package dev.marregui.comando.ui.kit.theme;

import com.formdev.flatlaf.FlatDarkLaf;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// [FocusRing] marks the content view that holds the keyboard in the tint, and keeps doing so across theme changes.
/// - The ring survives the look and feel being installed again, which a removed border does not.
/// - Without the keyboard, the ring paints nothing.
/// - A tint chosen becomes the look and feel's own focus colour and tab underline.
class FocusRingTest {

    private static LookAndFeel previous;

    @BeforeAll
    static void flatLaf() throws Exception {
        previous = UIManager.getLookAndFeel();
        UIManager.setLookAndFeel(new FlatDarkLaf());
    }

    @AfterAll
    static void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> Theme.tint(Tint.TREASURE));
        UIManager.setLookAndFeel(previous);
    }

    /// A scroll pane with the ring keeps it after its UI is installed again, as a theme change does; one whose border
    /// was removed gets the look and feel's border back, which draws the system accent round it.
    @Test
    void theRingSurvivesTheLookAndFeelInstalledAgain() throws Exception {
        AtomicReference<Object> ring = new AtomicReference<>();
        AtomicReference<Object> removed = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JScrollPane withRing = new JScrollPane(new JPanel());
            FocusRing.install(withRing);
            JScrollPane without = new JScrollPane(new JPanel());
            without.setBorder(null);
            SwingUtilities.updateComponentTreeUI(withRing);
            SwingUtilities.updateComponentTreeUI(without);
            ring.set(withRing.getBorder());
            removed.set(without.getBorder());
        });
        assertThat(ring.get()).isInstanceOf(FocusRing.class);
        assertThat(removed.get()).as("a removed border comes back").isNotNull().isNotInstanceOf(FocusRing.class);
    }

    /// A scroll pane whose view does not hold the keyboard paints no ring: the edge stays the ground.
    @Test
    void withoutTheKeyboardTheRingPaintsNothing() throws Exception {
        BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        SwingUtilities.invokeAndWait(() -> {
            JScrollPane scroll = new JScrollPane(new JPanel());
            FocusRing.install(scroll);
            assertThat(FocusRing.shows(scroll)).isFalse();
            Graphics2D g = img.createGraphics();
            scroll.getBorder().paintBorder(scroll, g, 0, 0, 20, 20);
            g.dispose();
        });
        assertThat(img.getRGB(0, 0) & 0xFFFFFF).isZero();
        assertThat(img.getRGB(19, 19) & 0xFFFFFF).isZero();
    }

    /// Choosing a tint sets the look and feel up again, so its focused borders and the underline of the tab in front
    /// take the tint instead of the system accent.
    @Test
    void aTintChosenIsTheLookAndFeelsFocusColour() throws Exception {
        SwingUtilities.invokeAndWait(() -> Theme.tint(Tint.CHAMPAGNE));
        assertThat(UIManager.getColor("Component.focusedBorderColor")).isEqualTo(Tint.CHAMPAGNE.body);
        assertThat(UIManager.getColor("TabbedPane.underlineColor")).isEqualTo(Tint.CHAMPAGNE.body);
    }
}
