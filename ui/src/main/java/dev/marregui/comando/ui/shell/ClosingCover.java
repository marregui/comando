package dev.marregui.comando.ui.shell;

import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import javax.swing.JComponent;

import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * The glass pane of a window that is closing: greys the window and takes every click and key, so nothing typed
 * while the application closes is lost without the user seeing it refused.
 */
final class ClosingCover extends JComponent {

    ClosingCover() {
        setOpaque(false);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        final MouseAdapter swallow = new MouseAdapter() {
        };
        addMouseListener(swallow);
        addMouseMotionListener(swallow);
        addMouseWheelListener(swallow);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(final KeyEvent e) {
                e.consume();
            }

            @Override
            public void keyTyped(final KeyEvent e) {
                e.consume();
            }

            @Override
            public void keyReleased(final KeyEvent e) {
                e.consume();
            }
        });
    }

    @Override
    protected void paintComponent(final Graphics g) {
        g.setColor(Theme.VEIL);
        g.fillRect(0, 0, getWidth(), getHeight());
    }
}
