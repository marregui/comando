package dev.marregui.comando.ui.shell;

import dev.marregui.comando.ui.kit.async.Edt;

import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.RootPaneContainer;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Window;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * A theme chosen fading in over the one before (record 103). A picture of each shown window as it was covers it while
 * the theme is applied underneath; once the window has done what the change left for later (its layout, the work
 * queued behind the change), a second picture is taken of it in the new theme, and the first fades out over the
 * second. Each frame draws the two pictures and nothing else, so the fade runs at the screen's pace however much the
 * window holds; and the second picture is the window as it then is, so nothing jumps when the pictures go.
 *
 * <p>FlatLaf's own fade took the second picture at once, before the window had finished, and what it finished later
 * appeared at the end with a jump; fading the first picture over the live window instead repainted the whole window at
 * every frame, 8 to 15 ms each in a nearly empty one, more with a transcript, and the fade stuttered. EDT only.
 */
final class ThemeFade {

    /** The layer the pictures lie on: above drags and popups, as FlatLaf's fade put its own. */
    static final Integer LAYER = JLayeredPane.DRAG_LAYER + 1;
    /** How often the pictures are drawn again as they fade: about sixty times a second. */
    private static final int STEP_MILLIS = 16;
    /** How many turns of the event queue the window has after the change before its second picture is taken. */
    private static final int SETTLE_TURNS = 2;

    private static List<Picture> running = List.of();
    private static Timer timer;
    /** Counts the fades begun, so that the settling of one that another replaced does nothing. */
    private static long generation;
    /** What the fade running has still to do halfway; run by {@link #stop} if the fade ends before. */
    private static Runnable pendingHalf = () -> { };

    private ThemeFade() {
    }

    /**
     * Runs {@code change}, which applies a theme, under a picture of the windows as they were, then fades that picture
     * out over {@code millis} into a picture of them in the new theme; {@code halfway} runs when the fade is half done,
     * or at once when there is nothing to fade.
     */
    static void run(Runnable change, int millis, Runnable halfway) {
        stop();
        long fade = ++generation;
        List<Picture> pictures = new ArrayList<>();
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w instanceof RootPaneContainer r) {
                Picture p = Picture.over(r.getLayeredPane());
                if (p != null) {
                    pictures.add(p);
                }
            }
        }
        running = pictures;
        pendingHalf = halfway;
        try {
            change.run();
        } catch (RuntimeException | Error e) {
            // the theme half applied: the window as it is shows, never a picture left over it
            stop();
            throw e;
        }
        if (pictures.isEmpty()) {
            runHalf();
            return;
        }
        settle(fade, SETTLE_TURNS, millis);
    }

    /** After {@code turns} more turns of the event queue, takes the second pictures and starts the fade. */
    private static void settle(long fade, int turns, int millis) {
        Edt.later(() -> {
            if (fade != generation) {
                return;
            }
            if (turns > 1) {
                settle(fade, turns - 1, millis);
                return;
            }
            for (Picture p : running) {
                p.takeNew();
            }
            long start = System.nanoTime();
            timer = new Timer(STEP_MILLIS, e -> {
                float left = 1f - (System.nanoTime() - start) / 1_000_000f / millis;
                if (left <= 0.5f) {
                    runHalf();
                }
                if (left <= 0f) {
                    stop();
                    return;
                }
                for (Picture p : running) {
                    p.alpha = left;
                    p.repaint();
                }
            });
            timer.start();
        });
    }

    /** Ends a fade running or settling, taking its pictures away. */
    static void stop() {
        runHalf();
        generation++;
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        for (Picture p : running) {
            p.remove();
        }
        running = List.of();
    }

    /** Runs what the fade has to do halfway, once. */
    private static void runHalf() {
        Runnable r = pendingHalf;
        pendingHalf = () -> { };
        r.run();
    }

    /**
     * One window's pictures, laid over its layered pane: the theme before, drawn at {@link #alpha} over the theme
     * chosen once it is taken; opaque, so that the window under it is not painted while it lies there.
     */
    private static final class Picture extends JComponent {

        private final JLayeredPane pane;
        private final BufferedImage old;
        private BufferedImage now;
        private float alpha = 1f;

        private Picture(JLayeredPane pane, BufferedImage old) {
            this.pane = pane;
            this.old = old;
            setOpaque(true);
            setBounds(0, 0, pane.getWidth(), pane.getHeight());
        }

        /** The picture of {@code pane} as it paints now, laid over it; null when it has no size. */
        static Picture over(JLayeredPane pane) {
            if (pane.getWidth() <= 0 || pane.getHeight() <= 0) {
                return null;
            }
            Picture p = new Picture(pane, shoot(pane));
            pane.add(p, LAYER);
            p.paintImmediately(0, 0, p.getWidth(), p.getHeight());
            return p;
        }

        /** Takes the picture of the window in the theme chosen, with this one out of the way. */
        void takeNew() {
            pane.validate();
            setVisible(false);
            try {
                now = shoot(pane);
            } finally {
                setVisible(true);
            }
        }

        /**
         * {@code pane} painted into an image at the screen's scale, so that a picture on a Retina screen is as sharp as
         * the window: an image in memory, which the screen cannot take back as it can a volatile one.
         */
        private static BufferedImage shoot(JLayeredPane pane) {
            GraphicsConfiguration gc = pane.getGraphicsConfiguration();
            AffineTransform scale = gc == null ? new AffineTransform() : gc.getDefaultTransform();
            int w = (int) Math.ceil(pane.getWidth() * scale.getScaleX());
            int h = (int) Math.ceil(pane.getHeight() * scale.getScaleY());
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            try {
                g.scale(scale.getScaleX(), scale.getScaleY());
                pane.paint(g);
            } finally {
                g.dispose();
            }
            return img;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                int w = getWidth();
                int h = getHeight();
                if (now != null) {
                    g.drawImage(now, 0, 0, w, h, null);
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                }
                g.drawImage(old, 0, 0, w, h, null);
            } finally {
                g.dispose();
            }
        }

        void remove() {
            pane.remove(this);
            pane.repaint(getBounds());
        }
    }
}
