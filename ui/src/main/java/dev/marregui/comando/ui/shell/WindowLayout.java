package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.model.LinearTickets;
import dev.marregui.comando.ui.kit.theme.Gold;
import dev.marregui.comando.ui.panels.SessionsSidebar;

import javax.swing.JFrame;
import java.awt.Frame;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.Set;
import java.util.TreeSet;
import java.util.prefs.Preferences;

/**
 * The window's layout across restarts, in the user's preferences: its bounds and whether it was maximized, the
 * dividers, the sidebar's state and tab, the side pane, the editors' theme, the gold and Linear's site. The one place each key is named.
 */
final class WindowLayout {

    /** What a window shows that is kept: the sidebar and the splits around it. */
    record Saved(boolean sidebarShown, int sidebarDivider, int sidebarTab, boolean manualOrder, boolean sidePaneHidden,
                 int lowerDivider, int sideDivider) {
    }

    /**
     * True opens the window on no screen and never takes the desktop's focus, for tests that need a real window while
     * the desktop is in use: {@code ./gradlew :app:uiTest} sets it.
     */
    static final String OFF_SCREEN_PROPERTY = "comando.offScreen";

    private final Preferences prefs;
    private final boolean offScreen;

    WindowLayout(Preferences prefs) {
        this.prefs = prefs;
        this.offScreen = Boolean.getBoolean(OFF_SCREEN_PROPERTY);
    }

    /**
     * Puts the window where it was, kept on a screen that still exists: a window saved on a detached display opens
     * centred on the main one. Returns the height it opens at. Off screen, the window is undecorated, as macOS moves a
     * titled window back onto a screen, and left of every screen.
     */
    int restoreBounds(JFrame frame) {
        int w = prefs.getInt("window.width", 1500);
        int h = prefs.getInt("window.height", 950);
        if (offScreen) {
            Rectangle screens = new Rectangle();
            for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                screens = screens.union(d.getDefaultConfiguration().getBounds());
            }
            frame.setUndecorated(true);
            frame.setAutoRequestFocus(false);
            frame.setBounds(screens.x - w - 1_000, screens.y, w, h);
            return h;
        }
        int x = prefs.getInt("window.x", Integer.MIN_VALUE);
        int y = prefs.getInt("window.y", Integer.MIN_VALUE);
        Rectangle saved = new Rectangle(x, y, w, h);
        Rectangle screen = onScreen(saved);
        if (x != Integer.MIN_VALUE && screen != null) {
            Rectangle fit = saved.intersection(screen);
            frame.setBounds(fit.width < 400 || fit.height < 300 ? new Rectangle(screen.x, screen.y, Math.min(w, screen.width),
                    Math.min(h, screen.height)) : fit);
        } else {
            frame.setSize(w, h);
            frame.setLocationRelativeTo(null);
        }
        return h;
    }

    /** The bounds of the screen {@code r} overlaps most, or null when it is on none. */
    private static Rectangle onScreen(Rectangle r) {
        Rectangle best = null;
        long bestArea = 0;
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle b = d.getDefaultConfiguration().getBounds();
            Rectangle i = b.intersection(r);
            long area = i.isEmpty() ? 0 : (long) i.width * i.height;
            if (area > bestArea) {
                best = b;
                bestArea = area;
            }
        }
        return best;
    }

    /** Whether the window was maximized; never off screen, where maximized would put it on one. */
    boolean maximized() {
        return !offScreen && prefs.getBoolean("window.maximized", false);
    }

    /**
     * The sidebar tab in front when the window closed. {@code sidebar.tab} held it before the tabs took one row
     * (Sessions, Panels, Connections, Files); it is read once, in that order, until {@code sidebar.front} is saved.
     */
    int sidebarTab() {
        int front = prefs.getInt("sidebar.front", -1);
        if (front >= 0) {
            return front;
        }
        return switch (prefs.getInt("sidebar.tab", 0)) {
            case 1 -> SessionsSidebar.PANELS;
            case 2 -> SessionsSidebar.CONNECTIONS;
            case 3 -> SessionsSidebar.FILES;
            default -> SessionsSidebar.SESSIONS;
        };
    }

    boolean manualOrder() {
        return prefs.getBoolean("sessions.manualOrder", false);
    }

    boolean lightEditors() {
        return prefs.getBoolean("editor.light", false);
    }

    void lightEditors(boolean light) {
        prefs.putBoolean("editor.light", light);
    }

    /** Linear's workspace and team keys as learnt from its links, which make a tab card's tickets links. */
    LinearTickets.Site linearSite() {
        String teams = prefs.get("linear.teams", "");
        return new LinearTickets.Site(prefs.get("linear.workspace", ""), teams.isEmpty() ? Set.of() : Set.of(teams.split(",")));
    }

    void linearSite(LinearTickets.Site site) {
        prefs.put("linear.workspace", site.workspace());
        prefs.put("linear.teams", String.join(",", new TreeSet<>(site.teams())));
    }

    Gold gold() {
        return Gold.named(prefs.get("theme.gold", Gold.TREASURE.name()));
    }

    void gold(Gold gold) {
        prefs.put("theme.gold", gold.name());
    }

    boolean sidebarShown() {
        return prefs.getBoolean("sidebar.visible", true);
    }

    int sidebarDivider() {
        return prefs.getInt("divider.sidebar", 260);
    }

    void sidebarDivider(int at) {
        prefs.putInt("divider.sidebar", at);
    }

    /** Where the lower panel's divider was, else a little under the middle of a window {@code height} high. */
    int lowerDivider(int height) {
        return prefs.getInt("divider.lower", (int) (height * 0.45));
    }

    boolean sidePaneHidden() {
        return prefs.getBoolean("sidePane.hidden", false);
    }

    /** Where the side pane's divider was; -1 when it never showed. */
    int sideDivider() {
        return prefs.getInt("divider.side", -1);
    }

    /**
     * Keeps the window's bounds, unless maximized, and what it shows. A divider of a hidden part is not kept:
     * {@code sidebarDivider} and {@code sideDivider} are -1 then, and the last shown position stays.
     */
    void save(JFrame frame, Saved s) {
        boolean maximized = (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
        prefs.putBoolean("window.maximized", maximized);
        if (!maximized) {
            prefs.putInt("window.width", frame.getWidth());
            prefs.putInt("window.height", frame.getHeight());
            prefs.putInt("window.x", frame.getX());
            prefs.putInt("window.y", frame.getY());
        }
        if (s.sidebarDivider() >= 0) {
            prefs.putInt("divider.sidebar", s.sidebarDivider());
        }
        prefs.putBoolean("sidebar.visible", s.sidebarShown());
        prefs.putInt("sidebar.front", s.sidebarTab());
        prefs.putBoolean("sessions.manualOrder", s.manualOrder());
        prefs.putBoolean("sidePane.hidden", s.sidePaneHidden());
        prefs.putInt("divider.lower", s.lowerDivider());
        if (s.sideDivider() >= 0) {
            prefs.putInt("divider.side", s.sideDivider());
        }
    }
}
