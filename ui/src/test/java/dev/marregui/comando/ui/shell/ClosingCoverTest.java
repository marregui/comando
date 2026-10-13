package dev.marregui.comando.ui.shell;

import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class ClosingCoverTest {

    @Test
    void theCoverGreysTheWindowAndTakesEveryKeyAndClick() {
        ClosingCover cover = new ClosingCover();
        cover.setSize(20, 10);
        BufferedImage image = new BufferedImage(20, 10, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        cover.paint(g);
        g.dispose();
        assertThat(image.getRGB(10, 5) >>> 24).as("a translucent veil").isBetween(1, 254);

        assertThat(cover.isFocusable()).isTrue();
        assertThat(cover.getMouseListeners()).isNotEmpty();
        assertThat(cover.getMouseMotionListeners()).isNotEmpty();
        assertThat(cover.getMouseWheelListeners()).isNotEmpty();
        for (int id : new int[]{KeyEvent.KEY_PRESSED, KeyEvent.KEY_TYPED, KeyEvent.KEY_RELEASED}) {
            KeyEvent e = new KeyEvent(cover, id, 0L, 0, id == KeyEvent.KEY_TYPED ? KeyEvent.VK_UNDEFINED : KeyEvent.VK_A, 'a');
            for (KeyListener l : cover.getKeyListeners()) {
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
