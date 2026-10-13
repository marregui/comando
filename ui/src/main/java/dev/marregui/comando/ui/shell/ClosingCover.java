package dev.marregui.comando.ui.shell;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;

/**
 * The glass pane of a window that is closing: greys the window and takes every click and key, so nothing typed
 * while the application closes is lost without the user seeing it refused.
 */
final class ClosingCover extends JComponent {

    private static final Color VEIL = new Color(0, 0, 0, 110);

    ClosingCover() {
        setOpaque(false);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        MouseAdapter swallow = new MouseAdapter() {
        };
        addMouseListener(swallow);
        addMouseMotionListener(swallow);
        addMouseWheelListener(swallow);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                e.consume();
            }

            @Override
            public void keyTyped(KeyEvent e) {
                e.consume();
            }

            @Override
            public void keyReleased(KeyEvent e) {
                e.consume();
            }
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(VEIL);
        g.fillRect(0, 0, getWidth(), getHeight());
    }
}
