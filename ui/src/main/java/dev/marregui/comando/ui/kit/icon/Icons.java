package dev.marregui.comando.ui.kit.icon;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.Icon;
import java.awt.Color;
import java.util.concurrent.ConcurrentHashMap;

/** The one icon set: SVGs under {@code /icons}, scaled for HiDPI and recoloured by the theme. */
public final class Icons {

    private static final ConcurrentHashMap<String, FlatSVGIcon> CACHE = new ConcurrentHashMap<>();
    /** Icons made of others, by name: a repository's folder with git's mark. */
    private static final ConcurrentHashMap<String, Icon> COMPOSED = new ConcurrentHashMap<>();
    /** Icons painted in code, by name, at any size asked: the tints' swatches. */
    private static final ConcurrentHashMap<String, Icon> PAINTED = new ConcurrentHashMap<>();
    /**
     * The golds the SVGs are drawn in: a new file's dot, painted in the tint chosen at each paint ({@link Theme#tint()});
     * the amber of the work and skills folders, images, logs, the warning and the interrupt, painted in
     * {@link Theme#ATTENTION}, which no tint changes (record 96).
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

    /** {@code c}; the SVGs' gold as the tint chosen, their amber as the attention's gold, each in {@code c}'s alpha. */
    static Color gilded(Color c) {
        int rgb = c.getRGB() & 0xFFFFFF;
        Color to = rgb == SVG_GOLD ? Theme.tint().body : rgb == SVG_AMBER ? Theme.ATTENTION : null;
        return to == null ? c : Theme.withAlpha(to, c.getAlpha());
    }

    public static Icon get(String name, int size) {
        Icon painted = PAINTED.get(name);
        if (painted != null) {
            return painted;
        }
        return CACHE.computeIfAbsent(name + '@' + size, k -> new FlatSVGIcon("icons/" + name + ".svg", size, size));
    }

    /**
     * A folder's icon by its name ({@code folder}, {@code folder-scratchpad}, … {@code folder-git}): the sidebar's family
     * ({@code icons/sidebar/}), drawn as the sidebar's tabs are, an outline in one colour per kind (record 98), with git's
     * mark on a repository's; any other name as {@link #get}.
     */
    public static Icon folder(String name) {
        return switch (name) {
            case "folder" -> get("sidebar/folder");
            case "folder-scratchpad" -> get("sidebar/scratchpad");
            case "folder-knowledge" -> get("sidebar/knowledge");
            case "folder-memory" -> get("sidebar/memory");
            case "folder-skills" -> get("sidebar/skills");
            case "folder-plugin" -> get("sidebar/plugin");
            case "folder-work" -> get("sidebar/work");
            case "folder-git" -> COMPOSED.computeIfAbsent("folder-git", k -> new OverlayIcon(get("sidebar/folder"), get(GIT_BADGE)));
            default -> get(name);
        };
    }

    /** Git's mark, in git's colours, drawn over a repository's folder. */
    public static final String GIT_BADGE = "sidebar/git-badge";

    /** Names {@code icon}, painted in code, so that a command can name it as it names an SVG. */
    public static void put(String name, Icon icon) {
        PAINTED.put(name, icon);
    }
}
