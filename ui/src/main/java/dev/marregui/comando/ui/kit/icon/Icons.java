package dev.marregui.comando.ui.kit.icon;

import java.awt.Color;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.Icon;

import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * The one icon set, Classic: SVGs under {@code /icons}, scaled for HiDPI and recoloured by the theme. There is no
 * other set to switch to: an icon carries a behaviour its user has learned, and changing it disrupts that (record 156).
 */
public final class Icons {

    private static final ConcurrentHashMap<String, SvgIcon> CACHE = new ConcurrentHashMap<>();
    /** Icons made of others, by name: a repository's folder with git's mark. */
    private static final ConcurrentHashMap<String, Icon> COMPOSED = new ConcurrentHashMap<>();
    /** Icons painted in code, by name, at any size asked: the tints' swatches. */
    private static final ConcurrentHashMap<String, Icon> PAINTED = new ConcurrentHashMap<>();
    /**
     * The golds the SVGs are drawn in: a new file's dot, painted in the tint chosen at each paint
     * ({@link Theme#tint()}); the amber of the work and skills folders, images, logs, the warning and the interrupt,
     * painted in {@link Theme#ATTENTION}, which no tint changes (record 96).
     */
    static final int SVG_GOLD = 0xF0BA4C;
    static final int SVG_AMBER = 0xEDA200;
    /** The purple of a due checkpoint, drawn as the dark theme's; painted in {@link Theme#THINKING}, by mode. */
    static final int SVG_PURPLE = 0xB99BF8;
    /** The Locate button's arrow, 7 by 16. */
    private static final SvgIcon LOCATE_ARROW = new SvgIcon("icons/locate-arrow.svg", 7, 16, Icons::gilded);

    private Icons() {
    }

    public static Icon get(final String name) {
        return get(name, 16);
    }

    /**
     * {@code c}; the SVGs' gold as the tint chosen, their amber as the attention's gold, their purple as the theme's
     * own (light or dark), each in {@code c}'s alpha.
     */
    static Color gilded(final Color c) {
        final Color to = switch (c.getRGB() & 0xFFFFFF) {
            case SVG_GOLD -> Theme.tint().body;
            case SVG_AMBER -> Theme.ATTENTION;
            case SVG_PURPLE -> Theme.THINKING;
            default -> null;
        };
        return to == null ? c : Theme.withAlpha(to, c.getAlpha());
    }

    /** The SVG {@code name} at {@code size} pixels, or the icon painted in code under that name at its own size. */
    public static Icon get(final String name, final int size) {
        final Icon painted = PAINTED.get(name);
        if (painted != null) {
            return painted;
        }
        return CACHE.computeIfAbsent(name + '@' + size,
                _ -> new SvgIcon("icons/" + name + ".svg", size, size, Icons::gilded));
    }

    /**
     * A folder's icon by its name ({@code folder}, {@code folder-scratchpad}, … {@code folder-git}): the sidebar's
     * family ({@code icons/sidebar/}), drawn as the sidebar's tabs are, an outline in one colour per kind (record 98),
     * with git's mark on a repository's; any other name as {@link #get(String)}.
     */
    public static Icon folder(final String name) {
        return switch (name) {
            case "folder" -> get("sidebar/folder");
            case "folder-scratchpad" -> get("sidebar/scratchpad");
            case "folder-knowledge" -> get("sidebar/knowledge");
            case "folder-memory" -> get("sidebar/memory");
            case "folder-skills" -> get("sidebar/skills");
            case "folder-plugin" -> get("sidebar/plugin");
            case "folder-work" -> get("sidebar/work");
            case "folder-jobs" -> get("sidebar/jobs");
            case "folder-git" -> COMPOSED.computeIfAbsent("folder-git", k -> new OverlayIcon(get("sidebar/folder"), get(GIT_BADGE)));
            default -> get(name);
        };
    }

    /** Git's mark, in git's colours, drawn over a repository's folder. */
    public static final String GIT_BADGE = "sidebar/git-badge";

    /**
     * {@code name}'s icon with a small left arrow before it: the Locate button's, which goes to that mark's tab in the
     * sidebar, told from the tab's own icon by the arrow (record 119).
     */
    public static Icon locate(final String name) {
        return COMPOSED.computeIfAbsent("locate-" + name, _ -> new BesideIcon(LOCATE_ARROW, get(name)));
    }

    /** Names {@code icon}, painted in code, so that a command can name it as it names an SVG. */
    public static void put(final String name, final Icon icon) {
        PAINTED.put(name, icon);
    }
}
