package dev.marregui.comando.ui.kit.icon;

import com.formdev.flatlaf.extras.FlatSVGIcon;

import javax.swing.Icon;
import java.util.concurrent.ConcurrentHashMap;

/** The one icon set: SVGs under {@code /icons}, scaled for HiDPI and recoloured by the theme. */
public final class Icons {

    private static final ConcurrentHashMap<String, FlatSVGIcon> CACHE = new ConcurrentHashMap<>();

    private Icons() {
    }

    public static Icon get(String name) {
        return get(name, 16);
    }

    public static Icon get(String name, int size) {
        return CACHE.computeIfAbsent(name + '@' + size, k -> new FlatSVGIcon("icons/" + name + ".svg", size, size));
    }
}
