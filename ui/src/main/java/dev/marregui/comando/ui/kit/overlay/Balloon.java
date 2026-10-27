package dev.marregui.comando.ui.kit.overlay;

import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AWTEvent;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

/**
 * A short message beside a component that asks for nothing: it goes after a few seconds, or at the next click or key.
 * One at a time; a new one replaces the one showing. EDT only.
 */
public final class Balloon {

    /** How long a balloon stays. */
    static final int SHOW_MILLIS = 2_500;

    private static Popup shown;
    private static Timer timer;
    private static AWTEventListener dismiss;

    private Balloon() {
    }

    /** Shows {@code text} at {@code at}, in {@code owner}'s coordinates. */
    public static void show(JComponent owner, Point at, String text) {
        hide();
        JLabel label = new JLabel(Plain.text(text));
        label.setOpaque(true);
        label.setBackground(Theme.CARD);
        label.setForeground(Theme.MUTED_STRONG);
        label.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.CARD_BORDER),
                BorderFactory.createEmptyBorder(Theme.SPACE_S, Theme.SPACE_M, Theme.SPACE_S, Theme.SPACE_M)));
        Point p = new Point(at);
        SwingUtilities.convertPointToScreen(p, owner);
        shown = PopupFactory.getSharedInstance().getPopup(owner, label, p.x, p.y);
        shown.show();
        timer = new Timer(SHOW_MILLIS, e -> hide());
        timer.setRepeats(false);
        timer.start();
        // the click that showed it has been handled: only a later press or key takes it away
        dismiss = event -> {
            if (event.getID() == MouseEvent.MOUSE_PRESSED || event.getID() == KeyEvent.KEY_PRESSED) {
                hide();
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(dismiss, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
    }

    /** Whether a balloon shows. */
    public static boolean showing() {
        return shown != null;
    }

    public static void hide() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        if (dismiss != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(dismiss);
            dismiss = null;
        }
        if (shown != null) {
            shown.hide();
            shown = null;
        }
    }
}
