package dev.marregui.comando.ui.shell;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [ClosingCover] veils the window while it closes and swallows all input.
/// - The cover paints a translucent veil.
/// - It is focusable, listens to mouse, motion and wheel, and consumes every key event.
class ClosingCoverTest {

    /// The painted centre is neither transparent nor opaque, mouse listeners are installed, and pressed, typed and
    /// released keys are each consumed.
    @Test
    void theCoverGreysTheWindowAndTakesEveryKeyAndClick() {
        final ClosingCover cover = new ClosingCover();
        cover.setSize(20, 10);
        final BufferedImage image = new BufferedImage(20, 10, BufferedImage.TYPE_INT_ARGB);
        final var g = image.createGraphics();
        cover.paint(g);
        g.dispose();
        assertThat(image.getRGB(10, 5) >>> 24).as("a translucent veil").isBetween(1, 254);

        assertThat(cover.isFocusable()).isTrue();
        assertThat(cover.getMouseListeners()).isNotEmpty();
        assertThat(cover.getMouseMotionListeners()).isNotEmpty();
        assertThat(cover.getMouseWheelListeners()).isNotEmpty();
        for (final int id : new int[]{KeyEvent.KEY_PRESSED, KeyEvent.KEY_TYPED, KeyEvent.KEY_RELEASED}) {
            final KeyEvent e = new KeyEvent(cover, id, 0L, 0, id == KeyEvent.KEY_TYPED ? KeyEvent.VK_UNDEFINED : KeyEvent.VK_A, 'a');
            for (final KeyListener l : cover.getKeyListeners()) {
                switch (id) {
                    case KeyEvent.KEY_PRESSED -> l.keyPressed(e);
                    case KeyEvent.KEY_TYPED -> l.keyTyped(e);
                    default -> l.keyReleased(e);
                }
            }
            assertThat(e.isConsumed()).as("key event %d", id).isTrue();
        }
    }
}
