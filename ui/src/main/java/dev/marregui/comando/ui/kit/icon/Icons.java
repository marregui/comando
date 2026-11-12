package dev.marregui.comando.ui.kit.icon;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.Icon;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.extras.FlatSVGIcon;

import dev.marregui.comando.ui.kit.theme.Theme;

/** The icons: SVGs of the icon set chosen (record 155), scaled for HiDPI and recoloured by the theme. */
public final class Icons {

    private static final ConcurrentHashMap<String, Icon> CACHE = new ConcurrentHashMap<>();
    /** The SVG for each set, name and size; a missing set SVG is remembered as Classic's SVG. */
    private static final ConcurrentHashMap<String, FlatSVGIcon> SVGS = new ConcurrentHashMap<>();
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
    static final int SVG_IVORY = 0xE8E2D4;
    /** Written by the EDT settings action and read by the EDT paint calls. */
    private static volatile IconSet chosen = IconSet.CLASSIC;
    /** The Locate button's arrow, 7 by 16, Classic's in every set (record 155). */
    private static final FlatSVGIcon LOCATE_ARROW = new FlatSVGIcon(IconSet.CLASSIC.path("locate-arrow"), 7, 16);

    static {
        FlatSVGIcon.ColorFilter.getInstance().setMapper(Icons::gilded);
    }

    private Icons() {
    }

    public static Icon get(final String name) {
        return get(name, 16);
    }

    public static IconSet chosen() {
        return chosen;
    }

    /** Paints every icon from {@code set} from the next paint on; the caller repaints the windows. */
    public static void choose(final IconSet set) {
        chosen = set;
    }

    /**
     * {@code c}; the SVGs' gold as the tint chosen, their amber as the attention's gold, a set's ivory as the text
     * colour on a light theme (record 155), each in {@code c}'s alpha.
     */
    static Color gilded(final Color c) {
        return gilded(c, chosen, Theme.light(), UIManager.getColor("Label.foreground"));
    }

    static Color gilded(final Color c, final IconSet set, final boolean light, final Color text) {
        final int rgb = c.getRGB() & 0xFFFFFF;
        final Color to;
        if (rgb == SVG_GOLD) {
            to = Theme.tint().body;
        } else if (rgb == SVG_AMBER) {
            to = Theme.ATTENTION;
        } else if (rgb == SVG_IVORY && set != IconSet.CLASSIC && light) {
            to = text;
        } else {
            to = null;
        }
        return to == null ? c : Theme.withAlpha(to, c.getAlpha());
    }

    /** The SVG {@code name} at {@code size} pixels, or the icon painted in code under that name at its own size. */
    public static Icon get(final String name, final int size) {
        final Icon painted = PAINTED.get(name);
        if (painted != null) {
            return painted;
        }
        return CACHE.computeIfAbsent(name + '@' + size, _ -> new SetIcon(name, size));
    }

    /** {@code name} at 16 pixels from {@code set}, for a set picker sample. */
    public static Icon of(final IconSet set, final String name) {
        return svg(set, name, 16);
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

    /** {@code set}'s SVG of {@code name}, else Classic's: the fallback is looked up first, outside the map's update. */
    private static FlatSVGIcon svg(final IconSet set, final String name, final int size) {
        final String key = set.id() + '@' + name + '@' + size;
        final FlatSVGIcon known = SVGS.get(key);
        if (known != null) {
            return known;
        }
        final FlatSVGIcon icon = new FlatSVGIcon(set.path(name), size, size);
        final FlatSVGIcon chosenOne = set == IconSet.CLASSIC || icon.hasFound() ? icon : svg(IconSet.CLASSIC, name, size);
        final FlatSVGIcon raced = SVGS.putIfAbsent(key, chosenOne);
        return raced == null ? chosenOne : raced;
    }

    /** One icon per name and size, handed out once, that paints the chosen set's SVG; disabled, that SVG's grey. */
    private static final class SetIcon implements Icon, FlatLaf.DisabledIconProvider {

        private final String name;
        private final int size;
        private final boolean disabled;

        SetIcon(final String name, final int size) {
            this(name, size, false);
        }

        private SetIcon(final String name, final int size, final boolean disabled) {
            this.name = name;
            this.size = size;
            this.disabled = disabled;
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(final Component component, final Graphics graphics, final int x, final int y) {
            final FlatSVGIcon svg = svg(chosen, name, size);
            (disabled ? svg.getDisabledIcon() : svg).paintIcon(component, graphics, x, y);
        }

        @Override
        public Icon getDisabledIcon() {
            return disabled ? this : new SetIcon(name, size, true);
        }
    }
}
