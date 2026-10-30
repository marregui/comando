package dev.marregui.comando.ui.panels;

import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Consumer;

/**
 * The theme chooser's Dark | Light switch (record 128): a pill with a knob of polished metal, the tint's ramp, that
 * slides to the mode, a moon on it in dark and a sun in light; the mode's label lit in the tint, the other muted. A
 * click on the pill flips it, a click on a label goes to that label's mode. The knob slides first and {@code changed}
 * hears the mode once it is there, so the slide is seen before the window turns. EDT only.
 */
final class ModeSwitch extends JComponent {

    /** How long the knob takes to slide. */
    static final int SLIDE_MILLIS = 160;
    private static final int TRACK_W = 56;
    private static final int TRACK_H = 24;
    private static final int STEP_MILLIS = 16;

    private boolean light;
    /** Where the knob is, 0 at dark and 1 at light. */
    private float at;
    private Timer slide;
    private Consumer<Boolean> changed = b -> { };

    ModeSwitch(boolean light) {
        this.light = light;
        this.at = light ? 1f : 0f;
        setFocusable(false);
        setToolTipText("Turn the window dark or light: the twin of the theme chosen (D, L)");
        getAccessibleContext().setAccessibleName("Dark or light");
        describe();
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int track = trackX();
                if (e.getX() < track) {
                    go(false);
                } else if (e.getX() > track + TRACK_W) {
                    go(true);
                } else {
                    go(!ModeSwitch.this.light);
                }
            }
        });
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            // read by screen readers and the tests as a toggle, its description the mode
            accessibleContext = new AccessibleJComponent() {
                @Override
                public AccessibleRole getAccessibleRole() {
                    return AccessibleRole.TOGGLE_BUTTON;
                }
            };
        }
        return accessibleContext;
    }

    /** {@code changed} hears the mode each time the knob reaches one it was sent to. */
    void onChange(Consumer<Boolean> changed) {
        this.changed = changed;
    }

    boolean light() {
        return light;
    }

    /** Sends the knob to {@code toLight}; nothing when it is there or on its way. */
    void go(boolean toLight) {
        if (toLight == light) {
            return;
        }
        light = toLight;
        describe();
        if (slide != null) {
            slide.stop();
        }
        float from = at;
        long start = System.nanoTime();
        slide = new Timer(STEP_MILLIS, e -> {
            float t = Math.min(1f, (System.nanoTime() - start) / 1_000_000f / SLIDE_MILLIS);
            // eased: quick away, gentle on arrival
            float eased = 1f - (1f - t) * (1f - t);
            at = from + ((toLight ? 1f : 0f) - from) * eased;
            repaint();
            if (t >= 1f) {
                ((Timer) e.getSource()).stop();
                slide = null;
                changed.accept(toLight);
            }
        });
        slide.start();
    }

    private void describe() {
        getAccessibleContext().setAccessibleDescription(light ? "Light" : "Dark");
        getAccessibleContext().firePropertyChange(AccessibleContext.ACCESSIBLE_DESCRIPTION_PROPERTY, null, light ? "Light" : "Dark");
    }

    private Font labelFont() {
        return getFont() == null ? UIManager.getFont("Label.font") : getFont();
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics m = getFontMetrics(labelFont());
        int w = m.stringWidth("Light") + Theme.SPACE_S + TRACK_W + Theme.SPACE_S + m.stringWidth("Light");
        return new Dimension(w, Math.max(TRACK_H, m.getHeight()) + 2);
    }

    /** The track's left edge: the pill in the middle, a label each side. */
    private int trackX() {
        return (getWidth() - TRACK_W) / 2;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Tint tint = Theme.tint();
            int tx = trackX();
            int ty = (getHeight() - TRACK_H) / 2;
            RoundRectangle2D track = new RoundRectangle2D.Float(tx + 0.5f, ty + 0.5f, TRACK_W - 1, TRACK_H - 1, TRACK_H, TRACK_H);
            g.setColor(Theme.CARD);
            g.fill(track);
            g.setColor(tint.shade);
            g.draw(track);
            float r = TRACK_H / 2f - 3f;
            float cx = tx + 3f + r + at * (TRACK_W - 6f - 2 * r);
            float cy = ty + TRACK_H / 2f;
            g.setPaint(tint.ramp(cy - r, cy + r, false));
            g.fill(new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));
            g.setColor(Theme.ground());
            if (at >= 0.5f) {
                sun(g, cx, cy, r * 0.62f);
            } else {
                moon(g, cx, cy, r * 0.5f);
            }
            Font f = labelFont();
            g.setFont(f);
            FontMetrics m = g.getFontMetrics();
            int base = (getHeight() + m.getAscent() - m.getDescent()) / 2;
            g.setColor(light ? Theme.MUTED : tint.body);
            g.drawString("Dark", tx - Theme.SPACE_S - m.stringWidth("Dark"), base);
            g.setColor(light ? tint.body : Theme.MUTED);
            g.drawString("Light", tx + TRACK_W + Theme.SPACE_S, base);
        } finally {
            g.dispose();
        }
    }

    private static void moon(Graphics2D g, float cx, float cy, float r) {
        Area crescent = new Area(new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));
        crescent.subtract(new Area(new Ellipse2D.Float(cx - r + r * 0.6f, cy - r - r * 0.2f, 2 * r, 2 * r)));
        g.fill(crescent);
    }

    private static void sun(Graphics2D g, float cx, float cy, float r) {
        float core = r * 0.55f;
        g.fill(new Ellipse2D.Float(cx - core, cy - core, 2 * core, 2 * core));
        g.setStroke(new BasicStroke(Math.max(1f, r * 0.18f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            g.draw(new Line2D.Double(cx + Math.cos(a) * r * 0.8, cy + Math.sin(a) * r * 0.8, cx + Math.cos(a) * r * 1.1,
                    cy + Math.sin(a) * r * 1.1));
        }
    }
}
