package dev.marregui.comando.ui.kit.theme;

import java.awt.Color;

/**
 * A colour laid over the ground chosen, worked out again when another ground is: a surface of the theme's first tier
 * (record 103). Swing reads a colour's value each time it paints, so a background or a border made once with one
 * follows a ground chosen later, with no listener. Safe on any thread: the ground and the value it gave are read and
 * written as one.
 */
final class OnGround extends Color {

    private final transient Color over;
    private final float amount;
    /** The ground's ordinal in the high half, the value in the low, or −1 before the first read. */
    private volatile long cached = -1;

    OnGround(Color over, float amount) {
        super(Tint.over(Ground.GRAPHITE.color, over, amount).getRGB());
        this.over = over;
        this.amount = amount;
    }

    @Override
    public int getRGB() {
        Ground g = Theme.groundChosen();
        long c = cached;
        if (c != -1 && (int) (c >>> 32) == g.ordinal()) {
            return (int) c;
        }
        int rgb = Tint.over(g.color, over, amount).getRGB();
        cached = (long) g.ordinal() << 32 | (rgb & 0xFFFFFFFFL);
        return rgb;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Color c && c.getRGB() == getRGB();
    }

    @Override
    public int hashCode() {
        return getRGB();
    }
}
