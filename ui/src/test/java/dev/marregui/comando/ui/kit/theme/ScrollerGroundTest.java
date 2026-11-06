package dev.marregui.comando.ui.kit.theme;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.LookAndFeel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.kit.text.Editors;

import static org.assertj.core.api.Assertions.assertThat;

/// A scroller is drawn on the panels' ground: no track or corner shows a colour of its own.
/// - Every theme's scroll bars and the corner between them are its ground: a plain pane, a sidebar's, an editor's.
/// - A pane made before another theme is chosen takes the new ground with the rest of the window.
@ExtendWith(OnEdt.class)
class ScrollerGroundTest {

    private static final int RGB = 0xFFFFFF;
    /// Far taller and wider than the pane, so each thumb stays at its start and the bar's far end shows the track.
    private static final Dimension CONTENT = new Dimension(4000, 4000);
    private static final Dimension PANE = new Dimension(200, 200);

    /// In each theme a plain pane, a sidebar's and an editor's paint their tracks and corner in the ground chosen.
    @Test
    void everyThemesTracksAndCornerAreItsGround() throws UnsupportedLookAndFeelException {
        onComandosLook(() -> {
            for (final NamedTheme t : NamedTheme.values()) {
                Theme.choose(t.ground, t.tint);
                assertOnGround(plain(), t.id + ", plain");
                assertOnGround(sidebar(), t.id + ", sidebar");
                assertOnGround(editor(), t.id + ", editor");
            }
        });
    }

    /// A sidebar's pane made on a dark ground, with the window's tree updated on a light one, paints the light ground:
    /// it is opaque, and its background is the look's again after the update.
    @Test
    void aPaneMadeBeforeAnotherThemeTakesItsGround() throws UnsupportedLookAndFeelException {
        onComandosLook(() -> {
            Theme.choose(Ground.GRAPHITE, Tint.TREASURE);
            final JScrollPane sp = sidebar();
            Theme.choose(Ground.FOG, Tint.SLATE_INK);
            SwingUtilities.updateComponentTreeUI(sp);
            assertOnGround(sp, "after the change");
        });
    }

    /// Runs `body` on the look the application installs, then puts the test JVM's look back for the next test.
    private static void onComandosLook(final Runnable body) throws UnsupportedLookAndFeelException {
        final LookAndFeel before = UIManager.getLookAndFeel();
        Theme.install();
        try {
            body.run();
        } finally {
            Theme.choose(Ground.GRAPHITE, Tint.TREASURE);
            UIManager.setLookAndFeel(before);
        }
    }

    private static JScrollPane plain() {
        final JPanel content = new JPanel();
        content.setPreferredSize(CONTENT);
        return new JScrollPane(content);
    }

    private static JScrollPane sidebar() {
        final JScrollPane sp = plain();
        SidebarLook.ground(sp);
        return sp;
    }

    private static JScrollPane editor() {
        final RSyntaxTextArea area = new RSyntaxTextArea();
        area.setPreferredSize(CONTENT);
        return Editors.scroll(area, true);
    }

    private static void assertOnGround(final JScrollPane sp, final String what) {
        sp.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);
        // a pane that is not opaque shows its parent in the corner
        final JPanel parent = new JPanel(new BorderLayout());
        parent.add(sp);
        parent.setSize(PANE);
        parent.doLayout();
        sp.doLayout();
        sp.getVerticalScrollBar().doLayout();
        sp.getHorizontalScrollBar().doLayout();
        final BufferedImage image = new BufferedImage(PANE.width, PANE.height, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();
        parent.paint(g);
        g.dispose();
        final Rectangle down = sp.getVerticalScrollBar().getBounds();
        final Rectangle across = sp.getHorizontalScrollBar().getBounds();
        final int ground = Theme.ground().getRGB() & RGB;
        final int downX = down.x + down.width / 2;
        final int acrossY = across.y + across.height / 2;
        assertThat(image.getRGB(downX, down.y + down.height - 3) & RGB).as(what + ": vertical track").isEqualTo(ground);
        assertThat(image.getRGB(across.x + across.width - 3, acrossY) & RGB).as(what + ": horizontal track")
                .isEqualTo(ground);
        assertThat(image.getRGB(downX, acrossY) & RGB).as(what + ": corner").isEqualTo(ground);
    }
}
