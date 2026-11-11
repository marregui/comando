package dev.marregui.comando.ui.kit.theme;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.OptionalInt;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.accessibility.AccessibleState;
import javax.accessibility.AccessibleStateSet;
import javax.accessibility.AccessibleValue;
import javax.swing.JComponent;

/**
 * An instrument of the console style (record 151): a caption, then a track filled from the left to a share of a
 * hundred, with an optional tick on the track. Its colours are the theme's, read at each paint. It has no timer: it
 * paints again only when {@link #show} or {@link #tick} changes what it shows, or when its window does, as after a
 * theme change. Assistive technology reads it as a progress bar named by its caption, valued in percent.
 */
public final class Gauge extends JComponent {

    /** The track's height and length (record 151): a status bar's line holds it beside its caption. */
    static final int TRACK_H = 6;
    static final int TRACK_W = 60;
    /** The tick stands out of the track by a pixel above and below. */
    static final int TICK_H = TRACK_H + 2;

    private final String caption;
    private Color fill = Theme.MUTED;
    private int percent;
    private OptionalInt tick = OptionalInt.empty();

    public Gauge(final String caption) {
        this.caption = caption;
        setOpaque(false);
        setFont(Theme.technical());
    }

    /** Shows {@code percent}, kept within 0 to 100, as the fill's length, in {@code fill}. */
    public void show(final int percent, final Color fill) {
        final int kept = Math.clamp(percent, 0, 100);
        // the same colour, not an equal one: a theme colour's value changes with the mode
        if (kept == this.percent && fill == this.fill) {
            return;
        }
        final int before = this.percent;
        this.percent = kept;
        this.fill = fill;
        if (accessibleContext != null && before != kept) {
            accessibleContext.firePropertyChange(AccessibleContext.ACCESSIBLE_VALUE_PROPERTY, before, kept);
        }
        repaint();
    }

    /** The mark on the track at {@code percent}, kept within 0 to 100, of its length; none when empty. */
    public void tick(final OptionalInt percent) {
        final OptionalInt kept = percent.isPresent() ? OptionalInt.of(Math.clamp(percent.getAsInt(), 0, 100)) : percent;
        if (kept.equals(tick)) {
            return;
        }
        tick = kept;
        repaint();
    }

    /** The caption given; for tests. */
    public String caption() {
        return caption;
    }

    /** The share shown; for tests. */
    public int percent() {
        return percent;
    }

    /** The fill's colour; for tests. */
    public Color fill() {
        return fill;
    }

    /** The tick's place; for tests. */
    public OptionalInt tick() {
        return tick;
    }

    /** The technical face again: the look and feel set up anew may have another label font. */
    @Override
    public void updateUI() {
        super.updateUI();
        setFont(Theme.technical());
    }

    @Override
    public Dimension getPreferredSize() {
        if (isPreferredSizeSet()) {
            return super.getPreferredSize();
        }
        final Insets in = getInsets();
        final FontMetrics m = getFontMetrics(getFont());
        return new Dimension(in.left + trackX(m) + TRACK_W + in.right,
                in.top + Math.max(m.getHeight(), TICK_H) + in.bottom);
    }

    /** As wide as preferred: a row that gives way shortens its texts, never the track. */
    @Override
    public Dimension getMinimumSize() {
        return isMinimumSizeSet() ? super.getMinimumSize() : getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return isMaximumSizeSet() ? super.getMaximumSize() : getPreferredSize();
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleGauge();
        }
        return accessibleContext;
    }

    @Override
    protected void paintComponent(final Graphics graphics) {
        final Graphics2D g = (Graphics2D) graphics.create();
        try {
            final Insets in = getInsets();
            final FontMetrics m = g.getFontMetrics(getFont());
            final int middle = in.top + (getHeight() - in.top - in.bottom) / 2;
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(getFont());
            g.setColor(Theme.MUTED);
            g.drawString(caption, in.left, middle + (m.getAscent() - m.getDescent()) / 2);
            final int x = in.left + trackX(m);
            final int y = middle - TRACK_H / 2;
            g.setColor(Theme.CARD_BORDER);
            g.drawRect(x, y, TRACK_W - 1, TRACK_H - 1);
            g.setColor(fill);
            g.fillRect(x, y, fillWidth(percent), TRACK_H);
            if (tick.isPresent()) {
                g.setColor(Theme.MUTED_STRONG);
                g.fillRect(x + tickOffset(tick.getAsInt()), y - (TICK_H - TRACK_H) / 2, 1, TICK_H);
            }
        } finally {
            g.dispose();
        }
    }

    /** The fill's length in pixels at {@code percent}: none at 0, the whole track at 100. */
    static int fillWidth(final int percent) {
        return percent * TRACK_W / 100;
    }

    /** The tick's pixel column from the track's left edge at {@code percent}; at 100, the track's last column. */
    static int tickOffset(final int percent) {
        return Math.min(TRACK_W - 1, percent * TRACK_W / 100);
    }

    /** Where the track starts from the left inset: after the caption and the space that parts them. */
    private int trackX(final FontMetrics m) {
        return caption.isEmpty() ? 0 : m.stringWidth(caption) + Theme.SPACE_XS;
    }

    /** The gauge as assistive technology reads it: a progress bar, named by its caption, valued 0 to 100. */
    private final class AccessibleGauge extends AccessibleJComponent implements AccessibleValue {

        @Override
        public String getAccessibleName() {
            return accessibleName != null ? accessibleName : caption;
        }

        @Override
        public AccessibleRole getAccessibleRole() {
            return AccessibleRole.PROGRESS_BAR;
        }

        @Override
        public AccessibleStateSet getAccessibleStateSet() {
            final AccessibleStateSet states = super.getAccessibleStateSet();
            states.add(AccessibleState.HORIZONTAL);
            return states;
        }

        @Override
        public AccessibleValue getAccessibleValue() {
            return this;
        }

        @Override
        public Number getCurrentAccessibleValue() {
            return percent;
        }

        /** Read only: it shows the reading it is given. */
        @Override
        public boolean setCurrentAccessibleValue(final Number n) {
            return false;
        }

        @Override
        public Number getMinimumAccessibleValue() {
            return 0;
        }

        @Override
        public Number getMaximumAccessibleValue() {
            return 100;
        }
    }
}
