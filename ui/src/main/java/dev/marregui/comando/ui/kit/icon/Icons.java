package dev.marregui.comando.ui.kit.icon;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.Icon;
import java.awt.Color;
import java.util.concurrent.ConcurrentHashMap;

/** The one icon set: SVGs under {@code /icons}, scaled for HiDPI and recoloured by the theme. */
public final class Icons {

    private static final ConcurrentHashMap<String, FlatSVGIcon> CACHE = new ConcurrentHashMap<>();
    /**
     * The golds the SVGs are drawn in: a new file's dot, and the amber of the work and skills folders, images, logs, the
     * warning and the interrupt. Each is painted as the gold chosen at each paint, {@link Theme#gold()}.
     */
    static final int SVG_GOLD = 0xF0BA4C;
    static final int SVG_AMBER = 0xEDA200;

    static {
        FlatSVGIcon.ColorFilter.getInstance().setMapper(Icons::gilded);
    }

    private Icons() {
    }

    public static Icon get(String name) {
        return get(name, 16);
    }

    /** {@code c}, or the gold chosen in its alpha when {@code c} is one of the SVGs' golds. */
    static Color gilded(Color c) {
        int rgb = c.getRGB() & 0xFFFFFF;
        if (rgb != SVG_GOLD && rgb != SVG_AMBER) {
            return c;
        }
        Color gold = Theme.gold().body;
        return new Color(gold.getRed(), gold.getGreen(), gold.getBlue(), c.getAlpha());
    }

    public static Icon get(String name, int size) {
        return CACHE.computeIfAbsent(name + '@' + size, k -> new FlatSVGIcon("icons/" + name + ".svg", size, size));
    }
}
