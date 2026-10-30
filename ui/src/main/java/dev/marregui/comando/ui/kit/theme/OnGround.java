package dev.marregui.comando.ui.kit.theme;

import java.awt.Color;

/**
 * A colour laid over the ground chosen, worked out again when another ground is: a surface of the theme's first tier
 * (record 103). Swing reads a colour's value each time it paints, so a background or a border made once with one
 * follows a ground chosen later, with no listener. On a light ground it is laid over with its own colour and amount,
 * a darker step where the dark ground takes a lighter one (record 127). Safe on any thread: the ground and the value
 * it gave are read and written as one.
 */
final class OnGround extends Color {

    private final transient Color over;
    private final float amount;
    private final transient Color overLight;
    private final float amountLight;
    /** The ground the value was worked out on and the value, or null before the first read; read and written as one. */
    private transient volatile Worked worked;

    private record Worked(Ground ground, int rgb) {
    }

    OnGround(Color over, float amount, Color overLight, float amountLight) {
        super(Tint.over(Ground.GRAPHITE.color, over, amount).getRGB());
        this.over = over;
        this.amount = amount;
        this.overLight = overLight;
        this.amountLight = amountLight;
    }

    @Override
    public int getRGB() {
        Ground g = Theme.groundChosen();
        Worked w = worked;
        if (w != null && w.ground() == g) {
            return w.rgb();
        }
        int rgb = on(g).getRGB();
        worked = new Worked(g, rgb);
        return rgb;
    }

    /** The colour on {@code g}, chosen or not. */
    Color on(Ground g) {
        return g.light ? Tint.over(g.color, overLight, amountLight) : Tint.over(g.color, over, amount);
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
